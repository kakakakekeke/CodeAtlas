package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.GraphAssert.node;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 범위 밖 방언(MySQL, PostgreSQL, HSQL, MSSQL)의 DDL·스크립트: 지원이 아니라 죽지 않고, 틀린 사실을 만들지 않고,
 * 경고를 쏟아내지 않는 것이 목표다(F-007-R16, F-007-R17, F-006-R18, F-006-R22). 공개 프로젝트에서 찾은 문제의 최소 재현이다.
 */
class ForeignDialectDdlTest {

    private static GraphModel analyze(String path, String sql) {
        try (TestProject project = TestProject.create().file(path, sql)) {
            return project.run(new SqlAnalyzer()).graph();
        }
    }

    @Test
    @DisplayName("F-007-AC16 MySQL의 KEY/INDEX/UNIQUE KEY/FULLTEXT KEY/CONSTRAINT .. FOREIGN KEY는 테이블 수준 절이다: 가짜 컬럼 KEY가 생기지 않는다")
    void mysqlIndexClauses() {
        GraphModel g = analyze("db/my.sql", """
                CREATE TABLE T1 (
                  ID VARCHAR(20) NOT NULL,
                  BBS_ID CHAR(20) DEFAULT NULL,
                  NM VARCHAR(60) DEFAULT 'x',
                  CD VARCHAR(10),
                  MEMO TEXT,
                  PRIMARY KEY (ID),
                  KEY T1_ibfk_1 (BBS_ID),
                  INDEX IX_T1_NM (NM, CD),
                  UNIQUE KEY UK_T1_CD (CD),
                  FULLTEXT KEY FT_T1_MEMO (MEMO),
                  KEY IX_T1_USING USING BTREE (NM),
                  CONSTRAINT T1_ibfk_1 FOREIGN KEY (BBS_ID) REFERENCES T_BBS (BBS_ID) ON DELETE CASCADE ON UPDATE CASCADE
                );
                CREATE TABLE T2 (
                  ID INT,
                  KEY VARCHAR(10),
                  INDEX NUMBER(5),
                  UNIQUE (ID)
                );
                """);
        assertThat(targets(g, "db:table:T1", EdgeKind.CONTAINS)).containsExactly(
                "db:column:T1.BBS_ID", "db:column:T1.CD", "db:column:T1.ID", "db:column:T1.MEMO", "db:column:T1.NM");
        Node t1 = node(g, "db:table:T1");
        assertThat(t1.attrList("primaryKey")).containsExactly("ID");
        assertThat(t1.attrList("indexes")).containsExactly("T1_IBFK_1(BBS_ID)", "IX_T1_NM(NM,CD)", "IX_T1_USING(NM)");
        assertThat(node(g, "db:column:T1.BBS_ID").attr("indexed")).isEqualTo(true);
        assertThat(node(g, "db:column:T1.NM").attr("indexed")).isEqualTo(true);
        assertThat(node(g, "db:column:T1.CD").attr("unique")).isEqualTo(true);
        Edge fk = edge(g, "db:table:T1", EdgeKind.REFERENCES, "db:table:T_BBS");
        assertThat(fk.attrList("columns")).containsExactly("BBS_ID");
        assertThat(fk.attrString("onDelete")).isEqualTo("CASCADE");
        // DEFAULT NULL은 기본값이 없는 것이다
        assertThat(node(g, "db:column:T1.BBS_ID").attr("defaultValue")).isNull();
        assertThat(node(g, "db:column:T1.NM").attrString("defaultValue")).isEqualTo("'x'");
        // KEY, INDEX라는 이름의 컬럼(뒤가 타입)은 컬럼이다
        assertThat(targets(g, "db:table:T2", EdgeKind.CONTAINS)).containsExactly("db:column:T2.ID", "db:column:T2.INDEX", "db:column:T2.KEY");
        assertThat(node(g, "db:column:T2.KEY").attrString("dataType")).isEqualTo("VARCHAR(10)");
        assertThat(node(g, "db:column:T2.INDEX").attrString("dataType")).isEqualTo("NUMBER(5)");
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-007-AC17 MySQL 덤프: 백틱 이름, AUTO_INCREMENT, UNSIGNED, COMMENT, CHARACTER SET/COLLATE, ON UPDATE, ENGINE= 옵션을 진단 없이 읽는다")
    void mysqlDump() {
        GraphModel g = analyze("db/dump.sql", """
                SET FOREIGN_KEY_CHECKS=0;
                # 한 줄 주석
                DROP TABLE IF EXISTS `cms_doc`;
                CREATE TABLE `cms_doc` (
                  `doc_id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT '문서 번호',
                  `title` varchar(200) CHARACTER SET utf8 COLLATE utf8_bin NOT NULL COMMENT '제목, 쉼표가 있는 (설명)',
                  `key` varchar(64) COLLATE utf8_bin DEFAULT NULL,
                  `type` tinyint(4) NOT NULL DEFAULT '1' COMMENT '종류(1:일반,2:인기)',
                  `ctime` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  `orders` bigint(20) unsigned NOT NULL,
                  PRIMARY KEY (`doc_id`),
                  KEY `cms_doc_orders` (`orders`)
                ) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COMMENT='문서';
                CREATE TABLE IF NOT EXISTS sys_area
                (
                	id varchar(64) NOT NULL COMMENT '번호',
                	del_flag char(1) DEFAULT '0' NOT NULL COMMENT '삭제 표시',
                	PRIMARY KEY (id)
                ) COMMENT = '지역';
                ALTER TABLE `cms_doc` ADD CONSTRAINT `fk_doc_area` FOREIGN KEY (`type`) REFERENCES `sys_area` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
                CREATE INDEX `ix_doc_title` ON `cms_doc` (`title` ASC);
                ALTER TABLE `cms_doc` ADD INDEX `ix_doc_type` (`type`), ADD UNIQUE KEY `uk_doc_key` (`key`);
                """);
        assertThat(targets(g, "db:table:CMS_DOC", EdgeKind.CONTAINS)).containsExactly(
                "db:column:CMS_DOC.CTIME", "db:column:CMS_DOC.DOC_ID", "db:column:CMS_DOC.KEY", "db:column:CMS_DOC.ORDERS",
                "db:column:CMS_DOC.TITLE", "db:column:CMS_DOC.TYPE");
        Node id = node(g, "db:column:CMS_DOC.DOC_ID");
        assertThat(id.attrString("dataType")).isEqualTo("INT(10) UNSIGNED");
        assertThat(id.attr("identity")).isEqualTo(true);
        assertThat(id.attr("primaryKey")).isEqualTo(true);
        assertThat(id.attr("nullable")).isEqualTo(false);
        assertThat(node(g, "db:column:CMS_DOC.TITLE").attrString("dataType")).isEqualTo("VARCHAR(200)");
        assertThat(node(g, "db:column:CMS_DOC.TITLE").attr("nullable")).isEqualTo(false);
        assertThat(node(g, "db:column:CMS_DOC.KEY").attr("defaultValue")).isNull();
        assertThat(node(g, "db:column:CMS_DOC.TYPE").attrString("defaultValue")).isEqualTo("'1'");
        assertThat(node(g, "db:column:CMS_DOC.CTIME").attrString("defaultValue")).isEqualTo("CURRENT_TIMESTAMP(3)");
        assertThat(node(g, "db:table:CMS_DOC").attrList("indexes")).containsExactly("CMS_DOC_ORDERS(ORDERS)", "IX_DOC_TITLE(TITLE)", "IX_DOC_TYPE(TYPE)");
        assertThat(node(g, "db:column:CMS_DOC.KEY").attr("unique")).isEqualTo(true);
        assertThat(node(g, "db:table:SYS_AREA").declared()).isTrue();
        assertThat(node(g, "db:column:SYS_AREA.DEL_FLAG").attrString("defaultValue")).isEqualTo("'0'");
        assertThat(edge(g, "db:table:CMS_DOC", EdgeKind.REFERENCES, "db:table:SYS_AREA").attrString("constraint")).isEqualTo("FK_DOC_AREA");
        assertThat(g.nodes(NodeKind.DB_TABLE)).hasSize(2);
        assertThat(GraphAssert.codes(g)).as("대상 외 방언뿐인 프로젝트라는 안내(F-007-R25)만 있다").containsExactly(SqlDiagnosticCodes.DIALECT_NOT_TARGET);
    }

    @Test
    @DisplayName("F-007-AC17 HSQL의 CREATE MEMORY/CACHED TABLE을 테이블로 읽고, MSSQL의 IF EXISTS (..) DROP ..는 조용히 건너뛴다")
    void hsqlAndMssql() {
        GraphModel g = analyze("db/etc.sql", """
                CREATE MEMORY TABLE SAMPLE(ID VARCHAR(16) NOT NULL PRIMARY KEY,NAME VARCHAR(50));
                CREATE CACHED TABLE PUBLIC.LOG(ID BIGINT NOT NULL, MSG VARCHAR(100));
                IF EXISTS (SELECT name FROM sysindexes WHERE name = 'IX_A') drop index T_A.IX_A;
                if exists (select TABLE_NAME from INFORMATION_SCHEMA.TABLES where TABLE_NAME = 'T_A') drop table T_A;
                IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'T_B') DROP TABLE T_B;
                """);
        assertThat(node(g, "db:table:SAMPLE").declared()).isTrue();
        assertThat(node(g, "db:column:SAMPLE.ID").attr("primaryKey")).isEqualTo(true);
        assertThat(node(g, "db:table:LOG").attrString("schema")).isEqualTo("PUBLIC");
        // IF EXISTS 안의 시스템 테이블을 읽는 테이블로 만들지 않는다
        assertThat(g.nodes(NodeKind.DB_TABLE)).extracting(Node::name).containsExactlyInAnyOrder("SAMPLE", "LOG");
        assertThat(g.nodes(NodeKind.SQL_STATEMENT)).isEmpty();
        assertThat(GraphAssert.codes(g)).as("대상 외 방언뿐인 프로젝트라는 안내(F-007-R25)만 있다").containsExactly(SqlDiagnosticCodes.DIALECT_NOT_TARGET);
    }

    @Test
    @DisplayName("F-006-AC22 세미콜론 없이 줄바꿈으로만 나뉜 스크립트(HSQL .script 형식)는 줄 시작 키워드에서 다시 나눈다")
    void lineSeparatedScript() {
        GraphModel g = analyze("db/sampledb.sql", """
                DROP TABLE IF EXISTS SAMPLE
                DROP TABLE IF EXISTS IDS
                CREATE MEMORY TABLE SAMPLE(ID VARCHAR(16) NOT NULL PRIMARY KEY,NAME VARCHAR(50),USE_YN CHAR(1))
                CREATE MEMORY TABLE IDS(TABLE_NAME VARCHAR(16) NOT NULL PRIMARY KEY,NEXT_ID DECIMAL(30) NOT NULL)
                SET SCHEMA PUBLIC
                INSERT INTO SAMPLE VALUES('S-1','Runtime','Y')
                INSERT INTO SAMPLE VALUES('S-2','Run; time','Y')
                INSERT INTO IDS VALUES('SAMPLE',3)
                """);
        assertThat(node(g, "db:table:SAMPLE").declared()).isTrue();
        assertThat(node(g, "db:table:SAMPLE").location().startLine()).isEqualTo(3);
        assertThat(node(g, "db:table:IDS").declared()).isTrue();
        assertThat(targets(g, "db:table:SAMPLE", EdgeKind.CONTAINS)).containsExactly("db:column:SAMPLE.ID", "db:column:SAMPLE.NAME", "db:column:SAMPLE.USE_YN");
        assertThat(hasEdge(g, "sql:stmt:db/sampledb.sql:6", EdgeKind.WRITES, "db:table:SAMPLE")).isTrue();
        assertThat(hasEdge(g, "sql:stmt:db/sampledb.sql:7", EdgeKind.WRITES, "db:table:SAMPLE")).isTrue();
        assertThat(hasEdge(g, "sql:stmt:db/sampledb.sql:8", EdgeKind.WRITES, "db:table:IDS")).isTrue();
        assertThat(GraphAssert.codes(g)).as("대상 외 방언뿐인 프로젝트라는 안내(F-007-R25)만 있다").containsExactly(SqlDiagnosticCodes.DIALECT_NOT_TARGET);
    }

    @Test
    @DisplayName("F-006-AC22 여러 줄에 걸친 구문이 있는 보통 스크립트는 줄 시작 키워드에서 끊지 않는다")
    void multiLineStatementsAreNotSplitByLine() {
        GraphModel g = analyze("db/x.sql", """
                CREATE TABLE T_A (
                  ID NUMBER
                );
                INSERT INTO T_A (ID)
                SELECT ID FROM T_SRC
                 WHERE ID > 0;
                MERGE INTO T_A D USING T_SRC S ON (D.ID = S.ID)
                 WHEN NOT MATCHED THEN
                INSERT (ID) VALUES (S.ID);
                """);
        assertThat(g.nodes(NodeKind.SQL_STATEMENT)).extracting(Node::id).containsExactlyInAnyOrder("sql:stmt:db/x.sql:4", "sql:stmt:db/x.sql:7");
        assertThat(hasEdge(g, "sql:stmt:db/x.sql:4", EdgeKind.READS, "db:table:T_SRC")).isTrue();
        assertThat(edge(g, "sql:stmt:db/x.sql:7", EdgeKind.WRITES, "db:table:T_A").attrString("op")).isEqualTo("MERGE");
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC22 구문이 있어 보이는데 테이블도 구문도 얻지 못한 SQL 파일은 WARN 진단을 남긴다. 관리 구문만 있는 파일과 빈 파일은 조용하다")
    void nothingReadWarning() {
        try (TestProject project = TestProject.create()
                .file("db/garbage.sql", "CREATE FOO BAR BAZ\nCREATE ANOTHER THING\nCREATE WIDGET X\n")
                .file("db/drop.sql", "DROP TABLE T_A;\nDROP TABLE T_B;\nCOMMIT;\n")
                .file("db/empty.sql", "-- 주석뿐\n\n")
                .file("db/ok.sql", "CREATE TABLE T_OK (ID NUMBER);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            List<Diagnostic> warns = g.diagnostics().stream().filter(d -> d.severity() == Severity.WARN).toList();
            assertThat(warns).hasSize(1);
            assertThat(warns.get(0).code()).isEqualTo(DiagnosticCodes.PARSE_FAILED);
            assertThat(warns.get(0).message()).contains("읽은 것이 없음");
            assertThat(warns.get(0).location().file()).isEqualTo("db/garbage.sql");
            assertThat(g.diagnostics()).noneMatch(d -> d.location() != null && ("db/drop.sql".equals(d.location().file())
                    || "db/empty.sql".equals(d.location().file()) || "db/ok.sql".equals(d.location().file())));
        }
    }
}
