package io.codeatlas.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 정규화한 DB 객체 이름. 따옴표 없는 식별자는 대문자로 바꾸고, 큰따옴표로 감싼 식별자는 따옴표만 벗겨 원문 그대로 둔다.
 * schema는 이름 바로 앞 한정자이고 없으면 null이다.
 */
public record DbName(String schema, String name) {

    /**
     * {@code schema.name} 또는 {@code name} 형태의 원문을 나눠 정규화한다. 한정자가 둘 이상이면 이름 바로 앞 것만 schema로 남긴다.
     * 예: {@code hr.orders} → (HR, ORDERS), {@code "Orders"} → (null, Orders).
     */
    public static DbName parse(String raw) {
        List<String> parts = split(raw);
        String name = parts.get(parts.size() - 1);
        String schema = parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
        return new DbName(schema, name);
    }

    /** 점으로 나누지 않고 식별자 하나를 정규화한다. 컬럼명처럼 한정자가 없는 이름에 쓴다. */
    public static String normalize(String identifier) {
        String s = identifier == null ? "" : identifier.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s.toUpperCase(Locale.ROOT);
    }

    /** 따옴표 밖의 점에서 나눠 각 조각을 정규화한다. */
    static List<String> split(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("DB 객체 이름이 null이다");
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == '.' && !inQuotes) {
                parts.add(normalize(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(normalize(current.toString()));
        return parts;
    }

    /** schema가 있으면 {@code SCHEMA.NAME}, 없으면 {@code NAME}. */
    @Override
    public String toString() {
        return schema == null ? name : schema + "." + name;
    }
}
