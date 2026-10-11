package io.codeatlas.core.spi;

/** 스캐너가 붙이는 파일 종류(설계 4.2). */
public enum FileType {
    /** {@code .java} */
    JAVA,
    /** 루트 요소가 {@code mapper}인 XML */
    MYBATIS_MAPPER,
    /** 루트 요소가 {@code beans}인 XML */
    SPRING_XML,
    /** 그 밖의 XML */
    XML,
    /** {@code .sql .ddl .pks .pkb .pls .plb .prc .fnc .trg .db2 .spsql}, 그리고 {@code --ddl}로 직접 준 파일 */
    SQL,
    /** {@code .js .mjs .cjs .jsx} */
    JS,
    /** {@code .ts .tsx} ({@code .d.ts} 제외) */
    TS,
    /** {@code .jsp .jspf .tag} */
    JSP,
    /** {@code .html .htm} */
    HTML,
    /** {@code .properties} */
    PROPERTIES,
    /** {@code .yml .yaml} */
    YAML,
    /** {@code .jar} */
    JAR,
    OTHER
}
