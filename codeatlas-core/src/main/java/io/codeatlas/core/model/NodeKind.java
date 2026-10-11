package io.codeatlas.core.model;

import java.util.List;
import java.util.Optional;

/** 노드 종류. 각 종류의 ID 접두사는 그래프 계약(설계 3.3)을 따른다. */
public enum NodeKind {
    FILE("file"),
    JAVA_PACKAGE("java:package"),
    JAVA_TYPE("java:type"),
    JAVA_METHOD("java:method"),
    JAVA_FIELD("java:field"),
    SPRING_BEAN("spring:bean"),
    HTTP_ENDPOINT("http:endpoint"),
    HTTP_REQUEST("http:request"),
    BATCH_JOB("batch:job"),
    BATCH_STEP("batch:step"),
    MYBATIS_MAPPER("mybatis:mapper"),
    SQL_STATEMENT("mybatis:stmt", "sql:stmt"),
    SQL_FRAGMENT("mybatis:fragment"),
    DB_TABLE("db:table"),
    DB_VIEW("db:view"),
    DB_COLUMN("db:column"),
    DB_ROUTINE("db:routine"),
    DB_PACKAGE("db:package"),
    DB_SEQUENCE("db:sequence"),
    DB_SYNONYM("db:synonym"),
    JS_MODULE("js:module"),
    JS_FUNCTION("js:function"),
    JSP_PAGE("jsp:page"),
    UNRESOLVED("unresolved");

    private final List<String> idPrefixes;

    NodeKind(String... idPrefixes) {
        this.idPrefixes = List.of(idPrefixes);
    }

    /** 이 종류의 노드 ID가 가질 수 있는 접두사(끝의 콜론 제외). 예: {@code db:table}. */
    public List<String> idPrefixes() {
        return idPrefixes;
    }

    /** 대표 접두사. */
    public String idPrefix() {
        return idPrefixes.get(0);
    }

    /** ID의 접두사로 종류를 알아낸다. 계약에 없는 접두사면 비어 있다. */
    public static Optional<NodeKind> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (NodeKind kind : values()) {
            for (String prefix : kind.idPrefixes) {
                if (id.length() > prefix.length() && id.charAt(prefix.length()) == ':' && id.startsWith(prefix)) {
                    return Optional.of(kind);
                }
            }
        }
        return Optional.empty();
    }
}
