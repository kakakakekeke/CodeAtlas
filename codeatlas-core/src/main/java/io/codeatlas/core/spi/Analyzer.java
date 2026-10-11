package io.codeatlas.core.spi;

/**
 * 분석기. 프로젝트 파일을 읽어 그래프에 노드와 엣지를 쓴다.
 * {@code META-INF/services/io.codeatlas.core.spi.Analyzer}에 구현 클래스 이름을 적어 등록한다. 인자 없는 public 생성자가 있어야 한다.
 */
public interface Analyzer {

    /** 내장 소스를 만드는 분석기(생산자)의 표준 순서. */
    int ORDER_PRODUCER = 100;
    /** 내장 소스를 꺼내 분석하는 분석기(소비자)의 표준 순서. */
    int ORDER_CONSUMER = 200;

    /** 진단과 로그에 쓰는 이름. */
    String name();

    /** 실행 순서. 작은 값이 먼저다. 생산자는 100, 소비자는 200. */
    default int order() {
        return ORDER_PRODUCER;
    }

    /**
     * 프로젝트 단위로 한 번 불린다. 파일별 실패는 스스로 잡아 {@code ctx.graph().report(...)}로 남기고 다음 파일로 간다.
     * 여기서 던진 예외는 파이프라인이 ERROR 진단으로 남기고 다음 분석기를 실행한다.
     */
    void analyze(AnalysisContext ctx) throws Exception;
}
