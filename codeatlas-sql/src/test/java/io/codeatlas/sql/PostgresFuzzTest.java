package io.codeatlas.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.sql.analysis.Catalog;
import io.codeatlas.sql.analysis.FactExtractor;
import io.codeatlas.sql.lexer.Lexer;
import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.parser.DdlParser;
import io.codeatlas.sql.parser.SqlParser;
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
 * PostgreSQL 읽기 방식의 퍼즈성 테스트: 달러 인용, COPY 데이터 구간, PostgreSQL DML을 임의로 망가뜨려도 렉서·분할기·파서·분석기가
 * 예외 없이 끝난다(F-019-R9의 "파일을 멈추지 않는다").
 */
class PostgresFuzzTest {

    private static final String SCRIPT = """
            SET search_path = public, pg_catalog;
            \\connect shop
            CREATE TABLE public.customers (id bigint NOT NULL, email text, tags text[], profile jsonb);
            CREATE FUNCTION f(p_id bigint) RETURNS int AS $body$ BEGIN DELETE FROM t WHERE id = p_id; RETURN 1; END; $body$ LANGUAGE plpgsql;
            CREATE FUNCTION two() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1; SELECT CASE WHEN true THEN 2 ELSE 3 END; END;
            DO $$ BEGIN PERFORM f(1); END $$;
            COPY public.customers (id, email) FROM stdin;
            1\ta@x.com
            2\t'b; c
            \\.
            SELECT pg_catalog.setval('public.orders_id_seq', 10, true);
            BEGIN;
            INSERT INTO customers (email, name) VALUES ($1, E'it\\'s') ON CONFLICT (email) DO UPDATE SET name = EXCLUDED.name RETURNING id;
            DELETE FROM order_items oi USING orders o WHERE o.id = oi.order_id AND o.status = 'NEW';
            WITH RECURSIVE t AS (SELECT id FROM orders WHERE id = 1 UNION ALL SELECT o.id FROM orders o JOIN t ON o.customer_id = t.id),
                 del AS (DELETE FROM session_cache WHERE k = 'a' RETURNING k) SELECT t.id, (SELECT count(*) FROM del) FROM t;
            SELECT DISTINCT ON (c.id) c.id, c.profile->>'grade', c.profile #>> '{addr,city}', c.tags[1], x.amount::text, g.n
              FROM customers c LEFT JOIN LATERAL (SELECT amount FROM orders o WHERE o.customer_id = c.id LIMIT 1) x ON true
              CROSS JOIN generate_series(1, 3) WITH ORDINALITY AS g(n, i)
             WHERE c.name ILIKE '%a%' AND c.tags @> ARRAY['x']::text[] AND c.email ~* 'vip' AND c.id = ANY($2::bigint[])
               AND c.created_at >= now() - interval '7 days' AND c.memo NOT SIMILAR TO 'x%'
             ORDER BY c.id NULLS LAST LIMIT ALL OFFSET 5 FOR NO KEY UPDATE OF c SKIP LOCKED;
            UPDATE ONLY orders o SET status = 'S' FROM customers c WHERE c.id = o.customer_id RETURNING *;
            TRUNCATE TABLE ONLY orders, customers RESTART IDENTITY CASCADE;
            SELECT nextval('orders_id_seq'::regclass), setval('s', (SELECT max(id) FROM orders));
            COMMIT;
            """;

    private static final String JUNK = "()',;.=*@{}:?\"-/\n \t%&|<>!#$[]\\~";
    private static final List<String> WORDS = List.of(" $$ ", "$body$", " $x$ ", "\n\\.\n", "\nCOPY t FROM stdin;\n", " ON CONFLICT ", " DO ", " USING ",
            " ARRAY[", " [", "] ", "::", " ->> ", " #>> ", " @> ", " ~* ", " WITH ", " AS (", " DELETE ", " RETURNING ", " ONLY ", " BEGIN ", " END ",
            " ATOMIC ", " E'", "\n\\x\n", " LATERAL ", " ILIKE ", " SIMILAR TO ", " nextval('", "${x}", "#{y}", " FOR ", " LIMIT ");

