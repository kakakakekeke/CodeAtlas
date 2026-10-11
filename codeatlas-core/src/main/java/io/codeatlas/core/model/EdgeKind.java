package io.codeatlas.core.model;

/** 엣지 종류. from → to의 뜻은 그래프 계약(설계 3.4)을 따른다. */
public enum EdgeKind {
    /** 포함. FILE→최상위 선언, TYPE→멤버, TABLE→COLUMN, MAPPER→STATEMENT 등. */
    CONTAINS,
    /** TYPE→TYPE 상속. */
    EXTENDS,
    /** TYPE→TYPE 인터페이스 구현. */
    IMPLEMENTS,
    /** TYPE→TYPE 필드로 가진 타입. attrs: field, multiplicity. */
    ASSOCIATES,
    /** TYPE→TYPE 시그니처·본문에서 쓰는 타입. */
    DEPENDS_ON,
    /** METHOD→METHOD 재정의·구현. */
    OVERRIDES,
    /** 호출. attrs: callType. */
    CALLS,
    /** METHOD→TYPE new. */
    INSTANTIATES,
    /** METHOD→FIELD 필드 접근. attrs: access(read, write). */
    ACCESSES,
    /** TYPE/METHOD/FIELD→TYPE 애너테이션. */
    ANNOTATED_BY,
    /** TYPE 또는 METHOD→SPRING_BEAN 빈 정의. */
    DEFINES_BEAN,
    /** TYPE→TYPE 의존성 주입. attrs: via, qualifier. */
    INJECTS,
    /** HTTP_ENDPOINT→METHOD 요청 매핑. */
    ROUTES_TO,
    /** METHOD→JSP_PAGE 뷰 이름으로 JSP를 가리킴. */
    RENDERS,
    /** JS_FUNCTION/JS_MODULE/JSP_PAGE→HTTP_REQUEST 요청 지점. */
    REQUESTS,
    /** HTTP_REQUEST→HTTP_ENDPOINT 요청 URL과 엔드포인트 대응. */
    MATCHES,
    /** JS_MODULE→JS_MODULE import, require. */
    IMPORTS,
    /** 구문·프래그먼트→프래그먼트, JSP→JSP, JSP→JS_MODULE 포함. */
    INCLUDES,
    /** METHOD→SQL_STATEMENT/ROUTINE, BATCH_STEP→SQL_STATEMENT 실행. */
    EXECUTES,
    /** SQL_STATEMENT→JAVA_TYPE parameterType, resultType 등. attrs: role. */
    USES_TYPE,
    /** SQL_STATEMENT→DB_TABLE 또는 DB_COLUMN 조회. */
    READS,
    /** SQL_STATEMENT→DB_TABLE 또는 DB_COLUMN 변경. attrs: op. */
    WRITES,
    /** DB_TABLE→DB_TABLE 한 구문 안의 등치 조건. attrs: leftColumns, rightColumns, statement. */
    JOINS,
    /** DB_TABLE→DB_TABLE (자식→부모) 외래 키. attrs: columns, refColumns, constraint, source, evidenceCount. */
    REFERENCES,
    /** DB_SYNONYM→대상 객체. */
    ALIAS_OF,
    /** BATCH_STEP→TYPE/BEAN/METHOD 스텝 구성 요소. attrs: role. */
    USES,
    /** BATCH_STEP→BATCH_STEP 스텝 흐름. attrs: on. */
    NEXT,
    /** ROUTINE(트리거)→DB_TABLE 트리거 대상. */
    TRIGGERS_ON
}
