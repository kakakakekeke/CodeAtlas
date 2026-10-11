package io.codeatlas.core.scan;

import io.codeatlas.core.spi.FileType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** 파일 종류 분류(설계 4.2). 확장자로 나누고, XML은 루트 요소를 가볍게 읽어 구분한다. */
public final class FileClassifier {

    private static final Set<String> SQL_EXTENSIONS =
            Set.of("sql", "ddl", "pks", "pkb", "pls", "plb", "prc", "fnc", "trg", "db2", "spsql", "pgsql");
    private static final Set<String> JS_EXTENSIONS = Set.of("js", "mjs", "cjs", "jsx");
    private static final Set<String> TS_EXTENSIONS = Set.of("ts", "tsx");
    private static final Set<String> JSP_EXTENSIONS = Set.of("jsp", "jspf", "tag");

    /** 루트 요소를 찾으려고 읽는 최대 바이트 수. */
    private static final int SNIFF_BYTES = 64 * 1024;

    private FileClassifier() {
    }

    /** 파일 종류. XML이면 파일 앞부분을 읽어 루트 요소를 본다. 읽지 못하면 {@link FileType#XML}. */
    public static FileType classify(Path file) {
        FileType byName = classifyByName(file.getFileName().toString());
        if (byName != FileType.XML) {
            return byName;
        }
        String root;
        try {
            root = sniffXmlRoot(file);
        } catch (IOException e) {
            return FileType.XML;
        }
        if ("mapper".equals(root)) {
            return FileType.MYBATIS_MAPPER;
        }
        if ("beans".equals(root)) {
            return FileType.SPRING_XML;
        }
        return FileType.XML;
    }

    /** 파일 이름(확장자)만으로 본 종류. XML은 내용을 보지 않고 {@link FileType#XML}. */
    public static FileType classifyByName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".d.ts")) {
            return FileType.OTHER;
        }
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        if (ext.equals("java")) {
            return FileType.JAVA;
        }
        if (ext.equals("xml")) {
            return FileType.XML;
        }
        if (SQL_EXTENSIONS.contains(ext)) {
            return FileType.SQL;
        }
        if (JS_EXTENSIONS.contains(ext)) {
            return FileType.JS;
        }
        if (TS_EXTENSIONS.contains(ext)) {
            return FileType.TS;
        }
        if (JSP_EXTENSIONS.contains(ext)) {
            return FileType.JSP;
        }
        return switch (ext) {
            case "html", "htm" -> FileType.HTML;
            case "properties" -> FileType.PROPERTIES;
            case "yml", "yaml" -> FileType.YAML;
            case "jar" -> FileType.JAR;
            default -> FileType.OTHER;
        };
    }

    /** XML 파일의 루트 요소 이름(네임스페이스 접두사 제외). 찾지 못하면 null. */
    public static String sniffXmlRoot(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(SNIFF_BYTES);
        }
        return sniffXmlRoot(decodeHead(head));
    }

    /** XML 본문 앞부분에서 루트 요소 이름을 찾는다. 선언, 주석, DOCTYPE, 처리 명령은 건너뛴다. */
    static String sniffXmlRoot(String text) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '﻿') {
                i++;
            } else if (text.startsWith("<?", i)) {
                i = after(text, "?>", i);
            } else if (text.startsWith("<!--", i)) {
                i = after(text, "-->", i);
            } else if (text.startsWith("<!", i)) {
                i = skipDeclaration(text, i);
            } else if (c == '<') {
                int start = i + 1;
                int end = start;
                while (end < n && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '>' && text.charAt(end) != '/') {
                    end++;
                }
                String name = text.substring(start, end);
                int colon = name.lastIndexOf(':');
                name = colon < 0 ? name : name.substring(colon + 1);
                return name.isEmpty() ? null : name;
            } else {
                return null;
            }
            if (i < 0) {
                return null;
            }
        }
        return null;
    }

    private static int after(String text, String terminator, int from) {
        int end = text.indexOf(terminator, from);
        return end < 0 ? -1 : end + terminator.length();
    }

    /** {@code <!DOCTYPE ... [ ... ]>}를 건너뛴다. 따옴표와 내부 서브셋 안의 '>'는 끝으로 보지 않는다. */
    private static int skipDeclaration(String text, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth <= 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /** 요소 이름은 ASCII라 가정하고, UTF-16 BOM이 있을 때만 UTF-16으로, 아니면 바이트를 그대로 문자로 본다. */
    private static String decodeHead(byte[] head) {
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return new String(head, 2, head.length - 2, StandardCharsets.UTF_16LE);
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return new String(head, 2, head.length - 2, StandardCharsets.UTF_16BE);
        }
        int offset = head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF ? 3 : 0;
        return new String(head, offset, head.length - offset, StandardCharsets.ISO_8859_1);
    }
}
