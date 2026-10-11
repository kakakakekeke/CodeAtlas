package io.codeatlas.link;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Linker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 테이블 관계 추론(F-018). FK 제약이 없어도 SQL의 조인 조건(JOINS)과 키 정보로 REFERENCES(INFERRED)를 만든다.
 *
 * <p>테이블 쌍과 컬럼 대응 집합별로 근거 구문을 모으고, 한쪽 컬럼 집합만 그 테이블의 PK(또는 UNIQUE) 전체이면 그쪽을 부모로 본다.
 * 양쪽 다 키이거나 어느 쪽도 키가 아니면 방향을 정하지 않는다({@code directionKnown=false}, ID 사전순 작은 쪽 → 큰 쪽).
 * 같은 컬럼 대응의 DDL 관계가 있으면 만들지 않는다. DB 객체 정규화(order 100) 뒤에 돈다.
 * 키 정보가 없는 쪽은 유일 쪽(부모)으로 판정하지 않는다. 뷰가 낀 조인은 옵션 {@value #VIEW_RELATIONS_OPTION}을 켰을 때만
 * 관계로 만들고, 그때도 방향을 정하지 않는다(F-018-R11).
 *
 * <p>어느 쪽도 키가 아닐 때는 두 가지를 더 본다.
 * <ul>
 * <li>상수로 채운 키(F-018-R9): 조인 컬럼이 복합 키의 일부이고 나머지 키 컬럼이 같은 구문에서 리터럴·바인드로 고정돼 있으면
 * ({@code JOINS}의 {@code leftFixedColumns}/{@code rightFixedColumns}) 그쪽을 유일 쪽으로 본다. 공통코드 테이블 조인이 이 꼴이다.</li>
 * <li>형제 조인(F-018-R10): 양쪽 조인 컬럼이 같은 부모의 같은 컬럼을 참조(DDL 또는 이번 실행의 N:1 추론)하면 관계로 만들지 않는다.</li>
 * </ul>
 * 그래서 두 단계로 돈다: 묶음마다 방향을 먼저 판정하고, 확정된 N:1을 모은 뒤에 방향 미상 후보를 거르고 기록한다.
 */
public final class RelationInferenceLinker implements Linker {

    /** 최소 근거 구문 수 옵션({@code AnalysisOptions.extra}). 기본 1. */
    static final String MIN_EVIDENCE_OPTION = "link.minJoinEvidence";
    /** 뷰가 낀 조인으로도 관계를 추론할지({@code AnalysisOptions.extra}). 기본 false. */
    static final String VIEW_RELATIONS_OPTION = "link.inferViewRelations";
    static final String RELATION_NOT_RECORDED = "RELATION_NOT_RECORDED";
    private static final int MAX_EVIDENCE = 10;
    private static final int MAX_FIXED_VALUES = 10;

    /** 정규화한 컬럼 대응 하나. lo는 ID가 작은 쪽 테이블의 컬럼, hi는 큰 쪽 테이블의 컬럼. */
    private record Pair(String lo, String hi) implements Comparable<Pair> {
        @Override
        public int compareTo(Pair o) {
            int c = lo.compareTo(o.lo);
            return c != 0 ? c : hi.compareTo(o.hi);
        }
    }

    private record Key(String lo, String hi, List<Pair> pairs) {
    }

    /**
     * 근거 JOINS 엣지 하나.
     *
     * @param loFixed lo 쪽 테이블 인스턴스의 고정 컬럼(같은 구문에서 상수와 등치)
     * @param loValues lo 쪽 고정 값({@code COL=값})
     */
    private record Evidence(String statement, SourceLocation location, Set<String> loFixed, Set<String> hiFixed,
            List<String> loValues, List<String> hiValues) {
    }

    private static final class Group {
        final List<Evidence> evidence = new ArrayList<>();
    }

    private record TableKeys(Set<String> primaryKey, List<String> primaryKeyOrder, List<Set<String>> uniqueKeys) {
        boolean isKey(Set<String> columns) {
            return (!primaryKey.isEmpty() && primaryKey.equals(columns)) || uniqueKeys.contains(columns);
        }

        /**
         * 조인 컬럼이 어떤 키의 일부이고 그 키의 나머지 컬럼이 모두 고정돼 있으면 그 나머지 컬럼(키 순서대로), 아니면 null.
         * 키가 아닌 고정 컬럼({@code USE_YN = 'Y'})은 상관하지 않는다.
         */
        List<String> completedBy(Set<String> joinColumns, Set<String> fixedColumns) {
            if (joinColumns.isEmpty() || fixedColumns.isEmpty()) {
                return null;
            }
            List<List<String>> keys = new ArrayList<>();
            if (!primaryKeyOrder.isEmpty()) {
                keys.add(primaryKeyOrder);
            }
            uniqueKeys.forEach(k -> keys.add(new ArrayList<>(new TreeSet<>(k))));
            for (List<String> key : keys) {
                if (key.size() <= joinColumns.size() || !key.containsAll(joinColumns)) {
                    continue;
                }
                List<String> rest = key.stream().filter(c -> !joinColumns.contains(c)).toList();
                if (fixedColumns.containsAll(rest)) {
                    return rest;
                }
            }
            return null;
        }
    }

    /**
     * 묶음 하나의 판정.
     *
     * @param evidence 이 판정의 근거로 세는 엣지들(상수로 채운 키면 조건을 채운 것만)
     * @param fixedColumns {@code to} 쪽 키를 채운 고정 컬럼(상수로 채운 키가 아니면 빈 목록)
     * @param toIsLo {@code to}가 lo 쪽 테이블인가
     */
    private record Decision(Key key, boolean toIsLo, boolean directionKnown, String cardinality, List<Evidence> evidence,
            List<String> fixedColumns, String lowered, String directionFrom) {

        Decision(Key key, boolean toIsLo, boolean directionKnown, String cardinality, List<Evidence> evidence, List<String> fixedColumns) {
            this(key, toIsLo, directionKnown, cardinality, evidence, fixedColumns, null, null);
        }

        /** 확정했던 방향을 거둔 판정: 방향 미상으로 기록하고 까닭({@code declaredOpposite}, {@code pseudoKey})을 attrs에 남긴다. */
        Decision lower(String reason) {
            return new Decision(key, false, false, "unknown", evidence, List.of(), reason, null);
        }

        /**
         * 방향을 정하지 못한 판정에 DDL FK의 방향을 준다(F-018-R15): 같은 테이블 쌍의 선언된 관계가 가리키는 쪽이 부모다.
         * 관계의 수(cardinality)는 조인만으로는 여전히 모르므로 그대로 둔다.
         */
        Decision follow(boolean parentIsLo) {
            return new Decision(key, parentIsLo, true, cardinality, evidence, fixedColumns, lowered, "DDL");
        }

        String from() {
            return toIsLo ? key.hi() : key.lo();
        }

        String to() {
            return toIsLo ? key.lo() : key.hi();
        }

        List<String> columns() {
            return key.pairs().stream().map(p -> toIsLo ? p.hi() : p.lo()).toList();
        }

        List<String> refColumns() {
            return key.pairs().stream().map(p -> toIsLo ? p.lo() : p.hi()).toList();
        }

        boolean unknown() {
            return !directionKnown && cardinality.equals("unknown");
        }
    }

    @Override
    public String name() {
        return "link-relation-inference";
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public void link(AnalysisContext ctx) {
        GraphModel graph = ctx.graph();
        int minEvidence = minEvidence(ctx);
        boolean viewRelations = Boolean.parseBoolean(String.valueOf(ctx.options().extra().get(VIEW_RELATIONS_OPTION)).trim());
        Map<Key, Group> groups = collect(graph, viewRelations);
        if (groups.isEmpty()) {
            return;
        }
        Map<String, TableKeys> keyCache = new HashMap<>();
        Set<Key> declared = new HashSet<>();
        Set<String> declaredChildColumns = new HashSet<>();
        Map<String, Set<SourceLocation>> usedLocations = new HashMap<>();
        // 자식 컬럼("테이블\n컬럼") → 그 컬럼이 참조하는 부모 컬럼들("테이블\n컬럼"). DDL과 이번 실행의 N:1 추론에서 모은다
        Map<String, Set<String>> parentColumns = new HashMap<>();
        // DDL이 선언한 관계의 방향("자식\n부모"). 같은 테이블 쌍의 반대 방향 추론을 확정하지 않는 데 쓴다(F-018-R13)
        Set<String> declaredDirections = new HashSet<>();
        for (Edge reference : graph.edges(EdgeKind.REFERENCES)) {
            usedLocations.computeIfAbsent(reference.from() + "\n" + reference.to(), k -> new HashSet<>()).add(reference.location());
            if (!"INFERRED".equals(reference.attrString("source"))) {
                indexDeclared(graph, reference, keyCache, declared, declaredChildColumns, parentColumns);
                declaredDirections.add(reference.from() + "\n" + reference.to());
            }
        }

        // 1단계: 묶음마다 방향을 판정한다
        List<Decision> decisions = new ArrayList<>();
        for (Map.Entry<Key, Group> entry : groups.entrySet()) {
            Key key = entry.getKey();
            Group group = entry.getValue();
            if (statements(group.evidence).size() < minEvidence || declared.contains(key)) {
                continue;
            }
            Set<String> loColumns = new LinkedHashSet<>();
            Set<String> hiColumns = new LinkedHashSet<>();
            key.pairs().forEach(p -> {
                loColumns.add(p.lo());
                hiColumns.add(p.hi());
            });
            if (declaredChildColumns.contains(childKey(key.lo(), key.hi(), loColumns))
                    || declaredChildColumns.contains(childKey(key.hi(), key.lo(), hiColumns))) {
                continue;
            }
            // 뷰에는 키 정보가 없다: 상대가 PK 전체여도 뷰 쪽이 유일하지 않다고 볼 근거가 없으므로 방향을 정하지 않는다(F-018-R11)
            Decision decision = isView(key.lo()) || isView(key.hi())
                    ? new Decision(key, false, false, "unknown", group.evidence, List.of())
                    : decide(key, group, keys(graph, key.lo(), keyCache), keys(graph, key.hi(), keyCache), loColumns, hiColumns, minEvidence);
            if (decision.directionKnown() && !decision.from().equals(decision.to())
                    && declaredDirections.contains(decision.to() + "\n" + decision.from())
                    && !declaredDirections.contains(decision.from() + "\n" + decision.to())) {
                // DDL은 이 두 테이블의 관계를 반대 방향으로 선언했다: 추론한 방향을 확정하지 않는다(F-018-R13)
                decision = decision.lower("declaredOpposite");
            }
            decisions.add(decision);
            if (decision.directionKnown()) {
                List<String> columns = decision.columns();
                List<String> refColumns = decision.refColumns();
                for (int i = 0; i < columns.size(); i++) {
                    parentColumns.computeIfAbsent(decision.from() + "\n" + columns.get(i), k -> new HashSet<>())
                            .add(decision.to() + "\n" + refColumns.get(i));
                }
            }
        }

        // 2단계: 형제 조인을 거른다
        List<Decision> kept = new ArrayList<>();
        for (Decision decision : decisions) {
            if (decision.unknown() && siblings(decision.key(), parentColumns)) {
                continue;   // 같은 부모를 가리키는 자식끼리의 조인: 두 테이블 사이의 관계가 아니다
            }
            kept.add(decision);
        }
        // 3단계: "사실상 키"인 컬럼 쪽을 자식으로 확정하지 않는다(F-018-R14). 키가 아닌 컬럼이 여러 테이블과 방향 미상으로
        // 조인되면(등록자 ID처럼 다른 테이블들이 식별자로 쓰는 컬럼) 그 컬럼이 다른 테이블의 키와 조인돼도 FK라는 근거가 못 된다
        Map<String, Set<String>> partners = new HashMap<>();
        for (Decision decision : kept) {
            if (decision.unknown() && decision.lowered() == null && !decision.key().lo().equals(decision.key().hi())) {
                Key key = decision.key();
                partners.computeIfAbsent(columnsKey(key.lo(), key.pairs().stream().map(Pair::lo).toList()), k -> new HashSet<>()).add(key.hi());
                partners.computeIfAbsent(columnsKey(key.hi(), key.pairs().stream().map(Pair::hi).toList()), k -> new HashSet<>()).add(key.lo());
            }
        }
        for (Decision decision : kept) {
            boolean pseudoKey = decision.directionKnown()
                    && partners.getOrDefault(columnsKey(decision.from(), decision.columns()), Set.of()).size() >= PSEUDO_KEY_PARTNERS;
            Decision settled = pseudoKey ? decision.lower("pseudoKey") : decision;
            record(graph, followDeclared(settled, declaredDirections), usedLocations);
        }
    }

    /**
     * 방향을 정하지 못한 추론 관계의 테이블 쌍에 DDL REFERENCES가 한 방향으로만 있으면 그 방향을 따른다(F-018-R15).
     * 조인만으로는 어느 쪽이 부모인지 모르지만 DDL이 이미 답을 선언했다: 사전순으로 놓아 DDL과 반대로 보이게 하지 않는다.
     * DDL과 반대 방향으로 판정됐던 관계({@code declaredOpposite}, F-018-R13)는 근거가 서로 어긋나므로 방향 미상 그대로 둔다.
     */
    private static Decision followDeclared(Decision decision, Set<String> declaredDirections) {
        Key key = decision.key();
        if (decision.directionKnown() || key.lo().equals(key.hi()) || "declaredOpposite".equals(decision.lowered())) {
            return decision;
        }
        boolean loToHi = declaredDirections.contains(key.lo() + "\n" + key.hi());
        boolean hiToLo = declaredDirections.contains(key.hi() + "\n" + key.lo());
        if (loToHi == hiToLo) {
            return decision;
        }
        // lo → hi로 선언됐으면 부모는 hi다
        return decision.follow(hiToLo);
    }

    /** 키가 아닌 컬럼이 이만큼의 테이블과 방향 미상으로 조인되면 "사실상 키"로 본다. */
    private static final int PSEUDO_KEY_PARTNERS = 3;

    private static String columnsKey(String table, List<String> columns) {
        return table + "\n" + new TreeSet<>(columns);
    }

    /** 근거 엣지들의 구문(중복 제거, 나온 순서). */
    private static Set<String> statements(List<Evidence> evidence) {
        Set<String> statements = new LinkedHashSet<>();
        evidence.forEach(e -> statements.add(e.statement()));
        return statements;
    }

    private static Decision decide(Key key, Group group, TableKeys loKeys, TableKeys hiKeys, Set<String> loColumns, Set<String> hiColumns,
            int minEvidence) {
        boolean loIsKey = loKeys.isKey(loColumns);
        boolean hiIsKey = hiKeys.isKey(hiColumns);
        if (loIsKey != hiIsKey) {
            return new Decision(key, loIsKey, true, "N:1", group.evidence, List.of());
        }
        if (loIsKey) {
            return new Decision(key, false, false, "1:1", group.evidence, List.of());
        }
        // 어느 쪽도 조인 컬럼만으로는 키가 아니다: 같은 구문의 상수 조건이 키의 나머지를 채우는 근거를 찾는다
        List<Evidence> towardLo = new ArrayList<>();
        List<Evidence> towardHi = new ArrayList<>();
        List<Evidence> both = new ArrayList<>();
        Set<String> loRest = new LinkedHashSet<>();
        Set<String> hiRest = new LinkedHashSet<>();
        for (Evidence e : group.evidence) {
            List<String> lo = loKeys.completedBy(loColumns, e.loFixed());
            List<String> hi = hiKeys.completedBy(hiColumns, e.hiFixed());
            if (lo != null && hi != null) {
                both.add(e);
            } else if (lo != null) {
                towardLo.add(e);
                loRest.addAll(lo);
            } else if (hi != null) {
                towardHi.add(e);
                hiRest.addAll(hi);
            }
        }
        if (!towardLo.isEmpty() && towardHi.isEmpty() && statements(towardLo).size() >= minEvidence) {
            return new Decision(key, true, true, "N:1", towardLo, List.copyOf(loRest));
        }
        if (!towardHi.isEmpty() && towardLo.isEmpty() && statements(towardHi).size() >= minEvidence) {
            return new Decision(key, false, true, "N:1", towardHi, List.copyOf(hiRest));
        }
        if (towardLo.isEmpty() && towardHi.isEmpty() && !both.isEmpty() && statements(both).size() >= minEvidence) {
            Set<String> rest = new LinkedHashSet<>();
            both.forEach(e -> rest.addAll(hiKeys.completedBy(hiColumns, e.hiFixed())));
            return new Decision(key, false, false, "1:1", both, List.copyOf(rest));
        }
        return new Decision(key, false, false, "unknown", group.evidence, List.of());
    }

    /** 컬럼 대응마다 양쪽 컬럼이 같은 부모 컬럼을 참조하고, 그 부모 테이블이 모든 대응에서 같은가. */
    private static boolean siblings(Key key, Map<String, Set<String>> parentColumns) {
        Set<String> parents = null;
        for (Pair pair : key.pairs()) {
            Set<String> lo = parentColumns.getOrDefault(key.lo() + "\n" + pair.lo(), Set.of());
            Set<String> hi = parentColumns.getOrDefault(key.hi() + "\n" + pair.hi(), Set.of());
            Set<String> tables = new HashSet<>();
            for (String target : lo) {
                if (hi.contains(target)) {
                    tables.add(target.substring(0, target.indexOf('\n')));
                }
            }
            if (parents == null) {
                parents = tables;
            } else {
                parents.retainAll(tables);
            }
            if (parents.isEmpty()) {
                return false;
            }
        }
        return parents != null && !parents.isEmpty();
    }

    private static void record(GraphModel graph, Decision decision, Map<String, Set<SourceLocation>> usedLocations) {
        String from = decision.from();
        String to = decision.to();
        List<String> columns = decision.columns();
        List<String> refColumns = decision.refColumns();
        Set<SourceLocation> used = usedLocations.computeIfAbsent(from + "\n" + to, k -> new HashSet<>());
        SourceLocation first = null;
        SourceLocation location = null;
        for (Evidence e : decision.evidence()) {
            if (e.location() == null) {
                continue;
            }
            if (first == null) {
                first = e.location();
            }
            if (!used.contains(e.location())) {
                location = e.location();
                break;
            }
        }
        if (used.contains(location)) {
            // 그래프는 (from, to, kind, location)이 같은 엣지를 하나만 둔다. 겹치지 않는 근거 위치가 없으면 기록하지 못한다
            graph.report(Diagnostic.info(RELATION_NOT_RECORDED, "추론한 관계를 기록할 근거 위치가 다른 관계와 겹친다: "
                    + Ids.remainder(from) + columns + " -> " + Ids.remainder(to) + refColumns, first));
            return;
        }
        used.add(location);

        Set<String> statements = statements(decision.evidence());
        Edge.Builder edge = Edge.builder(from, to, EdgeKind.REFERENCES).inferred().location(location)
                .attr("columns", columns)
                .attr("refColumns", refColumns)
                .attr("source", "INFERRED")
                .attr("evidenceCount", statements.size())
                .attr("evidence", statements.stream().limit(MAX_EVIDENCE).toList())
                .attr("cardinality", decision.cardinality())
                .attr("directionKnown", decision.directionKnown());
        if (!decision.fixedColumns().isEmpty()) {
            Set<String> values = new LinkedHashSet<>();
            for (Evidence e : decision.evidence()) {
                for (String value : decision.toIsLo() ? e.loValues() : e.hiValues()) {
                    int eq = value.indexOf('=');
                    if (eq > 0 && decision.fixedColumns().contains(value.substring(0, eq))) {
                        values.add(value);
                    }
                }
            }
            edge.attr("fixedColumns", decision.fixedColumns());
            if (!values.isEmpty()) {
                edge.attr("fixedValues", values.stream().limit(MAX_FIXED_VALUES).toList());
            }
        }
        if (typeMismatch(graph, decision.key())) {
            edge.attr("typeMismatch", true);
        }
        if (decision.lowered() != null) {
            edge.attr(decision.lowered(), true);
        }
        if (decision.directionFrom() != null) {
            edge.attr("directionFrom", decision.directionFrom());
        }
        graph.addEdge(edge.build());
    }

    // ---- 근거 모으기 ----

    private static Map<Key, Group> collect(GraphModel graph, boolean viewRelations) {
        Map<Key, Group> groups = new LinkedHashMap<>();
        for (Edge join : graph.edges(EdgeKind.JOINS)) {
            if (!tableLike(join.from()) || !tableLike(join.to())) {
                continue;
            }
            if (!viewRelations && (isView(join.from()) || isView(join.to()))) {
                continue;   // 뷰가 낀 조인은 옵션을 켰을 때만 관계로 만든다
            }
            List<String> left = join.attrList("leftColumns");
            List<String> right = join.attrList("rightColumns");
            boolean self = join.from().equals(join.to());
            boolean swap = join.from().compareTo(join.to()) > 0;
            Set<Pair> pairs = new TreeSet<>();
            for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                String l = left.get(i);
                String r = right.get(i);
                if (l == null || r == null || l.isBlank() || r.isBlank() || (self && l.equals(r))) {
                    continue;   // 같은 컬럼끼리의 자기 조인은 관계가 아니다
                }
                pairs.add(swap ? new Pair(r, l) : new Pair(l, r));
            }
            if (pairs.isEmpty()) {
                continue;
            }
            boolean flip = swap;   // JOINS의 왼쪽(from)이 hi 쪽인가
            if (self) {
                Set<Pair> canonical = canonicalSelfJoin(pairs);
                flip = canonical != pairs;
                pairs = canonical;
            }
            Key key = new Key(swap ? join.to() : join.from(), swap ? join.from() : join.to(), List.copyOf(pairs));
            Group group = groups.computeIfAbsent(key, k -> new Group());
            String statement = join.attrString("statement");
            String lo = flip ? "right" : "left";
            String hi = flip ? "left" : "right";
            List<String> loValues = literalValues(join.attrList(lo + "FixedValues"));
            List<String> hiValues = literalValues(join.attrList(hi + "FixedValues"));
            group.evidence.add(new Evidence(statement != null ? statement : "@" + join.location(), join.location(),
                    literalColumns(join.attrList(lo + "FixedColumns"), join.attrList(lo + "FixedValues")),
                    literalColumns(join.attrList(hi + "FixedColumns"), join.attrList(hi + "FixedValues")), loValues, hiValues));
        }
        return groups;
    }

    /**
     * 리터럴로 고정된 컬럼만 남긴다(F-018-R12). 바인드·변수와의 등치({@code COL=?})는 "그 구문이 한 행을 고른다"는 뜻일 뿐
     * 그 테이블이 관계의 유일 쪽(부모)이라는 근거가 아니다(상세 테이블의 순번을 바인드로 고정한 조회). 값 목록이 없는 컬럼은 그대로 둔다.
     */
    private static Set<String> literalColumns(List<String> columns, List<String> values) {
        Set<String> literal = new HashSet<>(columns);
        Set<String> bound = new HashSet<>();
        Set<String> withLiteral = new HashSet<>();
        for (String value : values) {
            int eq = value.indexOf('=');
            if (eq > 0) {
                (value.substring(eq + 1).equals("?") ? bound : withLiteral).add(value.substring(0, eq));
            }
        }
        bound.removeAll(withLiteral);
        literal.removeAll(bound);
        return literal;
    }

    private static List<String> literalValues(List<String> values) {
        return values.stream().filter(v -> !v.endsWith("=?")).toList();
    }

    /** 자기 조인은 좌우를 뒤집어도 같은 관계다. 두 표현 중 사전순으로 앞선 것을 쓴다. */
    private static Set<Pair> canonicalSelfJoin(Set<Pair> pairs) {
        Set<Pair> mirrored = new TreeSet<>();
        pairs.forEach(p -> mirrored.add(new Pair(p.hi(), p.lo())));
        return mirrored.toString().compareTo(pairs.toString()) < 0 ? mirrored : pairs;
    }

    private static boolean tableLike(String id) {
        NodeKind kind = Ids.kindOf(id).orElse(null);
        return kind == NodeKind.DB_TABLE || kind == NodeKind.DB_VIEW;
    }

    private static boolean isView(String id) {
        return Ids.kindOf(id).orElse(null) == NodeKind.DB_VIEW;
    }

    // ---- DDL 관계 ----

    private static void indexDeclared(GraphModel graph, Edge reference, Map<String, TableKeys> keyCache,
            Set<Key> declared, Set<String> declaredChildColumns, Map<String, Set<String>> parentColumns) {
        List<String> columns = reference.attrList("columns");
        List<String> refColumns = reference.attrList("refColumns");
        if (columns.isEmpty()) {
            return;
        }
        if (refColumns.isEmpty()) {
            refColumns = keys(graph, reference.to(), keyCache).primaryKeyOrder();   // REFERENCES parent: 부모의 PK
        }
        if (refColumns.size() != columns.size()) {
            declaredChildColumns.add(childKey(reference.from(), reference.to(), new LinkedHashSet<>(columns)));
            return;
        }
        boolean swap = reference.from().compareTo(reference.to()) > 0;
        Set<Pair> pairs = new TreeSet<>();
        for (int i = 0; i < columns.size(); i++) {
            pairs.add(swap ? new Pair(refColumns.get(i), columns.get(i)) : new Pair(columns.get(i), refColumns.get(i)));
            parentColumns.computeIfAbsent(reference.from() + "\n" + columns.get(i), k -> new HashSet<>())
                    .add(reference.to() + "\n" + refColumns.get(i));
        }
        if (reference.from().equals(reference.to())) {
            pairs = canonicalSelfJoin(pairs);
        }
        declared.add(new Key(swap ? reference.to() : reference.from(), swap ? reference.from() : reference.to(), List.copyOf(pairs)));
    }

    private static String childKey(String child, String parent, Set<String> childColumns) {
        return child + "\n" + parent + "\n" + new TreeSet<>(childColumns);
    }

    // ---- 키 정보 ----

    private static TableKeys keys(GraphModel graph, String tableId, Map<String, TableKeys> cache) {
        return cache.computeIfAbsent(tableId, id -> {
            Node table = graph.node(id).orElse(null);
            List<String> primaryKey = new ArrayList<>(table == null ? List.of() : table.attrList("primaryKey"));
            List<Set<String>> unique = new ArrayList<>();
            if (table != null) {
                for (String columns : table.attrList("uniqueKeys")) {
                    Set<String> set = new HashSet<>();
                    for (String column : columns.split(",")) {
                        if (!column.isBlank()) {
                            set.add(column.trim());
                        }
                    }
                    if (!set.isEmpty()) {
                        unique.add(set);
                    }
                }
            }
            List<String> columnLevelKey = new ArrayList<>();
            for (Node column : graph.successors(id, EdgeKind.CONTAINS)) {
                if (column.kind() != NodeKind.DB_COLUMN) {
                    continue;
                }
                if (column.attrBoolean("primaryKey")) {
                    columnLevelKey.add(column.name());
                }
                if (column.attrBoolean("unique")) {
                    unique.add(Set.of(column.name()));
                }
            }
            if (primaryKey.isEmpty()) {
                primaryKey = columnLevelKey;
            }
            return new TableKeys(new HashSet<>(primaryKey), List.copyOf(primaryKey), unique);
        });
    }

    // ---- 자료형 ----

    private static boolean typeMismatch(GraphModel graph, Key key) {
        for (Pair pair : key.pairs()) {
            String lo = typeFamily(graph, key.lo(), pair.lo());
            String hi = typeFamily(graph, key.hi(), pair.hi());
            if (lo != null && hi != null && !lo.equals(hi)) {
                return true;
            }
        }
        return false;
    }

    private static String typeFamily(GraphModel graph, String tableId, String column) {
        String columnId = Ids.column(DbObjectNormalizer.quote(Ids.remainder(tableId)), DbObjectNormalizer.quote(column));
        String dataType = graph.node(columnId).map(n -> n.attrString("dataType")).orElse(null);
        if (dataType == null || dataType.isBlank()) {
            return null;
        }
        String base = dataType.trim().toUpperCase(Locale.ROOT);
        int paren = base.indexOf('(');
        if (paren >= 0) {
            base = base.substring(0, paren);
        }
        String first = base.trim().split("\\s+")[0];
        return switch (first) {
            case "NUMBER", "NUMERIC", "DECIMAL", "DEC", "INTEGER", "INT", "SMALLINT", "BIGINT", "FLOAT", "DOUBLE", "REAL",
                    "DECFLOAT", "BINARY_FLOAT", "BINARY_DOUBLE",
                    // PostgreSQL의 별칭(F-019-R35): serial 류는 정수 컬럼에 시퀀스 기본값을 붙인 것이고, intN·floatN은 같은 타입의 다른 이름이다
                    "SERIAL", "BIGSERIAL", "SMALLSERIAL", "SERIAL2", "SERIAL4", "SERIAL8", "INT2", "INT4", "INT8",
                    "FLOAT4", "FLOAT8" -> "NUMERIC";
            case "CHAR", "CHARACTER", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2", "CLOB", "NCLOB", "LONG",
                    "GRAPHIC", "VARGRAPHIC", "DBCLOB",
                    // PostgreSQL의 TEXT는 길이 제한 없는 문자열이다(CLOB과 같은 자리). VARCHAR 컬럼과의 조인이 흔하다(F-019-R35)
                    "TEXT" -> "CHARACTER";
            // TIMESTAMPTZ·TIMETZ는 PostgreSQL의 TIMESTAMP·TIME WITH TIME ZONE이다(F-019-R35)
            case "DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ", "TIMETZ" -> "DATETIME";
            case "BOOL", "BOOLEAN" -> "BOOLEAN"; // PostgreSQL의 BOOL은 BOOLEAN이다(F-019-R35)
            default -> first;
        };
    }

    // ---- 옵션 ----

    private static int minEvidence(AnalysisContext ctx) {
        String raw = ctx.options().extra().get(MIN_EVIDENCE_OPTION);
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            ctx.graph().report(Diagnostic.warn(DiagnosticCodes.INVALID_OPTION,
                    MIN_EVIDENCE_OPTION + " 값이 숫자가 아니라 1로 본다: " + raw, null));
            return 1;
        }
    }
}
