package io.codeatlas.sql.analysis;

import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.sql.ast.Pos;

/** 분석한 텍스트 안의 위치({@link Pos})를 호스트 파일 위치로 바꾼다. */
@FunctionalInterface
public interface Locator {

    /** pos를 모르면({@link Pos#NONE}) 구문 전체를 대표하는 위치를 돌려준다. */
    SourceLocation locate(Pos pos);

    /** 위치가 실제 줄·열을 가리키는가(false면 모든 pos가 같은 위치로 간다). */
    default boolean exact() {
        return true;
    }

    /** SQL 파일: 텍스트 위치가 곧 파일 위치다. fallback은 pos를 모를 때 쓴다. */
    static Locator ofFile(String relativePath, Pos fallback) {
        return pos -> {
            Pos p = pos == null || !pos.known() ? fallback : pos;
            return p == null || !p.known() ? SourceLocation.ofFile(relativePath)
                    : new SourceLocation(relativePath, p.line(), p.column(), p.endLine(), p.endColumn());
        };
    }

    /** 내장 소스: {@link EmbeddedSource#locationOf}로 바꾼다. 위치를 보존하지 않는 조각이면 항상 origin이다. */
    static Locator ofEmbedded(EmbeddedSource source) {
        boolean exact = source.exactPositions() && source.origin() != null && source.origin().startLine() > 0;
        return new Locator() {
            @Override
            public SourceLocation locate(Pos pos) {
                if (!exact || pos == null || !pos.known()) {
                    return source.origin();
                }
                return source.locationOf(pos.line(), pos.column(), pos.endLine(), pos.endColumn());
            }

            @Override
            public boolean exact() {
                return exact;
            }
        };
    }
}
