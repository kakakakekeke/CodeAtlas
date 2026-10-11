package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.node;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DB가 제공하는 사전 뷰·카탈로그 테이블(F-006-R40). DDL에 없는 것이 당연하므로 테이블 노드에 {@code systemCatalog=true}를 붙여
 * 리포트가 "DDL에 없는 의심스러운 테이블"로 세지 않게 한다.
 */
class SystemCatalogTest {

    @Test
    @DisplayName("F-006-AC40 Oracle 데이터 사전(USER_/ALL_/DBA_ 뷰, V$ 뷰)과 DB2 카탈로그(SYSCAT.*, SYSIBM.*, SYSSTAT.*)의 테이블 노드에 systemCatalog=true가 붙고, 프로젝트의 테이블에는 붙지 않는다")
    void systemCatalogTables() {
        try (TestProject project = TestProject.create()
                .file("db/ddl.sql", "CREATE TABLE ALL_CODES (CD VARCHAR2(10));\n")
                .file("db/q.sql", """
                        SELECT C.COLUMN_NAME FROM USER_TAB_COLUMNS C, ALL_TABLES T WHERE T.TABLE_NAME = C.TABLE_NAME;
                        SELECT USERNAME FROM DBA_USERS;
                        SELECT SID FROM V$SESSION WHERE STATUS = 'ACTIVE';
                        SELECT TABNAME FROM SYSCAT.TABLES WHERE TABSCHEMA = CURRENT SCHEMA;
                        SELECT NAME FROM SYSIBM.SYSCOLUMNS;
                        SELECT CARD FROM SYSSTAT.TABLES;
                        SELECT U.USER_ID FROM USER_INFO U, USER_ROLES_MAP M, ALL_CODES A WHERE U.USER_ID = M.USER_ID;
                        SELECT TABNAME FROM APP.TABLES;
                        """)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            for (String name : new String[] {"USER_TAB_COLUMNS", "ALL_TABLES", "DBA_USERS", "V$SESSION", "SYSCOLUMNS"}) {
                assertThat(node(g, "db:table:" + name).attr("systemCatalog")).as(name).isEqualTo(true);
                assertThat(node(g, "db:table:" + name).declared()).as(name).isFalse();
            }
            // SYSCAT.TABLES와 SYSSTAT.TABLES는 스키마를 뗀 이름이 같다. 사전 스키마로 한정한 것만 봤을 때 표시한다
            assertThat(g.hasNode("db:table:TABLES")).isTrue();
            assertThat(node(g, "db:table:TABLES").attr("systemCatalog")).as("APP.TABLES로도 읽었다: 프로젝트의 테이블일 수 있다").isNull();
            // 이름이 USER_·ALL_로 시작해도 사전 뷰 이름이 아니면 프로젝트의 테이블이다. DDL에 정의된 테이블도 아니다
            assertThat(node(g, "db:table:USER_INFO").attr("systemCatalog")).isNull();
            assertThat(node(g, "db:table:USER_ROLES_MAP").attr("systemCatalog")).isNull();
            assertThat(node(g, "db:table:ALL_CODES").attr("systemCatalog")).isNull();
        }
    }

