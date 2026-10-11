package io.codeatlas.web.syntax;

/**
 * JavaScript 계열 소스를 구문 트리로 바꾸는 파서. 구현을 바꿔 끼울 수 있게 분석 로직과 떼어 놓았다(ADR-0005).
 */
public interface JsParser {

    /**
     * 소스를 파싱한다. 구문 오류가 있어도 예외를 던지지 않고 오류 노드가 든 트리를 돌려준다.
     *
     * @throws ParserUnavailableException 파서를 쓸 수 없는 환경일 때(네이티브 라이브러리를 올리지 못함)
     */
    SyntaxTree parse(String source, JsDialect dialect);
}
