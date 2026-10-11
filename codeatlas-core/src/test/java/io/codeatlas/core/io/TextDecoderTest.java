package io.codeatlas.core.io;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextDecoderTest {

    private static final String TEXT = "-- 주문 테이블\nSELECT * FROM 주문 WHERE 상태 = '완료';\n";
    private static final Charset MS949 = Charset.forName("MS949");

    @TempDir
    Path dir;

    @Test
    @DisplayName("F-001-AC4 MS949, UTF-8, BOM 붙은 UTF-8 파일을 같은 문자열로 읽는다")
    void detectsEncoding() throws Exception {
        Path ms949 = Files.write(dir.resolve("ms949.sql"), TEXT.getBytes(MS949));
        Path utf8 = Files.write(dir.resolve("utf8.sql"), TEXT.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bom = new ByteArrayOutputStream();
        bom.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        bom.write(TEXT.getBytes(StandardCharsets.UTF_8));
        Path utf8Bom = Files.write(dir.resolve("bom.sql"), bom.toByteArray());

        assertThat(new SourceFile(ms949, "ms949.sql", FileType.SQL).readText()).isEqualTo(TEXT);
        assertThat(new SourceFile(utf8, "utf8.sql", FileType.SQL).readText()).isEqualTo(TEXT);
        assertThat(new SourceFile(utf8Bom, "bom.sql", FileType.SQL).readText()).isEqualTo(TEXT);

        assertThat(TextDecoder.decode(TEXT.getBytes(MS949), null).charset().name()).isEqualTo(MS949.name());
        assertThat(TextDecoder.decode(TEXT.getBytes(StandardCharsets.UTF_8), null).charset()).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("F-001-AC4 UTF-16 BOM이 있으면 UTF-16으로 읽는다")
    void utf16Bom() {
        byte[] le = ("﻿" + TEXT).getBytes(StandardCharsets.UTF_16LE);
        assertThat(TextDecoder.decode(le, null).text()).isEqualTo(TEXT);
        byte[] be = ("﻿" + TEXT).getBytes(StandardCharsets.UTF_16BE);
        assertThat(TextDecoder.decode(be, null).text()).isEqualTo(TEXT);
    }

    @Test
    @DisplayName("F-001-AC5 인코딩을 지정하면 판별 없이 그 인코딩만 쓴다")
    void forcedEncoding() throws Exception {
        byte[] bytes = "가".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(dir.resolve("a.sql"), bytes);
        String expected = new String(bytes, StandardCharsets.ISO_8859_1);

        assertThat(new SourceFile(file, "a.sql", FileType.SQL, StandardCharsets.ISO_8859_1).readText()).isEqualTo(expected);
        assertThat(TextDecoder.decode(bytes, StandardCharsets.ISO_8859_1).text()).isEqualTo(expected);
        assertThat(TextDecoder.decode(bytes, null).text()).isEqualTo("가");
    }

    @Test
    @DisplayName("빈 파일은 빈 문자열이다")
    void empty() {
        assertThat(TextDecoder.decode(new byte[0], null).text()).isEmpty();
    }
}
