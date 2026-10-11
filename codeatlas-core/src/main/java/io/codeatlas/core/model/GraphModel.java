package io.codeatlas.core.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * 모든 분석기가 함께 쓰는 그래프. 스레드 안전하다. 조회 메서드가 돌려주는 컬렉션은 그 시점의 복사본이다.
 *
 * <p>엣지는 노드별·종류별 인덱스에 들어 있다. {@link #incoming(String, EdgeKind...)}, {@link #outgoing(String, EdgeKind...)},
 * {@link #edges(String, EdgeKind, String)}는 그 노드의 엣지 전체가 아니라 고른 종류의 엣지 수만큼만 일한다
 * (들어오는 엣지가 수십만 개인 컬럼·테이블에서 한 종류만 찾을 때 전체를 복사하지 않는다). 결과의 순서는 종류와 무관하게 엣지를 넣은 순서다.
 *
 * <pre>{@code
 * graph.declare(Node.builder(Ids.table("ORDERS"), NodeKind.DB_TABLE).name("ORDERS").location(loc).build());
 * graph.reference(Ids.table("CUSTOMER"), NodeKind.DB_TABLE, "CUSTOMER");   // 정의를 못 본 테이블
 * graph.addEdge(Edge.builder(stmtId, Ids.table("ORDERS"), EdgeKind.READS).location(loc).build());
 * graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED, "구문을 건너뜀", loc));
 * }</pre>
 */
public final class GraphModel {

    private record EdgeKey(String from, String to, EdgeKind kind, SourceLocation location) {
    }

    /** 저장된 엣지 하나. 인덱스가 이 객체를 함께 가리키므로 엣지를 바꿔 끼우면(마무리 단계의 신뢰도 변경) 조회에도 바로 보인다. */
    private static final class EdgeRef {
        private final EdgeKey key;
        /** 넣은 순서. 여러 종류의 목록을 합칠 때 순서를 되살리는 데 쓴다. */
        private final long sequence;
        private Edge edge;

        EdgeRef(EdgeKey key, long sequence, Edge edge) {
            this.key = key;
            this.sequence = sequence;
            this.edge = edge;
        }
    }

    private static final Comparator<EdgeRef> BY_SEQUENCE = Comparator.comparingLong(ref -> ref.sequence);

    /** 한 노드의 한 방향 엣지. 종류별 목록마다 넣은 순서(sequence 오름차순)다. */
    private static final class Adjacency {
        private final Map<EdgeKind, List<EdgeRef>> byKind = new EnumMap<>(EdgeKind.class);

        void add(EdgeRef ref) {
            byKind.computeIfAbsent(ref.key.kind(), k -> new ArrayList<>(2)).add(ref);
        }

        void remove(EdgeRef ref) {
            List<EdgeRef> refs = byKind.get(ref.key.kind());
            if (refs != null) {
                // 최근에 넣은 엣지를 지우는 일이 많아 뒤에서부터 찾는다.
                int at = refs.lastIndexOf(ref);
                if (at >= 0) {
                    refs.remove(at);
                }
                if (refs.isEmpty()) {
                    byKind.remove(ref.key.kind());
                }
            }
        }

        boolean isEmpty() {
            return byKind.isEmpty();
        }

        /** kinds가 null이면 모든 종류. 넣은 순서로 돌려준다. */
        List<Edge> edges(Set<EdgeKind> kinds) {
            List<EdgeRef> single = null;
            List<EdgeRef> merged = null;
            for (Map.Entry<EdgeKind, List<EdgeRef>> e : byKind.entrySet()) {
                if (kinds != null && !kinds.contains(e.getKey())) {
                    continue;
                }
                if (single == null && merged == null) {
                    single = e.getValue();
                } else {
                    if (merged == null) {
                        merged = new ArrayList<>(single);
                        single = null;
                    }
                    merged.addAll(e.getValue());
                }
            }
            if (merged != null) {
                // 종류별로 이미 정렬된 구간을 이어 붙인 것이라 병합 정렬이 구간 수에 비례해 끝난다.
                merged.sort(BY_SEQUENCE);
                single = merged;
            }
            if (single == null) {
                return List.of();
            }
            Edge[] result = new Edge[single.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = single.get(i).edge;
            }
            return Collections.unmodifiableList(Arrays.asList(result));
        }

