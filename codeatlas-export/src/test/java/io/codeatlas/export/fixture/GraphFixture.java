package io.codeatlas.export.fixture;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 테스트용 그래프 빌더. 그래프 계약(F-001 design 3장)대로 노드와 엣지를 손으로 넣는다.
 * 분석기 모듈 없이 출력 모듈만 검증하려고 둔다.
 *
 * <pre>{@code
 * GraphFixture f = GraphFixture.create();
 * f.table("ORDERS", "주문");
 * f.column("ORDERS", "ORDER_ID", "NUMBER(10)", 1, "pk", "notnull");
 * f.fk("ORDERS", "CUSTOMER", "FK_ORDERS_CUSTOMER", List.of("CUSTOMER_ID"), List.of("CUSTOMER_ID"));
 * GraphModel graph = f.graph();
 * }</pre>
 */
public final class GraphFixture {

    private final GraphModel graph = new GraphModel();
    /** 노드별로 {@link #calls}로 더한 호출 수(호출 지점의 줄을 정한다). */
    private final Map<String, Integer> callCounts = new java.util.HashMap<>();

    private GraphFixture() {
    }

    public static GraphFixture create() {
        return new GraphFixture();
    }

    public GraphModel graph() {
        return graph;
    }

    /** 같은 노드·엣지·진단을 거꾸로 된 순서로 넣은 그래프. 출력이 입력 순서에 기대지 않는지 볼 때 쓴다. */
    public GraphModel reversedCopy() {
        List<Node> nodes = new ArrayList<>(graph.nodes());
        List<Edge> edges = new ArrayList<>(graph.edges());
        List<Diagnostic> diagnostics = new ArrayList<>(graph.diagnostics());
        Collections.reverse(nodes);
        Collections.reverse(edges);
        Collections.reverse(diagnostics);
        return GraphModel.restore(graph.project(), nodes, edges, diagnostics);
    }

    // ---- DB ----

    public String table(String name) {
        return table(name, null);
    }

    public String table(String name, String comment) {
        String id = Ids.table(name);
        graph.declare(Node.builder(id, NodeKind.DB_TABLE).name(Ids.remainder(id))
                .location(SourceLocation.of("db/tables.sql", line(id))).attr("comment", comment).build());
        return id;
    }

    public String undeclaredTable(String name) {
        String id = Ids.table(name);
        graph.reference(id, NodeKind.DB_TABLE, Ids.remainder(id));
        return id;
    }

    public String view(String name) {
        String id = Ids.view(name);
        graph.declare(Node.builder(id, NodeKind.DB_VIEW).name(Ids.remainder(id))
                .location(SourceLocation.of("db/views.sql", line(id))).build());
        return id;
    }

    /**
     * 컬럼을 선언하고 테이블(없으면 뷰)에 CONTAINS로 붙인다.
     *
     * @param flags {@code pk}, {@code notnull}, {@code nullable}, {@code unique}, {@code comment=...}, {@code default=...}
     */
    public String column(String table, String name, String dataType, int ordinal, String... flags) {
        String id = Ids.column(table, name);
        Node.Builder b = Node.builder(id, NodeKind.DB_COLUMN).name(Ids.dbName(name))
                .attr("dataType", dataType).attr("ordinal", ordinal);
        for (String flag : flags) {
            switch (flag) {
                case "pk" -> b.attr("primaryKey", true);
                case "notnull" -> b.attr("nullable", false);
                case "nullable" -> b.attr("nullable", true);
                case "unique" -> b.attr("unique", true);
                default -> {
                    if (flag.startsWith("comment=")) {
                        b.attr("comment", flag.substring("comment=".length()));
                    } else if (flag.startsWith("default=")) {
                        b.attr("defaultValue", flag.substring("default=".length()));
                    } else {
                        throw new IllegalArgumentException("모르는 플래그: " + flag);
                    }
                }
            }
        }
        graph.declare(b.build());
        String owner = graph.hasNode(Ids.table(table)) || !graph.hasNode(Ids.view(table)) ? Ids.table(table) : Ids.view(table);
        graph.addEdge(Edge.of(owner, id, EdgeKind.CONTAINS));
        return id;
    }

