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
}
