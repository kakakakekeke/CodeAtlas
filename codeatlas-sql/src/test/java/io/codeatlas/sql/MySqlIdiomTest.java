package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 대상 외 방언(MySQL) 구문에서 틀린 사실을 만들지 않는다(F-006-R35, R36). 공개 프로젝트 재검증에서 {@code INTERVAL -6 MONTH}의
 * {@code INTERVAL}이 컬럼이, {@code INSERT IGNORE INTO t}의 {@code IGNORE}와 {@code DELETE a, b FROM ..}의 {@code a}가 테이블이,
 * {@code UPDATE a JOIN b .. SET}의 {@code JOIN}이 컬럼이 됐다.
 */
class MySqlIdiomTest {

    private static final String MAPPER = "src/main/resources/mapper/M_SQL_mysql.xml";

    private static final String DDL = """
            CREATE TABLE TB_A (ID INT NOT NULL, NM VARCHAR(50), REG_DT DATETIME, STATUS INT, PRIMARY KEY (ID));
            CREATE TABLE TB_B (ID INT NOT NULL, A_ID INT NOT NULL, VAL VARCHAR(100), PRIMARY KEY (ID));
            """;

    private static String owner(String id) {
        return Ids.mybatisStatement("M", id);
    }

    /** 방언(mysql 또는 null)을 단 내장 SQL 하나를 분석한다. */
    private static GraphModel analyze(String dialect, String sql) {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = project.file("db/ddl.sql", DDL).file(MAPPER, "<mapper namespace=\"M\"/>").context();
            ctx.graph().declare(Node.builder(owner("s"), NodeKind.SQL_STATEMENT).name("s").location(SourceLocation.of(MAPPER, 5)).build());
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner("s"), sql, SourceLocation.of(MAPPER, 5, 5, 5, 5), false,
                    dialect == null ? Map.of() : Map.of("dialect", dialect)));
            return TestProject.run(ctx, new SqlAnalyzer()).graph();
        }
    }

    private static List<String> undeclared(GraphModel g) {
        return g.nodes().stream().filter(n -> (n.kind() == NodeKind.DB_COLUMN || n.kind() == NodeKind.DB_TABLE) && !n.declared())
                .map(Node::id).sorted().toList();
    }

    private static void clean(GraphModel g) {
        assertThat(GraphAssert.diagnostics(g, DiagnosticCodes.PARSE_FAILED)).isEmpty();
        assertThat(GraphAssert.diagnostics(g, DiagnosticCodes.UNSUPPORTED_SYNTAX)).isEmpty();
        assertThat(GraphAssert.diagnostics(g, SqlDiagnosticCodes.UNKNOWN_COLUMN)).isEmpty();
        assertThat(undeclared(g)).as("DDL에 없는 테이블·컬럼이 생기지 않는다").isEmpty();
    }

    @Test
    @DisplayName("F-006-AC36 INTERVAL n 단위: INTERVAL -6 MONTH, INTERVAL #{n} DAY, INTERVAL 컬럼 DAY — INTERVAL이 컬럼이 되지 않는다(Oracle의 INTERVAL '6' MONTH도 그대로)")
    void intervalIsNotAColumn() {
        GraphModel g = analyze("mysql", "SELECT ID FROM TB_A WHERE REG_DT < ADDDATE(SYSDATE(), INTERVAL -6 MONTH) "
                + "AND REG_DT > DATE_SUB(NOW(), INTERVAL #{n} DAY) AND REG_DT < DATE_ADD(REG_DT, INTERVAL STATUS DAY) AND REG_DT > NOW() - INTERVAL 1 WEEK");
        clean(g);
        assertThat(targets(g, owner("s"), EdgeKind.READS)).containsExactly("db:column:TB_A.ID", "db:column:TB_A.REG_DT", "db:column:TB_A.STATUS", "db:table:TB_A");

        GraphModel oracle = analyze(null, "DELETE FROM TB_A WHERE REG_DT < SYSDATE - INTERVAL '6' MONTH AND REG_DT < SYSTIMESTAMP - INTERVAL '1 12' DAY TO HOUR");
        clean(oracle);
        assertThat(targets(oracle, owner("s"), EdgeKind.READS)).containsExactly("db:column:TB_A.REG_DT");
    }

    @Test
    @DisplayName("F-006-AC36 INSERT IGNORE INTO, REPLACE INTO, INSERT .. SET, ON DUPLICATE KEY UPDATE: 대상 테이블과 컬럼이 맞고 IGNORE가 테이블이 되지 않는다")
    void insertVariants() {
        GraphModel ignore = analyze("mysql", "INSERT IGNORE INTO TB_B (A_ID, VAL) VALUES (#{aId}, #{val})");
        clean(ignore);
        assertThat(targets(ignore, owner("s"), EdgeKind.WRITES)).containsExactly("db:column:TB_B.A_ID", "db:column:TB_B.VAL", "db:table:TB_B");
        assertThat(edge(ignore, owner("s"), EdgeKind.WRITES, "db:table:TB_B").attrString("op")).isEqualTo("INSERT");

        GraphModel replace = analyze("mysql", "REPLACE INTO TB_A (ID, NM) VALUES (#{id}, #{nm})");
        clean(replace);
        assertThat(targets(replace, owner("s"), EdgeKind.WRITES)).containsExactly("db:column:TB_A.ID", "db:column:TB_A.NM", "db:table:TB_A");

        GraphModel set = analyze("mysql", "INSERT INTO TB_B SET A_ID = #{aId}, VAL = #{val}");
        clean(set);
        assertThat(targets(set, owner("s"), EdgeKind.WRITES)).containsExactly("db:column:TB_B.A_ID", "db:column:TB_B.VAL", "db:table:TB_B");

        GraphModel upsert = analyze("mysql", "INSERT INTO TB_A (ID, NM) VALUES (#{id}, #{nm}) ON DUPLICATE KEY UPDATE NM = VALUES(NM), STATUS = STATUS + 1");
        clean(upsert);
        assertThat(targets(upsert, owner("s"), EdgeKind.WRITES)).contains("db:column:TB_A.ID", "db:column:TB_A.NM", "db:table:TB_A");
    }

    @Test
    @DisplayName("F-006-AC36 UPDATE a JOIN b ON .. SET, DELETE a, b FROM a JOIN b: 대상과 조인이 맞고 JOIN이 컬럼이, 별칭이 테이블이 되지 않는다")
    void multiTableUpdateAndDelete() {
        GraphModel update = analyze("mysql", "UPDATE TB_A a JOIN TB_B b ON a.ID = b.A_ID SET a.NM = b.VAL WHERE b.ID = #{id}");
        clean(update);
        assertThat(targets(update, owner("s"), EdgeKind.WRITES)).containsExactly("db:column:TB_A.NM", "db:table:TB_A");
        assertThat(hasEdge(update, owner("s"), EdgeKind.READS, "db:table:TB_B")).isTrue();
        assertThat(edge(update, "db:table:TB_A", EdgeKind.JOINS, "db:table:TB_B").attrList("leftColumns")).containsExactly("ID");

        GraphModel delete = analyze("mysql", "DELETE a, b FROM TB_A a INNER JOIN TB_B b ON a.ID = b.A_ID WHERE a.ID = #{id}");
        clean(delete);
        assertThat(targets(delete, owner("s"), EdgeKind.WRITES)).containsExactly("db:table:TB_A", "db:table:TB_B");
        assertThat(hasEdge(delete, "db:table:TB_A", EdgeKind.JOINS, "db:table:TB_B")).isTrue();
        assertThat(delete.hasNode("db:table:A")).isFalse();
    }

    @Test
    @DisplayName("F-006-AC36 사용자 변수 @v와 @v := 식: 주 테이블이 남고 ORDER BY 컬럼이 제 테이블에 붙는다. REGEXP, 인덱스 힌트, STRAIGHT_JOIN도 읽는다")
    void userVariablesAndOperators() {
        GraphModel g = analyze("mysql", "SELECT @RNUM := @RNUM + 1 AS RNUM, T.* FROM (SELECT a.ID, a.NM, "
                + "(SELECT COUNT(*) FROM TB_B b WHERE b.A_ID = a.ID) CNT FROM TB_A a WHERE a.STATUS = 1 ORDER BY a.REG_DT DESC) T, (SELECT @RNUM := 0) R");
        clean(g);
        assertThat(targets(g, owner("s"), EdgeKind.READS)).containsExactly("db:column:TB_A.ID", "db:column:TB_A.NM", "db:column:TB_A.REG_DT",
                "db:column:TB_A.STATUS", "db:column:TB_B.A_ID", "db:table:TB_A", "db:table:TB_B");

        GraphModel ops = analyze("mysql", "SELECT a.ID FROM TB_A a FORCE INDEX (IDX_NM) STRAIGHT_JOIN TB_B b ON a.ID = b.A_ID "
                + "WHERE a.NM REGEXP '^a' AND b.VAL NOT REGEXP #{p}");
        clean(ops);
        assertThat(targets(ops, owner("s"), EdgeKind.READS)).contains("db:table:TB_A", "db:table:TB_B", "db:column:TB_A.NM", "db:column:TB_B.VAL");
    }

    @Test
    @DisplayName("F-006-AC36 백슬래시 이스케이프('it\\'s')는 MySQL로 판정한 입력에서만 받는다. Oracle·DB2 입력의 ESCAPE '\\'는 그대로다")
    void backslashEscapeOnlyInMySql() {
        GraphModel mysql = analyze("mysql", "SELECT ID FROM TB_A WHERE NM = 'it\\'s' AND STATUS = 1");
        clean(mysql);
        assertThat(targets(mysql, owner("s"), EdgeKind.READS)).containsExactly("db:column:TB_A.ID", "db:column:TB_A.NM", "db:column:TB_A.STATUS", "db:table:TB_A");

        GraphModel oracle = analyze("oracle", "SELECT ID FROM TB_A WHERE NM LIKE '%\\_%' ESCAPE '\\' AND STATUS = 1");
        clean(oracle);
        assertThat(targets(oracle, owner("s"), EdgeKind.READS)).containsExactly("db:column:TB_A.ID", "db:column:TB_A.NM", "db:column:TB_A.STATUS", "db:table:TB_A");
    }

    @Test
    @DisplayName("F-006-AC35 SQL 키워드 이름은 DDL에 없는 컬럼으로 추측하지 않고 진단에도 올리지 않는다. DDL에 그 이름의 컬럼이 있으면 컬럼이다")
    void keywordsAreNotGuessedColumns() {
        // 해석하지 못한 표기가 문제 없이 지나가도(여기서는 가상의 함수 인자) 키워드는 컬럼으로 추측하지 않는다
        GraphModel g = analyze(null, "SELECT ID FROM TB_A WHERE REG_DT < SOME_FN(INTERVAL, 6) AND NM = OTHER_FN(DISTINCT, IGNORE)");
        assertThat(undeclared(g)).isEmpty();
        assertThat(GraphAssert.diagnostics(g, SqlDiagnosticCodes.UNKNOWN_COLUMN)).isEmpty();
        // 키워드가 아닌 이름은 전과 같이 하나뿐인 테이블의 컬럼이다
        GraphModel plain = analyze(null, "SELECT ID FROM TB_A WHERE DEL_YN = 'N'");
        assertThat(undeclared(plain)).containsExactly("db:column:TB_A.DEL_YN");
    }

    @Test
    @DisplayName("F-006-AC35 해석하지 못한 구간이 있는 구문에서는 DDL에 없는 컬럼을 만들지 않는다: 한정한 이름과 SET·INSERT 컬럼도 DDL로 확정되는 것만 남는다")
    void brokenStatementsKeepOnlyCatalogColumns() {
        GraphModel g = analyze(null, "UPDATE TB_A a SOMETHING STRANGE b SET a.NM = 1, a.GHOST = 2, PHANTOM = 3 WHERE a.ID = 1 AND a.NO_COL = 2");
        assertThat(GraphAssert.diagnostics(g, DiagnosticCodes.PARSE_FAILED)).isNotEmpty();
        assertThat(undeclared(g)).isEmpty();
        assertThat(hasEdge(g, owner("s"), EdgeKind.WRITES, "db:table:TB_A")).isTrue();
        // 문제 없이 읽은 구문에서는 전과 같이 한정한 이름과 SET 컬럼이 참조가 된다
        GraphModel fine = analyze(null, "UPDATE TB_A a SET a.GHOST = 2 WHERE a.NO_COL = 2");
        assertThat(undeclared(fine)).containsExactly("db:column:TB_A.GHOST", "db:column:TB_A.NO_COL");
    }
}
