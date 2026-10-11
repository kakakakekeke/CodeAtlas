package io.codeatlas.core.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 그래프 계약(설계 3.3)의 노드 ID를 만드는 헬퍼. 모든 모듈은 ID 문자열을 직접 조립하지 말고 이 클래스를 쓴다.
 *
 * <p>이름의 대소문자: Java·JS는 원문 그대로, DB 객체는 대문자로 정규화한다(큰따옴표로 감싼 식별자는 원문 그대로).
 */
public final class Ids {

    /** 생성자의 메서드명. */
    public static final String CONSTRUCTOR_NAME = "<init>";
    /** 잡을 모르는 스텝의 잡 자리. */
    public static final String UNKNOWN_JOB = "?";
    /** HTTP 메서드 지정이 없을 때. */
    public static final String ANY_METHOD = "ANY";

    private Ids() {
    }

    // ---- 파일 ----

    /** {@code file:<상대경로>} */
    public static String file(String relativePath) {
        return "file:" + path(relativePath);
    }

    // ---- Java ----

    /** {@code java:package:<패키지명>} */
    public static String javaPackage(String packageName) {
        return "java:package:" + packageName;
    }

    /** {@code java:type:<바이너리 이름>} 중첩 타입은 {@code Outer$Inner}. */
    public static String javaType(String binaryName) {
        return "java:type:" + binaryName;
    }

    /**
     * {@code java:method:<타입 바이너리 이름>#<메서드명>(<매개변수 타입 erasure의 바이너리 이름, 쉼표 구분>)}.
     * 배열은 {@code []} 접미사. 매개변수 타입 안의 공백은 지운다.
     */
    public static String javaMethod(String typeBinaryName, String methodName, List<String> parameterTypes) {
        String params = parameterTypes.stream().map(p -> p.replaceAll("\\s+", "")).collect(Collectors.joining(","));
        return "java:method:" + typeBinaryName + "#" + methodName + "(" + params + ")";
    }

    public static String javaMethod(String typeBinaryName, String methodName, String... parameterTypes) {
        return javaMethod(typeBinaryName, methodName, Arrays.asList(parameterTypes));
    }

    /** 생성자. 메서드명은 {@code <init>}. */
    public static String javaConstructor(String typeBinaryName, List<String> parameterTypes) {
        return javaMethod(typeBinaryName, CONSTRUCTOR_NAME, parameterTypes);
    }

    public static String javaConstructor(String typeBinaryName, String... parameterTypes) {
        return javaMethod(typeBinaryName, CONSTRUCTOR_NAME, Arrays.asList(parameterTypes));
    }

    /** {@code java:field:<타입 바이너리 이름>#<필드명>} */
    public static String javaField(String typeBinaryName, String fieldName) {
        return "java:field:" + typeBinaryName + "#" + fieldName;
    }

    // ---- Spring, HTTP, 배치 ----

    /** {@code spring:bean:<빈 이름>} */
    public static String springBean(String beanName) {
        return "spring:bean:" + beanName;
    }

    /**
     * {@code http:endpoint:<METHOD> <경로 패턴>}. METHOD는 대문자, null이나 빈 값이면 {@code ANY}.
     * 경로는 {@link #normalizePath(String)}로 정규화한다.
     */
    public static String httpEndpoint(String httpMethod, String pathPattern) {
        return "http:endpoint:" + httpMethod(httpMethod) + " " + normalizePath(pathPattern);
    }

    /** {@code http:request:<METHOD> <URL 패턴>@<파일>:<줄>}. URL의 동적 부분은 호출하는 쪽이 {@code {*}}로 바꿔 준다. */
    public static String httpRequest(String httpMethod, String urlPattern, String file, int line) {
        return "http:request:" + httpMethod(httpMethod) + " " + urlPattern + "@" + path(file) + ":" + line;
    }

    /** HTTP 메서드를 대문자로. null이나 빈 값이면 {@code ANY}. */
    public static String httpMethod(String httpMethod) {
        return httpMethod == null || httpMethod.isBlank() ? ANY_METHOD : httpMethod.trim().toUpperCase(Locale.ROOT);
    }

