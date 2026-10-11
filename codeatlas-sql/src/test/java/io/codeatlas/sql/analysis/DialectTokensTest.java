package io.codeatlas.sql.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 경로에서 DB 방언을 읽는 토큰 표(F-007-R18). mybatis 모듈의 {@code DialectVariants}와 같은 목록·같은 규칙이어야 한다:
 * 두 모듈은 코드를 공유하지 않으므로 mybatis 쪽 {@code DialectTokenTableTest}(F-009-AC38)에 <b>같은 표</b>를 둔다.
 * 표를 고칠 때는 두 테스트를 함께 고친다.
 */
class DialectTokensTest {

    @Test
    @DisplayName("F-007-AC20 방언 표준 이름과 기본 선호 순서: oracle, db2, tibero, altibase, 그다음 postgres … informix (mybatis와 같은 목록)")
    void namesAndOrder() {
        assertThat(DialectTokens.NAMES).containsExactly("oracle", "db2", "tibero", "altibase", "postgres", "mysql", "mariadb", "mssql",
                "cubrid", "goldilocks", "hsql", "h2", "derby", "sqlite", "sybase", "informix");
        assertThat(DialectTokens.PREFERRED).containsExactly("oracle", "db2", "tibero", "altibase");
        assertThat(DialectTokens.NAMES.subList(0, 4)).isEqualTo(List.copyOf(DialectTokens.PREFERRED));
    }

    // 아래 두 표는 mybatis 모듈 DialectTokenTableTest와 글자 그대로 같다.

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("F-007-AC20 방언 토큰 표: 표준 이름, 별칭, 끝에 버전 숫자가 붙은 것(mybatis와 같은 표)")
    @CsvSource(nullValues = "-", value = {
            "oracle, oracle", "ORACLE, oracle", "ora, oracle", "oracle11, oracle",
            "db2, db2", "DB2, db2",
            "tibero, tibero", "tibero6, tibero", "altibase, altibase",
            "postgres, postgres", "postgresql, postgres", "pgsql, postgres", "postgres9, postgres",
            "mysql, mysql", "mysql55, mysql", "mysql8, mysql",
            "mariadb, mariadb", "maria, mariadb",
            "mssql, mssql", "sqlserver, mssql", "sqlserver2012, mssql",
            "cubrid, cubrid", "goldilocks, goldilocks",
            "hsql, hsql", "hsqldb, hsql", "h2, h2",
            "derby, derby", "sqlite, sqlite", "sqlite3, sqlite", "sybase, sybase", "informix, informix",
            "auto, -", "all, -", "sql, -", "db, -", "h, -", "my, -", "oracledb, -", "2, -", "'', -"})
    void tokenTable(String token, String expected) {
        assertThat(DialectTokens.canonical(token)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("F-007-AC20 경로에서 방언 읽기: 파일 이름의 토큰(글자·숫자가 아닌 것으로 나눠 뒤에서부터), 그다음 폴더 이름(가까운 것부터, 이름 전체)(mybatis와 같은 표)")
    @CsvSource(nullValues = "-", value = {
            "mapper/EgovLoginUsr_SQL_oracle.xml, oracle",
            "mapper/Order-mysql.xml, mysql",
            "mapper/Order.postgresql.xml, postgres",
            "db/act/activiti.db2.create.engine.sql, db2",
            "db/act/activiti.mysql55.create.history.sql, mysql",
            "db/sys/jeesite_mssql.sql, mssql",
            "DATABASE/altibase/all_ebt_ddl_altibase.sql, altibase",
            "script/ddl/tibero/uss.umt_create_tibero.sql, tibero",
            "mapper/db2/sub/Stock.xml, db2",
            "db/ora/schema.sql, oracle",
            "db/oracle/mysql_compat.sql, mysql",
            "db/mysql/postgres/x.sql, postgres",
            "db/oracle_to_mysql.sql, mysql",
            "mapper\\maria\\Stock.xml, mariadb",
            "h2/schema.sql, h2",
            "mapper/OracleUserMapper.xml, -",
            "db/my-oracle-scripts/schema.sql, -",
            "db/schema.sql, -",
            "schema.sql, -"})
    void pathTable(String path, String expected) {
        assertThat(DialectTokens.ofPath(path)).isEqualTo(expected);
    }

    // 아래 표는 mybatis 모듈 DialectTokenTableTest와 글자 그대로 같다(매퍼 경로들 ; SQL 스크립트 경로들 ; 그 밖의 방언의 순서. 경로는 |로 나눈다).

    @ParameterizedTest(name = "[{index}] 매퍼 {0} / 스크립트 {1}")
    @DisplayName("F-007-AC29 그 밖의 방언(대상 방언과 그 계열이 아닌 것)의 순서: 매퍼와 SQL 스크립트 양쪽의 경로에 다 있는 방언이 이름의 사전순으로 먼저, 나머지가 사전순으로 그 뒤(mybatis와 같은 표)")
    @CsvSource(delimiter = ';', nullValues = "-", value = {
            "- ; - ; cubrid derby goldilocks h2 hsql informix mariadb mssql mysql postgres sqlite sybase",
            "mapper/A_SQL_postgres.xml|mapper/A_SQL_mysql.xml ; db/postgres/ddl.sql|db/mysql/ddl.sql"
                    + " ; mysql postgres cubrid derby goldilocks h2 hsql informix mariadb mssql sqlite sybase",
            "mapper/A_SQL_cubrid.xml|mapper/A_SQL_mysql.xml|mapper/A_SQL_postgres.xml ; db/a.mysql55.create.sql|db/postgresql/a.sql|db/hsqldb/a.sql"
                    + " ; mysql postgres cubrid derby goldilocks h2 hsql informix mariadb mssql sqlite sybase",
            "mapper/A_SQL_postgres.xml|mapper/A_SQL_mysql.xml ; db/schema.sql"
                    + " ; cubrid derby goldilocks h2 hsql informix mariadb mssql mysql postgres sqlite sybase",
            "mapper/A_SQL_oracle.xml|mapper/sqlite/A.xml|mapper/tibero/A.xml ; db/oracle/a.sql|db/tibero/a.sql|db/a_sqlite.sql|db/a_sybase.sql"
                    + " ; sqlite cubrid derby goldilocks h2 hsql informix mariadb mssql mysql postgres sybase"})
    void otherDialectOrder(String mapperPaths, String scriptPaths, String expected) {
        List<String> mappers = mapperPaths == null ? List.of() : List.of(mapperPaths.split("\\|"));
        List<String> scripts = scriptPaths == null ? List.of() : List.of(scriptPaths.split("\\|"));
        assertThat(DialectTokens.otherOrder(mappers, scripts)).containsExactly(expected.split(" "));
    }
}
