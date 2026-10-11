package io.codeatlas.core.spi;

/**
 * 링커. 모든 분석기가 끝난 뒤 그래프만 보고 엣지를 더한다.
 * {@code META-INF/services/io.codeatlas.core.spi.Linker}에 등록한다.
 */
public interface Linker {

    String name();

    /** 실행 순서. 작은 값이 먼저다. */
    default int order() {
        return 100;
    }

    void link(AnalysisContext ctx) throws Exception;
}
