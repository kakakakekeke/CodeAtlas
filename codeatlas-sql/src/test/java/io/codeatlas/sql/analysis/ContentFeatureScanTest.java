package io.codeatlas.sql.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.spi.Dialect;
import io.codeatlas.sql.lexer.Syntax;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 내용 특징 판정(F-007-R24, F-019-R2·R7)을 본문을 한 번 훑어서 하는 구현({@link DialectDetector#content})이, 패턴마다 본문 전체에
 * {@code find()}를 돌리는 판정과 같은 답을 내는지 본다. 한 번 훑는 구현은 패턴이 시작할 수 있는 자리(후보)에서만 패턴을 맞춰 보므로,
 * 패턴에 대안을 더하고 후보 표를 고치지 않으면 여기서 어긋난다.
 */
class ContentFeatureScanTest {

    private static final int PG_NONE = 0;
    private static final int PG_WEAK = 1;
    private static final int PG_STRONG = 2;

    /** 본문 전체에 정규식을 돌려 구한 PostgreSQL 내용 특징의 세기: 코드 자리(주석·문자열 밖)에서 시작한 일치만 센다. */
    private static int referencePostgresLevel(String text) {
        int n = text.length();
        boolean[] code = new boolean[n + 1];
        DialectDetector.CodePositions positions = new DialectDetector.CodePositions(text);
        for (int i = 0; i < n; ) {
            int end = positions.regionEndAt(i);
            if (end > i) {
                i = end;
            } else {
                code[i++] = true;
            }
        }
        Matcher m = DialectDetector.POSTGRES_FEATURES.matcher(text);
        int level = PG_NONE;
        int from = 0;
        while (from <= n && m.find(from)) {
            if (code[m.start()]) {
                if (m.start(1) >= 0) {
                    return PG_STRONG;
                }
                level = PG_WEAK;
            }
            from = m.start() + 1;
        }
        return level;
    }

    private static boolean referenceMysql(String text) {
        return DialectDetector.MYSQL_FEATURES.matcher(text).find();
    }

    /** 본문 전체에 정규식을 차례로 돌려 구한 방언: HSQL → MySQL → PostgreSQL 강 → Oracle → DB2 → PostgreSQL 약. */
    private static String referenceDialect(String text) {
        if (text.isEmpty()) {
            return null;
        }
        if (DialectDetector.HSQL_FEATURES.matcher(text).find()) {
            return "hsql";
        }
        if (referenceMysql(text)) {
            return "mysql";
        }
        int postgres = referencePostgresLevel(text);
        if (postgres == PG_STRONG) {
            return "postgres";
        }
        if (DialectDetector.ORACLE_FEATURES.matcher(text).find()) {
            return "oracle";
        }
        if (DialectDetector.DB2_IDENTITY.matcher(text).find() && DialectDetector.DB2_TABLESPACE.matcher(text).find()) {
            return "db2";
        }
        return postgres == PG_WEAK ? "postgres" : null;
    }

    private static Syntax referenceSyntax(String text) {
        if (referenceMysql(text)) {
            return Syntax.MYSQL;
        }
        return "postgres".equals(referenceDialect(text)) ? Syntax.POSTGRES : Syntax.DEFAULT;
    }

    private static void assertSameAsReference(String text) {
        String shown = text.length() > 300 ? text.substring(0, 300) + "…" : text;
        String label = "[" + shown.replace("\n", "\\n").replace("\r", "\\r") + "]";
        DialectDetector.Content content = DialectDetector.content(text);
        assertThat(content.dialect()).as("dialect %s", label).isEqualTo(referenceDialect(text));
        assertThat(content.mysql()).as("mysql %s", label).isEqualTo(referenceMysql(text));
        assertThat(DialectDetector.contentDialect(text)).as("contentDialect %s", label).isEqualTo(referenceDialect(text));
        assertThat(DialectDetector.mysqlFeatures(text)).as("mysqlFeatures %s", label).isEqualTo(referenceMysql(text));
        assertThat(DialectDetector.postgresFeatures(text)).as("postgresFeatures %s", label).isEqualTo(referencePostgresLevel(text) != PG_NONE);
        assertThat(DialectDetector.syntaxOfFile(null, null, content, null)).as("syntaxOfFile %s", label).isEqualTo(referenceSyntax(text));
        assertThat(DialectDetector.syntaxOfEmbedded(null, null, text)).as("syntaxOfEmbedded %s", label).isEqualTo(referenceSyntax(text));
    }

    /** 패턴의 대안마다 하나 이상: 그 대안이 맞는 본문. */
    private static final List<String> FEATURES = List.of(
            "AS $$", "AS $body$", "as\n$f1$", "DO $$", "do\t$x$", "LANGUAGE plpgsql", "language 'plpgsql'", "LANGUAGE\nPLPGSQL",
            "ALTER TABLE ONLY t", "alter  table if exists only t", "COPY t (a, b) FROM stdin", "copy public.t from STDIN", "COPY t(a)FROM stdin",
            "CREATE EXTENSION x", "create\textension x", "::regclass", ":: regclass", "SET search_path", "set  SEARCH_PATH", "pg_catalog.", "PG_CATALOG .",
            "OWNER TO app", "owner\nto app", "bigserial", "SMALLSERIAL", "jsonb", "Bytea", "timestamptz",
            "(id serial,", ", \"id\" serial)", "(id serial PRIMARY", ",id SERIAL NOT", "( _a1 serial default", "(id serial references", "#>", "#>>",
            "CREATE MEMORY TABLE", "create cached table", "SET DATABASE", "  set\tdatabase", "`name`", "`a$1`", "AUTO_INCREMENT", "auto_increment",
            "ENGINE = InnoDB", "engine=x", "VARCHAR2", "nvarchar2", "NUMBER (", "number(10)", "GENERATED ALWAYS AS IDENTITY",
            "generated by  default as identity", ") IN TS1", ")in \"TS\"");

    /** 특징의 조각, 구분자, 주석·문자열을 여닫는 글자, 이름 글자: 특징을 만들거나 깨뜨리거나 코드 밖으로 밀어낸다. */
    private static final List<String> GLUE = List.of(
            " ", " ", "\n", "\n", "\r\n", "\r", "\t", "\f", "\u000B", "\u2028", "\u2029", "\u0085", ";", ",", "(", ")", "'", "''", "--", "/*", "*/",
            "q'[", "]'", "nq'{", "}'", "E'", "e'\\'", "\\", "\"", "x", "A", "_", "$", "#", "1", "9", "한글", "é", ".", ":", "::", ">", "=", "`", "S", "se", "as", "do",
            "co", "al", "cr", "set", "serial", "number", "language", "owner", "copy", "create", "table", "only", "from", "stdin", "to", "plpgsql",
            "regclass", "search_path", "generated", "always", "engine", "memory", "database", "identity", "in", "if", "exists", "extension", "alter",
            "pg_catalog", "primary", "not", "varchar", "auto", "increment", "bytea", "json", "big", "small");

    @Test
    @DisplayName("F-019-AC2 한 번 훑는 내용 판정은 패턴마다 본문 전체에 정규식을 돌린 판정과 같다: 특징 하나짜리 본문, 앞뒤에 글자가 붙은 본문, 주석·문자열 속 본문")
    void scanAgreesWithWholeTextRegexOnFeatures() {
        assertSameAsReference("");
        for (String feature : FEATURES) {
            assertSameAsReference(feature);
            assertSameAsReference(feature.toUpperCase(Locale.ROOT));
            assertSameAsReference(feature.toLowerCase(Locale.ROOT));
            for (String glue : GLUE) {
                assertSameAsReference(glue + feature);
                assertSameAsReference(feature + glue);
                assertSameAsReference(glue + feature + glue);
                assertSameAsReference("SELECT 1;\n" + glue + " " + feature + " " + glue + "\nSELECT 2;");
            }
            // 특징의 앞이나 뒤를 한 글자씩 잘라 낸 것: 깨진 특징은 특징이 아니다
            for (int cut = 1; cut < feature.length(); cut++) {
                assertSameAsReference(feature.substring(cut));
                assertSameAsReference(feature.substring(0, cut));
            }
            assertSameAsReference("-- " + feature + "\nSELECT 1");
            assertSameAsReference("/* " + feature + " */ SELECT 1");
            assertSameAsReference("SELECT '" + feature.replace("'", "''") + "' FROM T");
            assertSameAsReference("SELECT 'a' FROM T; " + feature + " ; -- x");
        }
        for (String a : FEATURES) {
            for (String b : FEATURES) {
                assertSameAsReference(a + "\n" + b);
                assertSameAsReference("-- " + a + "\n" + b);
            }
        }
    }

    @Test
    @DisplayName("F-019-AC2 한 번 훑는 내용 판정은 특징·조각·주석·문자열을 임의로 이어 붙인 본문에서도 본문 전체에 정규식을 돌린 판정과 같다")
    void scanAgreesWithWholeTextRegexOnRandomTexts() {
        Random random = new Random(20261011L);
        List<String> all = new ArrayList<>(GLUE);
        all.addAll(FEATURES);
        for (int round = 0; round < 30_000; round++) {
            StringBuilder sb = new StringBuilder();
            int parts = 1 + random.nextInt(24);
            for (int i = 0; i < parts; i++) {
                // 조각 사이를 붙이거나 띄운다. 특징은 셋에 하나꼴로 넣는다
                String part = random.nextInt(3) == 0 ? FEATURES.get(random.nextInt(FEATURES.size())) : all.get(random.nextInt(all.size()));
                sb.append(part);
                if (random.nextInt(3) == 0) {
                    sb.append(' ');
                }
            }
            String text = sb.toString();
            if (random.nextInt(4) == 0) {
                text = random.nextBoolean() ? text.toUpperCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT);
            }
            assertSameAsReference(text);
        }
    }

    @Test
    @DisplayName("F-019-AC2 한 번 훑는 내용 판정은 빈 줄이 아주 많거나 여는 괄호·쉼표·줄 처음이 아주 많은 큰 본문도 한 번 훑는 시간에 끝난다")
    void scanIsLinearOnWhitespaceAndCandidateHeavyTexts() {
        String blankLines = "\n".repeat(400_000) + "SELECT 1;\n";
        String valueLists = "INSERT INTO T (A, B, C) VALUES (1, 'x, (y', 2), (3, 'as do copy set owner', 4);\n".repeat(60_000);
        String words = "as do la al co cr se sm pg ow bi by js ti au en nv va nu ge serial ( a , b\n".repeat(60_000);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () -> {
            for (String text : List.of(blankLines, valueLists, words)) {
                assertThat(DialectDetector.content(text)).isEqualTo(new DialectDetector.Content(null, false));
                assertThat(DialectDetector.content(text + "SET DATABASE x;\n").dialect()).isEqualTo("hsql");
                assertThat(DialectDetector.content(text + "ALTER TABLE ONLY t ADD x int;\n").dialect()).isEqualTo("postgres");
            }
        });
    }

    @Test
    @DisplayName("F-019-AC2 F-019-AC7 내용 판정 한 번의 결과(Content)가 파일의 방언과 읽기 방식을 함께 정한다: 본문을 받는 syntaxOfFile과 같은 답이고, 경로·옵션·프로젝트 방언이 먼저다")
    void contentDecidesDialectAndSyntaxTogether() {
        String pgDump = "ALTER TABLE ONLY public.orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);";
        String mysql = "CREATE TABLE `t` (`id` bigint) ENGINE=InnoDB;";
        String hsqlWithBacktick = "CREATE MEMORY TABLE T (ID INT);\nINSERT INTO `t` VALUES (1);";
        String oracle = "CREATE TABLE T (ID NUMBER(10), NM VARCHAR2(20));";
        String plain = "CREATE TABLE T (ID INT);";

        assertThat(DialectDetector.content(pgDump)).isEqualTo(new DialectDetector.Content("postgres", false));
        assertThat(DialectDetector.content(mysql)).isEqualTo(new DialectDetector.Content("mysql", true));
        // HSQL 특징이 방언으로는 먼저지만, 백틱이 있는 본문은 MySQL 표기로 읽어야 한다(전과 같다)
        assertThat(DialectDetector.content(hsqlWithBacktick)).isEqualTo(new DialectDetector.Content("hsql", true));
        assertThat(DialectDetector.content(oracle)).isEqualTo(new DialectDetector.Content("oracle", false));
        assertThat(DialectDetector.content(plain)).isEqualTo(new DialectDetector.Content(null, false));
        assertThat(DialectDetector.content(null)).isEqualTo(new DialectDetector.Content(null, false));
        assertThat(DialectDetector.content("")).isEqualTo(new DialectDetector.Content(null, false));

        for (String text : List.of(pgDump, mysql, hsqlWithBacktick, oracle, plain, "")) {
            DialectDetector.Content content = DialectDetector.content(text);
            for (String pathToken : new String[] {null, "postgres", "oracle", "mysql", "hsql"}) {
                for (Dialect option : new Dialect[] {null, Dialect.AUTO, Dialect.ORACLE, Dialect.DB2, Dialect.POSTGRES}) {
                    for (Syntax project : new Syntax[] {null, Syntax.DEFAULT, Syntax.MYSQL, Syntax.POSTGRES}) {
                        assertThat(DialectDetector.syntaxOfFile(pathToken, option, content, project))
                                .as("%s / %s / %s / %s", text, pathToken, option, project)
                                .isEqualTo(DialectDetector.syntaxOfFile(pathToken, option, text, project));
                    }
                }
            }
        }
        assertThat(DialectDetector.syntaxOfFile(null, null, DialectDetector.content(pgDump), null)).isEqualTo(Syntax.POSTGRES);
        assertThat(DialectDetector.syntaxOfFile(null, null, DialectDetector.content(mysql), null)).isEqualTo(Syntax.MYSQL);
        assertThat(DialectDetector.syntaxOfFile(null, null, DialectDetector.content(hsqlWithBacktick), null)).isEqualTo(Syntax.MYSQL);
        assertThat(DialectDetector.syntaxOfFile(null, null, DialectDetector.content(oracle), null)).isEqualTo(Syntax.DEFAULT);
        // 경로에 방언 이름이 있으면 내용 판정을 하지 않는다: 판정이 없어도(null) 된다
        assertThat(DialectDetector.syntaxOfFile("postgres", null, (DialectDetector.Content) null, null)).isEqualTo(Syntax.POSTGRES);
        assertThat(DialectDetector.syntaxOfFile(null, Dialect.ORACLE, (DialectDetector.Content) null, Syntax.POSTGRES)).isEqualTo(Syntax.DEFAULT);
    }
}
