package io.codeatlas.sql.script;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PostgreSQL 읽기 방식의 스크립트 분할(F-019-R9, F-019-R11): 달러 인용, {@code COPY … FROM stdin} 데이터 구간, psql 메타 명령. */
class PostgresScriptSplitterTest {

    private static List<ScriptUnit> split(String text, Syntax syntax) {
        List<ScriptUnit> units = new ArrayList<>();
        ScriptSplitter.forEach(text, units::add, null, syntax);
        return units;
    }

    private static List<ScriptUnit> split(String text) {
        return split(text, Syntax.POSTGRES);
    }

    /** 단위마다 "종류@시작줄:첫 두 단어" */
    private static String summary(List<ScriptUnit> units) {
        return units.stream()
                .map(u -> u.kind().name().charAt(0) + "@" + u.startLine() + ":"
                        + u.tokens().stream().limit(2).map(Token::upper).collect(Collectors.joining(" ")))
                .collect(Collectors.joining(" | "));
    }

    // ------------------------------------------------------------------ F-019-AC8

    @Test
    @DisplayName("F-019-AC8 달러 인용 본문의 ;에서 나누지 않는다: 실행 단위는 함수 하나와 SELECT 1 둘이다")
    void dollarQuotedBodyIsOneUnit() {
        List<ScriptUnit> units = split(
                "CREATE FUNCTION f() RETURNS int AS $body$ BEGIN DELETE FROM t; RETURN 1; END; $body$ LANGUAGE plpgsql; SELECT 1;");
        assertThat(summary(units)).isEqualTo("R@1:CREATE FUNCTION | S@1:SELECT 1");
        assertThat(units.get(0).text()).startsWith("CREATE FUNCTION f()").contains("DELETE FROM t; RETURN 1; END; $body$ LANGUAGE plpgsql");
        assertThat(units.get(0).tokens()).filteredOn(t -> t.type() == TokenType.STRING).hasSize(1);
        assertThat(units.get(1).text()).isEqualTo("SELECT 1");
    }

    @Test
    @DisplayName("F-019-AC8 여러 줄 함수, LANGUAGE가 본문 앞에 오는 꼴, 트리거, DO 블록도 종결자 ;에서 끝난다: 뒤의 구문을 삼키지 않는다")
    void routinesEndAtTerminator() {
        String script = """
                CREATE OR REPLACE FUNCTION public.close_order(p_order_id bigint)
                RETURNS integer
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    UPDATE orders SET status = 'SHIPPED' WHERE id = p_order_id;
                    RETURN 1;
                END;
                $$;

                CREATE TRIGGER orders_audit AFTER INSERT OR UPDATE ON public.orders FOR EACH ROW EXECUTE FUNCTION public.trg_orders_audit();

                DO $$ BEGIN DELETE FROM session_cache; END $$;

                CREATE PROCEDURE purge_old(p_days int) LANGUAGE sql AS 'DELETE FROM audit_log';
                CREATE TABLE t (id int);
                INSERT INTO t (id) VALUES (1);
                """;
        List<ScriptUnit> units = split(script);
        assertThat(summary(units)).isEqualTo(
                "R@1:CREATE OR | R@11:CREATE TRIGGER | S@13:DO $$ BEGIN DELETE FROM session_cache; END $$ | R@15:CREATE PROCEDURE | S@16:CREATE TABLE | S@17:INSERT INTO");
        assertThat(units.get(0).endLine()).isEqualTo(9);
        assertThat(units.get(1).text()).endsWith("public.trg_orders_audit();");
    }

    @Test
    @DisplayName("F-019-AC8 SQL 표준 본문(BEGIN ATOMIC .. END)은 안의 ;에서 끊지 않는다")
    void beginAtomicBody() {
        List<ScriptUnit> units = split("CREATE FUNCTION two() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1; SELECT CASE WHEN true THEN 2 ELSE 3 END; END;\n"
                + "CREATE FUNCTION one() RETURNS int LANGUAGE sql RETURN 1;\nSELECT 2;\n");
        assertThat(summary(units)).isEqualTo("R@1:CREATE FUNCTION | R@2:CREATE FUNCTION | S@3:SELECT 2");
        assertThat(units.get(0).text()).endsWith("END;");
    }

    @Test
    @DisplayName("F-019-AC8 닫히지 않은 달러 인용: 앞의 단위는 그대로 나오고 나머지는 닫히지 않은 문자열이 든 단위 하나다(예외 없이 끝난다)")
    void unterminatedDollarQuote() {
        List<ScriptUnit> units = split("SELECT 1;\nCREATE FUNCTION f() RETURNS int AS $$ BEGIN DELETE FROM t;\nRETURN 1; END;\nSELECT 2;\n");
        assertThat(summary(units)).isEqualTo("S@1:SELECT 1 | R@2:CREATE FUNCTION");
        assertThat(units.get(1).last().unterminated()).isTrue();
    }

    @Test
    @DisplayName("F-019-AC8 PostgreSQL 스크립트의 BEGIN; … COMMIT;은 트랜잭션 명령이다: 익명 블록으로 묶지 않는다")
    void transactionCommands() {
        List<ScriptUnit> units = split("BEGIN;\nINSERT INTO t (id) VALUES (1);\nUPDATE t SET id = 2;\nCOMMIT;\nSELECT 1;\n");
        assertThat(summary(units)).isEqualTo("S@1:BEGIN | S@2:INSERT INTO | S@3:UPDATE T | S@4:COMMIT | S@5:SELECT 1");
    }