    @Test
    @DisplayName("F-006-AC41 사전 스키마로 한정한 참조(information_schema.`TABLES`, INFORMATION_SCHEMA.COLUMNS, sys.objects)는 이름·백틱·대소문자와 상관없이, 구문 일부를 해석하지 못해 건진 참조여도 systemCatalog=true다. 한정자 없는 TABLES·COLUMNS는 일반 테이블이다")
    void schemaQualifiedCatalogReferences() {
        // jeesite GenDataBaseDictDao.xml의 꼴: 한 구문에 방언별 분기(oracle, mssql, mysql)가 이어 붙고 MSSQL 분기는 해석하지 못하는 표기([value])를 쓴다
        try (TestProject project = TestProject.create()
                .file("db/q.sql", """
                        SELECT t.table_name AS name, t.TABLE_COMMENT AS comments FROM information_schema.`TABLES` t WHERE t.TABLE_SCHEMA = (select database()) ORDER BY t.TABLE_NAME;
                        SELECT t.COLUMN_NAME AS name FROM user_tab_columns t
                        SELECT t.COLUMN_NAME AS name, isnull(g.[value], '') AS comments,
                          (t.DATA_TYPE + CASE WHEN t.DATA_TYPE IN ('varchar') THEN '(' + CONVERT(VARCHAR, t.CHARACTER_MAXIMUM_LENGTH) + ')' ELSE '' END) AS jdbcType
                        FROM INFORMATION_SCHEMA.COLUMNS t INNER JOIN sys.sysobjects o ON t.TABLE_NAME = o.name AND SCHEMA_NAME(o.uid) = t.TABLE_SCHEMA
                          LEFT JOIN sys.extended_properties g ON o.id = g.major_id AND t.ORDINAL_POSITION = g.minor_id AND g.name = 'MS_Description'
                        WHERE t.TABLE_SCHEMA = (SCHEMA_NAME()) ORDER BY t.ORDINAL_POSITION
                        SELECT lower(au.COLUMN_NAME) AS columnName FROM information_schema.`COLUMNS` au WHERE au.TABLE_SCHEMA = (select database());
                        SELECT lower(au.COLUMN_NAME) AS columnName FROM `information_schema`.`KEY_COLUMN_USAGE` au WHERE au.TABLE_NAME = 'x';
                        SELECT r.ROUTINE_NAME FROM Information_Schema.ROUTINES r;
                        SELECT X.A FROM TB_X X WHERE X.A = 1 LIMIT 1 FOO BAR FROM INFORMATION_SCHEMA.SCHEMATA S;
                        """)
                .file("db/plain.sql", """
                        SELECT COL_ID FROM COLUMNS WHERE COL_ID = 1;
                        SELECT ID FROM `VIEWS`;
                        """)
                .file("db/both.sql", """
                        SELECT TRIGGER_NAME FROM information_schema.TRIGGERS;
                        SELECT TRIGGER_NAME FROM TRIGGERS;
                        """)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            for (String name : new String[] {"TABLES", "KEY_COLUMN_USAGE", "ROUTINES", "SYSOBJECTS", "EXTENDED_PROPERTIES", "SCHEMATA"}) {
                assertThat(node(g, "db:table:" + name).attr("systemCatalog")).as(name).isEqualTo(true);
                assertThat(node(g, "db:table:" + name).declared()).as(name).isFalse();
            }
            // SCHEMATA는 해석하지 못한 토큰 뒤에서 건진 참조다(PARSE_FAILED와 함께 READS만 남는다)
            assertThat(GraphAssert.codes(g)).contains(io.codeatlas.core.model.DiagnosticCodes.PARSE_FAILED);
            // 한정자 없이도 읽은 이름은 프로젝트의 테이블일 수 있다: 붙이지 않는다
            assertThat(node(g, "db:table:COLUMNS").attr("systemCatalog")).isNull();
            assertThat(node(g, "db:table:TRIGGERS").attr("systemCatalog")).isNull();
            assertThat(node(g, "db:table:VIEWS").attr("systemCatalog")).isNull();
        }
        // 한정자 없는 COLUMNS가 없으면 INFORMATION_SCHEMA.COLUMNS도 표기된다
        try (TestProject project = TestProject.create()
                .file("db/q.sql", """
                        SELECT t.COLUMN_NAME AS name, isnull(g.[value], '') AS comments
                        FROM INFORMATION_SCHEMA.COLUMNS t LEFT JOIN sys.extended_properties g ON t.ORDINAL_POSITION = g.minor_id
                        WHERE t.TABLE_SCHEMA = (SCHEMA_NAME());
                        SELECT au.COLUMN_NAME FROM information_schema.`COLUMNS` au;
                        """)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:COLUMNS").attr("systemCatalog")).isEqualTo(true);
        }
    }
}
