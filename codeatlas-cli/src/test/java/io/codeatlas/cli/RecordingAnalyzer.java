package io.codeatlas.cli;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Analyzer;
import java.util.function.Consumer;

/**
 * CLI 테스트용 분석기. 받은 옵션을 기록하고 노드, 매달린 엣지, 진단을 하나씩 남긴다.
 * 테스트가 {@link #outOfMemoryWhile}이나 {@link #extra}를 걸어 두면 그대로 따른다({@link #reset()}으로 푼다).
 */
public class RecordingAnalyzer implements Analyzer {

    static volatile AnalysisOptions lastOptions;
    /** null이 아니면 이 값을 처리 중이라고 알린 뒤 {@link OutOfMemoryError}를 던진다(실제로 힙을 채우지 않는다). */
    static volatile String outOfMemoryWhile;
    /** null이 아니면 분석 끝에 불러 그래프에 노드, 엣지, 진단을 더 넣게 한다. */
    static volatile Consumer<AnalysisContext> extra;

    static void reset() {
        lastOptions = null;
        outOfMemoryWhile = null;
        extra = null;
    }

    @Override
    public String name() {
        return "cli-test-recording";
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public void analyze(AnalysisContext ctx) {
        lastOptions = ctx.options();
        String activity = outOfMemoryWhile;
        if (activity != null) {
            ctx.progress(activity);
            throw new OutOfMemoryError("Java heap space (테스트)");
        }
        ctx.graph().declare(Node.of("java:type:clitest.Marker", NodeKind.JAVA_TYPE, "Marker"));
        ctx.graph().addEdge(Edge.of("java:type:clitest.Marker", "java:type:clitest.Missing", EdgeKind.EXTENDS));
        ctx.graph().report(Diagnostic.warn("CLI_TEST", "표 | 문자와\n줄바꿈이 든 메시지", null));
        Consumer<AnalysisContext> more = extra;
        if (more != null) {
            more.accept(ctx);
        }
    }
}