        List<EdgeRef> refs() {
            List<EdgeRef> all = new ArrayList<>();
            byKind.values().forEach(all::addAll);
            all.sort(BY_SEQUENCE);
            return all;
        }
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<EdgeKey, EdgeRef> edges = new LinkedHashMap<>();
    private final Map<String, Adjacency> outgoing = new HashMap<>();
    private final Map<String, Adjacency> incoming = new HashMap<>();
    private long nextSequence;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private ProjectInfo project;

    public GraphModel() {
    }

    /**
     * 저장된 그래프를 병합 규칙 없이 그대로 복원한다. graph.json을 읽을 때 쓴다.
     */
    public static GraphModel restore(ProjectInfo project, Collection<Node> nodes, Collection<Edge> edges, Collection<Diagnostic> diagnostics) {
        GraphModel graph = new GraphModel();
        graph.project = project;
        for (Node node : nodes) {
            graph.nodes.put(node.id(), node);
        }
        for (Edge edge : edges) {
            graph.putEdge(edge);
        }
        graph.diagnostics.addAll(diagnostics);
        return graph;
    }

    // ---- 노드 ----

    /**
     * 정의를 등록한다. {@code declared=true}가 자동으로 붙는다. 같은 ID가 있으면 병합한다:
     * 새 attrs가 같은 키를 덮어쓰고, kind·name·qualifiedName은 새 값을 쓰고,
     * location은 먼저 정의된 쪽 것을 유지한다(기존 노드가 참조만 된 것이었거나 위치가 없었으면 새 위치).
     *
     * @return 그래프에 저장된(병합된) 노드
     */
    public Node declare(Node node) {
        lock.writeLock().lock();
        try {
            Node existing = nodes.get(node.id());
            Node merged;
            if (existing == null) {
                merged = node.toBuilder().attr(Attrs.DECLARED, true).build();
            } else {
                SourceLocation location = existing.declared() && existing.location() != null
                        ? existing.location()
                        : (node.location() != null ? node.location() : existing.location());
                Node.Builder b = existing.toBuilder()
                        .kind(node.kind()).name(node.name()).qualifiedName(node.qualifiedName()).location(location);
                b.attrs(node.attrs());
                b.attr(Attrs.DECLARED, true);
                merged = b.build();
            }
            nodes.put(merged.id(), merged);
            return merged;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 참조만 등록한다. 없으면 {@code declared=false}인 노드를 만들고, 있으면 기존 노드를 그대로 돌려준다.
     * 정의를 다른 분석기가 만들 수도 있는 대상(DDL에 없는 테이블, 소스가 없는 타입 등)에 쓴다.
     *
     * <p>새로 만드는 노드의 {@code name}은 준 값 그대로이고, {@code qualifiedName}은 ID에서 접두사를 뗀 나머지
     * ({@link Ids#remainder(String)})다. 예: {@code java:type:jakarta.annotation.Resource} → {@code jakarta.annotation.Resource},
     * {@code java:method:a.B#run(?)} → {@code a.B#run(?)}, {@code db:column:T.C} → {@code T.C}.
     * 나중에 같은 ID를 {@link #declare(Node)}하면 declare의 값으로 바뀐다.
     */
    public Node reference(String id, NodeKind kind, String name) {
        lock.writeLock().lock();
        try {
            Node existing = nodes.get(id);
            if (existing != null) {
                return existing;
            }
            Node created = Node.builder(id, kind).name(name).qualifiedName(Ids.remainder(id))
                    .attr(Attrs.DECLARED, false).build();
            nodes.put(id, created);
            return created;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 이미 있는 노드에 속성을 덧붙인다(같은 키는 덮어쓴다). 노드가 없으면 아무것도 하지 않고 빈 값을 돌려준다.
     * 다른 분석기가 만든 노드에 정보를 더할 때 쓴다.
     */
    public Optional<Node> putAttrs(String id, Map<String, ?> attrs) {
        lock.writeLock().lock();
        try {
            Node existing = nodes.get(id);
            if (existing == null) {
                return Optional.empty();
            }
            Node updated = existing.toBuilder().attrs(attrs).build();
            nodes.put(id, updated);
            return Optional.of(updated);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * oldId 노드를 newId 노드로 합친다. oldId로 들어오고 나가는 엣지를 전부 newId로 옮기고 oldId 노드를 지운다.
     * 옮긴 엣지가 이미 있는 엣지와 (from, to, kind, location)이 같으면 기존 것만 남긴다.
     * 옮긴 결과 자기 자신을 가리키게 된 엣지는 원래 self-edge(oldId→oldId)였던 것만 남긴다.
     * 노드의 속성은 옮기지 않는다. newId 노드가 아직 없어도 엣지는 옮긴다.
     * 링커가 {@code db:table:X} 참조 스텁을 뷰·시노님 대상 노드로 합칠 때 쓴다.
     *
     * @return 합쳤으면 true. oldId 노드가 없거나 두 ID가 같으면 아무 일도 하지 않고 false
     */
    public boolean replaceNode(String oldId, String newId) {
        lock.writeLock().lock();
        try {
            if (oldId.equals(newId) || !nodes.containsKey(oldId)) {
                return false;
            }
            // oldId 쪽 인덱스는 통째로 떼고, 엣지마다 반대쪽 끝점의 인덱스에서만 지운다(엣지가 많은 노드를 합칠 때 하나씩 찾아 지우지 않게).
            Adjacency out = outgoing.remove(oldId);
            Adjacency in = incoming.remove(oldId);
            Set<EdgeRef> touching = new LinkedHashSet<>();
            if (out != null) {
                touching.addAll(out.refs());
            }
            if (in != null) {
                touching.addAll(in.refs());
            }
            for (EdgeRef ref : touching) {
                edges.remove(ref.key);
                if (!ref.key.to().equals(oldId)) {
                    unindex(incoming, ref.key.to(), ref);
                }
                if (!ref.key.from().equals(oldId)) {
                    unindex(outgoing, ref.key.from(), ref);
                }
                Edge edge = ref.edge;
                boolean wasSelfEdge = edge.from().equals(edge.to());
                String from = edge.from().equals(oldId) ? newId : edge.from();
                String to = edge.to().equals(oldId) ? newId : edge.to();
                if (from.equals(to) && !wasSelfEdge) {
                    continue;
                }
                Edge moved = edge.withEndpoints(from, to);
                if (!edges.containsKey(keyOf(moved))) {
                    putEdge(moved);
                }
            }
            nodes.remove(oldId);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<Node> node(String id) {
        return read(() -> Optional.ofNullable(nodes.get(id)));
    }

    public boolean hasNode(String id) {
        return read(() -> nodes.containsKey(id));
    }

    /** 모든 노드(등록한 순서). */
    public Collection<Node> nodes() {
        return read(() -> List.copyOf(nodes.values()));
    }

    public Collection<Node> nodes(NodeKind kind) {
        return read(() -> nodes.values().stream().filter(n -> n.kind() == kind).toList());
    }

    public int nodeCount() {
        return read(nodes::size);
    }

    // ---- 엣지 ----

    /**
     * 엣지를 더한다. (from, to, kind, location)이 같은 엣지가 이미 있으면 더하지 않고 기존 엣지를 돌려준다.
     * to나 from이 아직 없는 노드 ID여도 된다. 끝까지 없으면 마무리 단계에서 미해결로 바뀐다.
     */
    public Edge addEdge(Edge edge) {
        lock.writeLock().lock();
        try {
            EdgeRef existing = edges.get(keyOf(edge));
            if (existing != null) {
                return existing.edge;
            }
            putEdge(edge);
            return edge;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 해석하지 못한 참조를 남긴다. {@code unresolved:<category>:<text>} 노드를 정의하고,
     * 신뢰도 UNRESOLVED인 엣지를 더하고, 진단 UNRESOLVED_REFERENCE(WARN)를 남긴다.
     *
     * @param category 분류. 예: {@code java-call}, {@code sql-dynamic}
     * @param text 해석하지 못한 원문
     * @param location 근거 위치. 없으면 null
     */
    public Edge addUnresolvedEdge(String from, EdgeKind kind, String category, String text, SourceLocation location) {
        String id = Ids.unresolved(category, text);
        lock.writeLock().lock();
        try {
            declare(Node.builder(id, NodeKind.UNRESOLVED).name(text).qualifiedName(text).location(location)
                    .attr("category", category).build());
            Edge edge = addEdge(Edge.builder(from, id, kind).unresolved().location(location).build());
            diagnostics.add(Diagnostic.warn(DiagnosticCodes.UNRESOLVED_REFERENCE, "해석하지 못한 참조(" + category + "): " + text, location));
            return edge;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 모든 엣지(등록한 순서). */
    public Collection<Edge> edges() {
        return read(() -> {
            Edge[] all = new Edge[edges.size()];
            int i = 0;
            for (EdgeRef ref : edges.values()) {
                all[i++] = ref.edge;
            }
            return Collections.unmodifiableList(Arrays.asList(all));
        });
    }

    public Collection<Edge> edges(EdgeKind kind) {
        return read(() -> {
            List<Edge> found = new ArrayList<>();
            for (EdgeRef ref : edges.values()) {
                if (ref.key.kind() == kind) {
                    found.add(ref.edge);
                }
            }
            return Collections.unmodifiableList(found);
        });
    }

    /** from에서 to로 가는 kind 엣지. 위치가 다르면 여러 개일 수 있다. */
    public List<Edge> edges(String from, EdgeKind kind, String to) {
        if (kind == null) {
            return List.of();
        }
        return read(() -> lookup(outgoing, from, EnumSet.of(kind)).stream().filter(e -> e.to().equals(to)).toList());
    }

    public int edgeCount() {
        return read(edges::size);
    }

    /** nodeId에서 나가는 엣지(넣은 순서). */
    public List<Edge> outgoing(String nodeId) {
        return read(() -> lookup(outgoing, nodeId, null));
    }

    /** nodeId에서 나가는 엣지 중 kinds에 든 것(넣은 순서). kinds가 비면 전부. 고른 종류의 엣지 수만큼만 일한다. */
    public List<Edge> outgoing(String nodeId, EdgeKind... kinds) {
        Set<EdgeKind> wanted = wanted(kinds);
        return read(() -> lookup(outgoing, nodeId, wanted));
    }

    /** nodeId로 들어오는 엣지(넣은 순서). */
    public List<Edge> incoming(String nodeId) {
        return read(() -> lookup(incoming, nodeId, null));
    }

    /** nodeId로 들어오는 엣지 중 kinds에 든 것(넣은 순서). kinds가 비면 전부. 고른 종류의 엣지 수만큼만 일한다. */
    public List<Edge> incoming(String nodeId, EdgeKind... kinds) {
        Set<EdgeKind> wanted = wanted(kinds);
        return read(() -> lookup(incoming, nodeId, wanted));
    }

    /** nodeId에서 나가는 엣지의 도착 노드(중복 없이, 그래프에 있는 것만). kinds가 비면 모든 종류. */
    public List<Node> successors(String nodeId, EdgeKind... kinds) {
        return neighbors(outgoing(nodeId, kinds), Edge::to);
    }

    /** nodeId로 들어오는 엣지의 출발 노드(중복 없이, 그래프에 있는 것만). kinds가 비면 모든 종류. */
    public List<Node> predecessors(String nodeId, EdgeKind... kinds) {
        return neighbors(incoming(nodeId, kinds), Edge::from);
    }

    // ---- 진단 ----

    public void report(Diagnostic diagnostic) {
        lock.writeLock().lock();
        try {
            diagnostics.add(diagnostic);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Diagnostic> diagnostics() {
        return read(() -> List.copyOf(diagnostics));
    }

    // ---- 프로젝트 정보 ----

    /** graph.json의 project 항목. 파이프라인이 채운다. 없으면 null. */
    public ProjectInfo project() {
        return read(() -> project);
    }

    public void project(ProjectInfo project) {
        lock.writeLock().lock();
        try {
            this.project = project;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---- 마무리 ----

    /**
     * 마무리 단계. 엣지의 from 또는 to가 없는 노드를 가리키면 그 ID로 {@code kind=UNRESOLVED},
     * {@code attrs.expectedPrefix=<ID 접두사>}, {@code attrs.category=<접두사에서 유도한 미해결 분류>}({@link Ids#danglingCategory})인
     * 노드를 만들고 엣지의 신뢰도를 UNRESOLVED로 바꾼다.
     * 만든 노드마다 진단 UNRESOLVED_REFERENCE를 남긴다. 파이프라인이 링커 뒤에 한 번 부른다.
     *
     * @return 새로 만든 UNRESOLVED 노드 수
     */
    public int resolveDanglingEdges() {
        lock.writeLock().lock();
        try {
            int created = 0;
            for (EdgeRef ref : edges.values()) {
                Edge edge = ref.edge;
                boolean dangling = false;
                for (String id : List.of(edge.from(), edge.to())) {
                    if (!nodes.containsKey(id)) {
                        dangling = true;
                        String prefix = Ids.prefixOf(id);
                        nodes.put(id, Node.builder(id, NodeKind.UNRESOLVED)
                                .name(Ids.remainder(id)).qualifiedName(id)
                                .attr(Attrs.DECLARED, false).attr("expectedPrefix", prefix)
                                .attr("category", Ids.danglingCategory(prefix)).build());
                        diagnostics.add(Diagnostic.warn(DiagnosticCodes.UNRESOLVED_REFERENCE,
                                "정의를 찾지 못한 노드를 가리키는 엣지: " + edge.from() + " -" + edge.kind() + "-> " + edge.to(), edge.location()));
                        created++;
                    } else if (createdByFinalization(nodes.get(id))) {
                        dangling = true;
                    }
                }
                if (dangling && edge.confidence() != Confidence.UNRESOLVED) {
                    ref.edge = edge.withConfidence(Confidence.UNRESOLVED);
                }
            }
            return created;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static boolean createdByFinalization(Node node) {
        return node.kind() == NodeKind.UNRESOLVED && node.attrs().containsKey("expectedPrefix");
    }

    // ---- 내부 ----

    private static EdgeKey keyOf(Edge edge) {
        return new EdgeKey(edge.from(), edge.to(), edge.kind(), edge.location());
    }

    private void putEdge(Edge edge) {
        EdgeKey key = keyOf(edge);
        EdgeRef existing = edges.get(key);
        if (existing != null) {
            // 같은 키의 엣지를 다시 넣으면(복원할 때의 중복) 자리는 그대로 두고 값만 바꾼다.
            existing.edge = edge;
            return;
        }
        EdgeRef ref = new EdgeRef(key, nextSequence++, edge);
        edges.put(key, ref);
        outgoing.computeIfAbsent(edge.from(), k -> new Adjacency()).add(ref);
        incoming.computeIfAbsent(edge.to(), k -> new Adjacency()).add(ref);
    }

    private static void unindex(Map<String, Adjacency> index, String nodeId, EdgeRef ref) {
        Adjacency adjacency = index.get(nodeId);
        if (adjacency != null) {
            adjacency.remove(ref);
            if (adjacency.isEmpty()) {
                index.remove(nodeId);
            }
        }
    }

    /** kinds가 null이면 모든 종류. */
    private static List<Edge> lookup(Map<String, Adjacency> index, String nodeId, Set<EdgeKind> kinds) {
        Adjacency adjacency = index.get(nodeId);
        return adjacency == null ? List.of() : adjacency.edges(kinds);
    }

    /** 조회할 종류. 비어 있으면(전부) null. */
    private static Set<EdgeKind> wanted(EdgeKind... kinds) {
        if (kinds == null || kinds.length == 0) {
            return null;
        }
        Set<EdgeKind> wanted = EnumSet.noneOf(EdgeKind.class);
        wanted.addAll(Arrays.asList(kinds));
        return wanted;
    }

    private List<Node> neighbors(List<Edge> edgeList, Function<Edge, String> end) {
        return read(() -> {
            Set<String> ids = new LinkedHashSet<>();
            edgeList.forEach(e -> ids.add(end.apply(e)));
            List<Node> result = new ArrayList<>(ids.size());
            for (String id : ids) {
                Node node = nodes.get(id);
                if (node != null) {
                    result.add(node);
                }
            }
            return List.copyOf(result);
        });
    }

    private <T> T read(java.util.function.Supplier<T> supplier) {
        lock.readLock().lock();
        try {
            return supplier.get();
        } finally {
            lock.readLock().unlock();
        }
    }
}
