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
 * 키워드와 같은 이름의 테이블·별칭·컬럼(F-006-R17). 공개 프로젝트에서 {@code SAMPLE}, {@code LOG} 같은 이름의 테이블이
 * "테이블 이름이 있어야 할 자리"로 실패해 테이블 노드가 하나도 생기지 않은 일이 있었다.
 */
class KeywordNameTest {

    /** 파서가 절의 시작으로 쓰지만 예약어가 아닌 키워드({@link Keywords#ALIAS_STOP_EXTRA}에서 예약어 DELETE를 뺀 것). */
    private static final Set<String> CLAUSE_KEYWORDS = clauseKeywords();

    /** 파서가 문맥에 따라 키워드로 읽는 흔한 이름. */
    private static final List<String> ORDINARY = List.of(
            "KEY", "VALUE", "TYPE", "NAME", "STATUS", "LEVEL", "COMMENT", "DATA", "MESSAGE", "FIRST", "NEXT", "ROW", "FILTER",
            "OVER", "XML", "BLOCK", "SEED", "TIES", "PERCENT", "NULLS", "SEARCH", "CYCLE", "RECURSIVE", "MATERIALIZED",
            "FINAL", "NEW", "OLD", "THE", "TEMP", "OPTION", "READ", "SHARE", "EXCLUSIVE", "IMMEDIATE", "EXCLUDE", "LAST",
            "IGNORE", "RESPECT", "WITHIN", "USE", "CHECK", "LOCAL", "SCN", "ERRORS", "LOCKED", "NOCYCLE", "ZONE");

    /** 식의 첫 토큰이 될 수 없는 절 키워드지만 예약어가 아니라 테이블 이름으로는 받는 것(별칭·컬럼 자리에서는 절 키워드다). */
    private static final List<String> SOFT_CLAUSE = List.of("LIMIT", "OFFSET", "WINDOW", "QUALIFY", "ESCAPE", "BULK", "LOOP");

    private static Set<String> clauseKeywords() {
        Set<String> set = new TreeSet<>(Keywords.ALIAS_STOP_EXTRA);
        set.remove("DELETE");
        return set;
    }

    static Stream<String> tableNames() {
        List<String> all = new ArrayList<>(CLAUSE_KEYWORDS);
        all.addAll(ORDINARY);
        all.addAll(SOFT_CLAUSE);
        return all.stream();
    }

    static Stream<String> aliasAndColumnNames() {
        List<String> all = new ArrayList<>(CLAUSE_KEYWORDS);
        all.addAll(ORDINARY);
        return all.stream();
    }

    private static StatementFacts clean(String sql) {
        ParseResult r = SqlParser.parse(sql);
        assertThat(r.problems()).as("problems of [%s]", sql).isEmpty();
        return new FactExtractor(new Catalog()).extract(r);
    }

    private static String reads(StatementFacts f) {
        return f.tableReads().stream().map(StatementFacts.TableRead::table).sorted().collect(Collectors.joining(","));
    }

    private static String writes(StatementFacts f) {
        return f.tableWrites().stream().map(w -> w.table() + "[" + w.op() + "]").sorted().collect(Collectors.joining(","));
    }

    private static String columns(StatementFacts f) {
        return Stream.concat(f.columnReads().stream(), f.columnWrites().stream()).map(c -> c.table() + "." + c.column())
                .distinct().sorted().collect(Collectors.joining(","));
    }

