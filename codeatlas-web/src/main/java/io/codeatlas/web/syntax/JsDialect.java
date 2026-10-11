package io.codeatlas.web.syntax;

import java.util.Locale;

/** 파서가 고를 문법. */
public enum JsDialect {
    /** JavaScript와 JSX ({@code .js .mjs .cjs .jsx}, JSP 인라인 스크립트). */
    JAVASCRIPT,
    /** TypeScript ({@code .ts}). {@code <T>x} 타입 단언이 있어 JSX를 받지 않는다. */
    TYPESCRIPT,
    /** TypeScript + JSX ({@code .tsx}). */
    TSX;

    /** 파일 이름의 확장자로 문법을 고른다. 모르는 확장자는 JavaScript. */
    public static JsDialect forFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tsx")) {
            return TSX;
        }
        if (lower.endsWith(".ts") || lower.endsWith(".mts") || lower.endsWith(".cts")) {
            return TYPESCRIPT;
        }
        return JAVASCRIPT;
    }
}
