package io.codeatlas.export.classdiagram;

/** 클래스 다이어그램 출력 형식. */
public enum ClassDiagramFormat {
    /** Mermaid {@code classDiagram}. */
    MERMAID("mmd"),
    /** PlantUML class 문법. */
    PLANTUML("puml");

    private final String extension;

    ClassDiagramFormat(String extension) {
        this.extension = extension;
    }

    /** 파일 확장자(점 없음). */
    public String extension() {
        return extension;
    }
}
