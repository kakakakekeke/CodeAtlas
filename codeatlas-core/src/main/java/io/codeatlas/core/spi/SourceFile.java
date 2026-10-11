package io.codeatlas.core.spi;

import io.codeatlas.core.io.TextDecoder;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 분석 대상 파일 하나.
 *
 * @param absolutePath 실제 파일 경로
 * @param relativePath 프로젝트 루트 기준 상대 경로('/' 구분). 프로젝트 밖의 DDL은 {@code ddl/<파일명>} 가상 경로
 * @param type 스캐너가 분류한 종류
 * @param encoding 지정 인코딩({@code --encoding}). null이면 읽을 때 판별한다
 */
public record SourceFile(Path absolutePath, String relativePath, FileType type, Charset encoding) {

    public SourceFile {
        Objects.requireNonNull(absolutePath, "absolutePath");
        Objects.requireNonNull(type, "type");
        relativePath = Objects.requireNonNull(relativePath, "relativePath").replace('\\', '/');
    }

    /** 인코딩을 판별해서 읽는 파일. */
    public SourceFile(Path absolutePath, String relativePath, FileType type) {
        this(absolutePath, relativePath, type, null);
    }

    /**
     * 파일 본문. 인코딩 지정이 없으면 UTF-8(엄격) → MS949 순으로 판별한다. BOM은 떼어 낸다.
     *
     * @throws UncheckedIOException 파일을 읽지 못하면
     */
    public String readText() {
        try {
            return TextDecoder.read(absolutePath, encoding).text();
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 읽지 못함: " + relativePath, e);
        }
    }

    /** 파일의 바이트 그대로. */
    public byte[] readBytes() {
        try {
            return Files.readAllBytes(absolutePath);
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 읽지 못함: " + relativePath, e);
        }
    }

    /** 경로를 뺀 파일 이름. */
    public String fileName() {
        return relativePath.substring(relativePath.lastIndexOf('/') + 1);
    }

    /** 이 파일의 FILE 노드 ID({@code file:<상대경로>}). */
    public String nodeId() {
        return Ids.file(relativePath);
    }

    /** 이 파일 안의 위치. */
    public SourceLocation location(int startLine, int startColumn, int endLine, int endColumn) {
        return new SourceLocation(relativePath, startLine, startColumn, endLine, endColumn);
    }

    /** 줄만 아는 위치. */
    public SourceLocation location(int line) {
        return SourceLocation.of(relativePath, line);
    }

    /** 파일 전체를 가리키는 위치. */
    public SourceLocation location() {
        return SourceLocation.ofFile(relativePath);
    }
}
