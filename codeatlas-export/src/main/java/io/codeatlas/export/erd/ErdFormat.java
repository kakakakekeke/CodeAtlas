package io.codeatlas.export.erd;

/** ERD 출력 형식. */
public enum ErdFormat {
    /** Mermaid {@code erDiagram}. */
    MERMAID("mmd"),
    /** PlantUML entity 문법. */
    PLANTUML("puml"),
    /** DBML(dbdiagram.io). */
    DBML("dbml");

    private final String extension;

    ErdFormat(String extension) {
        this.extension = extension;
    }

    /** 파일 확장자(점 없음). */
    public String extension() {
        return extension;
    }
}
