package io.codeatlas.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * F-006-R31: 선언된 테이블의 DDL에 없는 비한정 컬럼. 스코프에 실제 테이블이 하나뿐이면 그 테이블의 컬럼으로 참조하고(컬럼 노드는
 * {@code declared=false}), 테이블이 여럿이면 엣지 없이 주인 구문마다 {@code UNKNOWN_COLUMN}(INFO) 진단 하나를 남긴다.
 * 변수, 의사 컬럼, SELECT 별칭, 함수 이름, 따옴표 이름, 동적 조각은 컬럼으로 만들지 않는다.
 */
class UnknownColumnTest {

    private static final String MAPPER = "src/main/resources/mapper/EmpMapper.xml";

    private static final String DDL = """
            CREATE TABLE TB_EMP (EMP_ID VARCHAR2(10) NOT NULL, NM VARCHAR2(20), DEPT_ID VARCHAR2(10), CONSTRAINT PK_E PRIMARY KEY (EMP_ID));
            CREATE TABLE TB_DEPT (DEPT_ID VARCHAR2(10) NOT NULL, DEPT_NM VARCHAR2(20), CONSTRAINT PK_D PRIMARY KEY (DEPT_ID));
            CREATE TABLE TB_GRADE (GRADE_ID VARCHAR2(10) NOT NULL, GRADE_NM VARCHAR2(20), CONSTRAINT PK_G PRIMARY KEY (GRADE_ID));
            CREATE VIEW V_EMP AS SELECT EMP_ID, NM FROM TB_EMP;
            CREATE SEQUENCE SEQ_EMP;
            """;

    private static String owner(String statementId) {
        return Ids.mybatisStatement("com.a.EmpMapper", statementId);
    }