    private static List<String> mutations(Random random, int count) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i <= SCRIPT.length(); i += 5) {
            list.add(SCRIPT.substring(0, i));
            list.add(SCRIPT.substring(i));
        }
        list.add(SCRIPT.replace("(", ""));
        list.add(SCRIPT.replace(")", ""));
        list.add(SCRIPT.replace(";", ""));
        list.add(SCRIPT.replace("'", ""));
        list.add(SCRIPT.replace("$", ""));
        list.add(SCRIPT.replace("]", ""));
        list.add(SCRIPT.replace("\n", "\r\n"));
        list.add("[".repeat(3000) + "ARRAY[".repeat(3000));
        list.add("SELECT " + "ARRAY[".repeat(3000) + "1" + "]".repeat(3000));
        list.add("WITH a AS (DELETE FROM t RETURNING " + "(WITH b AS (DELETE FROM u RETURNING ".repeat(500));
        for (int n = 0; n < count; n++) {
            StringBuilder sb = new StringBuilder(SCRIPT);
            int edits = 1 + random.nextInt(6);
            for (int e = 0; e < edits && sb.length() > 0; e++) {
                int at = random.nextInt(sb.length());
                switch (random.nextInt(4)) {
                    case 0 -> sb.deleteCharAt(at);
                    case 1 -> sb.insert(at, JUNK.charAt(random.nextInt(JUNK.length())));
                    case 2 -> sb.delete(at, Math.min(sb.length(), at + random.nextInt(40)));
                    default -> sb.insert(at, WORDS.get(random.nextInt(WORDS.size())));
                }
            }
            list.add(sb.toString());
        }
        return list;
    }

    @Test
    @DisplayName("F-019-AC8 F-019-AC9 F-019-AC10 퍼즈: PostgreSQL 읽기 방식의 렉서, 분할기, DML·DDL 파서, 사실 추출이 망가진 입력에 예외 없이 끝난다")
    void lexerSplitterAndParser() {
        Random random = new Random(19L);
        FactExtractor extractor = new FactExtractor(new Catalog());
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (String text : mutations(random, 1500)) {
                try {
                    List<Token> all = Lexer.tokenize(text, Syntax.POSTGRES);
                    assertThat(all.get(all.size() - 1).isEof()).isTrue();
                    for (Token t : all) {
                        if (t.dollarQuoted()) {
                            assertThat(Lexer.tokenizeBody(t, Syntax.POSTGRES)).isNotEmpty();
                        }
                    }
                    for (boolean embedded : new boolean[] {false, true}) {
                        List<ScriptUnit> units = new ArrayList<>();
                        if (embedded) {
                            units.addAll(ScriptSplitter.splitEmbedded(text, Syntax.POSTGRES));
                        } else {
                            ScriptSplitter.forEach(text, units::add, null, Syntax.POSTGRES);
                        }
                        for (ScriptUnit unit : units) {
                            assertThat(unit.tokens()).isNotEmpty();
                            if (unit.kind() != ScriptUnit.Kind.STATEMENT) {
                                continue;
                            }
                            List<Token> tokens = new ArrayList<>(unit.tokens());
                            extractor.extract(DdlParser.isDdl(tokens) ? new DdlParser(tokens, Syntax.POSTGRES).parse()
                                    : new SqlParser(tokens, Syntax.POSTGRES).parseStatement());
                            // 겹치지 않는 문법은 기본 읽기 방식의 파서에도 있다
                            extractor.extract(SqlParser.parse(unit.text(), Syntax.DEFAULT));
                        }
                    }
                } catch (RuntimeException | StackOverflowError e) {
                    throw new AssertionError("예외 발생: [" + text + "]", e);
                }
            }
        });
    }

    @Test
    @DisplayName("F-019-AC8 F-019-AC10 퍼즈: 분석기 전체가 망가진 PostgreSQL 파일과 내장 소스에 대해 ERROR 진단 없이 끝난다")
    void analyzer() {
        Random random = new Random(23L);
        List<String> texts = mutations(random, 150);
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            try (TestProject project = TestProject.create()) {
                for (int i = 0; i < texts.size(); i++) {
                    project.file("db/postgres/f" + i + ".sql", texts.get(i));
                }
                AnalysisContext ctx = project.context();
                for (int i = 0; i < texts.size(); i++) {
                    String owner = Ids.mybatisStatement("ns", "s" + i);
                    ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).build());
                    ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, texts.get(i),
                            SourceLocation.of("m.xml", i + 1), i % 2 == 0, Map.of("dialect", "postgresql")));
                }
                GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
                assertThat(g.diagnostics()).noneMatch(d -> d.severity() == io.codeatlas.core.model.Severity.ERROR);
                assertThat(g.nodeCount()).isGreaterThan(texts.size());
            }
        });
    }
}
