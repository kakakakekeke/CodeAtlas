package io.codeatlas.web;

import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.web.analysis.JsProjectAnalysis;
import io.codeatlas.web.syntax.JsParser;
import io.codeatlas.web.syntax.TreeSitterJsParser;
import java.util.function.Supplier;

/**
 * JavaScript·TypeScript 분석기(F-010). JS·TS 파일과 {@code js} 내장 소스(JSP 인라인 스크립트)에서
 * JS_MODULE, JS_FUNCTION, HTTP_REQUEST 노드와 CONTAINS, IMPORTS, CALLS, REQUESTS 엣지를 만든다.
 *
 * <p>내장 소스의 소비자이므로 생산자(jsp, order 100) 뒤인 order 200에서 돈다.
 */
public final class JsAnalyzer implements Analyzer {

    private final Supplier<JsParser> parserFactory;

    public JsAnalyzer() {
        this(TreeSitterJsParser::new);
    }

    /** 파서를 바꿔 끼운다(테스트, 대체 구현). */
    public JsAnalyzer(Supplier<JsParser> parserFactory) {
        this.parserFactory = parserFactory;
    }

    @Override
    public String name() {
        return "web";
    }

    @Override
    public int order() {
        return ORDER_CONSUMER;
    }

    @Override
    public void analyze(AnalysisContext ctx) {
        new JsProjectAnalysis(ctx, parserFactory.get()).run();
    }
}
