package io.codeatlas.core.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 소스 파일의 인코딩 판별과 디코딩. BOM이 있으면 그것을 따르고, 없으면 UTF-8로 엄격하게 디코딩해 보고 실패하면 MS949로 읽는다.
 * 인코딩을 지정하면 판별하지 않고 그것만 쓴다. 결과 문자열에 BOM 문자는 들어가지 않는다.
 */
public final class TextDecoder {

    /** 디코딩 결과와 실제로 쓴 인코딩. */
    public record Decoded(String text, Charset charset) {
    }

    /** UTF-8이 아닐 때 쓰는 인코딩. JDK에 MS949가 없으면 EUC-KR, 그것도 없으면 ISO-8859-1. */
    public static final Charset FALLBACK = fallbackCharset();

    private TextDecoder() {
    }

    /**
     * @param forced 지정 인코딩. null이면 판별한다
     */
    public static Decoded decode(byte[] bytes, Charset forced) {
        if (forced != null) {
            return new Decoded(stripBom(new String(bytes, forced)), forced);
        }
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return new Decoded(text, StandardCharsets.UTF_8);
        } catch (CharacterCodingException notUtf8) {
            return new Decoded(new String(bytes, FALLBACK), FALLBACK);
        }
    }

    /** 파일을 읽어 디코딩한다. 파일을 고치지 않는다. */
    public static Decoded read(Path file, Charset forced) throws IOException {
        return decode(Files.readAllBytes(file), forced);
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static Charset fallbackCharset() {
        for (String name : new String[] {"MS949", "x-windows-949", "EUC-KR"}) {
            try {
                return Charset.forName(name);
            } catch (RuntimeException unavailable) {
                // 다음 후보로
            }
        }
        return StandardCharsets.ISO_8859_1;
    }
}
