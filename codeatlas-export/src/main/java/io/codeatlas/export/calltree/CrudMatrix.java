package io.codeatlas.export.calltree;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.calltree.FlowNavigator.Step;
import io.codeatlas.export.common.DataLoads;
import io.codeatlas.export.common.DbGraph;
import io.codeatlas.export.common.NodeLabels;
import io.codeatlas.export.common.Texts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * CRUD 매트릭스. 주체(구문, 메서드, 엔드포인트, 루틴)가 테이블마다 무엇을 하는지(C, R, U, D)를 모은다.
 * MERGE는 C와 U다. 종류를 모르는 쓰기는 W로 적는다.
 *
 * <p>글자를 정하는 규칙:
 * <ul>
 * <li>R은 테이블 수준 READS 엣지가 있을 때만 붙는다. 컬럼으로 가는 READS(조건절의 컬럼 등)는 세지 않는다:
 *     {@code DELETE FROM T WHERE x = ?}는 {@code D}, 단순 UPDATE는 {@code U}다.</li>
 * <li>대문자는 주체가 직접 하는 접근, 소문자는 DB 루틴(프로시저·함수·트리거)을 거쳐서만 닿는 접근이다. 같은 글자가 둘 다면 대문자다.
 *     구문은 자기 SQL이 직접, 구문이 부른 루틴(과 그 루틴이 부른 루틴)의 구문이 간접이다. 메서드는 직접 EXECUTES하는 구문이 직접이다.
 *     루틴은 자기 본문의 구문이 직접, 부른 루틴의 구문이 간접이다. 엔드포인트는 호출 사슬로 닿은 구문 가운데 루틴 본문의 것이 간접이다.
 *     예: {@code Ru}는 직접 읽고 루틴을 거쳐 갱신한다는 뜻이다. 칸을 대문자로 바꾸면 직접·간접을 합친 CRUD다.</li>
 * <li>대상 테이블을 정적으로 알 수 없는 동적 구문의 접근은 버리지 않고 마지막 열 {@value #DYNAMIC_COLUMN}에 모은다
 *     (그런 구문이 있을 때만 생기는 열이다). 구문 목록은 {@link #dynamicStatements()}다.</li>
 * <li>추론한 관계를 거쳐서만 닿은 칸은 끝에 {@value #INFERRED_MARK}가 붙는다(예: {@code R?}, {@code ru?}). 그 테이블에 닿는 확정 경로가
 *     하나라도 있으면 붙지 않는다. 엔드포인트는 호출 사슬의 걸음 가운데 추론 걸음({@link FlowNavigator.Step#inference()}: 콜트리의
 *     {@code ~}·{@code [?]}·{@code [trigger?]}. 요청 전 처리와 수신자 문맥이 대상 하나를 고른 상속 구문은 확정)을 거쳤는지로,
 *     메서드는 그 구문(루틴)으로 가는 {@code EXECUTES} 자체가 추론인지로 정한다. 구문·루틴 기준에는 붙지 않는다.</li>
 * </ul>
 *
 * <pre>{@code
 * CrudMatrix matrix = CrudMatrix.of(graph, Subject.ENDPOINT);
 * Files.writeString(out, matrix.toCsv(Layout.MATRIX, true));   // 엑셀용 UTF-8 BOM
 * }</pre>
 */
public final class CrudMatrix {

    /** 매트릭스의 주체. */
    public enum Subject {
        /** SQL 구문. */
        STATEMENT("구문"),
        /** 구문(또는 루틴)을 직접 EXECUTES하는 Java 메서드. 실행한 구문이 부르는 루틴의 구문까지 넣는다(소문자). */
        METHOD("메서드"),
        /** 호출 사슬로 구문에 닿는 엔드포인트. */
        ENDPOINT("엔드포인트"),
        /** 구문을 가진 루틴(프로시저, 함수, 트리거). 루틴이 부르는 루틴의 구문까지 넣는다(소문자). */
        ROUTINE("루틴");

        private final String title;

        Subject(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** 출력 모양. */
    public enum Layout {
        /** 행이 주체, 열이 테이블. */
        MATRIX,
        /** 행이 테이블, 열이 주체. */
        MATRIX_BY_TABLE,
        /** 한 줄에 (테이블, 주체, CRUD, 위치) 하나. */
        LIST
    }

    /**
     * 주체 하나.
     *
     * @param id 주체 노드 ID
     * @param label 표시 이름(구문과 메서드는 전체 이름)
     * @param location {@code file:line}. 없으면 null
     * @param cells 테이블 노드 ID → {@code CRUD} 글자(루틴을 거친 간접 접근은 소문자, 추론 경로로만 닿았으면 끝에 {@code ?}).
     *        동적 구문의 접근이 있으면 {@link #DYNAMIC_COLUMN} 키도 들어 있다
     */
    public record Row(String id, String label, String location, Map<String, String> cells) {
        public Row {
            cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        }
    }

    /**
     * 대상 테이블을 정적으로 알 수 없는 접근을 모으는 열의 이름이자 {@link Row#cells()}의 키.
     * 동적 구문이 하나라도 닿는 매트릭스에만 마지막 열(행·열을 바꾼 모양에서는 마지막 행)로 나온다.
     */
    public static final String DYNAMIC_COLUMN = "(동적)";

    /** 추론한 관계를 거쳐서만 닿은 칸의 끝에 붙는 표시. */
    public static final String INFERRED_MARK = "?";

    /** 호출 사슬과 루틴 호출을 따라갈 기본 최대 걸음 수. */
    public static final int DEFAULT_MAX_DEPTH = 20;

    private final Subject subject;
    private final List<Row> rows;
    private final List<String> tableIds;
    private final Map<String, String> tableNames;
    private final boolean dynamicColumn;
    private final List<DynamicStatement> dynamicStatements;

    private CrudMatrix(Subject subject, List<Row> rows, Map<String, String> tableNames, List<DynamicStatement> dynamicStatements) {
        this.subject = subject;
        this.rows = List.copyOf(rows);
        this.tableNames = tableNames;
        List<String> ids = new ArrayList<>(tableNames.keySet());
        ids.sort(Comparator.comparing((String id) -> tableNames.get(id)).thenComparing(Comparator.naturalOrder()));
        this.tableIds = List.copyOf(ids);
        this.dynamicColumn = rows.stream().anyMatch(row -> row.cells().containsKey(DYNAMIC_COLUMN));
        this.dynamicStatements = List.copyOf(dynamicStatements);
    }

    public static CrudMatrix of(GraphModel graph, Subject subject) {
        return of(graph, subject, DEFAULT_MAX_DEPTH);
    }

    /**
     * @param maxDepth 따라갈 최대 걸음 수. 엔드포인트는 엔드포인트에서 구문까지의 걸음 수다. 구문·메서드·루틴은 루틴 호출의 걸음 수다
     *        (구문→루틴, 메서드→루틴, 루틴→루틴이 한 걸음. 0이면 직접 접근만 센다)
     */
    public static CrudMatrix of(GraphModel graph, Subject subject, int maxDepth) {
        return of(graph, subject, maxDepth, false);
    }

    /**
     * @param maxDepth {@link #of(GraphModel, Subject, int)}와 같다
     * @param includeDataLoad true면 데이터 적재 구문({@code attrs.dataLoad=true}: 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 것)도
     *        넣는다. 기본은 뺀다(구문 기준 매트릭스의 행이 데이터 스크립트로 채워지지 않게)
     */
    public static CrudMatrix of(GraphModel graph, Subject subject, int maxDepth, boolean includeDataLoad) {
        Set<String> dataLoads = includeDataLoad ? Set.of() : DataLoads.ids(graph);
        // 구문 ID → (테이블 ID → 글자). 컬럼으로 가는 READS는 R로 세지 않는다. 동적 접근은 DYNAMIC_COLUMN에 모은다.
        Map<String, Map<String, Set<Character>>> byStatement = new HashMap<>();
        for (EdgeKind kind : List.of(EdgeKind.READS, EdgeKind.WRITES)) {
            for (Edge e : graph.edges(kind)) {
                if (dataLoads.contains(e.from())) {
                    continue;
                }
                String table = DynamicStatement.isDynamicAccess(graph, e) ? DYNAMIC_COLUMN : DbGraph.accessedTable(graph, e).orElse(null);
                List<Character> letters = DYNAMIC_COLUMN.equals(table) ? Access.letters(graph, e, e.from()) : Access.tableLetters(graph, e, e.from());
                if (table != null && !letters.isEmpty()) {
                    byStatement.computeIfAbsent(e.from(), k -> new HashMap<>()).computeIfAbsent(table, k -> new HashSet<>()).addAll(letters);
                }
            }
        }
        Map<String, Map<String, Set<Character>>> bySubject = new TreeMap<>();
        // 주체 → 확정 경로로 닿은 테이블. 여기 없는 칸은 추론 경로로만 닿은 것이다(구문·루틴 기준에서는 보지 않는다).
        Map<String, Set<String>> confirmed = new HashMap<>();
        boolean marksInference = subject == Subject.METHOD || subject == Subject.ENDPOINT;
        switch (subject) {
            case STATEMENT -> {
                // 테이블에 직접 접근하는 구문과, 루틴을 부르는 구문(CALL, SQL 안의 함수 호출)
                Set<String> statements = new HashSet<>(byStatement.keySet());
                for (Edge e : graph.edges(EdgeKind.CALLS)) {
                    // 값 자리에서 함수를 부르는 데이터 적재 구문도 적재 구문이다: 테이블 접근과 같은 규칙으로 뺀다.
                    if (!dataLoads.contains(e.from()) && kindOf(graph, e.from()) == NodeKind.SQL_STATEMENT
                            && kindOf(graph, e.to()) == NodeKind.DB_ROUTINE) {
                        statements.add(e.from());
                    }
                }
                for (String statement : statements) {
                    merge(bySubject, statement, byStatement.get(statement), false);
                    mergeRoutines(graph, bySubject, statement, calledRoutines(graph, List.of(statement)), maxDepth - 1, Set.of(), byStatement);
                }
            }
            case METHOD -> {
                for (Edge e : graph.edges(EdgeKind.EXECUTES)) {
                    if (kindOf(graph, e.from()) != NodeKind.JAVA_METHOD) {
                        continue;
                    }
                    // 이 EXECUTES로 닿은 테이블만 따로 모아, 엣지가 확정이면 그 칸들을 확정으로 적는다.
                    Map<String, Map<String, Set<Character>>> reached = new HashMap<>();
                    if (kindOf(graph, e.to()) == NodeKind.DB_ROUTINE) {
                        mergeRoutines(graph, reached, e.from(), List.of(e.to()), maxDepth - 1, Set.of(), byStatement);
                    } else {
                        merge(reached, e.from(), byStatement.get(e.to()), false);
                        mergeRoutines(graph, reached, e.from(), calledRoutines(graph, List.of(e.to())), maxDepth - 1, Set.of(), byStatement);
                    }
                    Map<String, Set<Character>> access = reached.get(e.from());
                    if (access != null) {
                        Map<String, Set<Character>> target = bySubject.computeIfAbsent(e.from(), k -> new HashMap<>());
                        access.forEach((table, letters) -> target.computeIfAbsent(table, k -> new HashSet<>()).addAll(letters));
                        if (e.confidence() != Confidence.INFERRED) {
                            confirmed.computeIfAbsent(e.from(), k -> new HashSet<>()).addAll(access.keySet());
                        }
                    }
                }
            }
            case ROUTINE -> {
                for (Node routine : graph.nodes(NodeKind.DB_ROUTINE)) {
                    List<String> own = statementsOf(graph, routine.id());
                    for (String statement : own) {
                        merge(bySubject, routine.id(), byStatement.get(statement), false);
                    }
                    List<String> callers = new ArrayList<>(own);
                    callers.add(routine.id());
                    mergeRoutines(graph, bySubject, routine.id(), calledRoutines(graph, callers), maxDepth - 1, Set.of(routine.id()), byStatement);
                }
            }
            case ENDPOINT -> {
                FlowNavigator navigator = new FlowNavigator(graph, true, true, includeDataLoad);
                for (Node endpoint : graph.nodes(NodeKind.HTTP_ENDPOINT)) {
                    reachable(navigator, endpoint.id(), maxDepth).forEach((reached, inferredOnly) -> {
                        Map<String, Set<Character>> access = byStatement.get(reached);
                        merge(bySubject, endpoint.id(), access, inRoutine(graph, reached));
                        if (access != null && !inferredOnly) {
                            confirmed.computeIfAbsent(endpoint.id(), k -> new HashSet<>()).addAll(access.keySet());
                        }
                    });
                }
            }
            default -> {
            }
        }
        List<Row> rows = new ArrayList<>();
        Map<String, String> tableNames = new HashMap<>();
        for (Map.Entry<String, Map<String, Set<Character>>> e : bySubject.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            Map<String, String> cells = new TreeMap<>();
            Set<String> sure = confirmed.getOrDefault(e.getKey(), Set.of());
            e.getValue().forEach((table, letters) -> {
                cells.put(table, Access.format(letters) + (marksInference && !sure.contains(table) ? INFERRED_MARK : ""));
                if (!DYNAMIC_COLUMN.equals(table)) {
                    tableNames.put(table, Ids.remainder(table));
                }
            });
            rows.add(new Row(e.getKey(), subjectLabel(graph, subject, e.getKey()), NodeLabels.location(graph, e.getKey()), cells));
        }
        return new CrudMatrix(subject, rows, tableNames, subject == Subject.STATEMENT ? DynamicStatement.find(graph) : List.of());
    }

    private static NodeKind kindOf(GraphModel graph, String id) {
        return NodeLabels.kindOf(graph, id);
    }

    /** 루틴이 직접 가진 구문(CONTAINS, ID 순). */
    private static List<String> statementsOf(GraphModel graph, String routineId) {
        List<String> statements = new ArrayList<>();
        for (Edge e : graph.outgoing(routineId, EdgeKind.CONTAINS)) {
            if (kindOf(graph, e.to()) == NodeKind.SQL_STATEMENT && !statements.contains(e.to())) {
                statements.add(e.to());
            }
        }
        statements.sort(Comparator.naturalOrder());
        return statements;
    }

    /** 구문이나 루틴이 CALLS로 부르는 루틴(중복 없이). */
    private static List<String> calledRoutines(GraphModel graph, List<String> callerIds) {
        List<String> routines = new ArrayList<>();
        for (String caller : callerIds) {
            for (Edge e : graph.outgoing(caller, EdgeKind.CALLS)) {
                if (kindOf(graph, e.to()) == NodeKind.DB_ROUTINE && !routines.contains(e.to())) {
                    routines.add(e.to());
                }
            }
        }
        return routines;
    }

    /** 구문이 DB 루틴(프로시저·함수·트리거)의 본문에 든 것인지. */
    private static boolean inRoutine(GraphModel graph, String statementId) {
        for (Edge e : graph.incoming(statementId, EdgeKind.CONTAINS)) {
            if (kindOf(graph, e.from()) == NodeKind.DB_ROUTINE) {
                return true;
            }
        }
        return false;
    }

    /**
     * 루틴들과 그 루틴이 (구문 안에서든 본문에서든) 부르는 루틴의 구문이 하는 접근을 주체의 간접 접근(소문자)으로 더한다.
     *
     * @param hops 처음 루틴들에서 더 따라갈 루틴 호출의 걸음 수. 음수면 아무것도 더하지 않는다
     * @param skip 다시 들어가지 않을 루틴(주체 자신). 재귀와 상호 호출에서 자기 구문을 간접으로 세지 않게 한다
     */
    private static void mergeRoutines(GraphModel graph, Map<String, Map<String, Set<Character>>> bySubject, String subjectId,
            List<String> routines, int hops, Set<String> skip, Map<String, Map<String, Set<Character>>> byStatement) {
        Set<String> seen = new HashSet<>(skip);
        List<String> frontier = new ArrayList<>();
        for (String routine : routines) {
            if (seen.add(routine)) {
                frontier.add(routine);
            }
        }
        for (int hop = 0; hop <= hops && !frontier.isEmpty(); hop++) {
            List<String> next = new ArrayList<>();
            for (String routine : frontier) {
                List<String> statements = statementsOf(graph, routine);
                for (String statement : statements) {
                    merge(bySubject, subjectId, byStatement.get(statement), true);
                }
                List<String> callers = new ArrayList<>(statements);
                callers.add(routine);
                for (String called : calledRoutines(graph, callers)) {
                    if (seen.add(called)) {
                        next.add(called);
                    }
                }
            }
            frontier = next;
        }
    }

    /** 접근 글자를 주체에 더한다. indirect면 소문자로 더한다(출력할 때 같은 글자의 대문자가 있으면 대문자가 이긴다). */
    private static void merge(Map<String, Map<String, Set<Character>>> bySubject, String subjectId, Map<String, Set<Character>> access,
            boolean indirect) {
        if (access == null) {
            return;
        }
        Map<String, Set<Character>> target = bySubject.computeIfAbsent(subjectId, k -> new HashMap<>());
        access.forEach((table, letters) -> {
            Set<Character> cell = target.computeIfAbsent(table, k -> new HashSet<>());
            for (char letter : letters) {
                cell.add(indirect ? Character.toLowerCase(letter) : letter);
            }
        });
    }

    /**
     * 엔드포인트에서 아래 방향으로 닿는 노드. 화면 흐름(RENDERS, REQUESTS, MATCHES)과 redirect로 넘어가는 핸들러는 따라가지 않는다
     * (redirect는 브라우저가 새로 보내는 별개의 요청이다). forward로 넘어가는 핸들러는 같은 요청의 처리라서 따라간다.
     *
     * @return 노드 ID → 추론 걸음을 거쳐서만 닿았으면 true(확정 경로가 하나라도 있으면 false).
     *         추론으로 먼저 닿은 자리에 확정 경로로 다시 닿으면 그 아래를 다시 따라간다
     */
    private static Map<String, Boolean> reachable(FlowNavigator navigator, String startId, int maxDepth) {
        record Visit(String id, String parent, Step arrival, boolean inferred, String key) {
        }
        Map<String, Boolean> nodes = new HashMap<>();
        // 방문 표시의 키 → 추론으로만 닿았는지
        Map<String, Boolean> seen = new HashMap<>();
        List<Visit> frontier = List.of(new Visit(startId, null, null, false, startId));
        seen.put(startId, false);
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            List<Visit> next = new ArrayList<>();
            for (Visit v : frontier) {
                // 같은 자리에 확정 경로로 다시 닿았으면 그 방문이 아래를 맡는다.
                if (v.inferred() && !seen.get(v.key())) {
                    continue;
                }
                for (Step step : navigator.callees(v.id(), v.parent(), v.arrival())) {
                    if (step.via() == EdgeKind.RENDERS || step.via() == EdgeKind.REQUESTS || step.via() == EdgeKind.MATCHES
                            || step.redirect() || step.layout()) {
                        continue;
                    }
                    // 문맥(직전 노드, 수신자 타입)에 따라 아래가 달라지는 노드는 문맥별로 따로 방문한다.
                    String key = (navigator.contextSensitive(step.nodeId()) ? step.nodeId() + "|" + v.id() + "|" + step.via() : step.nodeId())
                            + navigator.arrivalKey(step.nodeId(), step);
                    boolean inferred = v.inferred() || step.inferred();
                    Boolean before = seen.get(key);
                    if (before == null || (before && !inferred)) {
                        seen.put(key, inferred);
                        nodes.merge(step.nodeId(), inferred, Boolean::logicalAnd);
                        next.add(new Visit(step.nodeId(), v.id(), step, inferred, key));
                    }
                }
            }
            frontier = next;
        }
        return nodes;
    }

    private static String subjectLabel(GraphModel graph, Subject subject, String id) {
        return switch (subject) {
            case METHOD -> NodeLabels.methodLabel(Ids.remainder(id), true);
            case STATEMENT -> Ids.remainder(id);
            default -> NodeLabels.label(graph, id);
        };
    }

    public Subject subject() {
        return subject;
    }

    /** 주체(노드 ID 순). 테이블 접근(동적 구문의 접근 포함)이 하나도 없는 주체는 없다. */
    public List<Row> rows() {
        return rows;
    }

    /** 열로 나오는 테이블 이름(이름순). {@link #DYNAMIC_COLUMN} 열은 넣지 않는다. */
    public List<String> tables() {
        return tableIds.stream().map(tableNames::get).toList();
    }

    /** 대상 테이블을 알 수 없는 동적 구문의 접근이 있어 {@link #DYNAMIC_COLUMN} 열이 나오는지. */
    public boolean hasDynamicColumn() {
        return dynamicColumn;
    }

    /**
     * 대상 테이블을 정적으로 알 수 없는 구문(구문 ID 순). 구문 기준 매트릭스({@link Subject#STATEMENT})에만 채운다.
     * 다른 주체의 매트릭스에서는 비어 있고 {@link #DYNAMIC_COLUMN} 열만 나온다.
     */
    public List<DynamicStatement> dynamicStatements() {
        return dynamicStatements;
    }

    /**
     * @param table 테이블 이름 또는 노드 ID, 또는 {@link #DYNAMIC_COLUMN}
     * @return {@code CRUD} 글자(루틴을 거친 간접 접근은 소문자, 추론 경로로만 닿았으면 끝에 {@code ?}). 접근이 없으면 빈 문자열
     */
    public String cell(String subjectId, String table) {
        for (Row row : rows) {
            if (row.id().equals(subjectId)) {
                for (Map.Entry<String, String> e : row.cells().entrySet()) {
                    if (e.getKey().equals(table) || table.equals(tableNames.get(e.getKey()))) {
                        return e.getValue();
                    }
                }
            }
        }
        return "";
    }

    /**
     * CSV(RFC 4180, 줄 끝 {@code \n}).
     *
     * @param bom true면 UTF-8 BOM을 앞에 붙인다(엑셀이 한글을 깨뜨리지 않게)
     */
    public String toCsv(Layout layout, boolean bom) {
        StringBuilder out = new StringBuilder(bom ? "﻿" : "");
        for (List<String> line : table(layout, true)) {
            for (int i = 0; i < line.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(Texts.csvCell(line.get(i)));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Markdown 표. MATRIX 모양에서는 위치 열을 뺀다.
     * 동적 구문이 있으면({@link #dynamicStatements()}) 표 뒤에 "대상 테이블을 정적으로 알 수 없는 구문" 목록(구문, CRUD, 위치)을 붙인다.
     */
    public String toMarkdown(Layout layout) {
        StringBuilder out = new StringBuilder();
        List<List<String>> lines = table(layout, layout == Layout.LIST);
        if (rows.isEmpty()) {
            return "없음\n";
        }
        for (int n = 0; n < lines.size(); n++) {
            List<String> line = lines.get(n);
            out.append('|');
            for (int i = 0; i < line.size(); i++) {
                String cell = Texts.nullToEmpty(line.get(i));
                boolean name = n > 0 && (layout == Layout.LIST ? (i == 1 || i == 3) : (i == 0 && layout == Layout.MATRIX));
                out.append(' ').append(name ? Texts.mdCode(cell) : Texts.mdCell(cell)).append(" |");
            }
            out.append('\n');
            if (n == 0) {
                out.append('|').append("---|".repeat(line.size())).append('\n');
            }
        }
        if (!dynamicStatements.isEmpty()) {
            out.append("\n**대상 테이블을 정적으로 알 수 없는 구문 (").append(dynamicStatements.size()).append(")**\n\n");
            out.append("| 구문 | CRUD | 위치 |\n|---|---|---|\n");
            for (DynamicStatement d : dynamicStatements) {
                out.append("| ").append(Texts.mdCode(Ids.remainder(d.id()))).append(" | ").append(d.access()).append(" | ")
                        .append(Texts.mdCode(d.location())).append(" |\n");
            }
        }
        return out.toString();
    }

    /** 테이블 열(이름순)과, 동적 구문의 접근이 있으면 그 뒤의 {@link #DYNAMIC_COLUMN}. */
    private List<String> columnIds() {
        if (!dynamicColumn) {
            return tableIds;
        }
        List<String> ids = new ArrayList<>(tableIds);
        ids.add(DYNAMIC_COLUMN);
        return ids;
    }

    private String columnName(String id) {
        return DYNAMIC_COLUMN.equals(id) ? DYNAMIC_COLUMN : tableNames.get(id);
    }

    /** 머리줄을 포함한 칸들. */
    private List<List<String>> table(Layout layout, boolean withLocation) {
        List<List<String>> lines = new ArrayList<>();
        switch (layout) {
            case MATRIX -> {
                List<String> header = new ArrayList<>(List.of(subject.title()));
                if (withLocation) {
                    header.add("위치");
                }
                columnIds().forEach(id -> header.add(columnName(id)));
                lines.add(header);
                for (Row row : rows) {
                    List<String> line = new ArrayList<>(List.of(row.label()));
                    if (withLocation) {
                        line.add(Texts.nullToEmpty(row.location()));
                    }
                    columnIds().forEach(id -> line.add(row.cells().getOrDefault(id, "")));
                    lines.add(line);
                }
            }
            case MATRIX_BY_TABLE -> {
                List<String> header = new ArrayList<>(List.of("테이블"));
                rows.forEach(row -> header.add(row.label()));
                lines.add(header);
                for (String id : columnIds()) {
                    List<String> line = new ArrayList<>(List.of(columnName(id)));
                    rows.forEach(row -> line.add(row.cells().getOrDefault(id, "")));
                    lines.add(line);
                }
            }
            case LIST -> {
                lines.add(List.of("테이블", subject.title(), "CRUD", "위치"));
                for (String id : columnIds()) {
                    for (Row row : rows) {
                        String letters = row.cells().get(id);
                        if (letters != null) {
                            lines.add(List.of(columnName(id), row.label(), letters, Texts.nullToEmpty(row.location())));
                        }
                    }
                }
            }
            default -> {
            }
        }
        return lines;
    }
}
