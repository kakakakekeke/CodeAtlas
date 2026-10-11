package io.codeatlas.mybatis;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 매퍼 XML에 적힌 타입 이름을 Java 타입으로 해석한다. 별칭은 MyBatis와 같이 대소문자를 가리지 않는다.
 *
 * <p>해석 순서: MyBatis 내장 별칭 → 설정에서 읽은 별칭(typeAlias, 별칭 패키지) → FQN → 프로젝트에서 단순 이름이 유일하게 맞는 타입(추론).
 */
final class TypeAliases {

    enum Kind {
        /** JDK·표준 타입. 엣지를 만들지 않는다. */
        JDK,
        /** 별칭 또는 FQN으로 확정. */
        RESOLVED,
        /** 단순 이름이 유일하게 맞아 추론. */
        INFERRED,
        /** 해석하지 못함. */
        UNRESOLVED
    }

    /** @param fqn 해석한 타입의 FQN. JDK·UNRESOLVED면 null일 수 있다 */
    record Resolution(Kind kind, String fqn) {
    }

    /** MyBatis TypeAliasRegistry가 기본으로 등록하는 별칭(소문자). 배열 표기는 해석할 때 뗀다. */
    private static final Set<String> BUILT_IN = Set.of(
            "string", "byte", "char", "character", "long", "short", "int", "integer", "double", "float", "boolean",
            "_byte", "_char", "_character", "_long", "_short", "_int", "_integer", "_double", "_float", "_boolean",
            "date", "decimal", "bigdecimal", "biginteger", "object",
            "map", "hashmap", "list", "arraylist", "collection", "iterator", "resultset");

    /** 소문자 별칭 → FQN들. 둘 이상이면 모호한 별칭이다. */
    private final Map<String, Set<String>> aliases = new HashMap<>();
    private final JavaTypeIndex index;

    TypeAliases(JavaTypeIndex index) {
        this.index = index;
    }

    void register(String alias, String fqn) {
        if (alias == null || alias.isBlank() || fqn == null || fqn.isBlank()) {
            return;
        }
        aliases.computeIfAbsent(alias.trim().toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>()).add(fqn.trim());
    }

    /** 패키지(와 하위 패키지)의 타입을 단순 이름 별칭으로 등록한다. 등록한 수를 돌려준다. */
    int registerPackage(String packageSpec) {
        Map<String, String> types = index.typesInPackage(packageSpec);
        types.forEach(this::register);
        return types.size();
    }

    Resolution resolve(String typeName) {
        String name = typeName.trim();
        while (name.endsWith("[]")) {
            name = name.substring(0, name.length() - 2).trim();
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (BUILT_IN.contains(lower)) {
            return new Resolution(Kind.JDK, null);
        }
        Set<String> registered = aliases.get(lower);
        if (registered != null) {
            if (registered.size() > 1) {
                return new Resolution(Kind.UNRESOLVED, null);
            }
            return ofFqn(registered.iterator().next());
        }
        if (name.contains(".")) {
            return ofFqn(name);
        }
        String unique = index.uniqueBySimpleName(name);
        if (unique != null) {
            return new Resolution(Kind.INFERRED, unique);
        }
        return new Resolution(Kind.UNRESOLVED, null);
    }

    private static Resolution ofFqn(String fqn) {
        boolean standard = fqn.startsWith("java.") || fqn.startsWith("javax.") || fqn.startsWith("jakarta.");
        return new Resolution(standard ? Kind.JDK : Kind.RESOLVED, fqn);
    }

    static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
