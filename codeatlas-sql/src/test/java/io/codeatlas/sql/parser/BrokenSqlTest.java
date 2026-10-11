package io.codeatlas.sql.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.codeatlas.sql.analysis.Catalog;
import io.codeatlas.sql.analysis.FactExtractor;
import io.codeatlas.sql.analysis.StatementFacts;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 깨진 SQL에서도 죽지 않고 테이블과 조인을 건지는지 본다. */
class BrokenSqlTest {

    private static StatementFacts facts(String sql) {
        return new FactExtractor(new Catalog()).extract(SqlParser.parse(sql));
    }

    private static String tables(StatementFacts f) {
        String r = f.tableReads().stream().map(StatementFacts.TableRead::table).sorted().collect(Collectors.joining(","));
        String w = f.tableWrites().stream().map(x -> x.table() + "[" + x.op() + "]").sorted().collect(Collectors.joining(","));
        return r + "|" + w;
    }

    private static String joins(StatementFacts f) {
        return f.joins().stream()
                .map(j -> j.leftTable() + "(" + String.join(",", j.leftColumns()) + ")=" + j.rightTable() + "(" + String.join(",", j.rightColumns()) + ")")
                .sorted().collect(Collectors.joining(";"));
    }

    static Stream<Arguments> recoverable() {
        return Stream.of(
                // MyBatis 전개 잔재: 가벼운 문제(minor)만 생긴다
                Arguments.of("SELECT a.id FROM a WHERE AND a.x = ?", "A|", "", true),
                Arguments.of("SELECT a.id FROM a, b WHERE a.id = b.aid AND", "A,B|", "A(ID)=B(AID)", true),
                Arguments.of("SELECT a.id FROM a, b WHERE OR a.id = b.aid", "A,B|", "A(ID)=B(AID)", true),
                Arguments.of("SELECT a.id, FROM a", "A|", "", true),
                Arguments.of("SELECT a.id FROM a WHERE a.id IN () AND a.x = 1", "A|", "", true),
                Arguments.of("SELECT a.id FROM a WHERE a.id IN AND a.x = 1", "A|", "", true),
                Arguments.of("UPDATE a SET x = ?, y = ?, WHERE id = ?", "|A[UPDATE]", "", true),
                Arguments.of("UPDATE a SET WHERE id = ?", "|A[UPDATE]", "", true),
                Arguments.of("INSERT INTO a (x, y,) VALUES (?, ?,)", "|A[INSERT]", "", true),
                Arguments.of("SELECT a.id FROM a ORDER BY", "A|", "", true),
                Arguments.of("SELECT a.id FROM a WHERE", "A|", "", true),
                Arguments.of("SELECT a.id FROM a WHERE a.x = AND a.y = 1", "A|", "", true),
                Arguments.of("SELECT a.id FROM a JOIN b ON a.id = b.aid WHERE (a.x = 1 OR ) AND b.y = 2", "A,B|", "A(ID)=B(AID)", true),
                // 괄호 불균형, 잘린 절: 큰 문제(major)지만 테이블은 건진다
                Arguments.of("SELECT B.ID, (SELECT COUNT(* FROM CHILD C WHERE C.PID = B.ID AS CNT FROM PARENT B WHERE B.USE_YN IN ('Y', 'N'", "CHILD,PARENT|", "CHILD(PID)=PARENT(ID)", false),
                Arguments.of("SELECT a.id FROM a JOIN b ON (a.id = b.aid WHERE a.x = 1", "A,B|", "A(ID)=B(AID)", false),
                Arguments.of("SELECT a.id FROM a JOIN b ON a.id = b.aid) WHERE a.x = 1", "A,B|", "A(ID)=B(AID)", false),
                Arguments.of("SELECT a.id FROM a WHERE a.x = 1) AND a.y IN (SELECT b.y FROM b", "A,B|", "A(Y)=B(Y)", false),
                Arguments.of("SELECT FROM a, b WHERE", "A,B|", "", false),
                Arguments.of("SELECT a.id, FROM a LEFT JOIN b ON", "A,B|", "", true),
                Arguments.of("INSERT INTO t (a, b VALUES (1, 2)", "|T[INSERT]", "", false),
                Arguments.of("INSERT INTO t (a, b) SELECT x, y FROM s WHERE (((s.z = 1", "S|T[INSERT]", "", false),
                Arguments.of("UPDATE t SET a = (SELECT MAX(x FROM s WHERE s.id = t.id WHERE t.b = 1", "S|T[UPDATE]", "S(ID)=T(ID)", false),
                Arguments.of("DELETE FROM t WHERE id IN (SELECT id FROM s", "S|T[DELETE]", "T(ID)=S(ID)", false),
                Arguments.of("MERGE INTO t USING s ON (t.id = s.id WHEN MATCHED THEN UPDATE SET t.v = s.v", "S|T[MERGE]", "T(ID)=S(ID)", false),
                Arguments.of("SELECT * FROM a @@ ## b JOIN c ON a.id = c.id", "A,C|", "A(ID)=C(ID)", false),
                Arguments.of("SELECT x FROM a WHERE a.id = 1 %%% FROM b JOIN c ON 1 = 1", "A,B,C|", "", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recoverable")
    @DisplayName("F-006-AC11 깨진 SQL → 건진 테이블, 조인 (표)")
    void recovers(String sql, String expectedTables, String expectedJoins, boolean minorOnly) {
        ParseResult r = SqlParser.parse(sql);
        assertThat(r.problems()).as("problems").isNotEmpty();
        assertThat(r.hasMajorProblems()).as("major problems: " + r.problems()).isEqualTo(!minorOnly);
        StatementFacts f = new FactExtractor(new Catalog()).extract(r);
        assertThat(tables(f)).isEqualTo(expectedTables);
        assertThat(joins(f)).isEqualTo(expectedJoins);
    }

    @Test
    @DisplayName("F-006-AC11 모르는 구문은 Unparsed로 남고 예외가 없다")
    void unknownStatement() {
        ParseResult r = SqlParser.parse("FROBNICATE THE widgets WITH gusto");
        assertThat(r.statement()).isInstanceOf(io.codeatlas.sql.ast.Statement.Unparsed.class);
        assertThat(facts("FROBNICATE THE widgets").statementType()).isEqualTo("OTHER");
        assertThat(SqlParser.parse("").statement()).isInstanceOf(io.codeatlas.sql.ast.Statement.Empty.class);
        assertThat(SqlParser.parse("  -- only comment\n ;").statement()).isInstanceOf(io.codeatlas.sql.ast.Statement.Empty.class);
    }

    @Test
    @DisplayName("F-006-AC11 깊게 중첩된 괄호에서도 스택 오버플로 없이 끝난다")
    void deepNesting() {
        String open = "(".repeat(5000);
        String close = ")".repeat(5000);
        assertThatCode(() -> facts("SELECT " + open + "1" + close + " FROM t")).doesNotThrowAnyException();
        assertThatCode(() -> facts("SELECT * FROM t WHERE x IN " + "(SELECT x FROM t WHERE x IN ".repeat(3000))).doesNotThrowAnyException();
        assertThat(tables(facts("SELECT " + open + "1 FROM t"))).isNotNull();
    }

    private static final List<String> SEEDS = List.of(
            "SELECT e.ename, d.dname FROM emp e JOIN dept d ON e.deptno = d.deptno WHERE e.sal > (SELECT AVG(sal) FROM emp) ORDER BY 1",
            "WITH c AS (SELECT id, nm FROM t1) SELECT c.nm, t2.v FROM c LEFT JOIN t2 ON c.id = t2.t1_id WHERE t2.v IN ('a', 'b') FETCH FIRST 10 ROWS ONLY",
            "INSERT INTO hist (id, amt) SELECT o.id, SUM(i.amt) FROM ord o, item i WHERE i.ord_id = o.id(+) GROUP BY o.id",
            "UPDATE ord o SET (o.amt, o.cnt) = (SELECT SUM(i.amt), COUNT(*) FROM item i WHERE i.ord_id = o.id) WHERE o.st = 'A'",
            "MERGE INTO stock s USING (SELECT pid, qty FROM item) x ON (s.pid = x.pid) WHEN MATCHED THEN UPDATE SET s.qty = x.qty WHEN NOT MATCHED THEN INSERT (pid, qty) VALUES (x.pid, x.qty)",
            "DELETE FROM item i WHERE EXISTS (SELECT 1 FROM ord o WHERE o.id = i.ord_id AND o.st = 'D')",
            "SELECT CASE WHEN a.x = 1 THEN 'q''s' ELSE q'[z]' END, \"Quoted\".c, :b, #{p}, ${d} FROM a /* c */ WHERE a.dt BETWEEN ? AND ? -- end",
            "BEGIN pkg.proc(1, 'a'); UPDATE t SET x = 1 WHERE id = 2; END;",
            "{call pkg_order.close_order(?, ?)}",
            "SELECT LEVEL, c.id FROM cat c START WITH c.pid IS NULL CONNECT BY PRIOR c.id = c.pid",
            "SELECT * FROM (SELECT ROWNUM rn, x.* FROM (SELECT o.id FROM ord o ORDER BY o.id) x WHERE ROWNUM <= ?) WHERE rn >= ?");

    @Test
    @DisplayName("F-006-AC11 퍼즈: 임의로 자르거나 괄호·토큰을 지운 SQL에 대해 예외 없이 끝난다")
    void fuzz() {
        Random random = new Random(20261011L);
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (String seed : SEEDS) {
                // 모든 접두사
                for (int i = 0; i <= seed.length(); i++) {
                    runQuietly(seed.substring(0, i));
                    runQuietly(seed.substring(i));
                }
                // 괄호를 하나씩 지움
                for (int i = 0; i < seed.length(); i++) {
                    char c = seed.charAt(i);
                    if (c == '(' || c == ')' || c == '\'' || c == ',') {
                        runQuietly(seed.substring(0, i) + seed.substring(i + 1));
                    }
                }
                // 괄호 전부 제거, 임의 변형
                runQuietly(seed.replace("(", ""));
                runQuietly(seed.replace(")", ""));
                for (int n = 0; n < 300; n++) {
                    StringBuilder sb = new StringBuilder(seed);
                    int edits = 1 + random.nextInt(4);
                    for (int e = 0; e < edits && sb.length() > 0; e++) {
                        int at = random.nextInt(sb.length());
                        switch (random.nextInt(4)) {
                            case 0 -> sb.deleteCharAt(at);
                            case 1 -> sb.insert(at, "()',;.=*@{}:?\"-/ ".charAt(random.nextInt(17)));
                            case 2 -> sb.delete(at, Math.min(sb.length(), at + random.nextInt(12)));
                            default -> sb.insert(at, List.of(" SELECT ", " FROM ", " WHERE ", " JOIN ", " ON ", " ( ", " ) ", " AND ", " CASE ", " END ", " UNION ", " BEGIN ", " VALUES ").get(random.nextInt(13)));
                        }
                    }
                    runQuietly(sb.toString());
                }
            }
        });
    }

    private static void runQuietly(String sql) {
        try {
            ParseResult r = SqlParser.parse(sql);
            new FactExtractor(new Catalog()).extract(r);
        } catch (RuntimeException | StackOverflowError e) {
            throw new AssertionError("예외 발생: [" + sql + "]", e);
        }
    }

    private static String firstProblem(String sql) {
        ParseResult r = SqlParser.parse(sql);
        assertThat(r.problems()).as(sql).isNotEmpty();
        return r.problems().get(0).message();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("F-006-AC39 복구가 일어난 지점의 토큰과 기대한 것을 메시지에 담는다: AND가 빠진 조건, 잘못 들어간 기호, 정말로 닫는 괄호가 없는 경우를 가린다")
    void problemMessagesNameTheTokenAndTheExpectation() {
        // AND/OR가 빠졌다(전자정부 EgovEmplyrManage_SQL_oracle.xml): 전에는 "닫는 괄호가 없음"이었다
        assertThat(firstProblem("SELECT * FROM (SELECT ID FROM TB_A WHERE 1=1 STTUS_CODE LIKE ? AND ID LIKE '%' || ? ORDER BY 1 DESC) T WHERE RN > 1"))
                .isEqualTo("`STTUS_CODE` 앞에 AND/OR 또는 연산자가 필요한 자리");
        assertThat(firstProblem("SELECT ID FROM TB_A WHERE 1=1 STTUS_CODE LIKE ?")).isEqualTo("`STTUS_CODE` 앞에 AND/OR 또는 연산자가 필요한 자리");
        // 오타로 들어간 기호(EgovLoginLog_SQL_oracle.xml의 }}): 전에는 "닫는 괄호가 없음"이었다
        assertThat(firstProblem("SELECT * FROM (SELECT A.ID FROM TB_A A WHERE A.NM LIKE '%' || ? }} '%' AND A.X = 1 ORDER BY 1) T")).isEqualTo("예상하지 못한 `}`");
        assertThat(firstProblem("SELECT A.ID FROM TB_A A WHERE A.NM LIKE '%' || ? }} '%'")).isEqualTo("예상하지 못한 `}`");
        // 연산자가 빠진 문자열 연결(EgovConectStats_SQL_oracle.xml)
        assertThat(firstProblem("SELECT ID FROM TB_A WHERE NM LIKE '%' || ? '%' GROUP BY ID")).isEqualTo("`'%'` 앞에 AND/OR 또는 연산자가 필요한 자리");
        // 정말로 닫는 괄호가 없다
        assertThat(firstProblem("SELECT ID FROM TB_A WHERE ID IN (SELECT A_ID FROM TB_B")).isEqualTo("닫는 괄호가 없음");
        assertThat(firstProblem("SELECT NVL(NM, 'x' FROM TB_A")).startsWith("닫는 괄호가 없음");
    }
}