    @ParameterizedTest(name = "테이블 이름 {0}")
    @MethodSource("tableNames")
    @DisplayName("F-006-AC17 비예약 키워드를 테이블 이름으로: SELECT/JOIN/INSERT/UPDATE/DELETE/MERGE 대상")
    void asTableName(String k) {
        assertThat(reads(clean("SELECT ID FROM " + k + " WHERE ID = ?"))).isEqualTo(k);
        assertThat(reads(clean("SELECT ID FROM " + k))).isEqualTo(k);
        assertThat(reads(clean("SELECT A.ID FROM " + k + " A, T B WHERE A.ID = B.ID"))).isEqualTo(sorted(k, "T"));
        assertThat(reads(clean("SELECT A.ID FROM T A JOIN " + k + " B ON A.ID = B.ID"))).isEqualTo(sorted(k, "T"));
        assertThat(reads(clean("SELECT A.ID FROM T A LEFT OUTER JOIN " + k + " ON A.ID = " + k + ".ID"))).isEqualTo(sorted(k, "T"));
        assertThat(reads(clean("SELECT X.ID FROM SCH." + k + " X ORDER BY X.ID"))).isEqualTo(k);
        assertThat(writes(clean("INSERT INTO " + k + " (ID, MSG) VALUES (?, ?)"))).isEqualTo(k + "[INSERT]");
        assertThat(writes(clean("INSERT INTO " + k + " VALUES (?, ?)"))).isEqualTo(k + "[INSERT]");
        assertThat(writes(clean("INSERT INTO " + k + " SELECT ID FROM T"))).isEqualTo(k + "[INSERT]");
        assertThat(writes(clean("UPDATE " + k + " SET NM = ? WHERE ID = ?"))).isEqualTo(k + "[UPDATE]");
        assertThat(writes(clean("DELETE FROM " + k + " WHERE ID = ?"))).isEqualTo(k + "[DELETE]");
        assertThat(writes(clean("DELETE FROM " + k))).isEqualTo(k + "[DELETE]");
        assertThat(writes(clean("MERGE INTO " + k + " D USING T S ON (D.ID = S.ID) WHEN MATCHED THEN UPDATE SET D.NM = S.NM")))
                .isEqualTo(k + "[MERGE]");
    }

    @ParameterizedTest(name = "별칭 {0}")
    @MethodSource("aliasAndColumnNames")
    @DisplayName("F-006-AC17 비예약 키워드를 테이블 별칭으로")
    void asTableAlias(String k) {
        StatementFacts f = clean("SELECT " + k + ".ID FROM T " + k + " WHERE " + k + ".NM = ?");
        assertThat(reads(f)).isEqualTo("T");
        assertThat(columns(f)).isEqualTo("T.ID,T.NM");
        f = clean("SELECT " + k + ".ID FROM T " + k + ", U X WHERE " + k + ".ID = X.ID");
        assertThat(reads(f)).isEqualTo("T,U");
        f = clean("SELECT " + k + ".ID FROM U X JOIN T " + k + " ON " + k + ".ID = X.ID");
        assertThat(reads(f)).isEqualTo("T,U");
        f = clean("SELECT " + k + ".ID FROM T AS " + k);
        assertThat(reads(f)).isEqualTo("T");
        f = clean("UPDATE T " + k + " SET " + k + ".NM = ? WHERE " + k + ".ID = ?");
        assertThat(writes(f)).isEqualTo("T[UPDATE]");
        assertThat(columns(f)).isEqualTo("T.ID,T.NM");
    }

    @ParameterizedTest(name = "컬럼 {0}")
    @MethodSource("aliasAndColumnNames")
    @DisplayName("F-006-AC17 비예약 키워드를 컬럼 이름으로")
    void asColumnName(String k) {
        StatementFacts f = clean("SELECT A." + k + ", A.ID FROM T A WHERE A." + k + " = ? ORDER BY A." + k);
        assertThat(columns(f)).isEqualTo(sortedColumns("T.ID", "T." + k));
        assertThat(reads(clean("SELECT ID, " + k + " FROM T WHERE " + k + " = ?"))).isEqualTo("T");
        assertThat(reads(clean("SELECT " + k + ", ID FROM T WHERE ID = ? AND " + k + " IS NOT NULL"))).isEqualTo("T");
        f = clean("INSERT INTO T (ID, " + k + ") VALUES (?, ?)");
        assertThat(columns(f)).isEqualTo(sortedColumns("T.ID", "T." + k));
        f = clean("UPDATE T SET " + k + " = ?, NM = ? WHERE ID = ?");
        assertThat(writes(f)).isEqualTo("T[UPDATE]");
        assertThat(f.columnWrites().stream().map(StatementFacts.ColumnUse::column).sorted().collect(Collectors.joining(",")))
                .isEqualTo(sorted(k, "NM"));
    }