    /** 경로를 {@code /}로 시작하게 하고 겹친 {@code /}를 하나로 줄인다. 경로 변수 {@code {name}}은 그대로 둔다. */
    public static String normalizePath(String path) {
        String p = path == null ? "" : path.trim();
        p = ("/" + p).replaceAll("/{2,}", "/");
        return p;
    }

    /** {@code batch:job:<잡 이름>} */
    public static String batchJob(String jobName) {
        return "batch:job:" + jobName;
    }

    /** {@code batch:step:<잡 이름>/<스텝 이름>}. 잡을 모르면(null, 빈 값) {@code batch:step:?/<스텝 이름>}. */
    public static String batchStep(String jobName, String stepName) {
        return "batch:step:" + (jobName == null || jobName.isBlank() ? UNKNOWN_JOB : jobName) + "/" + stepName;
    }

    // ---- MyBatis, SQL 구문 ----

    /** {@code mybatis:mapper:<namespace>} */
    public static String mybatisMapper(String namespace) {
        return "mybatis:mapper:" + namespace;
    }

    /** 매퍼 구문 {@code mybatis:stmt:<namespace>.<id>}. 애너테이션 SQL은 namespace에 인터페이스 바이너리 이름, id에 메서드명을 준다. */
    public static String mybatisStatement(String namespace, String statementId) {
        return "mybatis:stmt:" + namespace + "." + statementId;
    }

    /** {@code mybatis:fragment:<namespace>.<id>} */
    public static String mybatisFragment(String namespace, String fragmentId) {
        return "mybatis:fragment:" + namespace + "." + fragmentId;
    }

    /** SQL 파일의 구문 {@code sql:stmt:<파일>:<시작 줄>} */
    public static String sqlStatement(String file, int startLine) {
        return "sql:stmt:" + path(file) + ":" + startLine;
    }

    /** 루틴 안의 구문 {@code sql:stmt:<루틴 이름>:<시작 줄>}. 루틴 ID({@code db:routine:PKG.P})를 줘도 되고 이름({@code PKG.P})만 줘도 된다. */
    public static String routineStatement(String routineIdOrName, int startLine) {
        String name = routineIdOrName.startsWith("db:routine:") ? routineIdOrName.substring("db:routine:".length()) : routineIdOrName;
        return "sql:stmt:" + name + ":" + startLine;
    }

    // ---- DB 객체 ----

    /** 식별자 하나를 정규화한다. 따옴표가 없으면 대문자, 큰따옴표로 감쌌으면 따옴표만 벗긴다. */
    public static String dbName(String identifier) {
        return DbName.normalize(identifier);
    }

    /** {@code db:table:<이름>}. {@code schema.name}을 주면 스키마를 뺀다. */
    public static String table(String name) {
        return "db:table:" + DbName.parse(name).name();
    }

    /** {@code db:view:<이름>} */
    public static String view(String name) {
        return "db:view:" + DbName.parse(name).name();
    }

    /** {@code db:column:<테이블 또는 뷰 이름>.<컬럼명>}. 테이블 이름의 스키마는 뺀다. */
    public static String column(String tableName, String columnName) {
        return "db:column:" + DbName.parse(tableName).name() + "." + DbName.normalize(columnName);
    }

    /** 패키지에 속하지 않은 루틴 {@code db:routine:<이름>}. {@code schema.name}을 주면 스키마를 뺀다. */
    public static String routine(String name) {
        return "db:routine:" + DbName.parse(name).name();
    }

    /** 패키지 소속 루틴 {@code db:routine:<패키지>.<이름>}. packageName이 null이나 빈 값이면 {@link #routine(String)}과 같다. */
    public static String routine(String packageName, String name) {
        if (packageName == null || packageName.isBlank()) {
            return routine(name);
        }
        return "db:routine:" + DbName.parse(packageName).name() + "." + DbName.normalize(name);
    }

    /** {@code db:package:<이름>} */
    public static String dbPackage(String name) {
        return "db:package:" + DbName.parse(name).name();
    }

    /** {@code db:sequence:<이름>} */
    public static String sequence(String name) {
        return "db:sequence:" + DbName.parse(name).name();
    }

    /** {@code db:synonym:<이름>} */
    public static String synonym(String name) {
        return "db:synonym:" + DbName.parse(name).name();
    }

