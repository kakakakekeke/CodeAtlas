package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.GraphAssert.node;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 같은 테이블의 DDL이 방언별 폴더에 여러 벌 있을 때(F-007-R14)와 upgrade 스크립트의 ALTER(F-007-R15).
 * 공개 프로젝트에서 한 테이블 노드에 위치는 첫 파일, 컬럼 타입은 마지막 파일 값이 섞이고 FK와 인덱스가 파일 수만큼 중복됐다.
 */
class DuplicateDefinitionTest {

    private static final String ORACLE = """
            CREATE TABLE T_USR (
              USR_ID VARCHAR2(20) NOT NULL,
              USR_NM VARCHAR2(60),
              GRP_ID CHAR(20),
              CONSTRAINT T_USR_PK PRIMARY KEY (USR_ID),
              CONSTRAINT T_USR_FK1 FOREIGN KEY (GRP_ID) REFERENCES T_GRP (GRP_ID)
            );
            CREATE TABLE T_GRP (
              GRP_ID CHAR(20) NOT NULL,
              GRP_NM VARCHAR2(60),
              CONSTRAINT T_GRP_PK PRIMARY KEY (GRP_ID)
            );
            CREATE INDEX T_USR_IX1 ON T_USR (USR_NM);
            COMMENT ON TABLE T_USR IS '사용자';
            CREATE OR REPLACE VIEW V_USR AS SELECT U.USR_ID, G.GRP_NM FROM T_USR U, T_GRP G WHERE U.GRP_ID = G.GRP_ID;
            CREATE SEQUENCE SEQ_USR;
            """;

    private static final String MYSQL = """
            CREATE TABLE T_USR (
              USR_ID varchar(20) NOT NULL,
              USR_NM varchar(60) DEFAULT NULL,
              GRP_ID char(20) DEFAULT NULL,
              MYSQL_ONLY int(11) DEFAULT NULL,
              PRIMARY KEY (USR_ID),
              KEY T_USR_FK1 (GRP_ID),
              CONSTRAINT T_USR_FK1 FOREIGN KEY (GRP_ID) REFERENCES T_GRP (GRP_ID)
            );
            CREATE TABLE T_GRP (
              GRP_ID char(20) NOT NULL,
              GRP_NM varchar(60) DEFAULT NULL,
              PRIMARY KEY (GRP_ID)
            );
            CREATE INDEX T_USR_IX1 ON T_USR (USR_NM);
            CREATE OR REPLACE VIEW V_USR AS SELECT U.USR_ID, G.GRP_NM FROM T_USR U, T_GRP G WHERE U.GRP_ID = G.GRP_ID;
            CREATE SEQUENCE SEQ_USR;
            """;

    private static final String ALTIBASE = MYSQL.replace("MYSQL_ONLY int(11)", "ALTI_ONLY INTEGER").replace("varchar", "VARCHAR")
            + "ALTER TABLE T_GRP ADD (ALTI_COL VARCHAR(10));\n";

