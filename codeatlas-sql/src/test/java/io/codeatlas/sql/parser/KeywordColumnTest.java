package io.codeatlas.sql.parser;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.sql.analysis.Catalog;
import io.codeatlas.sql.analysis.FactExtractor;
import io.codeatlas.sql.analysis.StatementFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 비예약 키워드와 같은 이름의 **비한정 컬럼**(F-006-R33). 한국 SI의 Oracle·DB2 스키마에는 {@code TYPE}, {@code STATUS}, {@code LEVEL},
 * {@code KEY}, {@code VALUE}, {@code COMMENT}, {@code LIMIT} 같은 컬럼 이름이 흔하다. 파서가 절이나 특수 구문의 시작으로 쓰는 낱말이라도
 * 컬럼 자리(SELECT 목록, WHERE, ORDER BY, GROUP BY, INSERT 컬럼 목록, UPDATE SET, 별칭 한정)에서는 컬럼으로 읽어야 한다.
 * {@link KeywordNameTest}(F-006-R17)가 테이블 이름·별칭 자리를 본다면 이 테스트는 식 자리를 본다.
 */
class KeywordColumnTest {

    /** 과제에서 짚은 이름: 흔한 컬럼 이름이면서 파서가 키워드로도 쓰는 것. */
    private static final List<String> COMMON = List.of(
            "LIMIT", "OFFSET", "WINDOW", "ESCAPE", "ROWS", "ONLY", "SAMPLE", "LOG", "KEY", "VALUE", "TYPE", "NAME", "STATUS",
            "COMMENT", "DATE", "TIMESTAMP", "YEAR", "MONTH", "DAY", "USER", "SIZE", "LEVEL", "MODE", "ACTION", "POSITION", "RESULT");

    /** 식의 첫 토큰이 될 수 없는 절 키워드로 다뤘지만 예약어가 아닌 것({@link Keywords#SOFT_CLAUSE_KEYWORDS}). */
    private static final List<String> SOFT_CLAUSE = List.of("LIMIT", "OFFSET", "WINDOW", "QUALIFY", "ESCAPE", "BULK", "LOOP");

    /** 파서가 어딘가에서 키워드로 읽는 그 밖의 비예약 낱말(절·수식어·함수 인자 키워드, 기간 단위, 특수 이름, SQL*Plus 명령 등). */
    private static final List<String> OTHER = List.of(
            // 기간 단위, 시간
            "TIME", "HOUR", "MINUTE", "SECOND", "YEARS", "MONTHS", "DAYS", "HOURS", "MINUTES", "SECONDS", "MICROSECOND", "ZONE",
            "TIMEZONE", "INTERVAL",
            // 분석 함수·페이징·잠금 절의 낱말
            "ROW", "RANGE", "GROUPS", "PARTITION", "SUBPARTITION", "OVER", "FILTER", "WITHIN", "KEEP", "IGNORE", "RESPECT", "NULLS",
            "FIRST", "NEXT", "LAST", "PREVIOUS", "TIES", "PERCENT", "SKIP", "WAIT", "NOWAIT", "LOCKED", "LOCK", "SHARE", "EXCLUSIVE",
            "READ", "OPTION", "CHECK", "LOCAL", "CASCADED", "OPTIMIZE", "QUERYNO", "UR", "CS", "RS", "RR", "USE", "LOCKS",
            // 계층·재귀·피벗·플래시백
            "SIBLINGS", "NOCYCLE", "CYCLE", "SEARCH", "RECURSIVE", "MATERIALIZED", "PIVOT", "UNPIVOT", "MODEL", "XML", "INCLUDE",
            "EXCLUDE", "VERSIONS", "SCN", "PERIOD", "SEED", "BLOCK",
            // 테이블 참조·DML의 낱말
            "NEW", "OLD", "FINAL", "THE", "LATERAL", "APPLY", "MATCHED", "OVERRIDING", "SYSTEM", "RETURN", "CALL", "DO", "ATOMIC",
            "ERRORS", "IMMEDIATE", "COLLECT", "DATA", "MESSAGE", "TEMP",
            // 함수 인자 안의 낱말
            "CONTENT", "DOCUMENT", "PASSING", "COLUMNS", "SEPARATOR", "PLACING", "LEADING", "TRAILING", "BOTH", "PRECISION",
            "VARYING", "NATIONAL",
            // 특수 레지스터·의사 이름(카탈로그에 컬럼으로 있으면 컬럼이다)
            "SCHEMA", "SERVER", "PATH", "DEGREE", "ISOLATION", "MEMBER", "SQLID", "UID", "ROWID", "SQL", "DUMMY", "ISO",
            // 구문·스크립트 명령의 첫 낱말
            "LABEL", "COLUMN", "HOST", "PASSWORD", "VARIABLE", "PRINT", "SHOW", "ECHO", "EXIT", "TIMING", "BREAK", "CLEAR", "COMPUTE",
            "PAUSE", "DESCRIBE", "ANALYZE", "RENAME", "REFRESH", "RELEASE", "FLUSH", "PURGE", "COMMIT", "ROLLBACK", "SAVEPOINT",
            "EXECUTE", "EXEC", "EXPLAIN", "TRUNCATE", "MERGE", "CONCAT",
            // 흔한 이름
            "CODE", "SEQ", "STATE", "OWNER", "TITLE", "CONTENTS", "COUNT", "SUM", "MIN", "MAX", "TEXT", "NO", "YES", "ID");