    // ---- 화면 ----

    /** {@code js:module:<상대경로>}. JSP 안의 인라인 스크립트는 JSP 경로를 그대로 쓴다. */
    public static String jsModule(String relativePath) {
        return "js:module:" + path(relativePath);
    }

    /** {@code js:function:<상대경로>#<이름 경로>}. 이름 경로는 바깥에서 안으로 {@code .}으로 잇는다. */
    public static String jsFunction(String relativePath, String namePath) {
        return "js:function:" + path(relativePath) + "#" + namePath;
    }

    /** 이름 없는 함수의 이름 조각 {@code <anonymous>@<줄>:<열>}. */
    public static String jsAnonymous(int line, int column) {
        return "<anonymous>@" + line + ":" + column;
    }

    /** {@code jsp:page:<상대경로>} */
    public static String jspPage(String relativePath) {
        return "jsp:page:" + path(relativePath);
    }

    // ---- 미해결 ----

    /** {@code unresolved:<분류>:<원문>} */
    public static String unresolved(String category, String text) {
        return "unresolved:" + category + ":" + text;
    }

    // ---- ID 해석 ----

    /**
     * ID의 접두사(끝의 콜론 제외). 계약에 있는 접두사면 그것을, 아니면 콜론 두 개까지(없으면 첫 콜론까지)를 돌려준다.
     * 예: {@code java:method:com.a.B#x()} → {@code java:method}.
     */
    public static String prefixOf(String id) {
        Optional<NodeKind> kind = NodeKind.fromId(id);
        if (kind.isPresent()) {
            for (String prefix : kind.get().idPrefixes()) {
                if (id.startsWith(prefix + ":")) {
                    return prefix;
                }
            }
        }
        int first = id.indexOf(':');
        if (first < 0) {
            return id;
        }
        int second = id.indexOf(':', first + 1);
        return second < 0 ? id.substring(0, first) : id.substring(0, second);
    }

    /** 접두사 뒤의 나머지. 예: {@code db:table:ORDERS} → {@code ORDERS}. */
    public static String remainder(String id) {
        String prefix = prefixOf(id);
        return prefix.length() >= id.length() ? id : id.substring(prefix.length() + 1);
    }

    /**
     * 마무리 단계가 매달린 엣지의 끝점으로 만든 UNRESOLVED 노드의 분류(설계 3.6). 엣지가 가리키던 ID의 접두사에서 유도한다:
     * {@code mybatis:stmt} → {@code mybatis-statement}, {@code java:method} → {@code java-call}, {@code java:type} → {@code java-type},
     * {@code db:routine} → {@code db-routine}, {@code spring:bean} → {@code spring-bean-ref}, {@code js:module} → {@code js-module},
     * {@code js:function} → {@code js-call}, {@code jsp:page} → {@code jsp-include}, {@code http:endpoint} → {@code http-endpoint}.
     * 그 밖에는 접두사의 {@code :}을 {@code -}로 바꾼 값이다({@code mybatis:fragment} → {@code mybatis-fragment}, {@code db:table} → {@code db-table}).
     *
     * @param expectedPrefix {@link #prefixOf(String)}가 돌려준 접두사(끝의 콜론 없음). null이면 null
     */
    public static String danglingCategory(String expectedPrefix) {
        if (expectedPrefix == null) {
            return null;
        }
        return switch (expectedPrefix) {
            case "mybatis:stmt" -> "mybatis-statement";
            case "java:method" -> "java-call";
            case "java:type" -> "java-type";
            case "db:routine" -> "db-routine";
            case "spring:bean" -> "spring-bean-ref";
            case "js:module" -> "js-module";
            case "js:function" -> "js-call";
            case "jsp:page" -> "jsp-include";
            case "http:endpoint" -> "http-endpoint";
            default -> expectedPrefix.replace(':', '-');
        };
    }

    /** ID의 접두사로 노드 종류를 알아낸다. */
    public static Optional<NodeKind> kindOf(String id) {
        return NodeKind.fromId(id);
    }

    private static String path(String relativePath) {
        String p = relativePath.replace('\\', '/');
        return p.startsWith("./") ? p.substring(2) : p;
    }
}
