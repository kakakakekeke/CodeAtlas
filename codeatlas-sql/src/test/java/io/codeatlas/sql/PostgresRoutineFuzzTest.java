package io.codeatlas.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.sql.lexer.Lexer;
import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.parser.RoutineLanguage;
import io.codeatlas.sql.parser.RoutineParser;
import io.codeatlas.sql.script.RoutineHeader;
import io.codeatlas.sql.script.ScriptSplitter;
import io.codeatlas.sql.script.ScriptUnit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL 루틴의 퍼즈성 테스트(F-019-R24~R31): 임의로 자르고 망가뜨린 루틴 헤더·PL/pgSQL 본문·트리거·DO 블록에서 헤더 파서, 본문 파서,
 * 분석기가 예외·무한 루프 없이 끝난다. 루틴 하나의 실패가 파일 전체를 멈추지 않는다.
 */
class PostgresRoutineFuzzTest {

    private static final String SCRIPT = """
            CREATE TABLE public.orders (id bigint NOT NULL, customer_id bigint, status text, memo text, amount numeric(12,2));
            CREATE FUNCTION public.calc(p_id bigint, numeric(12,2) DEFAULT 0, VARIADIC p_tags text[] = '{}') RETURNS numeric
                LANGUAGE sql STABLE
                AS $$
                SELECT coalesce(sum(o.amount), $2) FROM orders o WHERE o.id = p_id;
            $$;
            CREATE OR REPLACE FUNCTION public.close_order(p_order_id bigint, INOUT p_memo text DEFAULT NULL, OUT o_cnt integer)
                RETURNS record
                LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
                AS $body$
            <<main>>
            DECLARE
                v_cnt integer := 0;
                v_id orders.customer_id%TYPE;
                v_row orders%ROWTYPE;
                a ALIAS FOR $1;
                r record;
                c CURSOR (p_min bigint) FOR SELECT id FROM orders WHERE id > p_min;
                arr integer[] = ARRAY[1, 2];
            BEGIN
                SELECT o.customer_id INTO STRICT v_id FROM orders o WHERE o.id = p_order_id FOR UPDATE;
                v_cnt := calc(p_order_id)::integer;
                UPDATE orders SET status = 'SHIPPED', memo = coalesce(p_memo, memo) WHERE id = p_order_id RETURNING id INTO v_cnt;
                GET DIAGNOSTICS v_cnt = ROW_COUNT;
                IF NOT FOUND THEN
                    RAISE EXCEPTION 'order % not found', p_order_id USING ERRCODE = 'P0002';
                ELSIF v_cnt > 10 THEN
                    RAISE NOTICE 'large %: %', p_order_id, v_cnt;
                ELSE
                    v_cnt = 1;
                END IF;
                CASE v_cnt WHEN 1, 2 THEN NULL; ELSE arr[1] := 3; END CASE;
                FOR r IN SELECT i.id, i.qty FROM order_items i WHERE i.order_id = p_order_id LOOP
                    CONTINUE WHEN r.qty = 0;
                    UPDATE products SET stock = stock - r.qty WHERE id = r.id;
                END LOOP;
                FOR i IN REVERSE 10..1 BY 2 LOOP EXIT main WHEN i = 3; END LOOP;
                FOR r IN EXECUTE 'SELECT id FROM orders WHERE id > $1' USING v_cnt LOOP NULL; END LOOP;
                FOREACH v_cnt IN ARRAY arr LOOP PERFORM pg_sleep(v_cnt); END LOOP;
                WHILE v_cnt < 3 LOOP v_cnt := v_cnt + 1; END LOOP;
                OPEN c(1); FETCH c INTO v_cnt; MOVE c; CLOSE c;
                PERFORM public.touch_customer(v_id);
                PERFORM 1 FROM orders WHERE id = v_cnt;
                EXECUTE format('DELETE FROM %I WHERE k = $1 AND v = %L', 'session_cache', 'x') USING 'order:' || p_order_id::text;
                EXECUTE 'INSERT INTO ' || quote_ident(p_memo) || ' VALUES ($1)' INTO v_cnt USING v_cnt;
                ASSERT v_cnt > 0, 'positive';
                CALL public.purge_old(90);
                BEGIN
                    INSERT INTO payments (order_id, amount) VALUES (p_order_id, 0) ON CONFLICT DO NOTHING;
                EXCEPTION
                    WHEN unique_violation OR check_violation THEN
                        RAISE WARNING 'skipped %: %', p_order_id, SQLERRM;
                END;
                RETURN;
            EXCEPTION
                WHEN no_data_found THEN
                    RETURN;
            END main;
            $body$;
            CREATE FUNCTION public.order_ids() RETURNS TABLE(id bigint, status text) LANGUAGE plpgsql AS $$
            BEGIN
                RETURN QUERY SELECT o.id, o.status FROM orders o;
                RETURN QUERY EXECUTE 'SELECT id, status FROM orders_archive';
                RETURN NEXT;
            END;
            $$;
            CREATE FUNCTION public.trg_audit() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF TG_OP = 'UPDATE' AND NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;
                INSERT INTO order_events (order_id, event_type, payload) VALUES (NEW.id, TG_OP, to_jsonb(NEW));
                RETURN NEW;
            END;
            $$;
            CREATE CONSTRAINT TRIGGER orders_audit AFTER INSERT OR UPDATE OF status ON public.orders DEFERRABLE
                FOR EACH ROW WHEN (NEW.status IS DISTINCT FROM 'X') EXECUTE FUNCTION public.trg_audit('a');
            CREATE FUNCTION public.fast_hash(text) RETURNS integer LANGUAGE c IMMUTABLE STRICT AS '$libdir/shop_ext', 'fast_hash';
            CREATE FUNCTION cnt(bigint) RETURNS bigint LANGUAGE sql AS 'SELECT count(*) FROM orders WHERE memo = ''a'' AND customer_id = $1';
            CREATE FUNCTION one() RETURNS int LANGUAGE sql RETURN (SELECT max(id) FROM orders);
            CREATE PROCEDURE two(p bigint) LANGUAGE sql BEGIN ATOMIC UPDATE orders SET memo = CASE WHEN id = p THEN 'a' ELSE 'b' END; DELETE FROM payments; END;
            DO LANGUAGE plpgsql $$
            DECLARE
                v_n integer;
            BEGIN
                SELECT count(*) INTO v_n FROM session_cache;
                IF v_n > 0 THEN DELETE FROM session_cache WHERE expires_at < now(); END IF;
                PERFORM touch_customer(1);
            END
            $$;
            ALTER TABLE ONLY public.orders ATTACH PARTITION public.orders_2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
            SELECT 1;
            """;

