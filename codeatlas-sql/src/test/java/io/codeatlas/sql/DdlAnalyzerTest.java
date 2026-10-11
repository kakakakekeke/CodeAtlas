package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.GraphAssert.node;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.testing.TestProject;
import java.nio.charset.Charset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DdlAnalyzerTest {

    private static GraphModel analyze(String path, String sql) {
        try (TestProject project = TestProject.create().file(path, sql)) {
            return project.run(new SqlAnalyzer()).graph();
        }
    }

    @Test
    @DisplayName("F-007-AC1 CREATE TABLE(Oracle): 테이블·컬럼 노드, 타입, NULL 여부, 기본값, 순번, CONTAINS")
    void createTableOracle() {
        GraphModel g = analyze("db/01_tables.sql", """
                -- 주문
                CREATE TABLE SHOP.T_ORDER (
                    ID        NUMBER(12)          NOT NULL,
                    NM        VARCHAR2(100 CHAR)  DEFAULT 'x',
                    AMT       NUMBER(15, 2)       DEFAULT 0 NOT NULL,
                    DT        DATE                DEFAULT SYSDATE,
                    TS        TIMESTAMP(6) WITH TIME ZONE,
                    MEMO      CLOB,
                    "Mixed"   CHAR(1) NULL,
                    TOTAL     NUMBER GENERATED ALWAYS AS (AMT * 2) VIRTUAL,
                    CONSTRAINT CK_T CHECK (AMT >= 0) ENABLE
                ) TABLESPACE TS_DATA PCTFREE 10 STORAGE (INITIAL 64K NEXT 1M)
                  PARTITION BY RANGE (DT) (PARTITION P1 VALUES LESS THAN (TO_DATE('2020', 'YYYY')), PARTITION PMAX VALUES LESS THAN (MAXVALUE));
                CREATE GLOBAL TEMPORARY TABLE TMP_X (A NUMBER, B VARCHAR2(10)) ON COMMIT PRESERVE ROWS;
                """);
        Node t = node(g, "db:table:T_ORDER");
        assertThat(t.kind()).isEqualTo(NodeKind.DB_TABLE);
        assertThat(t.declared()).isTrue();
        assertThat(t.name()).isEqualTo("T_ORDER");
        assertThat(t.attrString("schema")).isEqualTo("SHOP");
        assertThat(t.location().file()).isEqualTo("db/01_tables.sql");
        assertThat(t.location().startLine()).isEqualTo(2);
        assertThat(targets(g, "db:table:T_ORDER", EdgeKind.CONTAINS)).containsExactly(
                "db:column:T_ORDER.AMT", "db:column:T_ORDER.DT", "db:column:T_ORDER.ID", "db:column:T_ORDER.MEMO",
                "db:column:T_ORDER.Mixed", "db:column:T_ORDER.NM", "db:column:T_ORDER.TOTAL", "db:column:T_ORDER.TS");
        assertThat(hasEdge(g, "file:db/01_tables.sql", EdgeKind.CONTAINS, "db:table:T_ORDER")).isTrue();

        Node id = node(g, "db:column:T_ORDER.ID");
        assertThat(id.kind()).isEqualTo(NodeKind.DB_COLUMN);
        assertThat(id.name()).isEqualTo("ID");
        assertThat(id.attrString("dataType")).isEqualTo("NUMBER(12)");
        assertThat(id.attr("nullable")).isEqualTo(false);
        assertThat(id.attr("ordinal")).isEqualTo(1);
        assertThat(id.attr("primaryKey")).isEqualTo(false);
        assertThat(id.location().startLine()).isEqualTo(3);

        Node nm = node(g, "db:column:T_ORDER.NM");
        assertThat(nm.attrString("dataType")).isEqualTo("VARCHAR2(100 CHAR)");
        assertThat(nm.attr("nullable")).isEqualTo(true);
        assertThat(nm.attrString("defaultValue")).isEqualTo("'x'");
        assertThat(nm.attr("ordinal")).isEqualTo(2);

        Node amt = node(g, "db:column:T_ORDER.AMT");
        assertThat(amt.attrString("dataType")).isEqualTo("NUMBER(15,2)");
        assertThat(amt.attrString("defaultValue")).isEqualTo("0");
        assertThat(amt.attr("nullable")).isEqualTo(false);
        assertThat(node(g, "db:column:T_ORDER.DT").attrString("defaultValue")).isEqualTo("SYSDATE");
        assertThat(node(g, "db:column:T_ORDER.TS").attrString("dataType")).isEqualTo("TIMESTAMP(6) WITH TIME ZONE");
        assertThat(node(g, "db:column:T_ORDER.Mixed").attr("nullable")).isEqualTo(true);
        assertThat(node(g, "db:column:T_ORDER.TOTAL").attrBoolean("virtual")).isTrue();
        assertThat(node(g, "db:column:T_ORDER.TOTAL").attr("ordinal")).isEqualTo(8);

        assertThat(node(g, "db:table:TMP_X").attrBoolean("temporary")).isTrue();
        assertThat(node(g, "db:column:TMP_X.B").attrString("dataType")).isEqualTo("VARCHAR2(10)");
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-007-AC1 CREATE TABLE(DB2): IDENTITY, WITH DEFAULT, FOR BIT DATA, IN/INDEX IN/COMPRESS 절")
    void createTableDb2() {
        GraphModel g = analyze("db/db2/01_tables.sql", """
                CREATE TABLE BATCH.TB_LOG (
                    LOG_ID   BIGINT        NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
                    DTL_ID   BIGINT        NOT NULL GENERATED BY DEFAULT AS IDENTITY,
                    STATUS   CHAR(2)       NOT NULL WITH DEFAULT 'OK',
                    CNT      INTEGER       NOT NULL WITH DEFAULT,
                    REG_TS   TIMESTAMP     NOT NULL WITH DEFAULT CURRENT TIMESTAMP,
                    RAW_ID   CHAR(16) FOR BIT DATA,
                    NM       VARCHAR(100) CCSID 1208,
                    AMT      DECIMAL(15,2) DEFAULT 0.0,
                    PRIMARY KEY (LOG_ID)
                ) IN TS_DATA INDEX IN TS_IDX ORGANIZE BY ROW DISTRIBUTE BY HASH (LOG_ID) COMPRESS YES;
                DECLARE GLOBAL TEMPORARY TABLE SESSION.TMP_A (A INT) ON COMMIT PRESERVE ROWS NOT LOGGED;
                """);
        Node t = node(g, "db:table:TB_LOG");
        assertThat(t.attrString("schema")).isEqualTo("BATCH");
        assertThat(t.attrList("primaryKey")).containsExactly("LOG_ID");
        Node logId = node(g, "db:column:TB_LOG.LOG_ID");
        assertThat(logId.attrString("dataType")).isEqualTo("BIGINT");
        assertThat(logId.attrBoolean("identity")).isTrue();
        assertThat(logId.attrBoolean("primaryKey")).isTrue();
        assertThat(node(g, "db:column:TB_LOG.DTL_ID").attrBoolean("identity")).isTrue();
        assertThat(node(g, "db:column:TB_LOG.STATUS").attrString("defaultValue")).isEqualTo("'OK'");
        assertThat(node(g, "db:column:TB_LOG.CNT").attrString("defaultValue")).isNull();
        assertThat(node(g, "db:column:TB_LOG.CNT").attr("nullable")).isEqualTo(false);
        assertThat(node(g, "db:column:TB_LOG.REG_TS").attrString("defaultValue")).isEqualTo("CURRENT TIMESTAMP");
        assertThat(node(g, "db:column:TB_LOG.RAW_ID").attrString("dataType")).isEqualTo("CHAR(16) FOR BIT DATA");
        assertThat(node(g, "db:column:TB_LOG.NM").attrString("dataType")).isEqualTo("VARCHAR(100)");
        assertThat(node(g, "db:column:TB_LOG.AMT").attrString("defaultValue")).isEqualTo("0.0");
        assertThat(targets(g, "db:table:TB_LOG", EdgeKind.CONTAINS)).hasSize(8);
        assertThat(node(g, "db:table:TMP_A").attrBoolean("temporary")).isTrue();
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-007-AC2 PK와 UNIQUE: 인라인, 테이블 수준, ALTER TABLE")
    void keys() {
        GraphModel g = analyze("ddl.sql", """
                CREATE TABLE A (ID NUMBER(8) NOT NULL PRIMARY KEY, CD VARCHAR2(10) UNIQUE, NM VARCHAR2(10));
                CREATE TABLE B (K1 NUMBER, K2 NUMBER, X NUMBER, Y NUMBER, Z NUMBER,
                    CONSTRAINT PK_B PRIMARY KEY (K1, K2) USING INDEX TABLESPACE TS_IDX,
                    CONSTRAINT UK_B UNIQUE (X),
                    UNIQUE (Y, Z));
                CREATE TABLE C (ID NUMBER, LOGIN VARCHAR2(30));
                ALTER TABLE C ADD CONSTRAINT PK_C PRIMARY KEY (ID);
                ALTER TABLE C ADD CONSTRAINT UK_C UNIQUE (LOGIN) ENABLE;
                CREATE TABLE D (ID INT NOT NULL, V INT);
                ALTER TABLE D ADD PRIMARY KEY (ID);
                """);
        assertThat(node(g, "db:table:A").attrList("primaryKey")).containsExactly("ID");
        assertThat(node(g, "db:column:A.ID").attrBoolean("primaryKey")).isTrue();
        assertThat(node(g, "db:column:A.CD").attrBoolean("unique")).isTrue();
        assertThat(node(g, "db:column:A.CD").attrBoolean("primaryKey")).isFalse();
        assertThat(node(g, "db:column:A.NM").attr("unique")).isNull();

        assertThat(node(g, "db:table:B").attrList("primaryKey")).containsExactly("K1", "K2");
        assertThat(node(g, "db:column:B.K1").attrBoolean("primaryKey")).isTrue();
        assertThat(node(g, "db:column:B.K1").attr("nullable")).isEqualTo(false);
        assertThat(node(g, "db:column:B.K2").attrBoolean("primaryKey")).isTrue();
        assertThat(node(g, "db:column:B.X").attrBoolean("unique")).isTrue();
        assertThat(node(g, "db:column:B.Y").attr("unique")).isNull();
        assertThat(node(g, "db:table:B").attrList("uniqueKeys")).containsExactly("Y,Z");

        assertThat(node(g, "db:table:C").attrList("primaryKey")).containsExactly("ID");
        assertThat(node(g, "db:column:C.ID").attrBoolean("primaryKey")).isTrue();
        assertThat(node(g, "db:column:C.LOGIN").attrBoolean("unique")).isTrue();
        assertThat(node(g, "db:table:D").attrList("primaryKey")).containsExactly("ID");
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-007-AC3 FK: 인라인, 테이블 수준, ALTER, 참조 컬럼 생략(부모 PK로 채움), 부모가 뒤에 정의됨")
    void foreignKeys() {
        try (TestProject project = TestProject.create()
                .file("db/a_child.sql", """
                        CREATE TABLE CHILD (
                            ID    NUMBER PRIMARY KEY,
                            P_ID  NUMBER CONSTRAINT FK_CHILD_P REFERENCES PARENT,
                            G_ID  NUMBER REFERENCES SHOP.GRP (GRP_ID) ON DELETE SET NULL,
                            A     NUMBER,
                            B     NUMBER,
                            SELF  NUMBER REFERENCES CHILD (ID),
                            CONSTRAINT FK_CHILD_AB FOREIGN KEY (A, B) REFERENCES PAIR ON DELETE CASCADE
                        );
                        ALTER TABLE CHILD2 ADD CONSTRAINT FK_C2 FOREIGN KEY (P_ID) REFERENCES PARENT (ID);
                        ALTER TABLE CHILD2 ADD (CONSTRAINT FK_C2G FOREIGN KEY (G_ID) REFERENCES GRP);
                        ALTER TABLE CHILD2 ADD FOREIGN KEY (X) REFERENCES NOWHERE;
                        -- ALTER TABLE CHILD ADD CONSTRAINT FK_NO FOREIGN KEY (A) REFERENCES COMMENTED (ID);
                        """)
                .file("db/b_parent.sql", """
                        CREATE TABLE PARENT (ID NUMBER NOT NULL, NM VARCHAR2(10), CONSTRAINT PK_PARENT PRIMARY KEY (ID));
                        CREATE TABLE PAIR (K1 NUMBER, K2 NUMBER, PRIMARY KEY (K1, K2));
                        CREATE TABLE SHOP.GRP (GRP_ID NUMBER PRIMARY KEY);
                        """)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            Edge p = edge(g, "db:table:CHILD", EdgeKind.REFERENCES, "db:table:PARENT");
            assertThat(p.attrList("columns")).containsExactly("P_ID");
            assertThat(p.attrList("refColumns")).containsExactly("ID");
            assertThat(p.attrString("constraint")).isEqualTo("FK_CHILD_P");
            assertThat(p.attrString("source")).isEqualTo("DDL");
            assertThat(p.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(p.location().file()).isEqualTo("db/a_child.sql");
            assertThat(p.location().startLine()).isEqualTo(3);

            Edge grp = edge(g, "db:table:CHILD", EdgeKind.REFERENCES, "db:table:GRP");
            assertThat(grp.attrList("columns")).containsExactly("G_ID");
            assertThat(grp.attrList("refColumns")).containsExactly("GRP_ID");
            assertThat(grp.attrString("onDelete")).isEqualTo("SET NULL");

            Edge pair = edge(g, "db:table:CHILD", EdgeKind.REFERENCES, "db:table:PAIR");
            assertThat(pair.attrList("columns")).containsExactly("A", "B");
            assertThat(pair.attrList("refColumns")).containsExactly("K1", "K2");
            assertThat(pair.attrString("onDelete")).isEqualTo("CASCADE");
            assertThat(pair.attrString("constraint")).isEqualTo("FK_CHILD_AB");

            Edge self = edge(g, "db:table:CHILD", EdgeKind.REFERENCES, "db:table:CHILD");
            assertThat(self.attrList("columns")).containsExactly("SELF");

            Edge c2 = edge(g, "db:table:CHILD2", EdgeKind.REFERENCES, "db:table:PARENT");
            assertThat(c2.attrString("constraint")).isEqualTo("FK_C2");
            assertThat(edge(g, "db:table:CHILD2", EdgeKind.REFERENCES, "db:table:GRP").attrList("refColumns")).containsExactly("GRP_ID");
            // 부모 정의가 없으면 refColumns는 비어 있고 부모는 참조 노드다
            Edge nowhere = edge(g, "db:table:CHILD2", EdgeKind.REFERENCES, "db:table:NOWHERE");
            assertThat(nowhere.attrList("refColumns")).isEmpty();
            assertThat(node(g, "db:table:NOWHERE").declared()).isFalse();
            // CREATE TABLE 없이 ALTER만 있는 테이블은 참조 노드
            assertThat(node(g, "db:table:CHILD2").declared()).isFalse();
            assertThat(g.hasNode("db:table:COMMENTED")).isFalse();
            assertThat(g.edges(EdgeKind.REFERENCES)).hasSize(7);
        }
    }

    @Test
    @DisplayName("F-007-AC4 ALTER TABLE: 컬럼 추가(ADD, ADD (..), ADD COLUMN), 변경(MODIFY, ALTER COLUMN), 삭제, 이름 변경")
    void alterColumns() {
        GraphModel g = analyze("ddl.sql", """
                CREATE TABLE T (ID NUMBER NOT NULL, OLD_NM VARCHAR2(5), GONE NUMBER);
                ALTER TABLE T ADD (C1 NUMBER, C2 DATE DEFAULT SYSDATE NOT NULL);
                ALTER TABLE T ADD C3 VARCHAR2(20);
                ALTER TABLE T MODIFY C1 VARCHAR2(10) NOT NULL;
                ALTER TABLE T MODIFY (C3 DEFAULT 'N', ID NUMBER(12));
                ALTER TABLE T DROP COLUMN GONE;
                ALTER TABLE T RENAME COLUMN OLD_NM TO NEW_NM;
                CREATE TABLE D (ID INT NOT NULL);
                ALTER TABLE D ADD COLUMN C4 INT ADD COLUMN C5 VARCHAR(10) NOT NULL WITH DEFAULT 'x';
                ALTER TABLE D ALTER COLUMN C4 SET DATA TYPE BIGINT;
                ALTER TABLE D ALTER COLUMN C4 SET NOT NULL;
                ALTER TABLE D ALTER COLUMN C5 DROP NOT NULL;
                ALTER TABLE D ALTER COLUMN ID SET DEFAULT 0;
                ALTER TABLE D ENABLE ROW MOVEMENT;
                """);
        assertThat(targets(g, "db:table:T", EdgeKind.CONTAINS)).containsExactly(
                "db:column:T.C1", "db:column:T.C2", "db:column:T.C3", "db:column:T.ID", "db:column:T.NEW_NM");
        assertThat(node(g, "db:column:T.C1").attrString("dataType")).isEqualTo("VARCHAR2(10)");
        assertThat(node(g, "db:column:T.C1").attr("nullable")).isEqualTo(false);
        assertThat(node(g, "db:column:T.C2").attrString("defaultValue")).isEqualTo("SYSDATE");
        assertThat(node(g, "db:column:T.C2").attr("nullable")).isEqualTo(false);
        assertThat(node(g, "db:column:T.C3").attrString("dataType")).isEqualTo("VARCHAR2(20)");
        assertThat(node(g, "db:column:T.C3").attrString("defaultValue")).isEqualTo("'N'");
        assertThat(node(g, "db:column:T.ID").attrString("dataType")).isEqualTo("NUMBER(12)");
        assertThat(node(g, "db:column:T.ID").attr("nullable")).isEqualTo(false);
        assertThat(node(g, "db:column:T.NEW_NM").attrString("dataType")).isEqualTo("VARCHAR2(5)");
        assertThat(node(g, "db:column:T.C3").attr("ordinal")).isEqualTo(5);

        assertThat(node(g, "db:column:D.C4").attrString("dataType")).isEqualTo("BIGINT");
        assertThat(node(g, "db:column:D.C4").attr("nullable")).isEqualTo(false);
        assertThat(node(g, "db:column:D.C5").attrString("defaultValue")).isEqualTo("'x'");
        assertThat(node(g, "db:column:D.C5").attr("nullable")).isEqualTo(true);
        assertThat(node(g, "db:column:D.ID").attrString("defaultValue")).isEqualTo("0");
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-007-AC5 COMMENT ON TABLE/COLUMN (MS949 파일, 문자열 안의 세미콜론과 따옴표)")
    void comments() {
        String sql = """
                CREATE TABLE SHOP.T (ID NUMBER, NM VARCHAR2(10));
                COMMENT ON TABLE SHOP.T IS '주문; 마스터';
                COMMENT ON COLUMN SHOP.T.ID IS '주문 ID';
                COMMENT ON COLUMN T.NM IS '이름(''별칭'')';
                COMMENT ON COLUMN NOPE.X IS '없는 테이블';
                COMMENT ON INDEX IX_T IS '무시';
                """;
        try (TestProject project = TestProject.create().file("db/comments.sql", sql, Charset.forName("MS949"))) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:T").attrString("comment")).isEqualTo("주문; 마스터");
            assertThat(node(g, "db:column:T.ID").attrString("comment")).isEqualTo("주문 ID");
            assertThat(node(g, "db:column:T.NM").attrString("comment")).isEqualTo("이름('별칭')");
            assertThat(g.diagnostics()).isEmpty();
        }
        GraphModel db2 = analyze("x.sql", "CREATE TABLE T (A INT, B INT);\nCOMMENT ON T (A IS 'first', B IS 'second');");
        assertThat(node(db2, "db:column:T.A").attrString("comment")).isEqualTo("first");
        assertThat(node(db2, "db:column:T.B").attrString("comment")).isEqualTo("second");
    }

    @Test
    @DisplayName("F-007-AC6 CREATE [UNIQUE] INDEX → 테이블 attrs.indexes, 컬럼 attrs.indexed")
    void indexes() {
        GraphModel g = analyze("ddl.sql", """
                CREATE TABLE T (A NUMBER, B NUMBER, C NUMBER, D VARCHAR2(10), E NUMBER, F NUMBER);
                CREATE INDEX IX1 ON T (A, B DESC) TABLESPACE TS_IDX;
                CREATE UNIQUE INDEX SHOP.UX1 ON SHOP.T (C);
                CREATE INDEX IX2 ON T (UPPER(D), A);
                CREATE UNIQUE INDEX UX2 ON T (E, F) ALLOW REVERSE SCANS;
                CREATE INDEX IX_NONE ON NO_TABLE (X);
                """);
        Node t = node(g, "db:table:T");
        assertThat(t.attrList("indexes")).containsExactly("IX1(A,B DESC)", "UX1 UNIQUE(C)", "IX2(UPPER(D),A)", "UX2 UNIQUE(E,F)");
        assertThat(node(g, "db:column:T.A").attrBoolean("indexed")).isTrue();
        assertThat(node(g, "db:column:T.B").attrBoolean("indexed")).isTrue();
        assertThat(node(g, "db:column:T.C").attrBoolean("indexed")).isTrue();
        assertThat(node(g, "db:column:T.C").attrBoolean("unique")).isTrue();
        assertThat(node(g, "db:column:T.D").attr("indexed")).isNull();
        assertThat(t.attrList("uniqueKeys")).containsExactly("E,F");
        assertThat(node(g, "db:table:NO_TABLE").declared()).isFalse();
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-007-AC13 인덱스 항목의 정렬 방향 DESC와 함수 기반 인덱스 식을 attrs.indexes에 보존한다(ASC는 기본값이라 적지 않는다)")
    void indexSortDirection() {
        GraphModel g = analyze("ddl.sql", """
                CREATE TABLE TB_ORDER_HIST (ORDER_ID NUMBER, CHG_DT DATE, NM VARCHAR2(10), E NUMBER, F NUMBER);
                CREATE INDEX IX_ORDER_HIST_01 ON TB_ORDER_HIST(ORDER_ID, CHG_DT DESC);
                CREATE INDEX IX_ORDER_HIST_02 ON TB_ORDER_HIST (ORDER_ID ASC, UPPER( NM )   DESC, CHG_DT desc);
                CREATE UNIQUE INDEX UX_ORDER_HIST_01 ON TB_ORDER_HIST (E DESC);
                CREATE UNIQUE INDEX UX_ORDER_HIST_02 ON TB_ORDER_HIST (E, F DESC);
                CREATE INDEX IX_ORDER_HIST_03 ON TB_ORDER_HIST (NVL(NM, 'x') || '-', E + 1);
                """);
        Node t = node(g, "db:table:TB_ORDER_HIST");
        assertThat(t.attrList("indexes")).containsExactly(
                "IX_ORDER_HIST_01(ORDER_ID,CHG_DT DESC)",
                "IX_ORDER_HIST_02(ORDER_ID,UPPER(NM) DESC,CHG_DT DESC)",
                "UX_ORDER_HIST_01 UNIQUE(E DESC)",
                "UX_ORDER_HIST_02 UNIQUE(E,F DESC)",
                "IX_ORDER_HIST_03(NVL(NM,'x') || '-',E + 1)");
        // 정렬 방향은 컬럼 표시(indexed, unique, uniqueKeys)에 영향을 주지 않는다
        assertThat(node(g, "db:column:TB_ORDER_HIST.CHG_DT").attrBoolean("indexed")).isTrue();
        assertThat(node(g, "db:column:TB_ORDER_HIST.E").attrBoolean("unique")).isTrue();
        assertThat(t.attrList("uniqueKeys")).containsExactly("E,F");
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-007-AC7 CREATE VIEW: 뷰·컬럼 노드, 정의 쿼리 구문 노드, READS, JOINS")
    void views() {
        GraphModel g = analyze("db/views.sql", """
                CREATE TABLE O (ID NUMBER(12) PRIMARY KEY, MID NUMBER(12), AMT NUMBER(15,2));
                CREATE TABLE M (ID NUMBER(12) PRIMARY KEY, NM VARCHAR2(100));

                CREATE OR REPLACE FORCE VIEW SHOP.V (A, B) AS
                SELECT O.ID, NVL(O.AMT, 0) FROM O WITH READ ONLY;

                CREATE VIEW V2 AS
                SELECT o.id, m.nm AS member_nm, o.amt * 2, (SELECT COUNT(*) FROM O x WHERE x.mid = m.id) cnt
                  FROM O o, M m
                 WHERE o.mid = m.id;

                CREATE MATERIALIZED VIEW MV BUILD IMMEDIATE REFRESH COMPLETE ON DEMAND AS SELECT * FROM M;
                """);
        Node v = node(g, "db:view:V");
        assertThat(v.kind()).isEqualTo(NodeKind.DB_VIEW);
        assertThat(v.attrString("schema")).isEqualTo("SHOP");
        assertThat(targets(g, "db:view:V", EdgeKind.CONTAINS)).containsExactly("db:column:V.A", "db:column:V.B", "sql:stmt:db/views.sql:4");
        assertThat(node(g, "db:column:V.A").attr("ordinal")).isEqualTo(1);
        assertThat(node(g, "db:column:V.A").attrString("dataType")).isEqualTo("NUMBER(12)");
        Node stmt = node(g, "sql:stmt:db/views.sql:4");
        assertThat(stmt.kind()).isEqualTo(NodeKind.SQL_STATEMENT);
        assertThat(stmt.attrString("statementType")).isEqualTo("SELECT");
        assertThat(targets(g, stmt.id(), EdgeKind.READS)).contains("db:table:O", "db:column:O.ID", "db:column:O.AMT");

        assertThat(targets(g, "db:view:V2", EdgeKind.CONTAINS)).containsExactly(
                "db:column:V2.CNT", "db:column:V2.ID", "db:column:V2.MEMBER_NM", "sql:stmt:db/views.sql:7");
        assertThat(node(g, "db:column:V2.MEMBER_NM").attrString("dataType")).isEqualTo("VARCHAR2(100)");
        assertThat(node(g, "db:column:V2.MEMBER_NM").attr("ordinal")).isEqualTo(2);
        assertThat(targets(g, "sql:stmt:db/views.sql:7", EdgeKind.READS)).contains("db:table:O", "db:table:M");
        Edge join = edge(g, "db:table:O", EdgeKind.JOINS, "db:table:M");
        assertThat(join.attrList("leftColumns")).containsExactly("MID");
        assertThat(join.attrList("rightColumns")).containsExactly("ID");
        assertThat(join.attrString("statement")).isEqualTo("sql:stmt:db/views.sql:7");

        assertThat(node(g, "db:view:MV").attrBoolean("materialized")).isTrue();
        assertThat(targets(g, "db:view:MV", EdgeKind.CONTAINS)).contains("db:column:MV.ID", "db:column:MV.NM");
        assertThat(hasEdge(g, "file:db/views.sql", EdgeKind.CONTAINS, "db:view:V")).isTrue();
        assertThat(g.hasNode("db:table:V")).isFalse();
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-007-AC7 뷰를 읽는 구문은 db:table: 참조로 남기고 컬럼 소속은 뷰 컬럼으로 정한다")
    void readingView() {
        GraphModel g = analyze("q.sql", """
                CREATE TABLE M (ID NUMBER, NM VARCHAR2(10));
                CREATE VIEW VM AS SELECT ID, NM FROM M;
                SELECT NM FROM VM, DUAL;
                """);
        assertThat(targets(g, "sql:stmt:q.sql:3", EdgeKind.READS)).containsExactly("db:column:VM.NM", "db:table:VM");
    }

    @Test
    @DisplayName("F-007-AC7 CREATE TABLE ... AS SELECT: 테이블 선언 + 구문 노드(WRITES 새 테이블, READS 원본)")
    void createTableAsSelect() {
        GraphModel g = analyze("ctas.sql", """
                CREATE TABLE SRC (ID NUMBER, NM VARCHAR2(10));
                CREATE TABLE BAK NOLOGGING AS SELECT s.ID, s.NM AS NAME FROM SRC s WHERE s.ID > 0;
                """);
        assertThat(node(g, "db:table:BAK").declared()).isTrue();
        assertThat(targets(g, "db:table:BAK", EdgeKind.CONTAINS)).containsExactly("db:column:BAK.ID", "db:column:BAK.NAME");
        Node stmt = node(g, "sql:stmt:ctas.sql:2");
        assertThat(stmt.attrString("statementType")).isEqualTo("INSERT");
        assertThat(edge(g, stmt.id(), EdgeKind.WRITES, "db:table:BAK").attrString("op")).isEqualTo("INSERT");
        assertThat(targets(g, stmt.id(), EdgeKind.READS)).contains("db:table:SRC");
    }

    @Test
    @DisplayName("F-007-AC8 시퀀스, 시노님, DB2 ALIAS")
    void sequencesAndSynonyms() {
        GraphModel g = analyze("db/objs.sql", """
                CREATE SEQUENCE SEQ_A START WITH 1 INCREMENT BY 1 NOCACHE;
                CREATE SEQUENCE SHOP.SEQ_B AS BIGINT START WITH 1 NO CYCLE CACHE 20;
                CREATE OR REPLACE SYNONYM SYN_T FOR SHOP.T;
                CREATE PUBLIC SYNONYM S FOR SHOP.T@REMOTE;
                CREATE ALIAS A FOR SCH.T;
                CREATE OR REPLACE PUBLIC ALIAS A2 FOR TABLE SCH.T2;
                """);
        assertThat(node(g, "db:sequence:SEQ_A").kind()).isEqualTo(NodeKind.DB_SEQUENCE);
        assertThat(node(g, "db:sequence:SEQ_A").declared()).isTrue();
        assertThat(node(g, "db:sequence:SEQ_B").attrString("schema")).isEqualTo("SHOP");
        Node syn = node(g, "db:synonym:SYN_T");
        assertThat(syn.kind()).isEqualTo(NodeKind.DB_SYNONYM);
        assertThat(syn.attrString("target")).isEqualTo("SHOP.T");
        assertThat(hasEdge(g, "db:synonym:SYN_T", EdgeKind.ALIAS_OF, "db:table:T")).isTrue();
        assertThat(node(g, "db:table:T").declared()).isFalse();
        assertThat(node(g, "db:synonym:S").attrBoolean("public")).isTrue();
        assertThat(hasEdge(g, "db:synonym:S", EdgeKind.ALIAS_OF, "db:table:T")).isTrue();
        assertThat(hasEdge(g, "db:synonym:A", EdgeKind.ALIAS_OF, "db:table:T")).isTrue();
        assertThat(hasEdge(g, "db:synonym:A2", EdgeKind.ALIAS_OF, "db:table:T2")).isTrue();
        assertThat(hasEdge(g, "file:db/objs.sql", EdgeKind.CONTAINS, "db:sequence:SEQ_A")).isTrue();
        assertThat(hasEdge(g, "file:db/objs.sql", EdgeKind.CONTAINS, "db:synonym:SYN_T")).isTrue();
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-007-AC9 DDL을 먼저 읽는다: 파일 순서상 DML이 앞에 있어도 카탈로그로 컬럼 소속과 조인을 정한다")
    void ddlFirst() {
        try (TestProject project = TestProject.create()
                .file("a_query.sql", "SELECT ENAME, DNAME FROM EMP, DEPT WHERE EMP_DEPT = DEPT_ID;")
                .file("z_ddl.sql", "CREATE TABLE EMP (EMPNO NUMBER, ENAME VARCHAR2(10), EMP_DEPT NUMBER);\n"
                        + "CREATE TABLE DEPT (DEPT_ID NUMBER, DNAME VARCHAR2(10));")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String stmt = "sql:stmt:a_query.sql:1";
            assertThat(targets(g, stmt, EdgeKind.READS)).containsExactly(
                    "db:column:DEPT.DEPT_ID", "db:column:DEPT.DNAME", "db:column:EMP.EMP_DEPT", "db:column:EMP.ENAME",
                    "db:table:DEPT", "db:table:EMP");
            Edge join = edge(g, "db:table:EMP", EdgeKind.JOINS, "db:table:DEPT");
            assertThat(join.attrList("leftColumns")).containsExactly("EMP_DEPT");
            assertThat(join.attrList("rightColumns")).containsExactly("DEPT_ID");
            assertThat(node(g, "db:column:EMP.ENAME").declared()).isTrue();
        }
    }

    @Test
    @DisplayName("F-007-AC10 관리 구문은 조용히 건너뛰고 모르는 구문만 UNSUPPORTED_SYNTAX(INFO)")
    void skippedStatements() {
        GraphModel g = analyze("misc.sql", """
                DROP TABLE T;
                GRANT SELECT ON T TO U;
                REVOKE ALL ON T FROM U;
                SET CURRENT SCHEMA = X;
                COMMIT;
                CONNECT TO SAMPLE;
                ALTER SESSION SET NLS_DATE_FORMAT = 'YYYYMMDD';
                ALTER INDEX IX REBUILD;
                CREATE USER U IDENTIFIED BY P;
                CREATE TABLESPACE TS DATAFILE 'x.dbf' SIZE 10M;
                CREATE OR REPLACE TYPE T_OBJ AS OBJECT (A NUMBER, B VARCHAR2(10));
                /
                CREATE FOO BAR BAZ;
                CREATE TABLE AFTER_ALL (ID NUMBER);
                """);
        assertThat(g.diagnostics()).hasSize(1);
        assertThat(g.diagnostics().get(0).code()).isEqualTo(DiagnosticCodes.UNSUPPORTED_SYNTAX);
        assertThat(g.diagnostics().get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(g.diagnostics().get(0).location().startLine()).isEqualTo(13);
        assertThat(node(g, "db:table:AFTER_ALL").declared()).isTrue();
        assertThat(g.hasNode("db:table:T")).isFalse();
    }

    @Test
    @DisplayName("F-007-AC11 같은 테이블 이름이 서로 다른 스키마로 정의되면 DUPLICATE_OBJECT_NAME")
    void duplicateNames() {
        GraphModel g = analyze("dup.sql", """
                CREATE TABLE A.T (ID NUMBER);
                CREATE TABLE B.T (ID NUMBER, X NUMBER);
                CREATE TABLE U (ID NUMBER);
                CREATE TABLE S.U (ID NUMBER);
                """);
        assertThat(diagnostics(g, DiagnosticCodes.DUPLICATE_OBJECT_NAME)).hasSize(1);
        assertThat(diagnostics(g, DiagnosticCodes.DUPLICATE_OBJECT_NAME).get(0).message()).contains("T").contains("A").contains("B");
        assertThat(node(g, "db:table:T").declared()).isTrue();
    }

    @Test
    @DisplayName("F-007-AC12 루틴 헤더: DB_ROUTINE/DB_PACKAGE 선언, 패키지 멤버, 트리거 대상, 본문 미분석 진단")
    void routines() {
        try (TestProject project = TestProject.create()
                .file("db/oracle/sp_p.prc", "CREATE OR REPLACE PROCEDURE SHOP.P (A IN NUMBER, B OUT VARCHAR2) IS\nBEGIN\n  UPDATE T SET X = 1;\nEND P;\n/\n")
                .file("db/oracle/fn_f.fnc", "CREATE OR REPLACE FUNCTION F (A NUMBER) RETURN VARCHAR2 IS\nBEGIN\n  RETURN 'x';\nEND;\n/\n")
                .file("db/oracle/pkg.pks", "CREATE OR REPLACE PACKAGE PKG AS\n  PROCEDURE A(X NUMBER);\n  FUNCTION B RETURN NUMBER;\nEND PKG;\n/\n")
                .file("db/oracle/pkg.pkb", "CREATE OR REPLACE PACKAGE BODY PKG AS\n  PROCEDURE HIDDEN IS BEGIN NULL; END;\n  PROCEDURE A(X NUMBER) IS\n  BEGIN\n    HIDDEN;\n  END A;\n  FUNCTION B RETURN NUMBER IS BEGIN RETURN 1; END B;\nEND PKG;\n/\n")
                .file("db/oracle/trg.trg", "CREATE OR REPLACE TRIGGER TRG AFTER UPDATE OF ST ON SHOP.T FOR EACH ROW\nBEGIN\n  NULL;\nEND;\n/\n")
                .file("db/db2/sp.db2", "--#SET TERMINATOR @\nCREATE PROCEDURE SP_D (IN P INT)\nLANGUAGE SQL\nBEGIN\n  DELETE FROM X;\nEND@\n")) {
            // 본문을 분석하지 않는 기본 핸들러의 동작을 본다(F-008이 기본값을 바꿔도 이 테스트는 그대로다)
            GraphModel g = project.run(new SqlAnalyzer(new io.codeatlas.sql.script.HeaderOnlyRoutineHandler())).graph();
            Node p = node(g, "db:routine:P");
            assertThat(p.kind()).isEqualTo(NodeKind.DB_ROUTINE);
            assertThat(p.declared()).isTrue();
            assertThat(p.attrString("routineType")).isEqualTo("PROCEDURE");
            assertThat(p.attrList("parameters")).containsExactly("A IN NUMBER", "B OUT VARCHAR2");
            assertThat(p.attrString("dialect")).isEqualTo("ORACLE");
            assertThat(p.attrString("schema")).isEqualTo("SHOP");
            assertThat(p.location().file()).isEqualTo("db/oracle/sp_p.prc");
            assertThat(hasEdge(g, "file:db/oracle/sp_p.prc", EdgeKind.CONTAINS, "db:routine:P")).isTrue();
            assertThat(node(g, "db:routine:F").attrString("routineType")).isEqualTo("FUNCTION");

            Node pkg = node(g, "db:package:PKG");
            assertThat(pkg.kind()).isEqualTo(NodeKind.DB_PACKAGE);
            assertThat(pkg.attrBoolean("hasBody")).isTrue();
            assertThat(targets(g, "db:package:PKG", EdgeKind.CONTAINS)).containsExactly("db:routine:PKG.A", "db:routine:PKG.B", "db:routine:PKG.HIDDEN");
            Node a = node(g, "db:routine:PKG.A");
            assertThat(a.attrString("routineType")).isEqualTo("PROCEDURE");
            assertThat(a.attrString("package")).isEqualTo("PKG");
            assertThat(a.name()).isEqualTo("A");
            assertThat(a.qualifiedName()).isEqualTo("PKG.A");
            assertThat(a.location().file()).isEqualTo("db/oracle/pkg.pkb");
            assertThat(a.location().startLine()).isEqualTo(3);
            assertThat(node(g, "db:routine:PKG.B").attrString("routineType")).isEqualTo("FUNCTION");

            Node trg = node(g, "db:routine:TRG");
            assertThat(trg.attrString("routineType")).isEqualTo("TRIGGER");
            assertThat(trg.attrString("triggerEvent")).isEqualTo("AFTER UPDATE OF ST");
            assertThat(hasEdge(g, "db:routine:TRG", EdgeKind.TRIGGERS_ON, "db:table:T")).isTrue();
            assertThat(node(g, "db:routine:SP_D").attrString("dialect")).isEqualTo("DB2");

            // 기본 구현은 본문을 분석하지 않는다: 본문의 테이블은 나오지 않고 INFO 진단이 남는다
            assertThat(g.hasNode("db:table:X")).isFalse();
            assertThat(g.diagnostics()).allMatch(d -> d.severity() == Severity.INFO && d.code().equals(DiagnosticCodes.UNSUPPORTED_SYNTAX));
            assertThat(g.diagnostics()).hasSize(5);
        }
    }
}
