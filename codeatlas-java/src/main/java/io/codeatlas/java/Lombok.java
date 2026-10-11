package io.codeatlas.java;

import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Name;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lombok 애너테이션 인식(F-002-R14). lombok jar가 classpath에 없어도 import와 단순 이름으로 알아본다.
 * 애너테이션 처리기는 돌리지 않으므로 jar가 있어도 결과는 같다.
 */
final class Lombok {

    static final String GETTER = "Getter";
    static final String SETTER = "Setter";
    static final String DATA = "Data";
    static final String VALUE = "Value";
    static final String NO_ARGS = "NoArgsConstructor";
    static final String ALL_ARGS = "AllArgsConstructor";
    static final String REQUIRED_ARGS = "RequiredArgsConstructor";
    static final String BUILDER = "Builder";
    static final String BUILDER_DEFAULT = "Builder.Default";
    static final String SUPER_BUILDER = "SuperBuilder";
    static final String SINGULAR = "Singular";
    static final String TO_STRING = "ToString";
    static final String EQUALS_AND_HASH_CODE = "EqualsAndHashCode";
    static final String WITH = "With";
    static final String ACCESSORS = "Accessors";
    static final String NON_NULL = "NonNull";
    static final String FIELD_DEFAULTS = "FieldDefaults";
    static final String NON_FINAL = "NonFinal";

    /** 점으로 이은 전체 이름 → 종류(attrs.lombok에 쓰는 이름). */
    private static final Map<String, String> KINDS = new LinkedHashMap<>();
    /** 로그 애너테이션의 종류 → 만들어지는 {@code log} 필드의 타입. */
    private static final Map<String, String> LOGGERS = new LinkedHashMap<>();

    static {
        for (String name : List.of(GETTER, SETTER, DATA, VALUE, NO_ARGS, ALL_ARGS, REQUIRED_ARGS, BUILDER, BUILDER_DEFAULT, SINGULAR,
                TO_STRING, EQUALS_AND_HASH_CODE, WITH, NON_NULL)) {
            KINDS.put("lombok." + name, name);
        }
        KINDS.put("lombok.experimental.SuperBuilder", SUPER_BUILDER);
        KINDS.put("lombok.experimental.Accessors", ACCESSORS);
        KINDS.put("lombok.experimental.FieldDefaults", FIELD_DEFAULTS);
        KINDS.put("lombok.experimental.NonFinal", NON_FINAL);
        KINDS.put("lombok.experimental.Wither", WITH);
        KINDS.put("lombok.experimental.With", WITH);
        logger("lombok.extern.slf4j.Slf4j", "Slf4j", "org.slf4j.Logger");
        logger("lombok.extern.slf4j.XSlf4j", "XSlf4j", "org.slf4j.ext.XLogger");
        logger("lombok.extern.log4j.Log4j2", "Log4j2", "org.apache.logging.log4j.Logger");
        logger("lombok.extern.log4j.Log4j", "Log4j", "org.apache.log4j.Logger");
        logger("lombok.extern.java.Log", "Log", "java.util.logging.Logger");
        logger("lombok.extern.apachecommons.CommonsLog", "CommonsLog", "org.apache.commons.logging.Log");
        logger("lombok.extern.jbosslog.JBossLog", "JBossLog", "org.jboss.logging.Logger");
        logger("lombok.extern.flogger.Flogger", "Flogger", "com.google.common.flogger.FluentLogger");
    }

    private Lombok() {
    }

    private static void logger(String annotation, String kind, String loggerType) {
        KINDS.put(annotation, kind);
        LOGGERS.put(kind, loggerType);
    }

    /** 점으로 이은 전체 이름이 Lombok 애너테이션인가. */
    static boolean isAnnotation(String qualifiedName) {
        return KINDS.containsKey(qualifiedName);
    }

    /** 로그 애너테이션이면 {@code log} 필드의 타입 FQN, 아니면 null. */
    static String loggerType(String kind) {
        return LOGGERS.get(kind);
    }

