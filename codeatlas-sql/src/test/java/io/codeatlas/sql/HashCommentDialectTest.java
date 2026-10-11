package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.GraphAssert.node;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 줄 처음의 {@code #}을 주석으로 보는 MySQL 관용은 MySQL로 판정한 SQL에서만 쓴다(F-006-R27).
 * DB2(z/OS)와 MSSQL에서는 {@code #}으로 시작하는 이름(임시 테이블 {@code #TMP})이 있어, 근거 없이 주석으로 보면 줄이 통째로 사라진다.
 */
class HashCommentDialectTest {

    private static final String DB2_SQL = "INSERT INTO SESSION.T_OUT (A)\nSELECT S.A FROM\n#TMP_SRC S\nWHERE S.A > 0;\n";

    private static GraphModel analyze(String path, String sql, AnalysisOptions options) {
        try (TestProject project = TestProject.create().file(path, sql)) {
            if (options != null) {
                project.options(options);
            }
            return project.run(new SqlAnalyzer()).graph();
        }
    }

    @Test
    @DisplayName("F-006-AC27 방언의 근거가 없거나 Oracle·DB2인 파일에서는 줄 처음의 #이 주석이 아니다: #TMP_SRC는 테이블 이름이다")
    void hashIsIdentifierWithoutMySqlEvidence() {
        for (String path : new String[] {"db/work.sql", "db/db2/work.sql", "db/work_oracle.sql"}) {
            GraphModel g = analyze(path, DB2_SQL, null);
            String statement = Ids.sqlStatement(path, 1);
            assertThat(hasEdge(g, statement, EdgeKind.READS, "db:table:#TMP_SRC")).as(path).isTrue();
            assertThat(hasEdge(g, statement, EdgeKind.WRITES, "db:table:T_OUT")).as(path).isTrue();
            assertThat(g.diagnostics()).as(path).isEmpty();
        }
        // --dialect를 줬으면 방언 이름이 없는 파일은 그 방언이다
        GraphModel g = analyze("db/work.sql", DB2_SQL, AnalysisOptions.builder().dialect(Dialect.DB2).build());
        assertThat(hasEdge(g, "sql:stmt:db/work.sql:1", EdgeKind.READS, "db:table:#TMP_SRC")).isTrue();
    }

    @Test
    @DisplayName("F-006-AC27 MySQL로 판정한 파일(경로의 방언 이름, mybatis.dialectVariant, 백틱·AUTO_INCREMENT·ENGINE= 특징)에서만 줄 처음의 #이 주석이다")
    void hashIsCommentInMySqlFiles() {
        String plain = "# 사용자 테이블\nCREATE TABLE T_USR (ID INT NOT NULL, NM VARCHAR(20));\n# 끝\n";
        GraphModel byPath = analyze("db/mysql/schema.sql", plain, null);
        assertThat(node(byPath, "db:table:T_USR").declared()).isTrue();
        assertThat(GraphAssert.codes(byPath)).as("대상 외 방언뿐인 프로젝트라는 안내(F-007-R25)만 있다").containsExactly(SqlDiagnosticCodes.DIALECT_NOT_TARGET);

        GraphModel byName = analyze("db/schema_mariadb.sql", plain, AnalysisOptions.builder().dialect(Dialect.ORACLE).build());
        assertThat(byName.hasNode("db:table:T_USR")).isTrue();
        assertThat(GraphAssert.codes(byName)).as("대상 외 방언뿐인 프로젝트라는 안내(F-007-R25)만 있다").containsExactly(SqlDiagnosticCodes.DIALECT_NOT_TARGET);

        GraphModel byOption = analyze("db/schema.sql", plain, AnalysisOptions.builder().extra("mybatis.dialectVariant", "mysql").build());
        assertThat(byOption.hasNode("db:table:T_USR")).isTrue();
        assertThat(byOption.diagnostics()).isEmpty();

        GraphModel byFeature = analyze("db/schema.sql",
                "# 사용자 테이블\nCREATE TABLE `T_USR` (`ID` int NOT NULL AUTO_INCREMENT, PRIMARY KEY (`ID`)) ENGINE=InnoDB;\n# 끝\n", null);
        assertThat(byFeature.hasNode("db:table:T_USR")).isTrue();
        assertThat(GraphAssert.codes(byFeature)).as("대상 외 방언뿐인 프로젝트라는 안내(F-007-R25)만 있다").containsExactly(SqlDiagnosticCodes.DIALECT_NOT_TARGET);

        // 근거가 없으면 주석이 아니다: 첫 줄이 구문에 섞여 CREATE TABLE을 읽지 못한다(전에는 조용히 주석으로 지웠다)
        GraphModel noEvidence = analyze("db/schema.sql", plain, null);
        assertThat(noEvidence.hasNode("db:table:T_USR") && noEvidence.diagnostics().isEmpty()).isFalse();
    }

    @Test
    @DisplayName("F-006-AC27 내장 SQL도 같다: attrs.dialect가 mysql·mariadb일 때만 줄 처음의 #이 주석이고, 그 밖에는 이름의 일부다")
    void embeddedSql() {
        try (TestProject project = TestProject.create().file("mapper/M.xml", "<mapper namespace=\"m\"/>")) {
            AnalysisContext ctx = project.context();
            String mysql = owner(ctx, "mysql");
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, mysql, "SELECT A.ID FROM T_A A\n# 주석 FROM T_GHOST\nWHERE A.ID = ?",
                    SourceLocation.of("mapper/M.xml", 1, 1, 1, 1), false, Map.of("dialect", "mysql")));
            String db2 = owner(ctx, "db2");
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, db2, "SELECT S.A FROM\n#TMP_SRC S\nWHERE S.A = ?",
                    SourceLocation.of("mapper/M.xml", 1, 1, 1, 1), false, Map.of("dialect", "db2")));
            String none = owner(ctx, "none");
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, none, "SELECT S.A FROM\n#TMP_SRC S\nWHERE S.A = ?",
                    SourceLocation.of("mapper/M.xml", 1, 1, 1, 1), false, Map.of()));
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(hasEdge(g, mysql, EdgeKind.READS, "db:table:T_A")).isTrue();
            assertThat(g.hasNode("db:table:T_GHOST")).isFalse();
            assertThat(hasEdge(g, db2, EdgeKind.READS, "db:table:#TMP_SRC")).isTrue();
            assertThat(hasEdge(g, none, EdgeKind.READS, "db:table:#TMP_SRC")).isTrue();
            assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).isEmpty();
        }
    }

    private static String owner(AnalysisContext ctx, String id) {
        String owner = Ids.mybatisStatement("m", id);
        ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name(id).location(SourceLocation.of("mapper/M.xml", 1)).build());
        return owner;
    }
}