    /** 속성이 하나도 없는 컬럼(방어 처리 확인용). */
    public String bareColumn(String table, String name) {
        String id = Ids.column(table, name);
        graph.declare(Node.builder(id, NodeKind.DB_COLUMN).name(Ids.dbName(name)).build());
        graph.addEdge(Edge.of(Ids.table(table), id, EdgeKind.CONTAINS));
        return id;
    }

    /** DDL 외래 키. child → parent. */
    public Edge fk(String child, String parent, String constraint, List<String> columns, List<String> refColumns) {
        return graph.addEdge(Edge.builder(Ids.table(child), Ids.table(parent), EdgeKind.REFERENCES)
                .attr("columns", columns).attr("refColumns", refColumns).attr("constraint", constraint)
                .attr("source", "DDL").location(SourceLocation.of("db/constraints.sql", line(child + parent + constraint))).build());
    }

    /** 추론 관계. directionKnown이 null이면 속성을 넣지 않는다. */
    public Edge inferredFk(String child, String parent, List<String> columns, List<String> refColumns, int evidence,
            Boolean directionKnown) {
        return graph.addEdge(Edge.builder(Ids.table(child), Ids.table(parent), EdgeKind.REFERENCES).inferred()
                .attr("columns", columns).attr("refColumns", refColumns).attr("source", "INFERRED")
                .attr("evidenceCount", evidence).attr("directionKnown", directionKnown).build());
    }

    // ---- Java ----

    /** 타입을 선언한다. 패키지 노드와 PACKAGE→TYPE CONTAINS도 만든다. */
    public String type(String binaryName, String typeKind, String... modifiers) {
        String id = Ids.javaType(binaryName);
        String simple = binaryName.substring(Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$')) + 1);
        graph.declare(Node.builder(id, NodeKind.JAVA_TYPE).name(simple).qualifiedName(binaryName)
                .location(SourceLocation.of(sourceFile(binaryName), line(id)))
                .attr("typeKind", typeKind).attr("modifiers", Arrays.asList(modifiers)).build());
        int dot = binaryName.lastIndexOf('.');
        if (dot > 0) {
            String pkg = binaryName.substring(0, dot);
            graph.declare(Node.of(Ids.javaPackage(pkg), NodeKind.JAVA_PACKAGE, pkg));
            if (binaryName.indexOf('$') < 0) {
                graph.addEdge(Edge.of(Ids.javaPackage(pkg), id, EdgeKind.CONTAINS));
            }
        }
        int dollar = binaryName.lastIndexOf('$');
        if (dollar > 0) {
            graph.addEdge(Edge.of(Ids.javaType(binaryName.substring(0, dollar)), id, EdgeKind.CONTAINS));
        }
        return id;
    }

    /** 소스가 없는 라이브러리 타입. */
    public String externalType(String binaryName, String typeKind) {
        String id = Ids.javaType(binaryName);
        graph.reference(id, NodeKind.JAVA_TYPE, binaryName.substring(binaryName.lastIndexOf('.') + 1));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("external", true);
        attrs.put("typeKind", typeKind);
        graph.putAttrs(id, attrs);
        return id;
    }

    public GraphFixture attr(String nodeId, String key, Object value) {
        graph.putAttrs(nodeId, Map.of(key, value));
        return this;
    }

    /** 메서드를 선언하고 타입에 CONTAINS로 붙인다. 매개변수 이름은 p0, p1…로 넣는다. */
    public String method(String typeBinaryName, String name, List<String> parameterTypes, String returnType, String... modifiers) {
        String id = Ids.javaMethod(typeBinaryName, name, parameterTypes);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parameterTypes.size(); i++) {
            names.add("p" + i);
        }
        graph.declare(Node.builder(id, NodeKind.JAVA_METHOD).name(name)
                .qualifiedName(typeBinaryName + "#" + name + "(" + String.join(",", parameterTypes) + ")")
                .location(SourceLocation.of(sourceFile(typeBinaryName), line(id)))
                .attr("modifiers", Arrays.asList(modifiers)).attr("returnType", returnType)
                .attr("parameterTypes", parameterTypes).attr("parameterNames", names)
                .attr("constructor", Ids.CONSTRUCTOR_NAME.equals(name) ? Boolean.TRUE : null).build());
        graph.addEdge(Edge.of(Ids.javaType(typeBinaryName), id, EdgeKind.CONTAINS));
        return id;
    }

