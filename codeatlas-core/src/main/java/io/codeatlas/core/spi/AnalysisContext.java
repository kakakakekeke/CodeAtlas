package io.codeatlas.core.spi;

import io.codeatlas.core.model.GraphModel;
import java.nio.file.Path;
import java.util.List;

/** 분석기와 링커가 받는 실행 컨텍스트. 스레드 안전하다. */
public interface AnalysisContext {

    /** 분석 대상 프로젝트의 루트(절대 경로). */
    Path projectRoot();

    /** 스캔한 파일. {@code files().ofType(FileType.JAVA)}처럼 골라 쓴다. */
    ProjectFiles files();

    /** 결과를 쓰는 그래프. 진단도 여기에 {@code graph().report(...)}로 남긴다. */
    GraphModel graph();

    AnalysisOptions options();

    /** 내장 소스를 등록한다(생산자). 주인 노드는 먼저 declare 해 둔다. */
    void addEmbedded(EmbeddedSource source);

    /** 지금까지 등록된 해당 언어의 내장 소스(등록한 순서, 복사본). 소비자는 생산자보다 뒤의 order에서 부른다. */
    List<EmbeddedSource> embedded(String language);

    /**
     * 지금 처리 중인 것(보통 파일의 상대 경로)을 알린다. 분석기·링커가 파일 하나를 시작할 때 부른다.
     * 분석 중 메모리가 모자라면 파이프라인이 이 값을 안내에 넣는다({@code AnalysisOutOfMemoryError}).
     * 값 하나를 덮어쓰기만 하므로 파일마다 불러도 된다. 기본 구현은 아무것도 하지 않는다(이 훅을 모르는 구현체도 그대로 동작한다).
     *
     * @param activity 처리 중인 것. null이면 지운다
     */
    default void progress(String activity) {
    }

    /** {@link #progress(String)}로 마지막에 알린 값. 알린 적이 없거나 구현체가 기록하지 않으면 null. */
    default String currentActivity() {
        return null;
    }

    /** 기본 구현을 만든다. 테스트에서는 {@code io.codeatlas.core.testing.TestProject}가 더 편하다. */
    static AnalysisContext of(Path projectRoot, ProjectFiles files, GraphModel graph, AnalysisOptions options) {
        return new DefaultAnalysisContext(projectRoot, files, graph, options);
    }
}
