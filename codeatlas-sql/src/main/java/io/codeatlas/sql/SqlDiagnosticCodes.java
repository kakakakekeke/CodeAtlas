package io.codeatlas.sql;

/** sql 모듈이 정의한 진단 코드(core의 {@code DiagnosticCodes}에 없는 것). */
public final class SqlDiagnosticCodes {

    /** INFO: SQL 스크립트 파일의 데이터 적재 INSERT를 테이블별 구문 노드 하나로 묶음(파일마다 1건, F-006-R15). */
    public static final String DATA_LOAD_GROUPED = "DATA_LOAD_GROUPED";

    /** INFO: upgrade·patch·migration 스크립트의 ALTER TABLE을 현재 DDL에 적용하지 않음(파일마다 1건, F-007-R15). */
    public static final String DDL_NOT_APPLIED = "DDL_NOT_APPLIED";

    /**
     * INFO: 같은 테이블의 DDL이 방언별 파일로 여러 벌 있어 한 방언을 골랐다(분석 한 번에 1건, 위치 없음, F-007-R19).
     * 메시지에 감지한 방언, 쓴 방언과 테이블 수, 다른 방언에서 보충한 테이블 수, 제외한 파일 수, 바꾸는 옵션이 있다.
     */
    public static final String DIALECT_VARIANT_DDL = "DIALECT_VARIANT_DDL";

    /**
     * WARN: 요청한 방언({@code --dialect}, {@code mybatis.dialectVariant})의 파일이 하나도 없어 다른 방언으로 대체했다(F-007-R19).
     * mybatis 모듈도 방언별 매퍼에 대해 같은 코드를 쓴다(F-009-R20).
     */
    public static final String DIALECT_NOT_FOUND = "DIALECT_NOT_FOUND";

    /**
     * INFO: 비한정 컬럼이 스코프의 선언된 테이블 어느 DDL에도 없어 소속 테이블을 정하지 못했다(주인 구문마다 1건, F-006-R31).
     * 메시지에 컬럼 이름(중복 없이)과 후보 테이블, 주인 구문 ID가 있다. 컬럼 엣지는 만들지 않는다.
     */
    public static final String UNKNOWN_COLUMN = "UNKNOWN_COLUMN";

    private SqlDiagnosticCodes() {
    }

    /**
     * 방언별 여러 벌은 아니지만 프로젝트의 SQL 스크립트가 대상 외 방언(MySQL 덤프 등)으로만 돼 있다(INFO, 분석 한 번에 1건, F-007-R25).
     * 대상 방언(Oracle, DB2)이 아니어서 일부 구문을 해석하지 못할 수 있다는 안내다.
     */
    public static final String DIALECT_NOT_TARGET = "DIALECT_NOT_TARGET";
}
