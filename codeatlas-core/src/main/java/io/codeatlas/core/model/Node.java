package io.codeatlas.core.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 그래프의 노드. 불변이다. {@link #builder(String, NodeKind)}나 {@link #of(String, NodeKind, String)}로 만든다.
 *
 * <pre>{@code
 * Node type = Node.builder(Ids.javaType("com.a.B"), NodeKind.JAVA_TYPE)
 *         .name("B").qualifiedName("com.a.B")
 *         .location(file.location(3, 1, 40, 2))
 *         .attr("typeKind", "class")
 *         .build();
 * graph.declare(type);
 * }</pre>
 */
public final class Node implements HasAttrs {

    private final String id;
    private final NodeKind kind;
    private final String name;
    private final String qualifiedName;
    private final SourceLocation location;
    private final Map<String, Object> attrs;

    private Node(String id, NodeKind kind, String name, String qualifiedName, SourceLocation location, Map<String, Object> attrs) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("노드 ID가 비어 있다");
        }
        this.id = id;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.name = name == null || name.isEmpty() ? Ids.remainder(id) : name;
        this.qualifiedName = qualifiedName == null || qualifiedName.isEmpty() ? this.name : qualifiedName;
        this.location = location;
        this.attrs = Attrs.freeze(attrs);
    }

    /** 이름만 있는 노드. qualifiedName은 name과 같다. */
    public static Node of(String id, NodeKind kind, String name) {
        return new Node(id, kind, name, null, null, Map.of());
    }

    public static Builder builder(String id, NodeKind kind) {
        return new Builder(id, kind);
    }

    /** 3.3의 규칙을 따르는 ID. */
    public String id() {
        return id;
    }

    public NodeKind kind() {
        return kind;
    }

    /** 짧은 이름(메서드명, 테이블명 등). */
    public String name() {
        return name;
    }

    /** 사람이 읽는 전체 이름. */
    public String qualifiedName() {
        return qualifiedName;
    }

    /** 정의 위치. 없으면 null. */
    public SourceLocation location() {
        return location;
    }

    @Override
    public Map<String, Object> attrs() {
        return attrs;
    }

    /** 정의를 본 노드면 true. 참조만 된 노드는 false. */
    public boolean declared() {
        return attrBoolean(Attrs.DECLARED);
    }

    /** 이 노드의 내용을 채운 빌더. */
    public Builder toBuilder() {
        Builder b = new Builder(id, kind);
        b.name = name;
        b.qualifiedName = qualifiedName;
        b.location = location;
        b.attrs.putAll(attrs);
        return b;
    }

    /** 속성 하나를 바꾼 새 노드. 그래프에 반영하려면 {@link GraphModel#putAttrs}를 쓴다. */
    public Node withAttr(String key, Object value) {
        return toBuilder().attr(key, value).build();
    }

    public Node withLocation(SourceLocation newLocation) {
        return toBuilder().location(newLocation).build();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Node other
                && id.equals(other.id)
                && kind == other.kind
                && name.equals(other.name)
                && qualifiedName.equals(other.qualifiedName)
                && Objects.equals(location, other.location)
                && attrs.equals(other.attrs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind, name, qualifiedName, location, attrs);
    }

    @Override
    public String toString() {
        return kind + " " + id + (attrs.isEmpty() ? "" : " " + attrs);
    }

    /** 노드 빌더. null 속성값은 무시한다. */
    public static final class Builder {
        private final String id;
        private NodeKind kind;
        private String name;
        private String qualifiedName;
        private SourceLocation location;
        private final Map<String, Object> attrs = new LinkedHashMap<>();

        private Builder(String id, NodeKind kind) {
            this.id = id;
            this.kind = kind;
        }

        public Builder kind(NodeKind kind) {
            this.kind = kind;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder qualifiedName(String qualifiedName) {
            this.qualifiedName = qualifiedName;
            return this;
        }

        public Builder location(SourceLocation location) {
            this.location = location;
            return this;
        }

        /** 값은 String, Number, Boolean, List&lt;String&gt;만 된다. null이면 넣지 않는다. */
        public Builder attr(String key, Object value) {
            Attrs.put(attrs, key, value);
            return this;
        }

        public Builder attrs(Map<String, ?> values) {
            Attrs.putAll(attrs, values);
            return this;
        }

        public Node build() {
            return new Node(id, kind, name, qualifiedName, location, attrs);
        }
    }
}
