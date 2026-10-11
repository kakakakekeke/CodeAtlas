package io.codeatlas.web.syntax;

/** 파서를 이 환경에서 쓸 수 없을 때(지원하지 않는 OS·CPU, 네이티브 라이브러리 로드 실패). */
public class ParserUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ParserUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