    /** 매개변수 없는 public void 메서드. */
    public String method(String typeBinaryName, String name) {
        return method(typeBinaryName, name, List.of(), "void", "public");
    }

    /** 소스가 없는 메서드(참조만). */
    public String externalMethod(String typeBinaryName, String name, String... parameterTypes) {
        String id = Ids.javaMethod(typeBinaryName, name, parameterTypes);
        graph.reference(id, NodeKind.JAVA_METHOD, name);
        return id;
    }

    public String field(String typeBinaryName, String name, String type, String... modifiers) {
        String id = Ids.javaField(typeBinaryName, name);
        graph.declare(Node.builder(id, NodeKind.JAVA_FIELD).name(name).qualifiedName(typeBinaryName + "#" + name)
                .location(SourceLocation.of(sourceFile(typeBinaryName), line(id)))
                .attr("modifiers", Arrays.asList(modifiers)).attr("type", type).build());
        graph.addEdge(Edge.of(Ids.javaType(typeBinaryName), id, EdgeKind.CONTAINS));
        return id;
    }

    // ---- 엣지 ----

    public Edge edge(String from, String to, EdgeKind kind) {
        return graph.addEdge(Edge.of(from, to, kind));
    }

    public Edge edge(String from, String to, EdgeKind kind, Map<String, ?> attrs) {
        return graph.addEdge(Edge.builder(from, to, kind).attrs(attrs).build());
    }

    public Edge edge(Edge edge) {
        return graph.addEdge(edge);
    }

    /** 호출 엣지. 호출 지점은 호출하는 쪽 노드의 파일이고, 줄은 그 노드에서 이 메서드로 호출을 더한 순서대로 늘어난다. */
    public Edge calls(String from, String to) {
        SourceLocation at = graph.node(from).map(Node::location).orElse(null);
        int order = callCounts.merge(from, 1, Integer::sum);
        return graph.addEdge(Edge.builder(from, to, EdgeKind.CALLS)
                .location(at == null ? null : SourceLocation.of(at.file(), at.startLine() + order)).build());
    }

    public Edge typeEdge(String fromBinaryName, String toBinaryName, EdgeKind kind) {
        return edge(Ids.javaType(fromBinaryName), Ids.javaType(toBinaryName), kind);
    }

    // ---- 웹, MyBatis, SQL ----

    public String endpoint(String httpMethod, String path, String handlerMethodId) {
        String id = Ids.httpEndpoint(httpMethod, path);
        graph.declare(Node.builder(id, NodeKind.HTTP_ENDPOINT).name(httpMethod + " " + path)
                .attr("httpMethod", httpMethod).attr("path", path)
                .location(graph.node(handlerMethodId).map(Node::location).orElse(null)).build());
        if (handlerMethodId != null) {
            graph.addEdge(Edge.of(id, handlerMethodId, EdgeKind.ROUTES_TO));
        }
        return id;
    }

