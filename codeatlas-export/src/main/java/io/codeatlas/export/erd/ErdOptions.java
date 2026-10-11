package io.codeatlas.export.erd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ERD의 범위와 표시 옵션. 불변이다.
 *
 * <pre>{@code
 * ErdOptions.builder().tables("ORDERS").neighborDepth(1).minEvidence(2).columnLevel(ColumnLevel.KEYS).build();
 * }</pre>
 */
public final class ErdOptions {

    /** 컬럼 표시 수준. */
    public enum ColumnLevel {
        /** 전부. */
        ALL,
        /** PK, FK, 그리고 그려지는 관계에 쓰인 컬럼만. */
        KEYS,
        /** 컬럼 없이 테이블만. */
        NONE
    }

    private final List<String> tables;
    private final int neighborDepth;
    private final List<String> patterns;
    private final boolean includeInferred;
    private final int minEvidence;
    private final boolean includeViews;
    private final ColumnLevel columnLevel;
    private final boolean showComments;

    private ErdOptions(Builder b) {
        this.tables = List.copyOf(b.tables);
        this.neighborDepth = Math.max(0, b.neighborDepth);
        this.patterns = List.copyOf(b.patterns);
        this.includeInferred = b.includeInferred;
        this.minEvidence = b.minEvidence;
        this.includeViews = b.includeViews;
        this.columnLevel = b.columnLevel;
        this.showComments = b.showComments;
    }

    /** 전체 테이블, 추론 관계 포함, 뷰 제외, 컬럼 전부, 주석 표시. */
    public static ErdOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 시작 테이블(이름 또는 노드 ID). 비어 있고 patterns도 비면 전체다. */
    public List<String> tables() {
        return tables;
    }

    /** 시작 테이블에서 관계를 따라 넓힐 깊이. 0이면 시작 테이블만. */
    public int neighborDepth() {
        return neighborDepth;
    }

    /** 이름 glob({@code *}, {@code ?}). 대소문자를 가리지 않는다. 맞는 테이블은 이웃으로 넓히지 않는다. */
    public List<String> patterns() {
        return patterns;
    }

    public boolean includeInferred() {
        return includeInferred;
    }

    /** 추론 관계의 최소 evidenceCount. DDL 관계에는 적용하지 않는다. */
    public int minEvidence() {
        return minEvidence;
    }

    public boolean includeViews() {
        return includeViews;
    }

    public ColumnLevel columnLevel() {
        return columnLevel;
    }

    public boolean showComments() {
        return showComments;
    }

    /** 범위를 좁히는 옵션이 하나도 없으면 true. */
    public boolean wholeSchema() {
        return tables.isEmpty() && patterns.isEmpty();
    }

    public static final class Builder {
        private final List<String> tables = new ArrayList<>();
        private int neighborDepth = 1;
        private final List<String> patterns = new ArrayList<>();
        private boolean includeInferred = true;
        private int minEvidence = 1;
        private boolean includeViews;
        private ColumnLevel columnLevel = ColumnLevel.ALL;
        private boolean showComments = true;

        private Builder() {
        }

        public Builder tables(String... names) {
            tables.addAll(Arrays.asList(names));
            return this;
        }

        public Builder tables(List<String> names) {
            tables.addAll(names);
            return this;
        }

        /** 기본 1. */
        public Builder neighborDepth(int depth) {
            this.neighborDepth = depth;
            return this;
        }

        public Builder patterns(String... globs) {
            patterns.addAll(Arrays.asList(globs));
            return this;
        }

        public Builder patterns(List<String> globs) {
            patterns.addAll(globs);
            return this;
        }

        public Builder includeInferred(boolean include) {
            this.includeInferred = include;
            return this;
        }

        public Builder minEvidence(int min) {
            this.minEvidence = min;
            return this;
        }

        public Builder includeViews(boolean include) {
            this.includeViews = include;
            return this;
        }

        public Builder columnLevel(ColumnLevel level) {
            this.columnLevel = level == null ? ColumnLevel.ALL : level;
            return this;
        }

        public Builder showComments(boolean show) {
            this.showComments = show;
            return this;
        }

        public ErdOptions build() {
            return new ErdOptions(this);
        }
    }
}
