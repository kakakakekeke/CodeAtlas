package io.codeatlas.export.erd;

import io.codeatlas.core.model.GraphModel;

/**
 * ERD 조회 API의 입구.
 *
 * <pre>{@code
 * String mermaid = Erd.render(graph, ErdOptions.builder().tables("ORDERS").neighborDepth(1).build(), ErdFormat.MERMAID);
 * }</pre>
 */
public final class Erd {

    private Erd() {
    }

    /** 그래프에서 옵션의 범위에 드는 ERD 중간 모델을 만든다. */
    public static ErdModel model(GraphModel graph, ErdOptions options) {
        return ErdModelBuilder.build(graph, options);
    }

    /** 그래프를 지정한 형식의 ERD 텍스트로 만든다. 줄 끝은 {@code \n}이다. */
    public static String render(GraphModel graph, ErdOptions options, ErdFormat format) {
        ErdOptions opts = options == null ? ErdOptions.defaults() : options;
        return render(model(graph, opts), opts, format);
    }

    /** 이미 만든 모델을 렌더링한다. */
    public static String render(ErdModel model, ErdOptions options, ErdFormat format) {
        ErdOptions opts = options == null ? ErdOptions.defaults() : options;
        return switch (format) {
            case MERMAID -> MermaidErdRenderer.render(model, opts);
            case PLANTUML -> PlantUmlErdRenderer.render(model, opts);
            case DBML -> DbmlErdRenderer.render(model, opts);
        };
    }
}
