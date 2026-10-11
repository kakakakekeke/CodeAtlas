package io.codeatlas.sql.analysis;

import io.codeatlas.core.model.DbName;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.sql.ast.Expr;
import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.ast.QName;
import io.codeatlas.sql.ast.Query;
import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.ast.TableRef;
import io.codeatlas.sql.parser.ParseResult;
import io.codeatlas.sql.script.ScriptUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 스크립트 파일 하나의 데이터 적재 INSERT를 테이블별 구문 노드 하나로 묶는다(F-006-R15, F-006-R24).
 *
 * <p>대상은 값이 리터럴(과 시퀀스·함수 호출·상수 식)뿐이고 서브쿼리가 없는 INSERT다: {@code INSERT .. VALUES (..)}(다중 행 포함),
 * {@code INSERT ALL INTO .. VALUES .. SELECT .. FROM DUAL}, {@code INSERT .. SELECT 리터럴 FROM DUAL [UNION ALL ..]}.
 * 한 파일에서 같은 테이블(INSERT ALL은 첫 대상 테이블)에 넣는 이런 구문이 {@link #MIN_STATEMENTS}건 이상이면 묶는다.
 *
 * <p>묶을지는 파일 단위로 정한다(F-006-R24). <b>데이터 적재 파일</b>은 (a) {@link #MIN_STATEMENTS}건 이상인 묶음이 하나라도 생긴 파일이거나,
 * (b) 구문이 리터럴 INSERT와 세션·트랜잭션 명령({@link #isSessionCommand}), 조건 없는 {@code DELETE FROM T}·{@code TRUNCATE}
 * ({@link #isReset})뿐이고 리터럴 INSERT가 {@link #MIN_STATEMENTS}건 이상인 파일이다. 적재 파일의 리터럴 INSERT는 건수와 상관없이
 * 테이블별로 묶는다(1건이어도 묶음이다). 적재 파일이 아니면 코드 몇 줄을 넣는 보통의 구문으로 보고 구문마다 노드를 만드는 쪽에 맡긴다.
 *
 * <p>묶음은 구문을 받는 대로 행 수와 컬럼 이름만 더하고 구문 자체는 놓아 준다. 62만 건짜리 스크립트도 테이블 수만큼의 메모리로 지나간다
 * (기준에 닿기 전의 후보는 테이블마다 {@code MIN_STATEMENTS - 1}건까지만 쥔다).
 *
 * <p>사용: 파일마다 하나 만들고, INSERT 단위마다 {@link #candidate}로 후보를 얻어 {@link #offer}에 준다. 그 밖의 단위는
 * {@link #noteOther()}(적재 파일 판정 (b)를 깨는 구문)나 {@link #noteReset()}으로 알린다. 파일을 다 읽으면 {@link #endOfFile()}을 부르고
 * (이때 적재 파일인지 정해진다) {@link #write}가 묶음 노드와 엣지를 쓴다.
 */
public final class DataLoadGrouper {

    /** 한 (파일, 테이블)의 리터럴 INSERT가 이만큼 모이면 묶는다. 파일의 리터럴 INSERT가 이만큼이어야 "INSERT뿐인 적재 파일"이다. */
    public static final int MIN_STATEMENTS = 10;

    /**
     * 적재 파일 판정 (b)에서 "INSERT뿐인 파일"을 깨지 않는 구문의 첫 키워드: 트랜잭션 명령, SET 류 세션 명령, SQL*Plus·CLP 줄 명령.
     * {@code ALTER SESSION}은 {@link #isSessionCommand}가 따로 본다.
     */
    private static final java.util.Set<String> SESSION_COMMANDS = java.util.Set.of(
            "COMMIT", "ROLLBACK", "SAVEPOINT", "SET", "USE", "PROMPT", "SPOOL", "WHENEVER", "EXIT", "QUIT", "DEFINE", "UNDEFINE",
            "SHOW", "ECHO", "CONNECT", "DISCONNECT", "TERMINATE");

    private static final int SQL_LIMIT = 2000;

    /** 리터럴뿐인 INSERT 구문 하나에서 얻은 것. */
    public record Candidate(List<Target> targets, int rows, List<FunctionUse> functions) {

        /** 묶음의 열쇠가 되는 테이블(첫 대상). */
        public String table() {
            return targets.get(0).table();
        }
    }

    /** INSERT 대상 하나: 테이블 이름(정규화)과 컬럼 목록. */
    public record Target(String table, Pos pos, List<QName> columns) {
    }

    /** 값 자리에서 부른 함수(카탈로그에 정의된 루틴인지는 나중에 본다). */
    public record FunctionUse(List<String> name, Pos pos) {
    }

    /** {@link #offer}의 결과. */
    public enum Offer {
        /** 아직 기준에 못 미쳐 구문을 쥐고 있어야 한다(끝까지 못 미치면 보통의 구문으로 처리한다). */
        HELD,
        /** 묶음에 더했다. 구문은 놓아도 된다. */
        GROUPED
    }

    private record Held(Object token, ScriptUnit unit, Candidate candidate) {
    }

    private static final class Group {
        final String table;
        final int line;
        final Pos pos;
        final String sql;
        final Dialect detected;
        /** 고른 방언이 아닌 방언의 파일에서 온 묶음이면 그 방언(F-007-R29). 아니면 null. */
        String borrowedFrom;
        int statements;
        long rows;
        final Map<String, Pos> tables = new LinkedHashMap<>();
        final Map<String, Map<String, Pos>> columns = new LinkedHashMap<>();
        final Map<String, FunctionUse> functions = new LinkedHashMap<>();

        Group(String table, ScriptUnit first) {
            this.table = table;
            this.line = first.startLine();
            this.pos = first.pos();
            this.sql = normalize(first.text());
            this.detected = DialectDetector.fromTokens(first.tokens());
        }

        void add(Candidate c) {
            statements++;
            rows += c.rows();
            for (Target t : c.targets()) {
                tables.putIfAbsent(t.table(), t.pos());
                Map<String, Pos> cols = columns.computeIfAbsent(t.table(), k -> new LinkedHashMap<>());
                for (QName column : t.columns()) {
                    cols.putIfAbsent(column.name(), column.pos());
                }
            }
            for (FunctionUse f : c.functions()) {
                functions.putIfAbsent(String.join(".", f.name()), f);
            }
        }
    }

    private final String path;
    private final Map<String, List<Held>> held = new LinkedHashMap<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final List<Object> released = new ArrayList<>();
    /** 받은 후보(리터럴 INSERT) 수. */
    private int candidates;
    /** 리터럴 INSERT·세션 명령·초기화 구문이 아닌 구문이 파일에 있었는가. */
    private boolean otherContent;
    /** 파일을 다 읽은 뒤 정해진다: 데이터 적재 파일인가. */
    private boolean loadFile;

    public DataLoadGrouper(String path) {
        this.path = path;
    }

    /**
     * 후보 구문을 준다.
     *
     * @param token 호출한 쪽이 이 구문을 알아보는 표(쥐고 있던 구문이 묶음으로 넘어가면 {@link #takeReleased()}로 돌려준다)
     */
    public Offer offer(Object token, ScriptUnit unit, Candidate candidate) {
        candidates++;
        String table = candidate.table();
        Group group = groups.get(table);
        if (group != null) {
            group.add(candidate);
            return Offer.GROUPED;
        }
        List<Held> list = held.computeIfAbsent(table, k -> new ArrayList<>());
        list.add(new Held(token, unit, candidate));
        if (list.size() < MIN_STATEMENTS) {
            return Offer.HELD;
        }
        group = new Group(table, list.get(0).unit());
        for (Held h : list) {
            group.add(h.candidate());
            released.add(h.token());
        }
        groups.put(table, group);
        held.remove(table);
        return Offer.GROUPED;
    }

    /** 쥐고 있다가 묶음으로 넘어간 구문의 표들. 한 번 돌려주면 비운다. */
    public List<Object> takeReleased() {
        List<Object> out = new ArrayList<>(released);
        released.clear();
        return out;
    }

    /** 리터럴 INSERT도 세션 명령도 초기화 구문도 아닌 구문이 있었다: 이 파일은 "INSERT뿐인 파일"이 아니다. */
    public void noteOther() {
        otherContent = true;
    }

    /**
     * 조건 없는 DELETE·TRUNCATE(초기화 구문)가 있었다. "INSERT뿐인 파일" 판정을 깨지 않는다({@link #noteOther()}와 달리 아무것도 적지 않는다).
     * 적재 파일이면 호출한 쪽이 그 구문 노드에 {@code dataLoad}를 붙이고, 진단에 적을 수도 호출한 쪽이 센다(노드가 된 것만: F-007-R29).
     */
    public void noteReset() {
    }

    /**
     * 파일을 다 읽었다. 이 파일이 데이터 적재 파일인지 정한다(F-006-R24): 기준({@link #MIN_STATEMENTS}건)을 채운 묶음이 하나라도 있거나,
     * 구문이 리터럴 INSERT·세션 명령·초기화 구문뿐이고 리터럴 INSERT가 기준 이상이다. 적재 파일이면 기준에 못 미쳐 쥐고 있던 후보도
     * 테이블별 묶음으로 넘긴다(그 구문의 표는 {@link #takeReleased()}로 돌려준다). 아니면 쥐고 있던 후보를 놓는다(보통의 구문으로 처리된다).
     */
    public void endOfFile() {
        loadFile = !groups.isEmpty() || (!otherContent && candidates >= MIN_STATEMENTS);
        if (loadFile) {
            for (Map.Entry<String, List<Held>> e : held.entrySet()) {
                Group group = new Group(e.getKey(), e.getValue().get(0).unit());
                for (Held h : e.getValue()) {
                    group.add(h.candidate());
                    released.add(h.token());
                }
                groups.put(e.getKey(), group);
            }
        }
        held.clear();
    }

    /** 데이터 적재 파일인가. {@link #endOfFile()} 뒤에만 뜻이 있다. */
    public boolean loadFile() {
        return loadFile;
    }

    /** 묶음 수. */
    public int groupCount() {
        return groups.size();
    }

    /** 묶음이 있는 테이블(묶음의 열쇠)들. */
    public java.util.Set<String> tables() {
        return java.util.Collections.unmodifiableSet(groups.keySet());
    }

    /**
     * 이 테이블의 묶음을 버린다: 같은 테이블의 적재 묶음이 순위가 앞선 다른 방언의 파일에 있거나(F-007-R20), 고른 방언이 아닌 방언의 파일이
     * 정의가 없는 테이블에 넣는 묶음이다(F-007-R29). 버린 묶음으로는 노드를 만들지 않는다.
     *
     * @return 버렸으면 true
     */
    public boolean drop(String table) {
        return groups.remove(table) != null;
    }

    /**
     * 이 테이블의 묶음이 고른 방언이 아닌 방언의 파일에서 온 것임을 적는다: 고른 방언의 파일에 그 테이블의 적재가 없어 남은 묶음이다
     * (F-007-R29). 묶음 노드에 {@code borrowedFrom=<방언>}이 붙는다.
     */
    public void borrow(String table, String dialect) {
        Group group = groups.get(table);
        if (group != null) {
            group.borrowedFrom = dialect;
        }
    }

    /** 구문이 {@link #MIN_STATEMENTS}건에 못 미치는데 적재 파일이라 묶인 묶음 수. */
    public int smallGroupCount() {
        return (int) groups.values().stream().filter(g -> g.statements < MIN_STATEMENTS).count();
    }

    /** 묶은 구문 수. */
    public int statementCount() {
        return groups.values().stream().mapToInt(g -> g.statements).sum();
    }

    /**
     * 묶음마다 구문 노드({@code sql:stmt:<파일>:<그 테이블의 첫 INSERT 줄>}, statementType=INSERT, dataLoad=true, rowCount)와
     * 테이블·컬럼 WRITES를 쓴다. 값 자리에서 부른 함수가 카탈로그에 정의된 루틴이면 그 호출도 묶음 노드에서 CALLS로 남긴다.
     *
     * @param fileDialect 파일의 방언. 모르면 null
     * @param detect fileDialect가 null일 때 묶음의 첫 구문의 특징으로 방언을 판정할지. 대상 외 방언의 파일이면 false다(방언을 적지 않는다)
     */
    public void write(GraphModel graph, Catalog catalog, Dialect fileDialect, boolean detect) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        for (Group g : groups.values()) {
            String id = Ids.sqlStatement(path, g.line);
            Locator locator = Locator.ofFile(path, g.pos);
            graph.declare(Node.builder(id, NodeKind.SQL_STATEMENT).name(fileName + ":" + g.line).qualifiedName(path + ":" + g.line)
                    .location(locator.locate(g.pos)).attr("sql", g.sql).build());
            graph.addEdge(Edge.of(Ids.file(path), id, EdgeKind.CONTAINS));
            StatementFacts facts = new StatementFacts();
            facts.noteType("INSERT");
            g.tables.forEach((table, pos) -> facts.write(table, "INSERT", false, pos));
            g.columns.forEach((table, cols) -> {
                String effective = catalog.resolveSynonym(table);
                cols.forEach((column, pos) -> facts.columnWrite(effective, column, "INSERT", pos));
            });
            for (FunctionUse f : g.functions.values()) {
                List<String> routine = routineOf(catalog, f.name());
                if (routine != null) {
                    facts.call(routine, false, f.pos());
                }
            }
            new FactsWriter(graph, catalog, id).add(facts, locator).finish(fileDialect != null ? fileDialect : detect ? g.detected : null);
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("dataLoad", true);
            attrs.put("rowCount", g.rows > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) g.rows);
            if (g.borrowedFrom != null) {
                attrs.put("borrowedFrom", g.borrowedFrom);
            }
            graph.putAttrs(id, attrs);
        }
    }

    /** SQL 식 안의 함수 호출과 같은 규칙: 카탈로그에 루틴으로 정의된 이름일 때만 {@code [PKG,] NAME}, 아니면 null. */
    private static List<String> routineOf(Catalog catalog, List<String> name) {
        int n = name.size();
        String fn = name.get(n - 1);
        if (n == 1) {
            return catalog.hasRoutine(null, fn) ? List.of(fn) : null;
        }
        String qualifier = name.get(n - 2);
        if (catalog.hasRoutine(qualifier, fn)) {
            return List.of(qualifier, fn);
        }
        return n == 2 && !catalog.hasPackage(qualifier) && catalog.hasRoutine(null, fn) ? List.of(fn) : null;
    }

    private static String normalize(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > SQL_LIMIT ? s.substring(0, SQL_LIMIT) : s;
    }

    // ------------------------------------------------------------------ 후보 판정

    /**
     * 적재 스크립트에 흔히 섞이는, 데이터를 읽지도 쓰지도 않는 구문인가: {@code COMMIT}·{@code ROLLBACK}·{@code SAVEPOINT},
     * SET 류 세션 명령({@code SET ..}, {@code ALTER SESSION ..}, {@code USE ..}), SQL*Plus·CLP 줄 명령({@code PROMPT}, {@code SPOOL},
     * {@code WHENEVER}, {@code EXIT}, {@code QUIT}, {@code DEFINE}, {@code UNDEFINE}, {@code SHOW}, {@code ECHO}, {@code CONNECT},
     * {@code DISCONNECT}, {@code TERMINATE}).
     */
    public static boolean isSessionCommand(ScriptUnit unit) {
        if (unit.kind() != ScriptUnit.Kind.STATEMENT) {
            return false;
        }
        String keyword = unit.keyword();
        if (keyword.equals("ALTER")) {
            return unit.tokens().size() > 1 && unit.tokens().get(1).is("SESSION");
        }
        return SESSION_COMMANDS.contains(keyword);
    }

    /**
     * 테이블을 비우는 초기화 구문인가: 파서가 문제 없이 읽은, 조건도 조인도 RETURNING도 없는 {@code DELETE [FROM] T}나 {@code TRUNCATE [TABLE] T}.
     * 대상이 동적이거나 서브쿼리면 아니다.
     */
    public static boolean isReset(ParseResult result) {
        return resetTable(result) != null;
    }

    /** {@link #isReset 초기화 구문}이면 비우는 테이블 이름(정규화. 묶음의 열쇠와 같은 꼴), 아니면 null. */
    public static String resetTable(ParseResult result) {
        if (result.hasProblems() || !result.salvagedReads().isEmpty()) {
            return null;
        }
        if (result.statement() instanceof Statement.Truncate t) {
            return t.table().anyDynamic() ? null : t.table().name();
        }
        return result.statement() instanceof Statement.Delete d && d.where() == null && d.from().isEmpty() && d.returning().isEmpty()
                && d.target() instanceof TableRef.Named named && !named.name().anyDynamic() && !named.pivot() ? named.name().name() : null;
    }

    /**
     * 파싱 결과가 데이터 적재 INSERT면 후보를, 아니면 null을 돌려준다. 파서가 문제를 남긴 구문, 서브쿼리나 테이블을 읽는 구문,
     * 조건부 다중 INSERT, RETURNING이 있는 구문, 대상이 동적인 구문은 후보가 아니다.
     */
    public static Candidate candidate(ParseResult result) {
        if (result.hasProblems() || !result.salvagedReads().isEmpty() || !(result.statement() instanceof Statement.Insert insert)) {
            return null;
        }
        if (!insert.conditions().isEmpty() || !insert.returning().isEmpty() || insert.targets().isEmpty()) {
            return null;
        }
        List<Target> targets = new ArrayList<>();
        List<FunctionUse> functions = new ArrayList<>();
        int valueRows = 0;
        for (Statement.InsertTarget t : insert.targets()) {
            if (!(t.target() instanceof TableRef.Named named) || named.name().anyDynamic() || named.pivot()) {
                return null;
            }
            for (QName column : t.columns()) {
                if (column.anyDynamic()) {
                    return null;
                }
            }
            for (List<Expr> row : t.values()) {
                for (Expr e : row) {
                    if (!constant(e, functions)) {
                        return null;
                    }
                }
            }
            valueRows += t.values().size();
            targets.add(new Target(named.name().name(), named.name().pos(), t.columns()));
        }
        int rows;
        if (insert.source() == null) {
            rows = Math.max(valueRows, 1); // DEFAULT VALUES는 한 행
        } else {
            int branches = dualRows(insert.source(), functions);
            if (branches < 0) {
                return null;
            }
            rows = valueRows > 0 ? valueRows : branches; // INSERT ALL은 INTO 절마다 한 행
        }
        return new Candidate(List.copyOf(targets), rows, List.copyOf(functions));
    }

    /** 테이블을 읽지 않는 상수 식인가(리터럴, 바인드, 시퀀스, 함수 호출, 그것들의 연산). 서브쿼리가 있으면 아니다. */
    private static boolean constant(Expr e, List<FunctionUse> functions) {
        if (e == null || e instanceof Expr.Literal || e instanceof Expr.Bind) {
            return true;
        }
        if (e instanceof Expr.ColumnRef c) {
            return !c.name().anyDynamic(); // 테이블이 없는 자리의 이름: SYSDATE, TRUE, 패키지 상수 같은 것
        }
        if (e instanceof Expr.SequenceRef s) {
            return !s.sequence().anyDynamic();
        }
        if (e instanceof Expr.Unary u) {
            return constant(u.operand(), functions);
        }
        if (e instanceof Expr.Binary b) {
            return constant(b.left(), functions) && constant(b.right(), functions);
        }
        if (e instanceof Expr.FuncCall f) {
            if (f.name().anyDynamic()) {
                return false;
            }
            functions.add(new FunctionUse(f.name().parts(), f.name().pos()));
            return allConstant(f.args(), functions) && allConstant(f.extras(), functions);
        }
        if (e instanceof Expr.Case c) {
            for (Expr.WhenClause w : c.whens()) {
                if (!constant(w.when(), functions) || !constant(w.then(), functions)) {
                    return false;
                }
            }
            return constant(c.operand(), functions) && constant(c.otherwise(), functions);
        }
        if (e instanceof Expr.Tuple t) {
            return allConstant(t.items(), functions);
        }
        if (e instanceof Expr.Between b) {
            return constant(b.value(), functions) && constant(b.low(), functions) && constant(b.high(), functions);
        }
        if (e instanceof Expr.In in) {
            return in.subquery() == null && constant(in.left(), functions) && allConstant(in.list(), functions);
        }
        return false; // 서브쿼리, EXISTS, 해석하지 못한 조각
    }

    private static boolean allConstant(List<Expr> list, List<FunctionUse> functions) {
        for (Expr e : list) {
            if (!constant(e, functions)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 쿼리가 {@code SELECT 상수.. FROM DUAL}(또는 FROM 없음, VALUES)이나 그것들의 UNION [ALL]이면 행 수, 아니면 -1.
     * 조건·그룹·정렬·CTE가 있으면 데이터 적재가 아니다.
     */
    private static int dualRows(Query q, List<FunctionUse> functions) {
        if (!q.ctes().isEmpty() || !q.orderBy().isEmpty() || !q.tail().isEmpty() || q.forUpdate()) {
            return -1;
        }
        return dualRows(q.body(), functions);
    }

    private static int dualRows(Query.QueryBody body, List<FunctionUse> functions) {
        if (body instanceof Query.Select s) {
            if (s.where() != null || s.startWith() != null || s.connectBy() != null || s.having() != null
                    || !s.groupBy().isEmpty() || !s.into().isEmpty() || s.items().isEmpty()) {
                return -1;
            }
            if (s.from().size() > 1 || (s.from().size() == 1 && !isDummy(s.from().get(0)))) {
                return -1;
            }
            for (Query.SelectItem item : s.items()) {
                if (!(item.expr() instanceof Expr.Star star && star.qualifier() == null) && !constant(item.expr(), functions)) {
                    return -1;
                }
            }
            return 1;
        }
        if (body instanceof Query.SetOperation op) {
            if (!op.op().startsWith("UNION")) {
                return -1;
            }
            int left = dualRows(op.left(), functions);
            int right = dualRows(op.right(), functions);
            return left < 0 || right < 0 ? -1 : left + right;
        }
        if (body instanceof Query.Nested n) {
            return dualRows(n.query(), functions);
        }
        if (body instanceof Query.Values v) {
            for (List<Expr> row : v.rows()) {
                if (!allConstant(row, functions)) {
                    return -1;
                }
            }
            return v.rows().size();
        }
        return -1;
    }

    /** {@code DUAL}, {@code SYS.DUAL}, {@code SYSIBM.SYSDUMMY1}인가. */
    private static boolean isDummy(TableRef ref) {
        if (!(ref instanceof TableRef.Named n) || n.pivot() || n.name().anyDynamic() || n.name().rawName().startsWith("\"")) {
            return false;
        }
        String name = DbName.normalize(n.name().rawName());
        String qualifier = n.name().qualifier();
        if (name.equals("DUAL")) {
            return n.name().size() == 1 || "SYS".equals(qualifier);
        }
        return name.equals("SYSDUMMY1") && (n.name().size() == 1 || "SYSIBM".equals(qualifier));
    }
}