    /** 요청 지점. fromNodeId -REQUESTS→ 요청 -MATCHES→ 엔드포인트(null이면 대응 없음). */
    public String request(String httpMethod, String url, String file, int line, String fromNodeId, String endpointId) {
        String id = Ids.httpRequest(httpMethod, url, file, line);
        graph.declare(Node.builder(id, NodeKind.HTTP_REQUEST).name(httpMethod + " " + url)
                .location(SourceLocation.of(file, line)).attr("httpMethod", httpMethod).attr("url", url).attr("path", url).build());
        graph.addEdge(Edge.of(fromNodeId, id, EdgeKind.REQUESTS, SourceLocation.of(file, line)));
        if (endpointId != null) {
            graph.addEdge(Edge.of(id, endpointId, EdgeKind.MATCHES));
        }
        return id;
    }

    public String jsModule(String path) {
        String id = Ids.jsModule(path);
        graph.declare(Node.builder(id, NodeKind.JS_MODULE).name(path).location(SourceLocation.ofFile(path)).build());
        return id;
    }

    public String jsFunction(String path, String namePath, int line) {
        String id = Ids.jsFunction(path, namePath);
        graph.declare(Node.builder(id, NodeKind.JS_FUNCTION).name(namePath).qualifiedName(path + "#" + namePath)
                .location(SourceLocation.of(path, line)).build());
        graph.addEdge(Edge.of(Ids.jsModule(path), id, EdgeKind.CONTAINS));
        return id;
    }

    public String jsp(String path) {
        String id = Ids.jspPage(path);
        graph.declare(Node.builder(id, NodeKind.JSP_PAGE).name(path).location(SourceLocation.ofFile(path)).build());
        return id;
    }

    public String statement(String namespace, String statementId, String statementType) {
        String id = Ids.mybatisStatement(namespace, statementId);
        String file = "src/main/resources/mapper/" + namespace.substring(namespace.lastIndexOf('.') + 1) + ".xml";
        graph.declare(Node.builder(id, NodeKind.SQL_STATEMENT).name(statementId).qualifiedName(namespace + "." + statementId)
                .location(SourceLocation.of(file, line(id)))
                .attr("statementType", statementType).attr("namespace", namespace).attr("statementId", statementId).build());
        graph.declare(Node.of(Ids.mybatisMapper(namespace), NodeKind.MYBATIS_MAPPER, namespace));
        graph.addEdge(Edge.of(Ids.mybatisMapper(namespace), id, EdgeKind.CONTAINS));
        return id;
    }

    public String routine(String name, String routineType) {
        String id = Ids.routine(name);
        graph.declare(Node.builder(id, NodeKind.DB_ROUTINE).name(Ids.remainder(id))
                .location(SourceLocation.of("db/" + name.toLowerCase() + ".prc", 1)).attr("routineType", routineType).build());
        return id;
    }

    /** 루틴 안의 구문. ROUTINE -CONTAINS→ 구문. */
    public String routineStatement(String routineId, int line, String statementType) {
        String id = Ids.routineStatement(routineId, line);
        graph.declare(Node.builder(id, NodeKind.SQL_STATEMENT)
                .location(SourceLocation.of("db/" + Ids.remainder(routineId).toLowerCase() + ".prc", line))
                .attr("statementType", statementType).build());
        graph.addEdge(Edge.of(routineId, id, EdgeKind.CONTAINS));
        return id;
    }

    /** 트리거 루틴. TRIGGER -TRIGGERS_ON→ 테이블. event가 null이면 {@code triggerEvent}를 넣지 않는다. */
    public String trigger(String name, String table, String event) {
        String id = routine(name, "TRIGGER");
        if (event != null) {
            graph.putAttrs(id, Map.of("triggerEvent", event));
        }
        graph.addEdge(Edge.of(id, Ids.table(table), EdgeKind.TRIGGERS_ON));
        return id;
    }

