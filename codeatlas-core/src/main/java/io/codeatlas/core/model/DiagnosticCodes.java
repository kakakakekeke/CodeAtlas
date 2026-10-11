package io.codeatlas.core.model;

/** 진단 코드. 모듈이 새 코드를 만들 때는 대문자와 밑줄로 쓴다. */
public final class DiagnosticCodes {
    /** 파일 또는 구문을 파싱하지 못함. */
    public static final String PARSE_FAILED = "PARSE_FAILED";
    /** 지원하지 않는 구문을 건너뜀. */
    public static final String UNSUPPORTED_SYNTAX = "UNSUPPORTED_SYNTAX";
    /** 해석하지 못한 참조. */
    public static final String UNRESOLVED_REFERENCE = "UNRESOLVED_REFERENCE";
    /** 동적 SQL이라 대상을 알 수 없음. */
    public static final String DYNAMIC_SQL = "DYNAMIC_SQL";
    /** 같은 이름의 DB 객체가 여러 번(여러 스키마에) 정의됨. */
    public static final String DUPLICATE_OBJECT_NAME = "DUPLICATE_OBJECT_NAME";
    /** 분석기가 예외로 끝남. */
    public static final String ANALYZER_FAILED = "ANALYZER_FAILED";
    /** 링커가 예외로 끝남. */
    public static final String LINKER_FAILED = "LINKER_FAILED";
    /** 출력기가 예외로 끝남. */
    public static final String EXPORTER_FAILED = "EXPORTER_FAILED";
    /** ServiceLoader로 구현체를 만들지 못함. */
    public static final String SERVICE_LOAD_FAILED = "SERVICE_LOAD_FAILED";
    /** 기본 제외 디렉터리라 스캔하지 않음. */
    public static final String EXCLUDED_DIRECTORY = "EXCLUDED_DIRECTORY";
    /** 옵션으로 준 경로가 없음. */
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    /** 파일 또는 디렉터리를 읽지 못함. */
    public static final String FILE_NOT_READABLE = "FILE_NOT_READABLE";
    /** 옵션 값이 잘못됨. */
    public static final String INVALID_OPTION = "INVALID_OPTION";

    private DiagnosticCodes() {
    }
}