    @Test
    @DisplayName("F-006-AC17 진짜 절은 뒤 토큰으로 가린다: SAMPLE (n), PARTITION (p), PIVOT (..), LOG ERRORS, DEFAULT VALUES")
    void realClausesStillRecognized() {
        assertThat(reads(clean("SELECT * FROM T SAMPLE (10) WHERE ID = 1"))).isEqualTo("T");
        assertThat(reads(clean("SELECT * FROM T SAMPLE BLOCK (10) SEED (1)"))).isEqualTo("T");
        assertThat(reads(clean("SELECT * FROM T PARTITION (P1) X WHERE X.ID = 1"))).isEqualTo("T");
        assertThat(reads(clean("SELECT * FROM T PIVOT (SUM(AMT) FOR MM IN ('01', '02')) P"))).isEqualTo("T");
        assertThat(writes(clean("INSERT INTO T (ID) VALUES (1) LOG ERRORS INTO ERR$_T REJECT LIMIT UNLIMITED"))).isEqualTo("T[INSERT]");
        assertThat(writes(clean("INSERT INTO T DEFAULT VALUES"))).isEqualTo("T[INSERT]");
        assertThat(writes(clean("INSERT INTO T OVERRIDING SYSTEM VALUE VALUES (1)"))).isEqualTo("T[INSERT]");
        assertThat(reads(clean("SELECT * FROM T FOR UPDATE SKIP LOCKED"))).isEqualTo("T");
        assertThat(reads(clean("SELECT * FROM T FOR UPDATE WAIT 3"))).isEqualTo("T");
        assertThat(reads(clean("SELECT * FROM T VERSIONS BETWEEN SCN 1 AND 2"))).isEqualTo("T");
        assertThat(reads(clean("SELECT * FROM T OPTIMIZE FOR 10 ROWS"))).isEqualTo("T");
        assertThat(reads(clean("SELECT * FROM T A, LATERAL (SELECT 1 FROM U WHERE U.ID = A.ID) L"))).isEqualTo("T,U");
        assertThat(writes(clean("DELETE FROM T RETURNING ID INTO :x"))).isEqualTo("T[DELETE]");
    }

    @Test
    @DisplayName("F-006-AC17 예약어는 여전히 테이블 이름이 아니다: 테이블이 빠진 구문은 문제로 남고 유령 테이블을 만들지 않는다")
    void reservedWordsAreNotTables() {
        for (String sql : List.of("SELECT A FROM WHERE X = 1", "SELECT A FROM T JOIN ON T.ID = 1", "UPDATE SET X = 1",
                "INSERT INTO VALUES (1)", "DELETE FROM WHERE ID = 1", "SELECT A FROM ORDER BY 1", "SELECT A FROM T, GROUP BY A")) {
            ParseResult r = SqlParser.parse(sql);
            assertThat(r.problems()).as(sql).isNotEmpty();
            StatementFacts f = new FactExtractor(new Catalog()).extract(r);
            assertThat(reads(f) + "|" + writes(f)).as(sql).doesNotContain("WHERE").doesNotContain("ON").doesNotContain("SET")
                    .doesNotContain("VALUES").doesNotContain("ORDER").doesNotContain("GROUP");
        }
    }

    private static String sorted(String a, String b) {
        return Stream.of(a, b).sorted().collect(Collectors.joining(","));
    }

    private static String sortedColumns(String a, String b) {
        return Stream.of(a, b).distinct().sorted().collect(Collectors.joining(","));
    }
}