    /**
     * 애너테이션이 Lombok의 것이면 그 종류({@code Getter}, {@code Builder.Default}, {@code Slf4j} 등), 아니면 null.
     * 해석된 바인딩이 있으면 그 이름으로, 없으면 단일 import → 전체 이름 → 와일드카드 import({@code lombok.*} 등) 순으로 본다.
     * 프로젝트가 만든 같은 이름의 애너테이션이나 import하지 않은 이름은 Lombok으로 보지 않는다.
     */
    static String kind(Unit unit, Annotation annotation) {
        ITypeBinding binding = annotation.resolveTypeBinding();
        if (binding != null && !binding.isRecovered()) {
            return KINDS.get(binding.getQualifiedName());
        }
        String written = annotation.getTypeName().getFullyQualifiedName();
        if (written.startsWith("lombok.")) {
            return KINDS.get(written);
        }
        int dot = written.indexOf('.');
        String first = dot < 0 ? written : written.substring(0, dot);
        String imported = unit.singleImports.get(first);
        if (imported != null) {
            return KINDS.get(imported + written.substring(first.length()));
        }
        for (String pkg : unit.onDemandImports) {
            String kind = pkg.startsWith("lombok") ? KINDS.get(pkg + "." + written) : null;
            if (kind != null) {
                return kind;
            }
        }
        return null;
    }

    /** {@code AccessLevel} 값의 이름(PUBLIC, PROTECTED, PACKAGE, PRIVATE, MODULE, NONE). 지정이 없으면 PUBLIC. */
    static String accessLevel(Unit.Anno anno, String member) {
        Expression value = anno == null ? null : anno.value(member);
        if (value instanceof Name name) {
            return JavaProject.simpleName(name.getFullyQualifiedName());
        }
        return "PUBLIC";
    }

    /** 접근 수준에 해당하는 수식어 목록. NONE이면 null(만들지 않는다). */
    static List<String> modifiers(String accessLevel) {
        return switch (accessLevel) {
            case "NONE" -> null;
            case "PROTECTED" -> List.of("protected");
            case "PRIVATE" -> List.of("private");
            case "PACKAGE", "MODULE" -> List.of();
            default -> List.of("public");
        };
    }

    static boolean flag(Unit.Anno anno, String member, boolean defaultValue) {
        Expression value = anno == null ? null : anno.value(member);
        if (value instanceof BooleanLiteral literal) {
            return literal.booleanValue();
        }
        return value != null && Constants.eval(value) instanceof Boolean b ? b : defaultValue;
    }

    static String string(Unit.Anno anno, String member, String defaultValue) {
        Expression value = anno == null ? null : anno.value(member);
        String s = value == null ? null : Constants.string(value);
        return s == null ? defaultValue : s;
    }

    // ---- 이름 규칙 (Lombok의 HandlerUtil과 같다) ----

    /** 접두사 + 첫 글자를 대문자로 바꾼 이름. 첫 글자가 소문자가 아니면 그대로 붙인다. */
    static String accessorName(String prefix, String name) {
        if (name.isEmpty() || !Character.isLowerCase(name.charAt(0))) {
            return prefix + name;
        }
        return prefix + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** {@code isRunning}처럼 is로 시작하는 boolean 필드인가. */
    static boolean isPrefixedBoolean(String field, boolean primitiveBoolean) {
        return primitiveBoolean && field.startsWith("is") && field.length() > 2 && !Character.isLowerCase(field.charAt(2));
    }

    /** getter 이름: boolean은 {@code isXxx}, is로 시작하는 boolean은 필드 이름 그대로, fluent면 필드 이름. */
    static String getterName(String field, boolean primitiveBoolean, boolean fluent) {
        if (fluent) {
            return field;
        }
        if (isPrefixedBoolean(field, primitiveBoolean)) {
            return accessorName("is", field.substring(2));
        }
        return accessorName(primitiveBoolean ? "is" : "get", field);
    }

    /** setter·wither 이름: is로 시작하는 boolean은 is를 떼고 접두사를 붙인다. fluent면 필드 이름. */
    static String prefixedName(String prefix, String field, boolean primitiveBoolean, boolean fluent) {
        if (fluent) {
            return field;
        }
        return accessorName(prefix, isPrefixedBoolean(field, primitiveBoolean) ? field.substring(2) : field);
    }

    /** 값 없는 {@code @Singular}의 단수 이름. 영어의 단순한 복수형만 다룬다. 정하지 못하면 null. */
    static String singularize(String plural) {
        if (plural.endsWith("ies") && plural.length() > 3) {
            return plural.substring(0, plural.length() - 3) + "y";
        }
        for (String suffix : List.of("sses", "xes", "ches", "shes", "zes")) {
            if (plural.endsWith(suffix)) {
                return plural.substring(0, plural.length() - 2);
            }
        }
        if (plural.endsWith("s") && !plural.endsWith("ss") && plural.length() > 1) {
            return plural.substring(0, plural.length() - 1);
        }
        return null;
    }
}
