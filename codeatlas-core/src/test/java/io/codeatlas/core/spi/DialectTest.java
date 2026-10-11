package io.codeatlas.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DialectTest {

    @Test
    @DisplayName("F-019-AC1 --dialect 값 postgres와 별칭 postgresql·pgsql은 POSTGRES다")
    void postgresNames() {
        assertThat(Dialect.parse("postgres")).isEqualTo(Dialect.POSTGRES);
        assertThat(Dialect.parse("PostgreSQL")).isEqualTo(Dialect.POSTGRES);
        assertThat(Dialect.parse(" pgsql ")).isEqualTo(Dialect.POSTGRES);
    }

    @Test
    @DisplayName("F-019-AC1 oracle·db2·auto는 전과 같고 그 밖의 이름은 받지 않는다")
    void otherNames() {
        assertThat(Dialect.parse("oracle")).isEqualTo(Dialect.ORACLE);
        assertThat(Dialect.parse("DB2")).isEqualTo(Dialect.DB2);
        assertThat(Dialect.parse("auto")).isEqualTo(Dialect.AUTO);
        assertThat(Dialect.parse(null)).isEqualTo(Dialect.AUTO);
        assertThat(Dialect.parse("")).isEqualTo(Dialect.AUTO);
        assertThatThrownBy(() -> Dialect.parse("mysql")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("F-019-R4 대상 방언의 표준 이름은 Dialect가 갖는다: oracle, db2, postgres이고 AUTO에는 없다")
    void canonicalNames() {
        assertThat(Dialect.ORACLE.canonicalName()).isEqualTo("oracle");
        assertThat(Dialect.DB2.canonicalName()).isEqualTo("db2");
        assertThat(Dialect.POSTGRES.canonicalName()).isEqualTo("postgres");
        assertThat(Dialect.AUTO.canonicalName()).isNull();
    }

    @Test
    @DisplayName("F-019-R4 표준 이름에서 대상 방언을 찾는다: 표준 이름 그대로일 때만이고 별칭·대문자·대상 외 방언·auto·null은 null이다")
    void ofCanonicalName() {
        for (Dialect dialect : Dialect.values()) {
            if (dialect != Dialect.AUTO) {
                assertThat(Dialect.ofCanonicalName(dialect.canonicalName())).isEqualTo(dialect);
            }
        }
        assertThat(Dialect.ofCanonicalName("oracle")).isEqualTo(Dialect.ORACLE);
        assertThat(Dialect.ofCanonicalName("db2")).isEqualTo(Dialect.DB2);
        assertThat(Dialect.ofCanonicalName("postgres")).isEqualTo(Dialect.POSTGRES);
        for (String other : new String[] {"postgresql", "pgsql", "ORACLE", "Oracle", " oracle", "mysql", "tibero", "auto", ""}) {
            assertThat(Dialect.ofCanonicalName(other)).as(other).isNull();
        }
        assertThat(Dialect.ofCanonicalName(null)).isNull();
    }
}
