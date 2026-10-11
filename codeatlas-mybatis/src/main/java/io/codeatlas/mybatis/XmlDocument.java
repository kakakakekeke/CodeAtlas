package io.codeatlas.mybatis;

import io.codeatlas.core.model.SourceLocation;
import java.util.List;

/**
 * 파싱한 XML 문서. 오류로 중단됐으면 {@link #errorMessage}가 있고 트리는 오류 지점까지만 들어 있다.
 *
 * @param raw 원본 문자열
 * @param root 루트 요소. 루트를 읽기 전에 실패했으면 null
 * @param errorMessage 파싱 오류 메시지. 없으면 null
 * @param errorLine 오류 줄(모르면 0)
 * @param errorColumn 오류 열(모르면 0)
 * @param skippedEntities 풀지 않고 건너뛴 엔티티 이름(외부 엔티티)
 */
record XmlDocument(String raw, LineMap lines, XmlElement root, String errorMessage, int errorLine, int errorColumn,
        List<String> skippedEntities) {

    boolean failed() {
        return errorMessage != null;
    }

    /** 원본의 [start, end) 구간 위치. */
    SourceLocation location(String file, int start, int end) {
        if (start < 0) {
            return SourceLocation.ofFile(file);
        }
        int last = Math.max(start, end);
        return new SourceLocation(file, lines.line(start), lines.column(start), lines.line(last), lines.column(last));
    }

    /** 요소 전체(시작 태그부터 끝 태그까지)의 위치. 오프셋을 모르면 줄만 준다. */
    SourceLocation location(String file, XmlElement element) {
        if (element.startOffset < 0) {
            return element.fallbackLine > 0 ? SourceLocation.of(file, element.fallbackLine) : SourceLocation.ofFile(file);
        }
        return location(file, element.startOffset, Math.max(element.endOffset, element.bodyStart));
    }
}
