package io.codeatlas.sql.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 프로젝트 설정에서 DB 방언 읽기(F-007-R21). mybatis 모듈의 {@code ProjectDialect}와 같은 키 목록·같은 규칙이어야 한다:
 * mybatis 쪽 {@code ProjectDialectTableTest}(F-009-AC41)에 <b>같은 표</b>를 둔다. 표를 고칠 때는 두 테스트를 함께 고친다.
 */
class ProjectDialectTest {

    // 아래 표는 mybatis 모듈 ProjectDialectTableTest와 글자 그대로 같다.

    @ParameterizedTest(name = "{0}={1} → {2}")
    @DisplayName("F-007-AC21 방언을 정하는 설정 키와 값 읽기(mybatis와 같은 표)")
    @CsvSource(nullValues = "-", delimiter = '|', value = {
            "Globals.DbType | hsql | hsql",
            "Globals.DbType | Oracle | oracle",
            "globals.dbtype | mysql | mysql",
            "jdbc.type | mssql | mssql",
            "jdbc.type | ${db.type} | -",
            "mybatis.configuration.database-id | db2 | db2",
            "mybatis.configuration.databaseId | tibero | tibero",
            "spring.datasource.url | jdbc:oracle:thin:@//db:1521/ORCL | oracle",
            "spring.datasource.url | jdbc:db2://host:50000/SHOP | db2",
            "spring.datasource.url | jdbc:log4jdbc:mysql://localhost:3306/shop | mysql",
            "spring.datasource.url | jdbc:mariadb://localhost/shop | mariadb",
            "spring.datasource.url | jdbc:postgresql://localhost/shop | postgres",
            "spring.datasource.url | jdbc:sqlserver://localhost;databaseName=shop | mssql",
            "spring.datasource.url | jdbc:jtds:sqlserver://localhost/shop | mssql",
            "spring.datasource.url | jdbc:hsqldb:mem:testdb | hsql",
            "spring.datasource.url | jdbc:h2:mem:testdb | h2",
            "spring.datasource.url | jdbc:tibero:thin:@db:8629:tibero | tibero",
            "spring.datasource.url | jdbc:Altibase://db:20300/mydb | altibase",
            "spring.datasource.url | jdbc:informix-sqli://db:9088/shop | informix",
            "spring.datasource.url | ${DB_URL} | -",
            "spring.datasource.url | http://example.com | -",
            "spring.datasource.driver-class-name | oracle.jdbc.OracleDriver | oracle",
            "spring.datasource.driverClassName | com.ibm.db2.jcc.DB2Driver | db2",
            "spring.datasource.driver-class-name | com.mysql.cj.jdbc.Driver | mysql",
            "spring.datasource.driver-class-name | org.mariadb.jdbc.Driver | mariadb",
            "spring.datasource.driver-class-name | org.postgresql.Driver | postgres",
            "spring.datasource.driver-class-name | com.microsoft.sqlserver.jdbc.SQLServerDriver | mssql",
            "spring.datasource.driver-class-name | org.hsqldb.jdbcDriver | hsql",
            "spring.datasource.driver-class-name | org.h2.Driver | h2",
            "spring.datasource.driver-class-name | com.tmax.tibero.jdbc.TbDriver | tibero",
            "spring.datasource.driver-class-name | Altibase.jdbc.driver.AltibaseDriver | altibase",
            "spring.datasource.driver-class-name | cubrid.jdbc.driver.CUBRIDDriver | cubrid",
            "spring.datasource.driver-class-name | net.sf.log4jdbc.sql.jdbcapi.DriverSpy | -",
            "spring.datasource.username | oracle | -",
            "Globals.UserName | mysql | -",
            "jdbc.type | '' | -"})
    void settingTable(String key, String value, String expected) {
        assertThat(ProjectDialect.dialectOf(key, value)).isEqualTo(expected);
    }

    @Test
    @DisplayName("F-007-AC21 .properties는 주석 줄을 빼고 읽고, YAML은 중첩 키를 점으로 이어 읽는다. 문서(프로파일)마다 값이 다르면 여럿이다")
    void readsFiles() {
        List<ProjectDialect.Setting> properties = ProjectDialect.settingsOf("conf/globals.properties", false,
                "# DB\n#Globals.DbType = oracle\n! Globals.DbType = db2\nGlobals.DbType = mysql\nGlobals.UserName=oracle\n");
        assertThat(properties).containsExactly(new ProjectDialect.Setting("mysql", "conf/globals.properties", "Globals.DbType", "mysql"));
        assertThat(properties.get(0).describe()).isEqualTo("conf/globals.properties:Globals.DbType=mysql");

        List<ProjectDialect.Setting> yaml = ProjectDialect.settingsOf("application.yml", true, """
                spring:
                  application:
                    name: shop
                  datasource:
                    # url: jdbc:mysql://old
                    url: "jdbc:oracle:thin:@db:1521:ORCL"   # 운영
                    username: mysql
                mybatis:
                  configuration:
                    database-id: oracle
                ---
                spring:
                  config.activate.on-profile: local
                  datasource:
                    url: jdbc:h2:mem:local
                """);
        assertThat(yaml).extracting(ProjectDialect.Setting::dialect).containsExactly("oracle", "h2", "oracle");
        assertThat(yaml).extracting(ProjectDialect.Setting::key).containsOnly("spring.datasource.url", "mybatis.configuration.database-id");
        // 한 방언으로 모이지 않으면 쓰지 않는다
        assertThat(ProjectDialect.agreed(yaml)).isNull();
        assertThat(ProjectDialect.agreed(properties).dialect()).isEqualTo("mysql");
        assertThat(ProjectDialect.agreed(List.of())).isNull();
    }
}
