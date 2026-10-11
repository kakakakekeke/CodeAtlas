package io.codeatlas.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** attrs 값 검사와 정규화. JSON으로 쓰고 다시 읽어도 같은 값이 되도록 숫자 타입을 맞춘다. */
final class Attrs {

    static final String DECLARED = "declared";

    private Attrs() {
    }

    /** 허용 타입(String, Number, Boolean, List&lt;String&gt;)인지 검사하고 정규화한 값을 돌려준다. */
    static Object normalize(String key, Object value) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("attrs 키가 비어 있다");
        }
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number n) {
            if (n instanceof Integer || n instanceof Double) {
                return n;
            }
            if (n instanceof Long || n instanceof Short || n instanceof Byte) {
                long l = n.longValue();
                return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (Object) (int) l : (Object) l;
            }
            return n.doubleValue();
        }
        if (value instanceof Iterable<?> items) {
            List<String> copy = new java.util.ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof String s)) {
                    throw new IllegalArgumentException("attrs '" + key + "'의 목록에는 문자열만 넣을 수 있다: " + item);
                }
                copy.add(s);
            }
            return List.copyOf(copy);
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        throw new IllegalArgumentException(
                "attrs '" + key + "'의 값은 String, Number, Boolean, List<String>만 된다: " + (value == null ? "null" : value.getClass().getName()));
    }

    /** null 값은 건너뛰고 정규화해 넣는다. */
    static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, normalize(key, value));
        }
    }

    static void putAll(Map<String, Object> target, Map<String, ?> source) {
        if (source != null) {
            source.forEach((k, v) -> put(target, k, v));
        }
    }

    static Map<String, Object> freeze(Map<String, Object> attrs) {
        return attrs.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
    }
}
