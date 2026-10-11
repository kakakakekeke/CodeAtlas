package io.codeatlas.export.classdiagram;

import io.codeatlas.core.model.EdgeKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 클래스 다이어그램의 범위와 표시 옵션. 불변이다.
 *
 * <pre>{@code
 * ClassDiagramOptions.builder().packagePrefix("com.shop.order").members(MemberLevel.PUBLIC).hideAccessors(true).build();
 * ClassDiagramOptions.builder().types("OrderService").neighborDepth(1).build();
 * }</pre>
 */
public final class ClassDiagramOptions {

    /** 멤버 표시 수준. */
    public enum MemberLevel {
        /** 전부. */
        ALL,
        /** public 멤버만. */
        PUBLIC,
        /** 멤버 없이 타입만. */
        NONE
    }

    /** 고를 수 있는 관계 종류. */
    public static final Set<EdgeKind> SUPPORTED_RELATIONS = Collections.unmodifiableSet(
            EnumSet.of(EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.ASSOCIATES, EdgeKind.DEPENDS_ON, EdgeKind.INJECTS));

    private final String packagePrefix;
    private final boolean includeSubpackages;
    private final List<String> types;
    private final int neighborDepth;
    private final MemberLevel members;
    private final Set<EdgeKind> relationKinds;
    private final boolean includeExternal;
    private final boolean showExternalSupertypes;
    private final boolean hideAccessors;
    private final boolean includeNested;
    private final boolean includeAnonymous;
    private final int maxTypes;
    private final boolean groupByPackage;

    private ClassDiagramOptions(Builder b) {
        this.packagePrefix = b.packagePrefix;
        this.includeSubpackages = b.includeSubpackages;
        this.types = List.copyOf(b.types);
        this.neighborDepth = Math.max(0, b.neighborDepth);
        this.members = b.members;
        this.relationKinds = Collections.unmodifiableSet(b.relationKinds.isEmpty() ? EnumSet.noneOf(EdgeKind.class)
                : EnumSet.copyOf(b.relationKinds));
        this.includeExternal = b.includeExternal;
        this.showExternalSupertypes = b.showExternalSupertypes;
        this.hideAccessors = b.hideAccessors;
        this.includeNested = b.includeNested;
        this.includeAnonymous = b.includeAnonymous;
        this.maxTypes = b.maxTypes;
        this.groupByPackage = b.groupByPackage;
    }

    /** 전체 타입, 멤버 전부, 상속·구현·연관·의존, external 제외, 익명·로컬 제외, 상한 60. */
    public static ClassDiagramOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 패키지 접두. null이면 패키지로 거르지 않는다. 빈 문자열은 이름 없는 패키지다. */
    public String packagePrefix() {
        return packagePrefix;
    }

    public boolean includeSubpackages() {
        return includeSubpackages;
    }

    /** 시작 타입(노드 ID, 바이너리 이름, 단순 이름). */
    public List<String> types() {
        return types;
    }

    /** 시작 타입에서 고른 관계를 양방향으로 따라 넓힐 깊이. */
    public int neighborDepth() {
        return neighborDepth;
    }

    public MemberLevel members() {
        return members;
    }

    public Set<EdgeKind> relationKinds() {
        return relationKinds;
    }

    public boolean includeExternal() {
        return includeExternal;
    }

    /** 범위 안 타입의 external 상위 타입을 이름만 그린다. */
    public boolean showExternalSupertypes() {
        return showExternalSupertypes;
    }

    public boolean hideAccessors() {
        return hideAccessors;
    }

    public boolean includeNested() {
        return includeNested;
    }

    public boolean includeAnonymous() {
        return includeAnonymous;
    }

    /** 타입 수 상한. 넘으면 멤버를 생략한다. 0 이하면 상한이 없다. */
    public int maxTypes() {
        return maxTypes;
    }

    public boolean groupByPackage() {
        return groupByPackage;
    }

    public static final class Builder {
        private String packagePrefix;
        private boolean includeSubpackages = true;
        private final List<String> types = new ArrayList<>();
        private int neighborDepth = 1;
        private MemberLevel members = MemberLevel.ALL;
        private Set<EdgeKind> relationKinds = EnumSet.of(EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.ASSOCIATES,
                EdgeKind.DEPENDS_ON);
        private boolean includeExternal;
        private boolean showExternalSupertypes;
        private boolean hideAccessors;
        private boolean includeNested = true;
        private boolean includeAnonymous;
        private int maxTypes = 60;
        private boolean groupByPackage;

        private Builder() {
        }

        public Builder packagePrefix(String prefix) {
            this.packagePrefix = prefix;
            return this;
        }

        /** 기본 true. */
        public Builder includeSubpackages(boolean include) {
            this.includeSubpackages = include;
            return this;
        }

        public Builder types(String... names) {
            types.addAll(Arrays.asList(names));
            return this;
        }

        public Builder types(List<String> names) {
            types.addAll(names);
            return this;
        }

        /** 기본 1. */
        public Builder neighborDepth(int depth) {
            this.neighborDepth = depth;
            return this;
        }

        public Builder members(MemberLevel level) {
            this.members = level == null ? MemberLevel.ALL : level;
            return this;
        }

        /** EXTENDS, IMPLEMENTS, ASSOCIATES, DEPENDS_ON, INJECTS 중에서 고른다. 그 밖의 종류는 무시한다. */
        public Builder relationKinds(EdgeKind... kinds) {
            return relationKinds(Arrays.asList(kinds));
        }

        public Builder relationKinds(Iterable<EdgeKind> kinds) {
            Set<EdgeKind> set = EnumSet.noneOf(EdgeKind.class);
            for (EdgeKind kind : kinds) {
                if (SUPPORTED_RELATIONS.contains(kind)) {
                    set.add(kind);
                }
            }
            this.relationKinds = set;
            return this;
        }

        public Builder includeExternal(boolean include) {
            this.includeExternal = include;
            return this;
        }

        public Builder showExternalSupertypes(boolean show) {
            this.showExternalSupertypes = show;
            return this;
        }

        public Builder hideAccessors(boolean hide) {
            this.hideAccessors = hide;
            return this;
        }

        /** 기본 true. false면 중첩 타입({@code Outer$Inner})을 뺀다. */
        public Builder includeNested(boolean include) {
            this.includeNested = include;
            return this;
        }

        /** 기본 false. true면 익명·로컬 타입도 넣는다. */
        public Builder includeAnonymous(boolean include) {
            this.includeAnonymous = include;
            return this;
        }

        public Builder maxTypes(int max) {
            this.maxTypes = max;
            return this;
        }

        public Builder groupByPackage(boolean group) {
            this.groupByPackage = group;
            return this;
        }

        public ClassDiagramOptions build() {
            return new ClassDiagramOptions(this);
        }
    }
}