    @Test
    @DisplayName("F-019-AC8 파일의 모양(종결자 수, 줄 시작 구문 키워드 수)을 셀 때 달러 인용 본문은 건너뛴다")
    void shapeSkipsDollarQuotes() {
        String script = "CREATE FUNCTION f() RETURNS int AS $$\nBEGIN\nDELETE FROM t;\nUPDATE t SET a = 1;\nINSERT INTO t VALUES (1);\nEND;\n$$ LANGUAGE plpgsql;\n";
        ScriptSplitter.Shape shape = ScriptSplitter.shapeOf(script, Syntax.POSTGRES);
        assertThat(shape.terminators()).isEqualTo(1);
        assertThat(shape.lineStartStatements()).isEqualTo(1);
        // 기본 읽기 방식은 전과 같이 센다
        assertThat(ScriptSplitter.shapeOf(script, Syntax.DEFAULT).terminators()).isEqualTo(5);
    }

    // ------------------------------------------------------------------ F-019-AC10

    private static final String COPY_SCRIPT = "COPY public.customers (id, email) FROM stdin;\n"
            + "1\ta@x.com; DROP TABLE t\n"
            + "2\tINSERT INTO 'ghost\n"
            + "\\.\n"
            + "\n"
            + "SELECT pg_catalog.setval('public.orders_id_seq', 10, true);\n"
            + "\\connect shop\n"
            + "COPY t FROM '/tmp/a.csv';\n";

    @Test
    @DisplayName("F-019-AC10 COPY … FROM stdin의 데이터 줄은 \\.까지 구문으로 읽지 않는다: 데이터 줄 수가 단위에 남고 그 뒤 구문은 따로 나뉜다. 줄 처음 \\ 메타 명령은 건너뛴다")
    void copyDataIsSkipped() {
        List<ScriptUnit> units = split(COPY_SCRIPT);
        assertThat(summary(units)).isEqualTo("S@1:COPY PUBLIC | S@6:SELECT PG_CATALOG | S@8:COPY T");
        assertThat(units.get(0).text()).isEqualTo("COPY public.customers (id, email) FROM stdin");
        assertThat(units.get(0).dataRows()).isEqualTo(2);
        assertThat(units.get(1).dataRows()).isEqualTo(-1);
        assertThat(units.get(1).text()).isEqualTo("SELECT pg_catalog.setval('public.orders_id_seq', 10, true)");
        assertThat(units.get(2).dataRows()).as("파일에서 읽는 COPY에는 데이터 구간이 없다").isEqualTo(-1);
    }

    @Test
    @DisplayName("F-019-AC10 데이터 구간의 변형: 빈 데이터, CRLF, \\.가 없이 끝난 파일, 옵션이 붙은 COPY, 소문자, 여러 줄에 걸친 COPY")
    void copyDataVariants() {
        List<ScriptUnit> empty = split("COPY t (a) FROM stdin;\n\\.\nSELECT 1;\n");
        assertThat(summary(empty)).isEqualTo("S@1:COPY T | S@3:SELECT 1");
        assertThat(empty.get(0).dataRows()).isZero();

        List<ScriptUnit> crlf = split("COPY t (a) FROM stdin;\r\n1\r\n2\r\n3\r\n\\.\r\nSELECT 1;\r\n");
        assertThat(summary(crlf)).isEqualTo("S@1:COPY T | S@6:SELECT 1");
        assertThat(crlf.get(0).dataRows()).isEqualTo(3);

        List<ScriptUnit> open = split("SELECT 1;\nCOPY t (a) FROM stdin;\n1\n2\n");
        assertThat(summary(open)).isEqualTo("S@1:SELECT 1 | S@2:COPY T");
        assertThat(open.get(1).dataRows()).isEqualTo(2);

        List<ScriptUnit> options = split("copy t (a, b)\n  from STDIN with (format csv, header);\na,b\n1,'x;y'\n\\.\nSELECT 1;\n");
        assertThat(summary(options)).isEqualTo("S@1:COPY T | S@6:SELECT 1");
        assertThat(options.get(0).dataRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("F-019-AC10 모양을 셀 때도 데이터 줄과 메타 명령 줄은 세지 않는다")
    void shapeSkipsCopyData() {
        ScriptSplitter.Shape shape = ScriptSplitter.shapeOf(COPY_SCRIPT, Syntax.POSTGRES);
        assertThat(shape.terminators()).isEqualTo(3);
        assertThat(shape.lineStartStatements()).isZero();
        assertThat(shape.contentLines()).isEqualTo(1);
    }

    @Test
    @DisplayName("F-019-AC10 기본 읽기 방식에서는 COPY의 데이터 구간과 메타 명령을 모른다(전과 같다): 데이터 줄이 구문으로 읽힌다")
    void defaultSyntaxDoesNotKnowCopyData() {
        List<ScriptUnit> units = split("COPY t (a) FROM stdin;\nINSERT\n\\.\nSELECT 1;\n", Syntax.DEFAULT);
        assertThat(units).hasSize(2);
        assertThat(units.get(1).first().upper()).as("데이터 줄이 구문의 처음으로 읽힌다").isEqualTo("INSERT");
        assertThat(units).allMatch(u -> u.dataRows() == -1);
    }
}