    private static void embed(AnalysisContext ctx, String statementId, int line, String sql) {
        String owner = owner(statementId);
        if (!ctx.graph().hasNode(owner)) {
            ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name(statementId)
                    .location(SourceLocation.of(MAPPER, line)).build());
        }
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, sql, SourceLocation.of(MAPPER, line, 5, line, 5), false, Map.of()));
    }

    private static AnalysisContext context(TestProject project) {
        return project.file("db/ddl.sql", DDL).file(MAPPER, "<mapper namespace=\"com.a.EmpMapper\"/>").context();
    }

    /** 구문 하나를 분석한다. 같은 주인의 전개본을 여럿 줄 수 있다. */
    private static GraphModel analyze(String... variants) {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            for (String sql : variants) {
                embed(ctx, "stmt", 10, sql);
            }
            return TestProject.run(ctx, new SqlAnalyzer()).graph();
        }
    }

    private static List<Diagnostic> unknown(GraphModel g) {
        return GraphAssert.diagnostics(g, SqlDiagnosticCodes.UNKNOWN_COLUMN);
    }

    /** DDL에 없는데 참조로 생긴 컬럼 노드의 ID(정렬). */
    private static List<String> undeclaredColumns(GraphModel g) {
        return g.nodes().stream().filter(n -> n.kind() == NodeKind.DB_COLUMN && !n.declared()).map(Node::id).sorted().toList();
    }

    @Test
    @DisplayName("F-006-AC31 테이블이 하나뿐인 SELECT: DDL에 없는 비한정 컬럼은 그 테이블의 컬럼 READS가 되고 노드는 declared=false, 진단은 없다")
    void singleTableSelect() {
        GraphModel g = analyze("SELECT emp_id AS id, nm FROM TB_EMP WHERE sub_dn = #{dn}");

        String stmt = owner("stmt");
        assertThat(GraphAssert.hasEdge(g, stmt, EdgeKind.READS, "db:column:TB_EMP.SUB_DN")).isTrue();
        assertThat(GraphAssert.node(g, "db:column:TB_EMP.SUB_DN").declared()).isFalse();
        assertThat(GraphAssert.node(g, "db:column:TB_EMP.EMP_ID").declared()).isTrue();
        assertThat(undeclaredColumns(g)).containsExactly("db:column:TB_EMP.SUB_DN");
        assertThat(unknown(g)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC31 테이블이 하나뿐인 UPDATE: SET의 컬럼(WRITES)과 WHERE의 DDL에 없는 컬럼(READS)이 같은 방식으로 남는다")
    void singleTableUpdate() {
        GraphModel g = analyze("UPDATE TB_EMP SET pw = #{pw} WHERE emp_idd = #{id}");

        String stmt = owner("stmt");
        assertThat(GraphAssert.hasEdge(g, stmt, EdgeKind.WRITES, "db:column:TB_EMP.PW")).isTrue();
        assertThat(GraphAssert.hasEdge(g, stmt, EdgeKind.READS, "db:column:TB_EMP.EMP_IDD")).isTrue();
        assertThat(undeclaredColumns(g)).containsExactly("db:column:TB_EMP.EMP_IDD", "db:column:TB_EMP.PW");
        assertThat(unknown(g)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC31 같은 테이블의 인스턴스만 여럿인 스코프(자기 조인)도 테이블은 하나다: 그 테이블의 컬럼으로 참조한다")
    void selfJoinIsOneTable() {
        GraphModel g = analyze("SELECT no_col FROM TB_EMP e, TB_EMP m WHERE e.EMP_ID = m.EMP_ID");

        assertThat(GraphAssert.hasEdge(g, owner("stmt"), EdgeKind.READS, "db:column:TB_EMP.NO_COL")).isTrue();
        assertThat(undeclaredColumns(g)).containsExactly("db:column:TB_EMP.NO_COL");
        assertThat(unknown(g)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC31 테이블이 여럿이고 어느 DDL에도 없는 컬럼: 엣지 없이 주인 구문에 UNKNOWN_COLUMN(INFO) 1건(컬럼은 중복 없이, 후보 테이블과 함께)")
    void manyTablesGiveOneDiagnostic() {
        GraphModel g = analyze(
                "SELECT e.NM FROM TB_EMP e JOIN TB_DEPT d ON e.DEPT_ID = d.DEPT_ID WHERE no_col = ? AND other_col = 1 AND no_col > 0",
                "SELECT e.NM FROM TB_EMP e JOIN TB_DEPT d ON e.DEPT_ID = d.DEPT_ID WHERE no_col = ? ORDER BY third_col");

        assertThat(undeclaredColumns(g)).isEmpty();
        List<Diagnostic> diagnostics = unknown(g);
        assertThat(diagnostics).hasSize(1);
        Diagnostic d = diagnostics.get(0);
        assertThat(d.severity()).isEqualTo(Severity.INFO);
        assertThat(d.message()).contains("NO_COL, OTHER_COL, THIRD_COL").contains("TB_EMP, TB_DEPT").contains(owner("stmt"));
        assertThat(d.message().split("NO_COL", -1)).as("컬럼 이름은 한 번만").hasSize(2);
        assertThat(d.location()).isNotNull();
        assertThat(d.location().file()).isEqualTo(MAPPER);
        assertThat(g.diagnostics()).hasSize(1);
    }

    @Test
    @DisplayName("F-006-AC31 서브쿼리: 안쪽 스코프의 테이블이 하나면 그 테이블의 컬럼, 바깥 스코프에 그 컬럼이 카탈로그상 있으면 전과 같이 바깥 것")
    void subqueryScopes() {
        GraphModel inner = analyze("SELECT e.NM FROM TB_EMP e WHERE e.DEPT_ID IN (SELECT no_col FROM TB_DEPT)");
        assertThat(GraphAssert.hasEdge(inner, owner("stmt"), EdgeKind.READS, "db:column:TB_DEPT.NO_COL")).isTrue();
        assertThat(undeclaredColumns(inner)).containsExactly("db:column:TB_DEPT.NO_COL");
        assertThat(unknown(inner)).isEmpty();

        GraphModel outer = analyze("SELECT e.EMP_ID FROM TB_EMP e WHERE EXISTS (SELECT 1 FROM TB_DEPT WHERE DEPT_NM = NM)");
        assertThat(GraphAssert.hasEdge(outer, owner("stmt"), EdgeKind.READS, "db:column:TB_EMP.NM")).isTrue();
        assertThat(undeclaredColumns(outer)).isEmpty();
        assertThat(unknown(outer)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC31 SELECT *만 고른 인라인 뷰(페이징 포장)를 거친 비한정 컬럼은 안쪽의 하나뿐인 테이블의 컬럼이다")
    void throughStarInlineView() {
        GraphModel g = analyze("SELECT * FROM (SELECT ROWNUM RN, T.* FROM (SELECT * FROM TB_EMP WHERE NM = ?) T) WHERE RN > ? AND no_col = 1");

        assertThat(GraphAssert.hasEdge(g, owner("stmt"), EdgeKind.READS, "db:column:TB_EMP.NO_COL")).isTrue();
        assertThat(undeclaredColumns(g)).containsExactly("db:column:TB_EMP.NO_COL");
        assertThat(unknown(g)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC31 테이블 옆에 컬럼 목록이 분명한 인라인 뷰가 있으면 추측하지 않고 진단으로 알린다(후보는 실제 테이블)")
    void tableWithInlineViewGivesDiagnostic() {
        GraphModel g = analyze("SELECT no_col FROM TB_EMP e, (SELECT DEPT_ID FROM TB_DEPT) v WHERE e.DEPT_ID = v.DEPT_ID");

        assertThat(undeclaredColumns(g)).isEmpty();
        assertThat(unknown(g)).hasSize(1);
        assertThat(unknown(g).get(0).message()).contains("NO_COL").contains("후보 테이블: TB_EMP)");
    }

    @Test
    @DisplayName("F-006-AC31 하나뿐인 릴레이션이 뷰여도 같은 규칙이다: 그 뷰의 컬럼으로 참조한다")
    void singleView() {
        GraphModel g = analyze("SELECT no_col FROM V_EMP WHERE EMP_ID = ?");

        assertThat(undeclaredColumns(g)).containsExactly("db:column:V_EMP.NO_COL");
        assertThat(unknown(g)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC31 DDL이 없는 테이블이 스코프에 끼어 있으면 '모름'이다: 전과 같은 규칙을 따르고 진단은 없다")
    void unknownTableInScopeKeepsOldRule() {
        GraphModel one = analyze("SELECT no_col FROM TB_EMP e, TB_NO_DDL x WHERE e.EMP_ID = x.EMP_ID");
        assertThat(undeclaredColumns(one)).contains("db:column:TB_NO_DDL.NO_COL").doesNotContain("db:column:TB_EMP.NO_COL");
        assertThat(unknown(one)).isEmpty();

        GraphModel two = analyze("SELECT no_col FROM TB_EMP e, TB_NO_DDL x, TB_NO_DDL2 y");
        assertThat(undeclaredColumns(two)).isEmpty();
        assertThat(unknown(two)).isEmpty();
    }

    static Stream<String> notColumns() {
        return Stream.of(
                // 의사 컬럼과 특수 이름, 시퀀스
                "SELECT ROWNUM, SYSDATE, LEVEL, ROWID, USER, SEQ_EMP.NEXTVAL FROM TB_EMP WHERE ROWNUM <= 10",
                "SELECT NM, CURRENT DATE, CURRENT TIMESTAMP FROM TB_EMP",
                // SELECT 별칭을 GROUP BY·HAVING·ORDER BY에서 쓴 것
                "SELECT DEPT_ID AS grp, COUNT(*) AS cnt FROM TB_EMP GROUP BY grp HAVING cnt > 1 ORDER BY cnt DESC, UPPER(grp)",
                // 따옴표 이름(문자열을 잘못 쓴 것), 동적 조각
                "SELECT NM FROM TB_EMP WHERE NM = \"kim\"",
                "SELECT NM FROM TB_EMP WHERE __DYNAMIC__ = ? ORDER BY __DYNAMIC__",
                "SELECT NM FROM TB_EMP WHERE COL___DYNAMIC__ = ?",
                // 함수 이름, 바인드
                "SELECT NVL(NM, 'x'), COUNT(*), TO_CHAR(SYSDATE, 'YYYY') FROM TB_EMP WHERE EMP_ID = :id AND NM = ? AND DEPT_ID = #{dept}",
                // 테이블이 없는 스코프, DUAL
                "SELECT some_name FROM DUAL",
                "INSERT INTO TB_EMP (EMP_ID, NM) VALUES (some_name, ?)",
                // 컬럼 목록이 분명한 인라인 뷰에 없는 이름(테이블이 아니다)
                "SELECT no_col FROM (SELECT EMP_ID, NM FROM TB_EMP) v",
                // 선택 목록이 동적인 인라인 뷰: 그 이름이 없다고 말할 수 없다
                "SELECT no_col FROM (SELECT __DYNAMIC__ FROM TB_EMP) v",
                "SELECT no_col FROM TB_DEPT d, (SELECT __DYNAMIC__ FROM TB_EMP) v",
                // 뷰(컬럼을 정의 쿼리에서 유도했다)가 낀 여러 테이블: 컬럼 목록이 다 있다고 말할 수 없어 진단도 없다
                "SELECT no_col FROM V_EMP v, TB_DEPT d WHERE v.EMP_ID = d.DEPT_ID",
                // 함수 인자 자리의 자료형·날짜 단위·문자셋 이름(범위 밖 방언에서 흔하다)
                "UPDATE TB_EMP SET NM = 0 WHERE NM < FLOOR(CONVERT(FLOAT, GETDATE()))",
                "SELECT CONVERT(NM, SIGNED), CONVERT(NM USING utf8), CONVERT(VARCHAR(10), NM, 120) FROM TB_EMP",
                "SELECT DATEADD(DAY, 1, NM), DATEDIFF(MONTH, NM, GETDATE()), TIMESTAMPDIFF(MINUTE, NM, NOW()) FROM TB_EMP",
                "SELECT NM FROM TB_EMP WHERE NM >= TRUNC(SYSDATE, MONTH) AND NM < TIMESTAMPADD(SQL_TSI_DAY, 1, NM)",
                // MySQL의 SELECT 수식어
                "SELECT SQL_CALC_FOUND_ROWS NM FROM TB_EMP",
                // 파서가 다 읽지 못한 구문(큰 문제가 남은 구문)에서는 추측하지 않는다
                "SELECT TOP 10 NM FROM TB_EMP",
                "SELECT NM FROM TB_EMP WHERE BINARY NM = 'a'");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notColumns")
    @DisplayName("F-006-AC31 의사 컬럼·특수 이름·시퀀스·SELECT 별칭·따옴표 이름·동적 조각·함수·바인드는 컬럼 참조도 진단도 만들지 않는다")
    void exclusions(String sql) {
        GraphModel g = analyze(sql);

        assertThat(undeclaredColumns(g)).as(sql).isEmpty();
        assertThat(unknown(g)).as(sql).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC31 테이블이 여럿일 때도 별칭·의사 컬럼·따옴표 이름은 진단에 들지 않는다")
    void exclusionsWithManyTables() {
        GraphModel g = analyze("SELECT e.DEPT_ID AS grp, COUNT(*) AS cnt, ROWNUM FROM TB_EMP e, TB_DEPT d WHERE e.DEPT_ID = d.DEPT_ID "
                + "AND e.NM = \"kim\" GROUP BY grp HAVING cnt > 1 ORDER BY cnt");

        assertThat(undeclaredColumns(g)).isEmpty();
        assertThat(unknown(g)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC31 전개본 조합이 만든 가짜 짝: 그 이름이 같은 주인의 다른 전개본이 읽는 테이블의 DDL에 있으면 DDL에 없는 쪽 참조·진단은 만들지 않는다")
    void nameExplainedByTableOfAnotherVariant() {
        // MyBatis <choose> 둘(컬럼 목록, 테이블)의 조합: 맞는 짝 둘과 어긋난 짝 둘
        GraphModel g = analyze(
                "SELECT DEPT_NM FROM (SELECT * FROM TB_DEPT ORDER BY DEPT_ID) WHERE ROWNUM <= ?",
                "SELECT NM FROM (SELECT * FROM TB_EMP ORDER BY EMP_ID) WHERE ROWNUM <= ?",
                "SELECT DEPT_NM FROM (SELECT * FROM TB_EMP ORDER BY EMP_ID) WHERE ROWNUM <= ?",
                "SELECT NM FROM (SELECT * FROM TB_DEPT ORDER BY DEPT_ID) WHERE ROWNUM <= ?");
        assertThat(GraphAssert.hasEdge(g, owner("stmt"), EdgeKind.READS, "db:column:TB_DEPT.DEPT_NM")).isTrue();
        assertThat(GraphAssert.hasEdge(g, owner("stmt"), EdgeKind.READS, "db:column:TB_EMP.NM")).isTrue();
        assertThat(undeclaredColumns(g)).isEmpty();
        assertThat(unknown(g)).isEmpty();

        // 맞는 짝의 전개본이 없어도 같다(전개본 수 상한 등): 다른 전개본이 TB_DEPT를 읽고 그 DDL에 DEPT_NM이 있다
        GraphModel partial = analyze(
                "SELECT DEPT_NM FROM (SELECT * FROM TB_EMP ORDER BY EMP_ID) WHERE ROWNUM <= ?",
                "SELECT GRADE_NM FROM (SELECT * FROM TB_DEPT ORDER BY DEPT_ID) WHERE ROWNUM <= ?",
                "SELECT * FROM (SELECT * FROM TB_GRADE ORDER BY GRADE_ID) WHERE ROWNUM <= ?");
        assertThat(undeclaredColumns(partial)).isEmpty();
        assertThat(unknown(partial)).isEmpty();

        // 테이블이 여럿인 전개본의 진단도 같다: DEPT_NM은 다른 전개본이 읽는 TB_DEPT의 컬럼이다
        GraphModel many = analyze(
                "SELECT DEPT_ID FROM TB_DEPT",
                "SELECT DEPT_NM FROM TB_EMP e, TB_GRADE g WHERE e.EMP_ID = g.GRADE_ID");
        assertThat(undeclaredColumns(many)).isEmpty();
        assertThat(unknown(many)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC31 같은 전개본 안에서 함께 읽는 테이블(보이지 않는 스코프)에 같은 이름이 있는 것은 근거가 아니다: 참조는 그대로 남는다")
    void sameNameInTableOfSameVariantIsNotEvidence() {
        // 두 전개본 모두 TB_DEPT를 서브쿼리에서 읽는다. 바깥의 DEPT_NM은 TB_EMP 말고는 볼 수 있는 테이블이 없다
        GraphModel g = analyze(
                "SELECT DEPT_NM FROM TB_EMP WHERE DEPT_ID IN (SELECT DEPT_ID FROM TB_DEPT)",
                "SELECT DEPT_NM FROM TB_EMP WHERE DEPT_ID IN (SELECT DEPT_ID FROM TB_DEPT) AND NM = ?");
        assertThat(undeclaredColumns(g)).containsExactly("db:column:TB_EMP.DEPT_NM");

        // 별칭으로 한정해 쓴 DDL에 없는 컬럼은 추측이 아니므로 다른 전개본과 상관없이 남는다
        GraphModel qualified = analyze("SELECT e.DEPT_NM FROM TB_EMP e", "SELECT d.DEPT_NM FROM TB_DEPT d");
        assertThat(undeclaredColumns(qualified)).containsExactly("db:column:TB_EMP.DEPT_NM");
    }

    @Test
    @DisplayName("F-006-AC31 F-008-AC18 루틴 본문: 변수·매개변수는 컬럼이 아니고, DDL에 없는 이름은 매퍼 구문과 같은 규칙이다(테이블이 하나면 declared=false 참조, 여럿이면 구문마다 진단 1건)")
    void routineBody() {
        String source = """
                CREATE OR REPLACE PROCEDURE SP_EMP (P_ID IN VARCHAR2) IS
                    V_NM VARCHAR2(100);
                    V_CNT NUMBER;
                BEGIN
                    SELECT NM
                      INTO V_NM
                      FROM TB_EMP
                     WHERE EMP_ID = P_ID
                       AND NO_COL = V_CNT;
                    SELECT COUNT(*)
                      INTO V_CNT
                      FROM TB_EMP E, TB_DEPT D
                     WHERE E.DEPT_ID = D.DEPT_ID
                       AND OTHER_COL = P_ID
                       AND OTHER_COL <> V_NM;
                    UPDATE TB_EMP SET NM = V_NM WHERE EMP_ID = P_ID;
                END;
                /
                """;
        try (TestProject project = TestProject.create().file("db/ddl.sql", DDL).file("db/oracle/sp_emp.prc", source)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            // 테이블이 하나뿐인 구문: 그 테이블의 컬럼으로 참조한다(F-008-R17. 전에는 루틴 본문이면 진단만 남겼다)
            assertThat(undeclaredColumns(g)).containsExactly("db:column:TB_EMP.NO_COL");
            assertThat(GraphAssert.hasEdge(g, Ids.routineStatement("SP_EMP", 5), EdgeKind.READS, "db:column:TB_EMP.NO_COL")).isTrue();
            // 테이블이 여럿인 구문: 구문마다 진단 1건, 변수·매개변수는 메시지에 없다
            List<Diagnostic> diagnostics = unknown(g);
            assertThat(diagnostics).hasSize(1);
            Diagnostic many = diagnostics.get(0);
            assertThat(many.severity()).isEqualTo(Severity.INFO);
            assertThat(many.message()).contains("OTHER_COL").contains("TB_EMP, TB_DEPT").doesNotContain("NO_COL")
                    .doesNotContain("P_ID").doesNotContain("V_NM").doesNotContain("V_CNT");
            assertThat(many.message().split("OTHER_COL", -1)).as("컬럼 이름은 한 번만").hasSize(2);
            assertThat(many.location().file()).isEqualTo("db/oracle/sp_emp.prc");
            assertThat(many.location().startLine()).isBetween(10, 15);
        }
    }

    @Test
    @DisplayName("F-006-AC31 SQL 파일의 구문과 뷰 정의에도 같은 규칙이 적용된다(구문마다 진단 1건)")
    void sqlFileStatements() {
        String script = """
                SELECT NO_COL FROM TB_EMP WHERE EMP_ID = '1';
                SELECT X1, X1, X2 FROM TB_EMP E, TB_DEPT D WHERE E.DEPT_ID = D.DEPT_ID;
                """;
        try (TestProject project = TestProject.create().file("db/ddl.sql", DDL).file("db/query.sql", script)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(undeclaredColumns(g)).containsExactly("db:column:TB_EMP.NO_COL");
            assertThat(GraphAssert.hasEdge(g, Ids.sqlStatement("db/query.sql", 1), EdgeKind.READS, "db:column:TB_EMP.NO_COL")).isTrue();
            List<Diagnostic> diagnostics = unknown(g);
            assertThat(diagnostics).hasSize(1);
            assertThat(diagnostics.get(0).message()).contains("X1, X2").contains(Ids.sqlStatement("db/query.sql", 2));
            assertThat(diagnostics.get(0).location().startLine()).isEqualTo(2);
        }
    }
}
