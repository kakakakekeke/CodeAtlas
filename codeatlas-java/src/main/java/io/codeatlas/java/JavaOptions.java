package io.codeatlas.java;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.spi.AnalysisOptions;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Java 분석기 옵션. {@code AnalysisOptions.extra()}의 {@code java.*} 키에서 읽는다.
 *
 * @param includeJdkTypes {@code java.includeJdkTypes}: JDK 타입으로의 ASSOCIATES/DEPENDS_ON도 만든다
 * @param externalCalls {@code java.externalCalls}: 소스 없는 타입의 메서드 호출을 어디까지 기록할지
 * @param sqlSessionTypes MyBatis SqlSession 계열로 볼 타입의 단순 이름({@code java.sqlSessionTypes}로 추가)
 */
record JavaOptions(boolean includeJdkTypes, ExternalCalls externalCalls, Set<String> sqlSessionTypes) {

    static final String KEY_INCLUDE_JDK_TYPES = "java.includeJdkTypes";
    static final String KEY_EXTERNAL_CALLS = "java.externalCalls";
    static final String KEY_SQL_SESSION_TYPES = "java.sqlSessionTypes";

    /** 외부 호출 기록 범위. */
    enum ExternalCalls { NONE, NON_JDK, ALL }

    static JavaOptions from(AnalysisOptions options, GraphModel graph) {
        Map<String, String> extra = options.extra();
        boolean jdkTypes = Boolean.parseBoolean(extra.getOrDefault(KEY_INCLUDE_JDK_TYPES, "false").trim());
        ExternalCalls calls = ExternalCalls.NON_JDK;
        String raw = extra.get(KEY_EXTERNAL_CALLS);
        if (raw != null) {
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "none" -> calls = ExternalCalls.NONE;
                case "nonjdk" -> calls = ExternalCalls.NON_JDK;
                case "all" -> calls = ExternalCalls.ALL;
                default -> graph.report(Diagnostic.warn(DiagnosticCodes.INVALID_OPTION,
                        KEY_EXTERNAL_CALLS + " 값은 none, nonJdk, all 중 하나여야 한다: " + raw, null));
            }
        }
        Set<String> sessionTypes = new LinkedHashSet<>(Set.of(
                "SqlSession", "SqlSessionTemplate", "SqlSessionManager", "DefaultSqlSession", "EgovAbstractMapper"));
        String more = extra.get(KEY_SQL_SESSION_TYPES);
        if (more != null) {
            for (String name : more.split(",")) {
                String n = name.trim();
                if (!n.isEmpty()) {
                    sessionTypes.add(n.substring(n.lastIndexOf('.') + 1));
                }
            }
        }
        return new JavaOptions(jdkTypes, calls, Set.copyOf(sessionTypes));
    }
}