    static Stream<String> columnNames() {
        Set<String> all = new TreeSet<>(COMMON);
        all.addAll(SOFT_CLAUSE);
        all.addAll(OTHER);
        return all.stream();
    }

    /** T(ID, NM, k), U(ID, k). */
    private static Catalog catalog(String k) {
        Catalog c = new Catalog();
        if (k.equals("ID")) {
            c.defineTable("T", "ID", "NM");
            c.defineTable("U", "ID", "NM");
        } else {
            c.defineTable("T", "ID", "NM", k);
            c.defineTable("U", "ID", k);
        }
        return c;
    }

    private static StatementFacts clean(String sql, Catalog catalog) {
        ParseResult r = SqlParser.parse(sql);
        assertThat(r.problems()).as("problems of [%s]", sql).isEmpty();
        return new FactExtractor(catalog).extract(r);
    }

    private static String reads(StatementFacts f) {
        return f.columnReads().stream().map(c -> c.table() + "." + c.column()).distinct().sorted().collect(Collectors.joining(","));
    }

    private static String writes(StatementFacts f) {
        return f.columnWrites().stream().map(c -> c.table() + "." + c.column()).distinct().sorted().collect(Collectors.joining(","));
    }

    private static List<String> tables(StatementFacts f) {
        return Stream.concat(f.tableReads().stream().map(StatementFacts.TableRead::table),
                f.tableWrites().stream().map(StatementFacts.TableWrite::table)).distinct().sorted().collect(Collectors.toList());
    }

    private static String joins(StatementFacts f) {
        return f.joins().stream()
                .map(j -> j.leftTable() + "(" + String.join(",", j.leftColumns()) + ")=" + j.rightTable() + "(" + String.join(",", j.rightColumns()) + ")")
                .sorted().collect(Collectors.joining(";"));
    }

    /** 기대 컬럼 목록: 중복을 없애고 정렬한다(k가 ID일 때도 같은 식으로 쓴다). */
    private static String cols(String... columns) {
        return Stream.of(columns).distinct().sorted().collect(Collectors.joining(","));
    }

    /** 한 문맥의 SQL과 기대(읽은 컬럼, 쓴 컬럼). */
    private record Case(String sql, String reads, String writes) {
    }

