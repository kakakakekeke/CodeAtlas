package io.codeatlas.core.model;

import java.util.List;
import java.util.Map;

/** attrs를 가진 값(노드, 엣지)의 조회 편의 메서드. */
public interface HasAttrs {

    /** 속성. 값은 String, Number, Boolean, List&lt;String&gt;만 들어 있다. 수정할 수 없다. */
    Map<String, Object> attrs();

    /** 속성값. 없으면 null. */
    default Object attr(String key) {
        return attrs().get(key);
    }

    /** 문자열 속성값. 없으면 null. 문자열이 아니면 toString 한 값. */
    default String attrString(String key) {
        Object value = attrs().get(key);
        return value == null ? null : value.toString();
    }

    /** 불리언 속성값. 없거나 불리언이 아니면 false. */
    default boolean attrBoolean(String key) {
        return Boolean.TRUE.equals(attrs().get(key));
    }

    /** 정수 속성값. 없거나 숫자가 아니면 defaultValue. */
    default int attrInt(String key, int defaultValue) {
        return attrs().get(key) instanceof Number n ? n.intValue() : defaultValue;
    }

    /** 문자열 목록 속성값. 없으면 빈 목록. 문자열 하나면 원소 하나짜리 목록. */
    @SuppressWarnings("unchecked")
    default List<String> attrList(String key) {
        Object value = attrs().get(key);
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return value instanceof String s ? List.of(s) : List.of();
    }
}
