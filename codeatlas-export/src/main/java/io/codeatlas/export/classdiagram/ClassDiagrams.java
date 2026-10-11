package io.codeatlas.export.classdiagram;

import io.codeatlas.core.model.GraphModel;

/**
 * 클래스 다이어그램 조회 API의 입구.
 *
 * <pre>{@code
 * String puml = ClassDiagrams.render(graph, ClassDiagramOptions.builder().packagePrefix("com.shop.order").build(),
 *         ClassDiagramFormat.PLANTUML);
 * }</pre>
 */
public final class ClassDiagrams {

    private ClassDiagrams() {
    }

    public static ClassDiagramModel model(GraphModel graph, ClassDiagramOptions options) {
        return ClassDiagramBuilder.build(graph, options);
    }

    /** 그래프를 지정한 형식의 클래스 다이어그램 텍스트로 만든다. 줄 끝은 {@code \n}이다. */
    public static String render(GraphModel graph, ClassDiagramOptions options, ClassDiagramFormat format) {
        ClassDiagramOptions opts = options == null ? ClassDiagramOptions.defaults() : options;
        return render(model(graph, opts), opts, format);
    }

    public static String render(ClassDiagramModel model, ClassDiagramOptions options, ClassDiagramFormat format) {
        ClassDiagramOptions opts = options == null ? ClassDiagramOptions.defaults() : options;
        return switch (format) {
            case MERMAID -> MermaidClassRenderer.render(model, opts);
            case PLANTUML -> PlantUmlClassRenderer.render(model, opts);
        };
    }
}
