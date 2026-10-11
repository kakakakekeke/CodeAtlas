package io.codeatlas.core.spi;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 옵션.
 *
 * @param classpath 타입 해석에 쓸 jar 또는 클래스 폴더({@code --classpath})
 * @param ddlPaths DDL 파일 또는 폴더({@code --ddl})
 * @param encoding 지정 인코딩({@code --encoding}). null이면 파일마다 판별한다
 * @param dialect SQL 방언({@code --dialect})
 * @param excludes 제외 glob({@code --exclude})
 * @param extra 그 밖의 옵션. 출력기에도 그대로 전달된다
 */
public record AnalysisOptions(
        List<Path> classpath,
        List<Path> ddlPaths,
        Charset encoding,
        Dialect dialect,
        List<String> excludes,
        Map<String, String> extra) {

    public AnalysisOptions {
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
        ddlPaths = ddlPaths == null ? List.of() : List.copyOf(ddlPaths);
        dialect = dialect == null ? Dialect.AUTO : dialect;
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /** 아무것도 지정하지 않은 옵션. */
    public static AnalysisOptions defaults() {
        return new AnalysisOptions(null, null, null, null, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 옵션 빌더. */
    public static final class Builder {
        private final List<Path> classpath = new ArrayList<>();
        private final List<Path> ddlPaths = new ArrayList<>();
        private Charset encoding;
        private Dialect dialect = Dialect.AUTO;
        private final List<String> excludes = new ArrayList<>();
        private final Map<String, String> extra = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder classpath(Path entry) {
            classpath.add(entry);
            return this;
        }

        public Builder ddlPath(Path fileOrDirectory) {
            ddlPaths.add(fileOrDirectory);
            return this;
        }

        public Builder encoding(Charset encoding) {
            this.encoding = encoding;
            return this;
        }

        public Builder dialect(Dialect dialect) {
            this.dialect = dialect;
            return this;
        }

        public Builder exclude(String glob) {
            excludes.add(glob);
            return this;
        }

        public Builder extra(String key, String value) {
            extra.put(key, value);
            return this;
        }

        public AnalysisOptions build() {
            return new AnalysisOptions(classpath, ddlPaths, encoding, dialect, excludes, extra);
        }
    }
}
