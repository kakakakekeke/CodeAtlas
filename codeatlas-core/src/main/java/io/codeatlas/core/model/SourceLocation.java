package io.codeatlas.core.model;

import java.util.Objects;

/**
 * 소스 위치. file은 프로젝트 루트 기준 상대 경로이고 구분자는 '/'다. 줄과 열은 1부터 세고 모르면 0이다.
 */
public record SourceLocation(String file, int startLine, int startColumn, int endLine, int endColumn) {

    public SourceLocation {
        Objects.requireNonNull(file, "file");
        file = file.replace('\\', '/');
    }

    /** 줄과 열을 모두 아는 위치. */
    public static SourceLocation of(String file, int startLine, int startColumn, int endLine, int endColumn) {
        return new SourceLocation(file, startLine, startColumn, endLine, endColumn);
    }

    /** 줄만 아는 위치. 열은 0이다. */
    public static SourceLocation of(String file, int line) {
        return new SourceLocation(file, line, 0, line, 0);
    }

    /** 파일만 아는 위치. */
    public static SourceLocation ofFile(String file) {
        return new SourceLocation(file, 0, 0, 0, 0);
    }

    /** 사람이 읽는 표기. 예: {@code src/A.java:10:5}. */
    @Override
    public String toString() {
        if (startLine <= 0) {
            return file;
        }
        return startColumn > 0 ? file + ":" + startLine + ":" + startColumn : file + ":" + startLine;
    }
}
