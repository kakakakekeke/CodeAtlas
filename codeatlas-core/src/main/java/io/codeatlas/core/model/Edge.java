package io.codeatlas.core.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 그래프의 엣지. 불변이다. 신뢰도의 기본값은 {@link Confidence#RESOLVED}다.
 *
 * <pre>{@code
 * graph.addEdge(Edge.of(typeId, methodId, EdgeKind.CONTAINS));
 * graph.addEdge(Edge.builder(callerId, calleeId, EdgeKind.CALLS)
 *         .location(file.location(12, 9, 12, 30))
 *         .attr("callType", "virtual")
 *         .build());
 * }</pre>
 */
public final class Edge implements HasAttrs {

    private final String from;
    private final String to;
    private final EdgeKind kind;
    private final Confidence confidence;
    private final SourceLocation location;
    private final Map<String, Object> attrs;

    private Edge(String from, String to, EdgeKind kind, Confidence confidence, SourceLocation location, Map<String, Object> attrs) {
        if (from == null || from.isEmpty() || to == null || to.isEmpty()) {
            throw new IllegalArgumentException("엣지의 from, to가 비어 있다: " + from + " -> " + to);
        }
        this.from = from;
        this.to = to;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.confidence = confidence == null ? Confidence.RESOLVED : confidence;
        this.location = location;
        this.attrs = Attrs.freeze(attrs);
    }

    /** 위치와 속성이 없는 RESOLVED 엣지. */
    public static Edge of(String from, String to, EdgeKind kind) {
        return new Edge(from, to, kind, Confidence.RESOLVED, null, Map.of());
    }

    /** 근거 위치가 있는 RESOLVED 엣지. */
    public static Edge of(String from, String to, EdgeKind kind, SourceLocation location) {
        return new Edge(from, to, kind, Confidence.RESOLVED, location, Map.of());
    }

    public static Builder builder(String from, String to, EdgeKind kind) {
        return new Builder(from, to, kind);
    }

    /** 출발 노드 ID. */
    public String from() {
        return from;
    }

    /** 도착 노드 ID. */
    public String to() {
        return to;
    }

    public EdgeKind kind() {
        return kind;
    }

    public Confidence confidence() {
        return confidence;
    }

    /** 근거 위치(호출 지점, SQL 구문 위치 등). 없으면 null. */
    public SourceLocation location() {
        return location;
    }

    @Override
    public Map<String, Object> attrs() {
        return attrs;
    }

    public Builder toBuilder() {
        Builder b = new Builder(from, to, kind);
        b.confidence = confidence;
        b.location = location;
        b.attrs.putAll(attrs);
        return b;
    }

    public Edge withConfidence(Confidence newConfidence) {
        return new Edge(from, to, kind, newConfidence, location, attrs);
    }

    /** 양 끝만 바꾼 새 엣지. 종류, 신뢰도, 위치, 속성은 그대로다. */
    public Edge withEndpoints(String newFrom, String newTo) {
        return new Edge(newFrom, newTo, kind, confidence, location, attrs);
    }

    public Edge withAttr(String key, Object value) {
        return toBuilder().attr(key, value).build();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Edge other
                && from.equals(other.from)
                && to.equals(other.to)
                && kind == other.kind
                && confidence == other.confidence
                && Objects.equals(location, other.location)
                && attrs.equals(other.attrs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, kind, confidence, location, attrs);
    }

    @Override
    public String toString() {
        return from + " -" + kind + "(" + confidence + ")-> " + to;
    }

    /** 엣지 빌더. null 속성값은 무시한다. */
    public static final class Builder {
        private final String from;
        private final String to;
        private final EdgeKind kind;
        private Confidence confidence = Confidence.RESOLVED;
        private SourceLocation location;
        private final Map<String, Object> attrs = new LinkedHashMap<>();

        private Builder(String from, String to, EdgeKind kind) {
            this.from = from;
            this.to = to;
            this.kind = kind;
        }

        public Builder confidence(Confidence confidence) {
            this.confidence = confidence;
            return this;
        }

        /** 추론으로 만든 관계. 근거 위치를 함께 준다. */
        public Builder inferred() {
            this.confidence = Confidence.INFERRED;
            return this;
        }

        public Builder unresolved() {
            this.confidence = Confidence.UNRESOLVED;
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

        public Edge build() {
            return new Edge(from, to, kind, confidence, location, attrs);
        }
    }
}
