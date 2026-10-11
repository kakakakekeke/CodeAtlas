package io.codeatlas.core.spi;

import io.codeatlas.core.model.SourceLocation;
import java.util.Map;
import java.util.Objects;

/**
 * 다른 언어 안에 박힌 코드 조각(매퍼 XML 안의 SQL, {@code @Select}의 SQL, JSP 안의 인라인 스크립트).
 * 생산자 분석기가 {@link AnalysisContext#addEmbedded}로 등록하고 순서가 뒤인 소비자 분석기가
 * {@link AnalysisContext#embedded}로 꺼내 분석한다.
 *
 * @param language {@link #SQL} 또는 {@link #JS}
 * @param ownerNodeId 이 조각의 주인 노드 ID. 생산자가 미리 declare 해 둔다. 소비자는 추출한 엣지의 from으로 이 ID를 쓴다
 * @param text 코드 본문
 * @param origin 조각이 시작하는 호스트 파일 위치
 * @param exactPositions true면 text가 호스트 파일의 연속 구간 그대로라 origin에 줄·열을 더해 실제 위치를 계산할 수 있다
 * @param attrs sql: dialect(선택), variant(동적 SQL 전개본 번호) 등
 */
public record EmbeddedSource(
        String language,
        String ownerNodeId,
        String text,
        SourceLocation origin,
        boolean exactPositions,
        Map<String, String> attrs) {

    public static final String SQL = "sql";
    public static final String JS = "js";

    public EmbeddedSource {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        text = text == null ? "" : text;
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }

    /** attrs 없는 SQL 조각. */
    public static EmbeddedSource sql(String ownerNodeId, String text, SourceLocation origin, boolean exactPositions) {
        return new EmbeddedSource(SQL, ownerNodeId, text, origin, exactPositions, Map.of());
    }

    /** attrs 없는 JavaScript 조각. */
    public static EmbeddedSource js(String ownerNodeId, String text, SourceLocation origin, boolean exactPositions) {
        return new EmbeddedSource(JS, ownerNodeId, text, origin, exactPositions, Map.of());
    }

    /**
     * 조각 안의 위치(줄·열 모두 1부터)를 호스트 파일의 위치로 바꾼다.
     * {@code exactPositions=false}이거나 origin의 줄을 모르면 origin을 그대로 돌려준다.
     * 조각의 첫 줄에 있는 위치만 origin의 시작 열만큼 밀린다.
     */
    public SourceLocation locationOf(int startLine, int startColumn, int endLine, int endColumn) {
        if (!exactPositions || origin == null || origin.startLine() <= 0) {
            return origin;
        }
        return new SourceLocation(origin.file(),
                hostLine(startLine), hostColumn(startLine, startColumn),
                hostLine(endLine), hostColumn(endLine, endColumn));
    }

    private int hostLine(int line) {
        return line <= 0 ? origin.startLine() : origin.startLine() + line - 1;
    }

    private int hostColumn(int line, int column) {
        if (column <= 0) {
            return 0;
        }
        return line <= 1 && origin.startColumn() > 0 ? origin.startColumn() + column - 1 : column;
    }
}
