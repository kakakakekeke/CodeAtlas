package io.codeatlas.core.pipeline;

import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;

/** META-INF/services로 등록해 ServiceLoader 탐색을 확인하는 테스트용 분석기. */
public class DiscoveredTestAnalyzer implements Analyzer {
    @Override
    public String name() {
        return "discovered-test";
    }

    @Override
    public void analyze(AnalysisContext ctx) {
        ctx.graph().declare(Node.of("java:type:discovered.Marker", NodeKind.JAVA_TYPE, "Marker"));
    }
}