    @Test
    @DisplayName("F-007-AC14 방언별 DDL 폴더가 함께 있으면 한 테이블의 정의는 한 파일 것만 쓴다(auto: Oracle·DB2 경로 우선). FK·인덱스·뷰 구문이 중복되지 않는다")
    void oneDefinitionPerTable() {
        try (TestProject project = TestProject.create()
                .file("DATABASE/altibase/all_ddl_altibase.sql", ALTIBASE)
                .file("DATABASE/mysql/all_ddl_mysql.sql", MYSQL)
                .file("DATABASE/oracle/all_ddl_oracle.sql", ORACLE)
                .file("DATABASE/mysql/comment_mysql.sql", "ALTER TABLE T_USR ADD COLUMN LATE_MYSQL int;\nALTER TABLE T_GRP COMMENT = 'x';\n")
                .file("DATABASE/common/extra.sql", "ALTER TABLE T_USR ADD (COMMON_COL NUMBER);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            Node usr = node(g, "db:table:T_USR");
            assertThat(usr.location().file()).isEqualTo("DATABASE/oracle/all_ddl_oracle.sql");
            assertThat(targets(g, "db:table:T_USR", EdgeKind.CONTAINS)).containsExactly(
                    "db:column:T_USR.COMMON_COL", "db:column:T_USR.GRP_ID", "db:column:T_USR.USR_ID", "db:column:T_USR.USR_NM");
            assertThat(node(g, "db:column:T_USR.USR_ID").attrString("dataType")).isEqualTo("VARCHAR2(20)");
            assertThat(node(g, "db:column:T_USR.USR_ID").location().file()).isEqualTo("DATABASE/oracle/all_ddl_oracle.sql");
            assertThat(node(g, "db:column:T_USR.GRP_ID").attrString("dataType")).isEqualTo("CHAR(20)");
            assertThat(usr.attrString("comment")).isEqualTo("사용자");
            assertThat(usr.attrList("indexes")).containsExactly("T_USR_IX1(USR_NM)");
            assertThat(g.edges("db:table:T_USR", EdgeKind.REFERENCES, "db:table:T_GRP")).hasSize(1);
            assertThat(edge(g, "db:table:T_USR", EdgeKind.REFERENCES, "db:table:T_GRP").location().file()).isEqualTo("DATABASE/oracle/all_ddl_oracle.sql");
            assertThat(g.incoming("db:table:T_USR", EdgeKind.CONTAINS)).extracting(e -> e.from()).containsExactly("file:DATABASE/oracle/all_ddl_oracle.sql");
            assertThat(targets(g, "db:table:T_GRP", EdgeKind.CONTAINS)).containsExactly("db:column:T_GRP.GRP_ID", "db:column:T_GRP.GRP_NM");
            // 뷰와 시퀀스도 한 벌만: 정의 쿼리 구문 노드가 방언 수만큼 생기지 않는다
            assertThat(node(g, "db:view:V_USR").location().file()).isEqualTo("DATABASE/oracle/all_ddl_oracle.sql");
            assertThat(targets(g, "db:view:V_USR", EdgeKind.CONTAINS).stream().filter(t -> t.startsWith("sql:stmt:"))).hasSize(1);
            assertThat(g.nodes(NodeKind.SQL_STATEMENT)).hasSize(1);
            assertThat(g.incoming("db:sequence:SEQ_USR", EdgeKind.CONTAINS)).hasSize(1);
            assertThat(node(g, "db:sequence:SEQ_USR").location().file()).isEqualTo("DATABASE/oracle/all_ddl_oracle.sql");

            // 방언별로 한 벌을 고른 중복에는 DUPLICATE_OBJECT_NAME의 "한 벌만 지정 권장"을 내지 않는다(F-007-R27. 전에는 파일 쌍마다 하나였다)
            assertThat(diagnostics(g, DiagnosticCodes.DUPLICATE_OBJECT_NAME)).isEmpty();
            // 방언별 DDL이 여러 벌이라는 요약이 분석 한 번에 하나 있다(F-007-R19)
            List<Diagnostic> summary = diagnostics(g, SqlDiagnosticCodes.DIALECT_VARIANT_DDL);
            assertThat(summary).hasSize(1);
            assertThat(summary.get(0).severity()).isEqualTo(Severity.INFO);
            assertThat(summary.get(0).message()).contains("oracle 파일을 사용(테이블 3개)");
            assertThat(g.diagnostics()).hasSize(1);
        }
    }

    @Test
    @DisplayName("F-007-AC14 --dialect가 있으면 그 방언 경로의 정의를, 힌트가 없으면 경로순 첫 파일의 정의를 쓴다")
    void selectionOrder() {
        try (TestProject project = TestProject.create()
                .file("db/db2/ddl.sql", "CREATE TABLE T_X (ID INTEGER NOT NULL, DB2_COL VARCHAR(10));\n")
                .file("db/oracle/ddl.sql", "CREATE TABLE T_X (ID NUMBER NOT NULL, ORA_COL VARCHAR2(10));\n")
                .options(AnalysisOptions.builder().dialect(Dialect.DB2).build())) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:T_X").location().file()).isEqualTo("db/db2/ddl.sql");
            assertThat(targets(g, "db:table:T_X", EdgeKind.CONTAINS)).containsExactly("db:column:T_X.DB2_COL", "db:column:T_X.ID");
        }
        try (TestProject project = TestProject.create()
                .file("db/db2/ddl.sql", "CREATE TABLE T_X (ID INTEGER NOT NULL, DB2_COL VARCHAR(10));\n")
                .file("db/oracle/ddl.sql", "CREATE TABLE T_X (ID NUMBER NOT NULL, ORA_COL VARCHAR2(10));\n")
                .options(AnalysisOptions.builder().dialect(Dialect.ORACLE).build())) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(targets(g, "db:table:T_X", EdgeKind.CONTAINS)).containsExactly("db:column:T_X.ID", "db:column:T_X.ORA_COL");
        }
        // 힌트가 없는 방언끼리: 그 밖의 방언의 순서(이름의 사전순, F-007-R28. 이 배치에서는 경로순과 같다). 다만 upgrade 스크립트는 뒤로 미룬다
        try (TestProject project = TestProject.create()
                .file("db/a_upgrade/update_2020.sql", "CREATE TABLE T_X (ID INT, OLD_COL INT);\n")
                .file("db/maria/ddl.sql", "CREATE TABLE T_X (ID INT, MARIA_COL INT);\n")
                .file("db/postgres/ddl.sql", "CREATE TABLE T_X (ID INT, PG_COL INT);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:T_X").location().file()).isEqualTo("db/maria/ddl.sql");
            assertThat(targets(g, "db:table:T_X", EdgeKind.CONTAINS)).containsExactly("db:column:T_X.ID", "db:column:T_X.MARIA_COL");
        }
    }

    @Test
    @DisplayName("F-007-AC14 같은 폴더 쌍에 파일 쌍이 여럿이면(테이블마다 파일 하나) 진단을 폴더 쌍 하나로 요약한다")
    void summaryByDirectoryPair() {
        try (TestProject project = TestProject.create()
                .file("script/ddl/a_new/t_a.sql", "CREATE TABLE T_A (ID NUMBER);\n")
                .file("script/ddl/a_new/t_b.sql", "CREATE TABLE T_B (ID NUMBER);\n")
                .file("script/ddl/a_new/t_c.sql", "CREATE TABLE T_C (ID NUMBER);\n")
                .file("script/ddl/b_old/t_a.sql", "CREATE TABLE T_A (ID INT);\n")
                .file("script/ddl/b_old/t_b.sql", "CREATE TABLE T_B (ID INT);\n")
                .file("script/ddl/b_old/t_c.sql", "CREATE TABLE T_C (ID INT);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            List<Diagnostic> dup = diagnostics(g, DiagnosticCodes.DUPLICATE_OBJECT_NAME);
            assertThat(dup).hasSize(1);
            assertThat(dup.get(0).message()).contains("3개").contains("script/ddl/a_new/").contains("script/ddl/b_old/");
            assertThat(node(g, "db:column:T_B.ID").attrString("dataType")).isEqualTo("NUMBER");
        }
    }

    @Test
    @DisplayName("F-007-AC15 CREATE TABLE이 있는 테이블에 대한 upgrade/patch/migration 폴더의 ALTER TABLE은 적용하지 않고 파일마다 INFO 하나를 남긴다")
    void upgradeAltersNotApplied() {
        try (TestProject project = TestProject.create()
                .file("db/sys/schema.sql", "CREATE TABLE SYS_USR (ID VARCHAR2(64) NOT NULL, NM VARCHAR2(100), PRIMARY KEY (ID));\n"
                        + "ALTER TABLE SYS_USR ADD (EMAIL VARCHAR2(200));\n")
                .file("db/upgrade/upgrade20131129.sql", "ALTER TABLE SYS_USR ADD (OLD_FLAG CHAR(1));\nALTER TABLE SYS_USR MODIFY NM VARCHAR2(10);\n"
                        + "ALTER TABLE NOT_DEFINED ADD (X NUMBER);\n")
                .file("db/patch/p1.sql", "ALTER TABLE SYS_USR DROP COLUMN NM;\n")
                .file("db/migration/V2__x.sql", "ALTER TABLE SYS_USR ADD CONSTRAINT FK_X FOREIGN KEY (NM) REFERENCES SYS_OTHER (ID);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(targets(g, "db:table:SYS_USR", EdgeKind.CONTAINS)).containsExactly(
                    "db:column:SYS_USR.EMAIL", "db:column:SYS_USR.ID", "db:column:SYS_USR.NM");
            assertThat(node(g, "db:column:SYS_USR.NM").attrString("dataType")).isEqualTo("VARCHAR2(100)");
            assertThat(hasEdge(g, "db:table:SYS_USR", EdgeKind.REFERENCES, "db:table:SYS_OTHER")).isFalse();
            // CREATE TABLE이 없는 테이블의 ALTER는 전처럼 참조 노드에 반영한다
            assertThat(node(g, "db:table:NOT_DEFINED").declared()).isFalse();
            assertThat(g.hasNode("db:column:NOT_DEFINED.X")).isTrue();
            List<Diagnostic> info = diagnostics(g, SqlDiagnosticCodes.DDL_NOT_APPLIED);
            assertThat(info).hasSize(3);
            assertThat(info).allMatch(d -> d.severity() == Severity.INFO);
            assertThat(info).extracting(d -> d.location().file()).containsExactlyInAnyOrder(
                    "db/upgrade/upgrade20131129.sql", "db/patch/p1.sql", "db/migration/V2__x.sql");
            assertThat(info.stream().filter(d -> d.location().file().contains("upgrade")).findFirst().orElseThrow().message()).contains("2건");
            assertThat(g.diagnostics()).hasSize(3);
        }
    }

    @Test
    @DisplayName("F-007-AC27 현재 정의가 FK를 전혀 담지 않는 스키마(제약을 따로 관리)면 upgrade 스크립트의 FK는 RESOLVED fromUpgrade 그대로다")
    void upgradeForeignKeysStayResolvedWithoutCurrentForeignKeys() {
        try (TestProject project = TestProject.create()
                .file("db/ddl.sql", """
                        CREATE TABLE TB_P (ID VARCHAR2(10) NOT NULL, NM VARCHAR2(20), CONSTRAINT PK_P PRIMARY KEY (ID));
                        CREATE TABLE TB_C (ID VARCHAR2(10) NOT NULL, P_ID VARCHAR2(10), CONSTRAINT PK_C PRIMARY KEY (ID));
                        """)
                .file("db/upgrade/update_20170328.sql", "ALTER TABLE TB_C ADD CONSTRAINT FK_C_P FOREIGN KEY (P_ID) REFERENCES TB_P (ID);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            var fk = edge(g, "db:table:TB_C", EdgeKind.REFERENCES, "db:table:TB_P");
            assertThat(fk.confidence()).isEqualTo(io.codeatlas.core.model.Confidence.RESOLVED);
            assertThat(fk.attr("fromUpgrade")).isEqualTo(true);
            assertThat(fk.attr("note")).isNull();
        }
    }

    @Test
    @DisplayName("F-007-AC22 upgrade 스크립트의 외래 키 추가는 대상 테이블·컬럼이 현재 정의에 있으면 적용하고(fromUpgrade), 적용하지 않은 ALTER는 종류별 건수를 진단에 담는다")
    void upgradeForeignKeysApplied() {
        try (TestProject project = TestProject.create()
                .file("model/schema.sql", """
                        CREATE TABLE CMS_ARTICLE (ARTICLE_ID INT NOT NULL, TOPIC_ID INT, PRIMARY KEY (ARTICLE_ID));
                        CREATE TABLE CMS_TOPIC (TOPIC_ID INT NOT NULL, PRIMARY KEY (TOPIC_ID));
                        CREATE TABLE CMS_TAG (TAG_ID INT NOT NULL, PRIMARY KEY (TAG_ID));
                        CREATE TABLE CMS_ARTICLE_TAG (ARTICLE_ID INT, TAG_ID INT, CONSTRAINT FK_4 FOREIGN KEY (TAG_ID) REFERENCES CMS_TAG (TAG_ID));
                        """)
                // upgrade 스크립트가 같은 테이블을 다시 만들기도 한다(선택되지 않은 정의 파일): 전에는 그 테이블의 FK가 진단 없이 사라졌다
                .file("model/update_20170328_1_to_20170405_1.sql", """
                        CREATE TABLE CMS_ARTICLE (ARTICLE_ID INT NOT NULL, TOPIC_ID INT, OLD_COL INT);
                        ALTER TABLE CMS_ARTICLE ADD CONSTRAINT FK_30 FOREIGN KEY (TOPIC_ID) REFERENCES CMS_TOPIC (TOPIC_ID) ON DELETE RESTRICT;
                        ALTER TABLE CMS_ARTICLE_TAG ADD CONSTRAINT FK_3 FOREIGN KEY (ARTICLE_ID) REFERENCES CMS_ARTICLE (ARTICLE_ID) ON DELETE CASCADE;
                        ALTER TABLE CMS_ARTICLE_TAG ADD CONSTRAINT FK_4 FOREIGN KEY (TAG_ID) REFERENCES CMS_TAG (TAG_ID);
                        ALTER TABLE CMS_ARTICLE_TAG ADD CONSTRAINT FK_GONE FOREIGN KEY (GONE_ID) REFERENCES CMS_TAG (TAG_ID);
                        ALTER TABLE CMS_ARTICLE_TAG ADD CONSTRAINT FK_NOPARENT FOREIGN KEY (TAG_ID) REFERENCES CMS_DROPPED (ID);
                        ALTER TABLE CMS_ARTICLE ADD COLUMN ADDED_COL INT;
                        ALTER TABLE CMS_ARTICLE_TAG MODIFY TAG_ID BIGINT;
                        """)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            var topic = edge(g, "db:table:CMS_ARTICLE", EdgeKind.REFERENCES, "db:table:CMS_TOPIC");
            assertThat(topic.attr("fromUpgrade")).isEqualTo(true);
            assertThat(topic.attrString("constraint")).isEqualTo("FK_30");
            assertThat(topic.attrString("source")).isEqualTo("DDL");
            assertThat(topic.attrList("columns")).containsExactly("TOPIC_ID");
            assertThat(topic.location().file()).isEqualTo("model/update_20170328_1_to_20170405_1.sql");
            assertThat(topic.location().startLine()).isEqualTo(2);
            assertThat(edge(g, "db:table:CMS_ARTICLE_TAG", EdgeKind.REFERENCES, "db:table:CMS_ARTICLE").attr("fromUpgrade")).isEqualTo(true);
            // 현재 정의 파일(schema.sql)이 다른 FK(FK_4)를 담고 있다: 거기 없는 upgrade FK는 지금은 없는 제약일 수 있어 INFERRED다(F-007-R26)
            assertThat(topic.confidence()).isEqualTo(io.codeatlas.core.model.Confidence.INFERRED);
            assertThat(topic.attrString("note")).isEqualTo("현재 정의에 없음");
            assertThat(edge(g, "db:table:CMS_ARTICLE_TAG", EdgeKind.REFERENCES, "db:table:CMS_ARTICLE").confidence())
                    .isEqualTo(io.codeatlas.core.model.Confidence.INFERRED);
            assertThat(edge(g, "db:table:CMS_ARTICLE_TAG", EdgeKind.REFERENCES, "db:table:CMS_TAG").confidence())
                    .isEqualTo(io.codeatlas.core.model.Confidence.RESOLVED);
            // 현재 정의에 이미 있는 FK는 한 번뿐이고 fromUpgrade가 아니다
            assertThat(g.edges("db:table:CMS_ARTICLE_TAG", EdgeKind.REFERENCES, "db:table:CMS_TAG")).hasSize(1);
            assertThat(edge(g, "db:table:CMS_ARTICLE_TAG", EdgeKind.REFERENCES, "db:table:CMS_TAG").attr("fromUpgrade")).isNull();
            // 자식 컬럼이 현재 정의에 없거나 부모 테이블이 정의돼 있지 않은 FK, 컬럼 변경은 적용하지 않는다
            assertThat(g.hasNode("db:table:CMS_DROPPED")).isFalse();
            assertThat(g.hasNode("db:column:CMS_ARTICLE_TAG.GONE_ID")).isFalse();
            assertThat(targets(g, "db:table:CMS_ARTICLE", EdgeKind.CONTAINS)).containsExactly("db:column:CMS_ARTICLE.ARTICLE_ID", "db:column:CMS_ARTICLE.TOPIC_ID");
            assertThat(node(g, "db:column:CMS_ARTICLE_TAG.TAG_ID").attrString("dataType")).isEqualTo("INT");
            List<Diagnostic> info = diagnostics(g, SqlDiagnosticCodes.DDL_NOT_APPLIED);
            assertThat(info).hasSize(1);
            assertThat(info.get(0).location().file()).isEqualTo("model/update_20170328_1_to_20170405_1.sql");
            assertThat(info.get(0).message()).contains("ALTER TABLE 4건을 적용하지 않음").contains("컬럼 추가 1").contains("컬럼 변경·삭제 1")
                    .contains("제약 추가 2").contains("외래 키 추가 2건").contains("fromUpgrade");
        }
    }

    @Test
    @DisplayName("F-007-AC14 같은 FK와 같은 인덱스가 두 번 선언돼도(인라인 + ALTER, 같은 CREATE INDEX 반복) 엣지와 indexes 항목은 하나다")
    void deduplicatesForeignKeysAndIndexes() {
        try (TestProject project = TestProject.create().file("db/x.sql", """
                CREATE TABLE P (ID NUMBER PRIMARY KEY);
                CREATE TABLE C (ID NUMBER, P_ID NUMBER REFERENCES P (ID));
                ALTER TABLE C ADD CONSTRAINT FK_C_P FOREIGN KEY (P_ID) REFERENCES P (ID);
                CREATE INDEX IX_C_P ON C (P_ID);
                CREATE INDEX IX_C_P ON C (P_ID);
                """)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(g.edges("db:table:C", EdgeKind.REFERENCES, "db:table:P")).hasSize(1);
            assertThat(edge(g, "db:table:C", EdgeKind.REFERENCES, "db:table:P").attrString("constraint")).isEqualTo("FK_C_P");
            assertThat(node(g, "db:table:C").attrList("indexes")).containsExactly("IX_C_P(P_ID)");
        }
    }
}