    private static List<Case> cases(String k) {
        String tk = "T." + k;
        List<Case> list = new ArrayList<>();
        // SELECT 목록
        list.add(new Case("SELECT " + k + " FROM T", cols(tk), ""));
        list.add(new Case("SELECT ID, " + k + " FROM T", cols("T.ID", tk), ""));
        list.add(new Case("SELECT " + k + ", NM FROM T", cols("T.NM", tk), ""));
        list.add(new Case("SELECT " + k + " AS X, NM FROM T", cols("T.NM", tk), ""));
        list.add(new Case("SELECT DISTINCT " + k + " FROM T", cols(tk), ""));
        list.add(new Case("SELECT NVL(" + k + ", 0), MAX(" + k + "), NM || " + k + " FROM T", cols("T.NM", tk), ""));
        list.add(new Case("SELECT CASE WHEN " + k + " = 1 THEN 'A' ELSE 'B' END FROM T", cols(tk), ""));
        // WHERE
        list.add(new Case("SELECT NM FROM T WHERE " + k + " = ?", cols("T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE " + k + " > 0 AND ID = ?", cols("T.ID", "T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE ID = ? AND " + k + " IS NOT NULL", cols("T.ID", "T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE ID = ? OR " + k + " <> 'X'", cols("T.ID", "T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE " + k + " IN (1, 2)", cols("T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE " + k + " NOT IN ('A')", cols("T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE " + k + " LIKE 'A%'", cols("T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE " + k + " BETWEEN 1 AND 2", cols("T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE (" + k + " = 1 OR " + k + " = 2)", cols("T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE ? = " + k, cols("T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE ID = " + k, cols("T.ID", "T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE ID < " + k + " + 1", cols("T.ID", "T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE " + k + " = (SELECT MAX(" + k + ") FROM U)", cols("T.NM", tk, "U." + k), ""));
        list.add(new Case("DELETE FROM T WHERE " + k + " = ?", cols(tk), ""));
        // ORDER BY, GROUP BY, HAVING
        list.add(new Case("SELECT NM FROM T ORDER BY " + k, cols("T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T ORDER BY " + k + " DESC", cols("T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T ORDER BY NM, " + k + " ASC", cols("T.NM", tk), ""));
        list.add(new Case("SELECT NM FROM T WHERE ID = ? ORDER BY " + k + ", NM", cols("T.ID", "T.NM", tk), ""));
        list.add(new Case("SELECT " + k + ", COUNT(*) FROM T GROUP BY " + k, cols(tk), ""));
        list.add(new Case("SELECT NM, " + k + " FROM T GROUP BY NM, " + k + " HAVING MAX(" + k + ") > 1 ORDER BY " + k, cols("T.NM", tk), ""));
        // INSERT 컬럼 목록, UPDATE SET
        list.add(new Case("INSERT INTO T (" + k + ", NM) VALUES (?, ?)", "", cols("T.NM", tk)));
        list.add(new Case("INSERT INTO T (NM, " + k + ") VALUES (?, ?)", "", cols("T.NM", tk)));
        list.add(new Case("INSERT INTO T (NM, " + k + ") SELECT 'x', " + k + " FROM U", cols("U." + k), cols("T.NM", tk)));
        list.add(new Case("UPDATE T SET " + k + " = ? WHERE NM = ?", cols("T.NM"), cols(tk)));
        list.add(new Case("UPDATE T SET NM = ?, " + k + " = " + k + " + 1 WHERE " + k + " < 10", cols(tk), cols("T.NM", tk)));
        // 별칭 한정
        list.add(new Case("SELECT A." + k + " FROM T A WHERE A." + k + " = ? ORDER BY A." + k, cols(tk), ""));
        list.add(new Case("SELECT T." + k + " FROM T GROUP BY T." + k, cols(tk), ""));
        list.add(new Case("UPDATE T A SET A." + k + " = ? WHERE A." + k + " IS NULL", cols(tk), cols(tk)));
        return list;
    }

    @ParameterizedTest(name = "컬럼 {0}")
    @MethodSource("columnNames")
    @DisplayName("F-006-AC33 비예약 키워드를 비한정 컬럼 이름으로: SELECT 목록·WHERE·ORDER BY·GROUP BY·INSERT 컬럼 목록·UPDATE SET·별칭 한정")
    void asUnqualifiedColumn(String k) {
        Catalog catalog = catalog(k);
        for (Case c : cases(k)) {
            StatementFacts f = clean(c.sql(), catalog);
            assertThat(reads(f)).as("읽은 컬럼 [%s]", c.sql()).isEqualTo(c.reads());
            assertThat(writes(f)).as("쓴 컬럼 [%s]", c.sql()).isEqualTo(c.writes());
        }
        // 조인 키로도 읽힌다
        StatementFacts join = clean("SELECT A.NM FROM T A, U B WHERE A." + k + " = B." + k, catalog);
        assertThat(joins(join)).isEqualTo("T(" + k + ")=U(" + k + ")");
        StatementFacts merge = clean("MERGE INTO T D USING U S ON (D.ID = S.ID) WHEN MATCHED THEN UPDATE SET D." + k + " = S." + k
                + " WHEN NOT MATCHED THEN INSERT (ID, " + k + ") VALUES (S.ID, S." + k + ")", catalog);
        assertThat(writes(merge)).isEqualTo(cols("T.ID", "T." + k));
    }

    @ParameterizedTest(name = "컬럼 {0}")
    @MethodSource("columnNames")
    @DisplayName("F-006-AC33 DDL이 없어도 문제 없이 파싱되고 테이블은 그대로다(의사 컬럼·특수 이름이 아니면 하나뿐인 테이블의 컬럼이 된다)")
    void parsesWithoutCatalog(String k) {
        Catalog none = new Catalog();
        // 의사 컬럼·특수 이름(USER, UID, ROWID 등)은 DDL에 컬럼으로 있을 때만 컬럼이다. 한정해 쓰면(ROWID 말고는) 컬럼이다
        boolean special = Keywords.PSEUDO_COLUMNS.contains(k) && !k.equals("LEVEL");
        for (Case c : cases(k)) {
            StatementFacts f = clean(c.sql(), none);
            assertThat(tables(f)).as("테이블 [%s]", c.sql()).doesNotContain(k).contains("T");
            if (!special || (!k.equals("ROWID") && (c.sql().contains("A." + k) || c.sql().contains("T." + k)))) {
                assertThat(reads(f) + "|" + writes(f)).as("컬럼 [%s]", c.sql()).contains("T." + k);
            }
        }
    }

    @Test
    @DisplayName("F-006-AC33 진짜 절은 그대로 절이다: LIMIT n, OFFSET n ROWS, WINDOW w AS (..), LIKE .. ESCAPE '\\', BULK COLLECT, QUALIFY 조건")
    void realClausesStillRecognized() {
        Catalog none = new Catalog();
        assertThat(reads(clean("SELECT NM FROM T WHERE ID = ? LIMIT 10", none))).isEqualTo("T.ID,T.NM");
        assertThat(reads(clean("SELECT NM FROM T WHERE ID = ? LIMIT ? OFFSET ?", none))).isEqualTo("T.ID,T.NM");
        assertThat(reads(clean("SELECT NM FROM T WHERE ID = ? LIMIT #{offset}, #{limit}", none))).isEqualTo("T.ID,T.NM");
        assertThat(reads(clean("SELECT NM FROM T ORDER BY NM OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY", none))).isEqualTo("T.NM");
        assertThat(reads(clean("SELECT NM FROM T ORDER BY NM OFFSET :skip ROWS FETCH FIRST :n ROWS ONLY", none))).isEqualTo("T.NM");
        assertThat(reads(clean("SELECT NM, SUM(ID) OVER W FROM T WINDOW W AS (PARTITION BY NM)", none))).contains("T.NM");
        assertThat(reads(clean("SELECT NM FROM T WHERE NM LIKE ? ESCAPE '\\' AND ID = 1", none))).isEqualTo("T.ID,T.NM");
        assertThat(reads(clean("SELECT NM FROM T WHERE NM LIKE '%' || ? || '%' ESCAPE ?", none))).isEqualTo("T.NM");
        assertThat(reads(clean("SELECT NM BULK COLLECT INTO V_LIST FROM T WHERE ID = 1", none))).isEqualTo("T.ID,T.NM");
        assertThat(reads(clean("SELECT NM FROM T WHERE ID = 1 QUALIFY ROW_NUMBER() OVER (ORDER BY NM) = 1", none))).isEqualTo("T.ID,T.NM");
        // 컬럼과 절이 한 구문에 함께 있다
        assertThat(reads(clean("SELECT LIMIT, OFFSET FROM T WHERE LIMIT > 0 ORDER BY OFFSET LIMIT 10 OFFSET 5", none)))
                .isEqualTo("T.LIMIT,T.OFFSET");
        assertThat(reads(clean("SELECT NM FROM T WHERE ESCAPE = 'Y' AND NM LIKE ? ESCAPE '!'", none))).isEqualTo("T.ESCAPE,T.NM");
    }

    @Test
    @DisplayName("F-006-AC33 깨진 구문의 절 키워드는 컬럼이 되지 않는다: 피연산자가 빠진 자리의 LIMIT n, OFFSET n")
    void brokenStatementsDoNotTurnClausesIntoColumns() {
        for (String sql : List.of("SELECT NM FROM T WHERE ID = LIMIT 10", "SELECT NM FROM T WHERE ID = ? AND LIMIT 10",
                "SELECT NM FROM T ORDER BY LIMIT 5", "SELECT NM FROM T WHERE ID > OFFSET 5 ROWS", "SELECT NM FROM T WHERE LIMIT ? OFFSET ?")) {
            StatementFacts f = new FactExtractor(new Catalog()).extract(SqlParser.parse(sql));
            assertThat(reads(f)).as(sql).doesNotContain("LIMIT").doesNotContain("OFFSET");
        }
    }

    @Test
    @DisplayName("F-006-AC33 LEVEL: CONNECT BY가 있는 구문에서는 의사 컬럼이고, 없는 구문에서는 (DDL을 모르는) 하나뿐인 테이블의 컬럼이다")
    void levelIsPseudoOnlyInHierarchicalQueries() {
        Catalog none = new Catalog();
        assertThat(reads(clean("SELECT LEVEL, NM FROM T START WITH PID IS NULL CONNECT BY PRIOR ID = PID", none)))
                .isEqualTo("T.ID,T.NM,T.PID");
        assertThat(reads(clean("SELECT NM FROM T CONNECT BY PRIOR ID = PID AND LEVEL <= 3 ORDER BY LEVEL", none))).isEqualTo("T.ID,T.NM,T.PID");
        assertThat(reads(clean("SELECT LEVEL AS N FROM DUAL CONNECT BY LEVEL <= 12", none))).isEmpty();
        // 계층 쿼리 안쪽의 스칼라 서브쿼리에서 바깥의 LEVEL을 쓴 것도 의사 컬럼이다
        assertThat(reads(clean("SELECT (SELECT MAX(U.NM) FROM U WHERE U.DEPTH = LEVEL) FROM T CONNECT BY PRIOR ID = PID", none)))
                .isEqualTo("T.ID,T.PID,U.DEPTH,U.NM");
        // CONNECT BY가 없는 구문
        assertThat(reads(clean("SELECT MENU_ID, LEVEL FROM T WHERE LEVEL = 1 ORDER BY LEVEL", none))).isEqualTo("T.LEVEL,T.MENU_ID");
        // DDL이 있는 테이블: 그 이름의 컬럼이 있으면 계층 쿼리에서도 컬럼이고, 없으면 추측하지 않는다(F-006-R31의 제외 대상 그대로)
        Catalog with = new Catalog();
        with.defineTable("T", "ID", "PID", "LEVEL");
        assertThat(reads(clean("SELECT LEVEL FROM T CONNECT BY PRIOR ID = PID", with))).isEqualTo("T.ID,T.LEVEL,T.PID");
        Catalog without = new Catalog();
        without.defineTable("T", "ID", "PID");
        StatementFacts f = clean("SELECT ID, LEVEL FROM T WHERE LEVEL = 1", without);
        assertThat(reads(f)).isEqualTo("T.ID");
        assertThat(f.unknownColumns()).isEmpty();
    }
}
