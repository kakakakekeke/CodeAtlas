package io.codeatlas.export.common;

import java.util.Collection;
import java.util.Map;

/**
 * 작은 JSON 직렬화기. Map(키 순서 유지), Collection, String, Number, Boolean, null만 다룬다.
 * 출력 모듈은 외부 라이브러리를 쓰지 않으므로 직접 쓴다. 들여쓰기는 공백 2칸이고 줄 끝은 {@code \n}이다.
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        out.append('\n');
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            quote(out, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                indent(out, depth + 1);
                quote(out, String.valueOf(e.getKey()));
                out.append(": ");
                write(out, e.getValue(), depth + 1);
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append('}');
        } else if (value instanceof Collection<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            boolean scalars = list.stream().allMatch(v -> v == null || v instanceof String || v instanceof Number || v instanceof Boolean);
            if (scalars) {
                out.append('[');
                int i = 0;
                for (Object item : list) {
                    write(out, item, depth + 1);
                    if (++i < list.size()) {
                        out.append(", ");
                    }
                }
                out.append(']');
                return;
            }
            out.append("[\n");
            int i = 0;
            for (Object item : list) {
                indent(out, depth + 1);
                write(out, item, depth + 1);
                out.append(++i < list.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append(']');
        } else {
            quote(out, value.toString());
        }
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