    private static final String JUNK = "()',;.=*@{}:?\"-/\n \t%&|<>!#$[]\\~";
    private static final List<String> WORDS = List.of(" $$ ", "$body$", " $x$ ", " BEGIN ", " END ", " END;", " END LOOP;", " END IF;", " DECLARE ", " LOOP ",
            " EXCEPTION WHEN ", " THEN ", " IF ", " ELSIF ", " FOR r IN ", " IN EXECUTE ", " EXECUTE ", " format('%I %s %L %% %1$s %", " USING ", " INTO STRICT ",
            " PERFORM ", " RETURN QUERY ", " RETURN NEXT ", " RAISE ", " CURSOR FOR ", " LANGUAGE plpgsql ", " LANGUAGE sql ", " AS ", " RETURNS TABLE (",
            " RETURNS SETOF ", " OUT ", " VARIADIC ", " DEFAULT ", " ATOMIC ", " CREATE FUNCTION f(", " CREATE TRIGGER t ", " EXECUTE FUNCTION ", " ON ", " DO ",
            " <<l>> ", " := ", "..", "::", " CASE ", " WHEN ", " FOREACH ", " ARRAY ", " ATTACH PARTITION ", "'", "''");

    private static List<String> mutations(Random random, int count) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i <= SCRIPT.length(); i += 11) {
            list.add(SCRIPT.substring(0, i));
            list.add(SCRIPT.substring(i));
        }
        for (String remove : List.of("(", ")", ";", "'", "$", "BEGIN", "END", "LOOP", "THEN", "AS", "LANGUAGE", "\n")) {
            list.add(SCRIPT.replace(remove, ""));
        }
        list.add(SCRIPT.replace("\n", "\r\n"));
        list.add("DO $$ " + "BEGIN ".repeat(3000) + "$$;");
        list.add("CREATE FUNCTION f() RETURNS int LANGUAGE plpgsql AS $$ BEGIN " + "IF a THEN ".repeat(3000) + " END; $$;");
        list.add("CREATE FUNCTION f(" + "a int DEFAULT (".repeat(2000) + ") RETURNS int LANGUAGE sql AS 'SELECT 1';");
        list.add("CREATE FUNCTION f() RETURNS int LANGUAGE plpgsql AS $$ BEGIN EXECUTE " + "format(".repeat(2000) + "'x'" + ")".repeat(2000) + "; END $$;");
        list.add("CREATE TRIGGER t AFTER INSERT ON x " + "WHEN (".repeat(2000) + " EXECUTE FUNCTION f();");
        for (int n = 0; n < count; n++) {
            StringBuilder sb = new StringBuilder(SCRIPT);
            int edits = 1 + random.nextInt(6);
            for (int e = 0; e < edits && sb.length() > 0; e++) {
                int at = random.nextInt(sb.length());
                switch (random.nextInt(4)) {
                    case 0 -> sb.deleteCharAt(at);
                    case 1 -> sb.insert(at, JUNK.charAt(random.nextInt(JUNK.length())));
                    case 2 -> sb.delete(at, Math.min(sb.length(), at + random.nextInt(60)));
                    default -> sb.insert(at, WORDS.get(random.nextInt(WORDS.size())));
                }
            }
            list.add(sb.toString());
        }
        return list;
    }

    @Test
    @DisplayName("F-019-AC22 F-019-AC23 퍼즈: PostgreSQL 루틴 헤더 파서와 PL/pgSQL 본문 파서가 망가진 입력에 예외·무한 루프 없이 끝난다")
    void headerAndBodyParser() {
        Random random = new Random(24L);
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (String text : mutations(random, 1200)) {
                try {
                    List<ScriptUnit> units = new ArrayList<>();
                    ScriptSplitter.forEach(text, units::add, null, Syntax.POSTGRES);
                    for (ScriptUnit unit : units) {
                        RoutineHeader header = RoutineHeader.parse(unit.tokens(), Syntax.POSTGRES);
                        assertThat(header).isNotNull();
                        assertThat(header.parameters()).isNotNull();
                        for (Token t : unit.tokens()) {
                            if (!t.dollarQuoted()) {
                                continue;
                            }
                            List<Token> all = Lexer.tokenizeBody(t, Syntax.POSTGRES);
                            List<Token> body = all.subList(0, all.size() - 1);
                            assertThat(new RoutineParser(body, RoutineLanguage.PLPGSQL).parseBody(false)).isNotNull();
                            assertThat(new RoutineParser(body, RoutineLanguage.PLPGSQL).parseStatementList()).isNotNull();
                            assertThat(new RoutineParser(body, RoutineLanguage.PLPGSQL).parseExpression()).isNotNull();
                        }
                    }
                    // 본문이 아닌 텍스트 전체도 본문 파서에 넣어 본다
                    List<Token> all = Lexer.tokenize(text, Syntax.POSTGRES);
                    assertThat(new RoutineParser(all.subList(0, all.size() - 1), RoutineLanguage.PLPGSQL).parseBody(false)).isNotNull();
                } catch (RuntimeException | StackOverflowError e) {
                    throw new AssertionError("예외 발생: [" + text + "]", e);
                }
            }
        });
    }

    @Test
    @DisplayName("F-019-AC23 F-019-AC28 퍼즈: 분석기 전체가 망가진 PostgreSQL 루틴 파일과 내장 DO 블록에 대해 ERROR 진단 없이 끝난다")
    void analyzer() {
        Random random = new Random(25L);
        List<String> texts = mutations(random, 200);
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(180), () -> {
            try (TestProject project = TestProject.create()) {
                for (int i = 0; i < texts.size(); i++) {
                    project.file("db/postgres/f" + i + ".sql", texts.get(i));
                }
                AnalysisContext ctx = project.context();
                for (int i = 0; i < texts.size(); i += 3) {
                    String owner = Ids.mybatisStatement("ns", "s" + i);
                    ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).build());
                    ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, "DO $f$ BEGIN " + texts.get(i) + " END $f$",
                            SourceLocation.of("m.xml", i + 1), i % 2 == 0, Map.of("dialect", "postgresql")));
                }
                GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
                assertThat(g.diagnostics()).noneMatch(d -> d.severity() == Severity.ERROR);
                assertThat(g.nodes(NodeKind.SQL_STATEMENT).size()).isGreaterThan(texts.size());
            }
        });
    }

    @Test
    @DisplayName("F-019-AC23 원본 픽스처는 진단 없이(풀 수 없는 동적 SQL 1건 제외) 분석된다")
    void pristineFixture() {
        try (TestProject project = TestProject.create().file("db/postgres/f.sql", SCRIPT)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(g.diagnostics()).extracting(d -> d.code() + "@" + d.location().startLine())
                    .containsExactly("DYNAMIC_SQL@" + RoutineAnalyzerTest.line(SCRIPT, "EXECUTE 'INSERT INTO ' || quote_ident(p_memo)"));
            assertThat(GraphAssert.targets(g, "db:routine:CLOSE_ORDER", io.codeatlas.core.model.EdgeKind.CALLS))
                    .containsExactly("db:routine:CALC", "db:routine:PURGE_OLD", "db:routine:TOUCH_CUSTOMER");
            assertThat(g.nodes(NodeKind.DB_TABLE)).extracting(Node::id).contains("db:table:ORDERS", "db:table:PRODUCTS", "db:table:PAYMENTS",
                    "db:table:SESSION_CACHE", "db:table:ORDER_ITEMS", "db:table:ORDER_EVENTS", "db:table:ORDERS_ARCHIVE")
                    .doesNotContain("db:table:R", "db:table:NEW", "db:table:OLD", "db:table:P_MEMO", "db:table:%I");
        }
    }
}