    /**
     * 대상 테이블을 정적으로 알 수 없는 동적 SQL의 접근(sql 분석기의 표현 그대로).
     * 구문 -READS/WRITES(UNRESOLVED)→ {@code unresolved:sql-dynamic:<구문 ID>}. WRITES면 op를 붙인다.
     */
    public Edge dynamicAccess(String statementId, EdgeKind kind, String op, String text) {
        String target = Ids.unresolved("sql-dynamic", statementId);
        SourceLocation at = graph.node(statementId).map(Node::location).orElse(null);
        graph.declare(Node.builder(target, NodeKind.UNRESOLVED).name(text).qualifiedName(statementId).location(at)
                .attr("category", "sql-dynamic").build());
        return graph.addEdge(Edge.builder(statementId, target, kind).unresolved().location(at).attr("text", text).attr("op", op).build());
    }

    /** 컬럼으로만 가는 READS(테이블 수준 엣지는 만들지 않는다). UPDATE·DELETE의 조건절 컬럼이 이렇게 나온다. */
    public GraphFixture readsColumn(String statementId, String table, String column) {
        String columnId = Ids.column(table, column);
        graph.reference(columnId, NodeKind.DB_COLUMN, Ids.dbName(column));
        graph.addEdge(Edge.builder(statementId, columnId, EdgeKind.READS).location(graph.node(statementId).map(Node::location).orElse(null)).build());
        return this;
    }

    /** 구문 → 테이블 READS. 컬럼을 주면 컬럼 엣지도 만든다(없는 컬럼은 참조 노드로). */
    public GraphFixture reads(String statementId, String table, String... columns) {
        access(statementId, table, EdgeKind.READS, null, columns);
        return this;
    }

    /** 구문 → 테이블 WRITES. op는 INSERT, UPDATE, DELETE, MERGE. */
    public GraphFixture writes(String statementId, String table, String op, String... columns) {
        access(statementId, table, EdgeKind.WRITES, op, columns);
        return this;
    }

    private void access(String statementId, String table, EdgeKind kind, String op, String... columns) {
        String tableId = graph.hasNode(Ids.view(table)) && !graph.hasNode(Ids.table(table)) ? Ids.view(table) : Ids.table(table);
        if (!graph.hasNode(tableId)) {
            graph.reference(tableId, NodeKind.DB_TABLE, Ids.remainder(tableId));
        }
        SourceLocation at = graph.node(statementId).map(Node::location).orElse(null);
        graph.addEdge(Edge.builder(statementId, tableId, kind).attr("op", op).location(at).build());
        for (String column : columns) {
            String columnId = Ids.column(table, column);
            graph.reference(columnId, NodeKind.DB_COLUMN, Ids.dbName(column));
            graph.addEdge(Edge.builder(statementId, columnId, kind).attr("op", op).location(at).build());
        }
    }

    public String job(String name) {
        String id = Ids.batchJob(name);
        graph.declare(Node.builder(id, NodeKind.BATCH_JOB).name(name).location(SourceLocation.of("src/main/resources/batch/" + name + ".xml", 3)).build());
        return id;
    }

    public String step(String job, String name) {
        String id = Ids.batchStep(job, name);
        graph.declare(Node.builder(id, NodeKind.BATCH_STEP).name(name).location(SourceLocation.of("src/main/resources/batch/" + job + ".xml", line(id))).build());
        if (!Ids.UNKNOWN_JOB.equals(job)) {
            graph.addEdge(Edge.of(Ids.batchJob(job), id, EdgeKind.CONTAINS));
        }
        return id;
    }

    // ---- 보조 ----

    private static String sourceFile(String binaryName) {
        int dollar = binaryName.indexOf('$');
        String top = dollar < 0 ? binaryName : binaryName.substring(0, dollar);
        return "src/main/java/" + top.replace('.', '/') + ".java";
    }

    /** ID에서 정해지는 줄 번호. 넣는 순서와 무관하게 같아야 결정적 출력 테스트를 할 수 있다. */
    private static int line(String key) {
        return 1 + Math.floorMod(key.hashCode(), 200);
    }
}
