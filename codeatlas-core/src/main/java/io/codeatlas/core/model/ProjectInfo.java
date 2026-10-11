package io.codeatlas.core.model;

import io.codeatlas.core.spi.AnalysisOptions;
import java.time.Instant;

/**
 * graph.json의 project 항목. name은 프로젝트 폴더 이름, root는 분석한 절대 경로.
 *
 * @param options 분석할 때 준 옵션(방언, 인코딩, classpath·DDL 경로, 제외 패턴, {@code --opt}). 경로는 입력 그대로다.
 *                주지 않으면(null) {@link AnalysisOptions#defaults()}다
 */
public record ProjectInfo(String name, String root, Instant analyzedAt, AnalysisOptions options) {

    public ProjectInfo {
        options = options == null ? AnalysisOptions.defaults() : options;
    }

    /** 옵션 없이 만든다. {@code options()}는 빈 옵션이다. */
    public ProjectInfo(String name, String root, Instant analyzedAt) {
        this(name, root, analyzedAt, null);
    }
}
