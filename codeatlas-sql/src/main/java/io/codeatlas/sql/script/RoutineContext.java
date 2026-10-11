package io.codeatlas.sql.script;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.sql.analysis.Catalog;
import io.codeatlas.sql.analysis.FactExtractor;
import io.codeatlas.sql.analysis.FactsWriter;
import io.codeatlas.sql.analysis.Locator;
import io.codeatlas.sql.analysis.StatementFacts;
import io.codeatlas.sql.ast.Pos;

/** 루틴 단위 핸들러가 받는 실행 환경: 그래프, 카탈로그, 방언, 위치 변환. */
public final class RoutineContext {

    private final GraphModel graph;
    private final Catalog catalog;
    private final Dialect dialect;
    private final String file;
    private final Locator locator;

    private Dialect fallbackDialect;

    public RoutineContext(GraphModel graph, Catalog catalog, Dialect dialect, String file, Locator locator) {
        this.graph = graph;
        this.catalog = catalog;
        this.dialect = dialect;
        this.file = file;
        this.locator = locator;
    }

    public GraphModel graph() {
        return graph;
    }

    /** 모든 DDL에서 만든 카탈로그(테이블·컬럼, 시노님, 패키지, 루틴, 스키마 이름). */
    public Catalog catalog() {
        return catalog;
    }

    /** 파일의 방언을 판정하지 못했을 때 본문을 어느 문법으로 읽을지의 힌트({@code --dialect}). 구문 노드의 dialect로는 적지 않는다. */
    public RoutineContext fallbackDialect(Dialect dialect) {
        this.fallbackDialect = dialect;
        return this;
    }

    public Dialect fallbackDialect() {
        return fallbackDialect;
    }

    /** 이 파일의 방언. 판정하지 못했으면 null. */
    public Dialect dialect() {
        return dialect;
    }

    /** 파일의 상대 경로. */
    public String file() {
        return file;
    }

    /** 토큰 위치(파일 기준)를 그래프 위치로 바꾼다. */
    public SourceLocation location(Pos pos) {
        return locator.locate(pos);
    }

    public Locator locator() {
        return locator;
    }

    /** 이 카탈로그를 쓰는 사실 추출기. 지역 변수는 {@link FactExtractor#variables}로 알려 준다. */
    public FactExtractor extractor() {
        return new FactExtractor(catalog);
    }

    /**
     * 구문 노드(이미 declare 한 것)에 사실을 엣지로 쓰고 statementType·dialect attrs를 채운다.
     * READS/WRITES/JOINS/CALLS와 동적 테이블 처리까지 한다.
     */
    public StatementFacts writeFacts(String statementNodeId, StatementFacts facts) {
        return new FactsWriter(graph, catalog, statementNodeId).add(facts, locator).finish(dialect);
    }
}
