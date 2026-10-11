package io.codeatlas.core.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.spi.FileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileClassifierTest {

    @Test
    @DisplayName("F-019-AC2 확장자 .pgsql은 SQL 파일이다")
    void pgsqlExtension() {
        assertThat(FileClassifier.classifyByName("fn_close_order.pgsql")).isEqualTo(FileType.SQL);
        assertThat(FileClassifier.classifyByName("schema.sql")).isEqualTo(FileType.SQL);
    }
}
