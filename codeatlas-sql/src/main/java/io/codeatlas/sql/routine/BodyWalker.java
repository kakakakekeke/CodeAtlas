package io.codeatlas.sql.routine;

import io.codeatlas.core.model.DbName;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.sql.analysis.Catalog;
import io.codeatlas.sql.analysis.FactExtractor;
import io.codeatlas.sql.analysis.FactsWriter;
import io.codeatlas.sql.analysis.Locator;
import io.codeatlas.sql.analysis.StatementFacts;
import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.ast.Routine;
import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.parser.DdlParser;
import io.codeatlas.sql.parser.ParseProblem;
import io.codeatlas.sql.parser.ParseResult;
import io.codeatlas.sql.parser.RoutineLanguage;
import io.codeatlas.sql.parser.RoutineParser;
import io.codeatlas.sql.parser.SqlParser;
import io.codeatlas.sql.parser.TokenCursor;
import io.codeatlas.sql.script.ScriptSplitter;
import io.codeatlas.sql.script.ScriptUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 루틴 본문 트리를 스코프와 함께 걸으며 SQL 구문, 호출, 동적 SQL 지점을 {@link Sink}에 넘긴다.
 * 주인이 루틴이면 구문마다 노드가 되고({@link RoutineSink}), 익명 블록이면 블록의 구문 노드 하나에 모인다({@link BlockSink}).
 */
final class BodyWalker {

    private static final int SQL_ATTR_LIMIT = 2000;
    private static final int MAX_DYNAMIC_NESTING = 3;

    /** 걸으며 찾은 것을 받는 쪽. */
    interface Sink {

        /**
         * 구문 하나.
         *
         * @param pos 구문의 범위(시작 줄이 구문 ID의 줄)
         * @param text 구문 원문(공백 정리)
         * @param attrs 구문 노드에 붙일 attrs(cursor, dynamic, dynamicKind)
         * @param fill 사실을 기록기에 더한다
         */
        void statement(Pos pos, String text, Map<String, Object> attrs, Consumer<FactsWriter> fill);

        /**
         * 루틴 호출 하나(판정이 끝난 것).
         *
         * @param packageName 대상의 패키지(없으면 null)
         * @param function 식 안의 함수 호출이면 true, 호출문이면 false
         */
        void call(String packageName, String name, Pos pos, boolean function);
    }

    /** 걷는 데 필요한 환경. */
    record Env(GraphModel graph, Catalog catalog, Dialect dialect, Locator locator, String sourceText, int sourceOffset,
            boolean lenient, Set<String> reported, Dialect interpret, Syntax syntax) {

        Env {
            syntax = syntax == null ? Syntax.DEFAULT : syntax;
        }

        Env(GraphModel graph, Catalog catalog, Dialect dialect, Locator locator, String sourceText, int sourceOffset,
                boolean lenient, Set<String> reported, Syntax syntax) {
            this(graph, catalog, dialect, locator, sourceText, sourceOffset, lenient, reported, dialect, syntax);
        }

        /**
         * 본문에서 풀어낸 동적 SQL 문자열을 읽는 방식. PostgreSQL 본문의 것은 PostgreSQL로 읽고, 그 밖에는 지금까지처럼 기본으로 읽는다
         * (MySQL 파일의 루틴에서 푼 문자열에 줄 처음 {@code #} 주석 규칙을 새로 적용하지 않는다, F-019-R8).
         */
        Syntax dynamicSyntax() {
            return syntax == Syntax.POSTGRES ? Syntax.POSTGRES : Syntax.DEFAULT;
        }
    }

    private record Target(String packageName, String name) {
    }

    private final Env env;
    private final String packageName;
    private final Sink sink;
    private final String ownerLabel;
    private final int dynamicNesting;
    /** PostgreSQL 읽기 방식의 본문인가(PL/pgSQL, LANGUAGE sql 본문). 호출 판정과 동적 SQL 풀기가 달라진다(F-019-R27·R28). */
    private final boolean postgres;
    private DynamicSql dynamicSql = new DynamicSql(null, Set.of());
    private Set<String> transientRelations = Set.of();
    private boolean dynamicSeen;
    private boolean scopeReliable = true;

    /**
     * @param packageName 본문이 들어 있는 패키지(정규화한 이름). 없으면 null
     * @param ownerLabel 진단 메시지에 적을 주인 이름
     */
    BodyWalker(Env env, String packageName, Sink sink, String ownerLabel) {
        this(env, packageName, sink, ownerLabel, 0);
    }

    private BodyWalker(Env env, String packageName, Sink sink, String ownerLabel, int dynamicNesting) {
        this.env = env;
        this.packageName = packageName;
        this.sink = sink;
        this.ownerLabel = ownerLabel;
        this.dynamicNesting = dynamicNesting;
        this.postgres = env.syntax() == Syntax.POSTGRES;
        this.dynamicSql = new DynamicSql(null, Set.of(), postgres);
    }

    /** FROM에 오지만 테이블이 아닌 이름(트리거의 전이 테이블)을 알려 준다. */
    void transientRelations(Set<String> names) {
        this.transientRelations = names;
    }

    /**
     * 본문 파서가 구조를 다 읽었는지 알려 준다. 문제를 남긴 본문(선언을 건너뛰었거나 BEGIN/END 짝이 어긋난 것)에서는 변수 스코프가
     * 빠져 있을 수 있어, 그 본문의 구문에서 DDL에 없는 이름을 컬럼으로 추측하지도 진단으로 알리지도 않는다(F-008-R17).
     */
    void scopeReliable(boolean reliable) {
        this.scopeReliable = reliable;
    }

    /** 동적 SQL 지점(풀렸든 아니든)을 만났는가. */
    boolean dynamicSeen() {
        return dynamicSeen;
    }

    /** 본문 하나를 걷는다. scope에는 매개변수와 바깥(패키지) 변수가 들어 있다. */
    void walkBody(Routine.Block body, VarScope scope, Set<String> parameterNames) {
        dynamicSql = new DynamicSql(body, parameterNames, postgres);
        // 선언을 보지 못한 이름이라도 본문에서 값을 받으면(대입, INTO) 변수다: 다른 파일의 패키지 명세에 선언된 패키지 변수 등
        scope.declareAll(assignedNames(body));
        block(body, scope);
    }

    /** 본문에서 값을 받는 이름(정규화): 대입문의 대상, {@code INTO} 대상, {@code FETCH .. INTO} 대상. 레코드·컬렉션이면 첫 조각이다. */
    private static Set<String> assignedNames(Routine.Block body) {
        Set<String> names = new java.util.HashSet<>();
        Routine.visit(body, new Routine.Visitor() {
            @Override
            public void statement(Routine.Stmt s) {
                if (s instanceof Routine.Assign a) {
                    for (io.codeatlas.sql.ast.QName target : a.targets()) {
                        if (!target.anyDynamic()) {
                            names.add(target.parts().get(0));
                        }
                    }
                } else if (s instanceof Routine.Fetch f && f.into() != null) {
                    for (Routine.NameRef r : f.into().refs()) {
                        if (!r.call() && !r.name().anyDynamic()) {
                            names.add(r.name().parts().get(0));
                        }
                    }
                }
            }

            @Override
            public void sql(Routine.Sql q) {
                names.addAll(q.intoTargets());
            }
        });
        return names;
    }

    /** 식 하나만 걷는다(트리거의 WHEN 조건). */
    void walkExpression(Routine.Expr e, VarScope scope) {
        expr(e, scope);
    }

    /** 호출문 하나만 판정해 기록한다: PostgreSQL 트리거의 {@code EXECUTE FUNCTION f(..)}(F-019-R29). */
    void walkCall(io.codeatlas.sql.ast.QName name, VarScope scope) {
        Target t = resolveCall(name.parts(), scope, true);
        if (t != null) {
            sink.call(t.packageName(), t.name(), name.pos(), false);
        }
    }

    // ------------------------------------------------------------------ 블록과 선언

    private void block(Routine.Block b, VarScope parent) {
        VarScope scope = new VarScope(parent);
        for (Routine.Decl d : b.declarations()) {
            if (d instanceof Routine.Variable v) {
                scope.declareAll(v.names());
            } else if (d instanceof Routine.Cursor c) {
                scope.declare(c.name());
            } else if (d instanceof Routine.Subprogram sp) {
                scope.declareRoutine(sp.name());
            }
        }
        for (Routine.Decl d : b.declarations()) {
            declaration(d, scope);
        }
        statements(b.statements(), scope);
        for (Routine.ExceptionHandler h : b.handlers()) {
            statements(h.statements(), scope);
        }
    }

    private void declaration(Routine.Decl d, VarScope scope) {
        if (d instanceof Routine.Variable v) {
            expr(v.init(), scope);
        } else if (d instanceof Routine.Cursor c) {
            VarScope inner = new VarScope(scope);
            for (Routine.Param p : c.params()) {
                inner.declare(p.name());
                expr(p.defaultValue(), scope);
            }
            if (c.query() != null) {
                sql(c.query(), inner, Map.of("cursor", c.name()));
            }
        } else if (d instanceof Routine.Subprogram sp) {
            // 지역 서브프로그램: 노드를 만들지 않고 본문의 구문과 호출을 바깥 주인의 것으로 기록한다
            VarScope inner = new VarScope(scope);
            for (Routine.Param p : sp.params()) {
                inner.declare(p.name());
                expr(p.defaultValue(), scope);
            }
            if (sp.body() != null) {
                block(sp.body(), inner);
            }
        } else if (d instanceof Routine.Handler h) {
            statement(h.body(), new VarScope(scope));
        }
    }

    private void statements(List<Routine.Stmt> list, VarScope scope) {
        for (Routine.Stmt s : list) {
            statement(s, scope);
        }
    }

    // ------------------------------------------------------------------ 문장

    private void statement(Routine.Stmt s, VarScope scope) {
        if (s == null) {
            return;
        }
        if (s instanceof Routine.Sql q) {
            sql(q, scope, Map.of());
        } else if (s instanceof Routine.Block b) {
            block(b, scope);
        } else if (s instanceof Routine.If i) {
            for (Routine.Branch br : i.branches()) {
                expr(br.condition(), scope);
                statements(br.statements(), scope);
            }
            statements(i.otherwise(), scope);
        } else if (s instanceof Routine.Case c) {
            expr(c.selector(), scope);
            for (Routine.Branch br : c.branches()) {
                expr(br.condition(), scope);
                statements(br.statements(), scope);
            }
            statements(c.otherwise(), scope);
        } else if (s instanceof Routine.Loop l) {
            loop(l, scope);
        } else if (s instanceof Routine.ForAll f) {
            expr(f.bounds(), scope);
            VarScope inner = new VarScope(scope);
            inner.declare(f.index());
            statement(f.statement(), inner);
        } else if (s instanceof Routine.Assign a) {
            expr(a.index(), scope);
            callsAndSubqueries(a.value(), scope);
            // 대입문은 문장 전체가 구문 노드가 된다: v := seq.NEXTVAL, SET v = NEXT VALUE FOR seq
            sequences(a.value(), scope, a.pos(), a.tokens().isEmpty() && a.value() != null ? a.value().tokens() : a.tokens());
        } else if (s instanceof Routine.Call c) {
            call(c, scope);
        } else if (s instanceof Routine.Open o) {
            expr(o.args(), scope);
            if (o.query() != null) {
                sql(o.query(), scope, o.cursor() == null ? Map.of() : Map.of("cursor", o.cursor()));
            }
            if (o.dynamic() != null) {
                dynamicPoint(o.dynamic().tokens(), o.pos(), "OPEN FOR", scope);
                expr(o.dynamic(), scope);
            }
            expr(o.using(), scope);
        } else if (s instanceof Routine.Fetch f) {
            expr(f.into(), scope);
        } else if (s instanceof Routine.ExecuteImmediate e) {
            if (e.sql() != null) {
                dynamicPoint(e.sql().tokens(), e.pos(), postgres ? "EXECUTE" : "EXECUTE IMMEDIATE", scope);
                expr(e.sql(), scope);
            }
            expr(e.rest(), scope);
        } else if (s instanceof Routine.Prepare p) {
            if (p.sql() != null) {
                dynamicPoint(p.sql().tokens(), p.pos(), "PREPARE", scope);
                expr(p.sql(), scope);
            } else {
                dynamicSeen = true;
            }
        } else if (s instanceof Routine.Execute e) {
            expr(e.rest(), scope);
        } else if (s instanceof Routine.Return r) {
            if (r.query() != null) {
                sql(r.query(), scope, Map.of());
            }
            expr(r.value(), scope);
        } else if (s instanceof Routine.Jump j) {
            expr(j.when(), scope);
        } else if (s instanceof Routine.Raise r) {
            expr(r.detail(), scope);
        } else if (s instanceof Routine.Simple x) {
            if (x.keyword().equals("PIPE ROW") || x.keyword().equals("EXPRESSION") || x.keyword().equals("ASSERT")) {
                expr(x.detail(), scope);
            } else {
                subqueries(x.detail(), scope);
            }
        } else if (s instanceof Routine.Unknown u) {
            report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                    "지원하지 않는 구문(건너뜀): " + u.keyword() + " (" + ownerLabel + ")", env.locator().locate(u.pos())));
            subqueries(u.salvage(), scope);
        }
    }

    private void loop(Routine.Loop l, VarScope scope) {
        VarScope inner = new VarScope(scope);
        inner.declare(l.variable());
        switch (l.kind()) {
            case FOR_QUERY:
                if (l.query() != null) {
                    sql(l.query(), scope, l.cursor() == null ? Map.of() : Map.of("cursor", l.cursor()));
                }
                inner.declare(l.cursor());
                if (l.columnsInScope()) {
                    declareQueryColumns(l.query(), scope, inner);
                }
                statements(l.statements(), inner);
                break;
            case REPEAT:
                statements(l.statements(), inner);
                expr(l.control(), inner);
                break;
            default:
                expr(l.control(), scope);
                statements(l.statements(), inner);
                break;
        }
    }

    /**
     * DB2 {@code FOR v AS [c CURSOR FOR] SELECT .. DO}: 선택 목록의 컬럼 이름(별칭, 별칭이 없으면 단순 컬럼 이름)을 루프 본문의 변수로 선언한다
     * (F-008-R16). {@code *}는 카탈로그가 그 테이블의 컬럼을 알 때만 펼친다. 이름을 다 알 수 없으면(컬럼을 모르는 테이블의 {@code *},
     * 해석하지 못한 쿼리) 스코프를 열린 것으로 표시해, 그 안에서는 선언되지 않은 이름을 컬럼이라고 추측하지 않게 한다.
     */
    private void declareQueryColumns(Routine.Sql query, VarScope outer, VarScope inner) {
        if (query == null || !(query.statement() instanceof Statement.Select select)) {
            inner.markOpen();
            return;
        }
        FactExtractor.OutputColumns columns = new FactExtractor(env.catalog()).syntax(env.syntax()).variables(outer::isVariable).inPackage(packageName)
                .transientRelations(transientRelations::contains).outputColumns(select.query());
        for (FactExtractor.OutputColumn c : columns.columns()) {
            inner.declare(c.name());
        }
        if (!columns.complete()) {
            inner.markOpen();
        }
    }

    private void call(Routine.Call c, VarScope scope) {
        List<String> parts = c.name().parts();
        int n = parts.size();
        if (n >= 2 && parts.get(n - 2).equals("DBMS_SQL") && parts.get(n - 1).equals("PARSE") && c.args() != null) {
            List<List<Token>> args = splitArguments(c.args().tokens());
            if (args.size() >= 2) {
                dynamicPoint(args.get(1), c.pos(), "DBMS_SQL", scope);
            } else {
                dynamicSeen = true;
            }
        } else {
            Target t = resolveCall(parts, scope, true);
            if (t != null) {
                sink.call(t.packageName(), t.name(), c.name().pos(), false);
            } else if (postgres && !c.tokens().isEmpty()) {
                // PERFORM setval('seq', ..): 내장 시퀀스 함수의 호출문도 시퀀스 참조다(F-019-R16)
                sequences(new Routine.Expr(c.tokens(), List.of(), List.of(), c.pos()), scope, c.pos(), c.tokens());
            }
        }
        expr(c.args(), scope);
    }

    private static List<List<Token>> splitArguments(List<Token> tokens) {
        List<List<Token>> out = new ArrayList<>();
        int parens = 0;
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0 && t.isSymbol(",")) {
                out.add(tokens.subList(start, i));
                start = i + 1;
            }
        }
        out.add(tokens.subList(start, tokens.size()));
        return out;
    }

    // ------------------------------------------------------------------ 식과 호출 판정

    private void expr(Routine.Expr e, VarScope scope) {
        if (e == null) {
            return;
        }
        callsAndSubqueries(e, scope);
        sequences(e, scope, e.pos(), e.tokens());
    }

    private void callsAndSubqueries(Routine.Expr e, VarScope scope) {
        if (e == null) {
            return;
        }
        subqueries(e, scope);
        for (Routine.NameRef r : e.refs()) {
            if (postgres && !r.call()) {
                continue; // PostgreSQL의 함수 호출에는 괄호가 있다: 괄호 없는 이름은 변수·컬럼·키워드다
            }
            Target t = resolveCall(r.name().parts(), scope, false);
            if (t != null) {
                sink.call(t.packageName(), t.name(), r.name().pos(), true);
            }
        }
    }

    /**
     * 식 안의 시퀀스 참조(F-008-R15). SQL 구문이 아닌 자리(대입식, 조건식, 인자, 초기값)의 {@code seq.NEXTVAL}·{@code seq.CURRVAL},
     * {@code NEXT VALUE FOR seq}·{@code PREVIOUS VALUE FOR seq}(DB2의 옛 표기 {@code NEXTVAL FOR}·{@code PREVVAL FOR} 포함)를
     * 구문 노드 하나({@code sequence=true})로 만들고 시퀀스로 READS를 낸다. SQL 구문 안의 시퀀스 참조와 같은 표현이다.
     *
     * <p>{@code x.NEXTVAL}의 x가 변수·레코드면 필드 접근이므로 시퀀스가 아니다. 식 안의 서브쿼리는 그 구문이 따로 기록하므로 건너뛴다.
     *
     * @param statementPos 구문 노드의 위치(시작 줄이 구문 ID의 줄)
     * @param statementTokens 구문 노드의 {@code sql}로 적을 원문의 토큰
     */
    private void sequences(Routine.Expr e, VarScope scope, Pos statementPos, List<Token> statementTokens) {
        if (e == null || e.tokens().size() < 3) {
            return;
        }
        List<Token> tokens = e.tokens();
        Set<Token> inSubquery = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Routine.Sql q : e.subqueries()) {
            inSubquery.addAll(q.tokens());
        }
        StatementFacts facts = null;
        int n = tokens.size();
        int i = 0;
        while (i < n) {
            Token t = tokens.get(i);
            boolean afterDot = i > 0 && tokens.get(i - 1).isSymbol(".");
            if (inSubquery.contains(t) || !t.isIdentifier() || afterDot) {
                i++;
                continue;
            }
            if (postgres && t.isAny("NEXTVAL", "CURRVAL", "SETVAL") && i + 2 < n && tokens.get(i + 1).isSymbol("(")
                    && tokens.get(i + 2).type() == io.codeatlas.sql.lexer.TokenType.STRING) {
                // PostgreSQL nextval('seq')·currval('seq')·setval('seq', ..): 첫 인자가 문자열 리터럴일 때만 시퀀스 참조다(F-019-R16)
                io.codeatlas.sql.ast.QName sequenceName = SqlParser.sequenceName(tokens.get(i + 2));
                if (sequenceName != null) {
                    if (facts == null) {
                        facts = new StatementFacts();
                        facts.noteType("OTHER");
                    }
                    facts.sequence(sequenceName.name(), Pos.of(tokens.get(i + 2)));
                }
                i += 3;
                continue;
            }
            int nameAt = -1;
            if (t.isAny("NEXT", "PREVIOUS") && i + 3 < n && tokens.get(i + 1).is("VALUE") && tokens.get(i + 2).is("FOR")
                    && tokens.get(i + 3).isIdentifier()) {
                nameAt = i + 3;
            } else if (t.isAny("NEXTVAL", "PREVVAL") && i + 2 < n && tokens.get(i + 1).is("FOR") && tokens.get(i + 2).isIdentifier()) {
                nameAt = i + 2;
            }
            // 점으로 이은 이름을 끝까지 읽는다
            int from = nameAt >= 0 ? nameAt : i;
            int last = from;
            while (last + 2 < n && tokens.get(last + 1).isSymbol(".") && tokens.get(last + 2).isIdentifier()) {
                last += 2;
            }
            Token sequence = null;
            if (nameAt >= 0) {
                sequence = tokens.get(last); // [스키마.]시퀀스
            } else if (last > from && tokens.get(last).isAny("NEXTVAL", "CURRVAL")
                    && !scope.isVariable(DbName.normalize(tokens.get(from).text()))) {
                sequence = tokens.get(last - 2); // [스키마.]시퀀스.NEXTVAL
            }
            if (sequence != null && !sequence.isDynamic()) {
                if (facts == null) {
                    facts = new StatementFacts();
                    facts.noteType("OTHER");
                }
                facts.sequence(DbName.normalize(sequence.text()), Pos.of(sequence));
            }
            i = last + 1;
        }
        if (facts != null) {
            StatementFacts found = facts;
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("sequence", true);
            sink.statement(statementPos, sourceText(statementTokens), attrs, w -> w.add(found, env.locator()));
        }
    }

    private void subqueries(Routine.Expr e, VarScope scope) {
        if (e == null) {
            return;
        }
        for (Routine.Sql q : e.subqueries()) {
            sql(q, scope, Map.of());
        }
    }

    /**
     * 호출 대상을 판정한다. 변수·지역 서브프로그램이면 null, 같은 패키지의 루틴이면 그 패키지 소속, 카탈로그에 정의된 루틴이면 그것,
     * 내장이면 null, 그 밖에는 호출문 형태일 때만 참조로 만든다.
     *
     * @param statementForm 문장 자리의 호출(프로시저 호출문, CALL)이면 true
     */
    private Target resolveCall(List<String> parts, VarScope scope, boolean statementForm) {
        Catalog catalog = env.catalog();
        int n = parts.size();
        String first = parts.get(0);
        if (scope.isVariable(first)) {
            return null; // 변수, 컬렉션 첨자, 레코드 필드, 커서
        }
        String name = parts.get(n - 1);
        if (postgres) {
            // PostgreSQL에는 패키지가 없다: 이름 앞의 조각은 스키마(와 데이터베이스)이므로 뗀다(F-019-R27)
            if (Builtins.isPostgresCatalogSchema(parts)) {
                return null;
            }
            if (catalog.hasRoutine(null, name)) {
                return new Target(null, name);
            }
            if (!statementForm || Builtins.isBuiltin(parts) || Builtins.isPostgresBuiltin(parts)) {
                return null;
            }
            return new Target(null, name);
        }
        if (n == 1) {
            if (scope.isLocalRoutine(name)) {
                return null;
            }
            if (packageName != null && catalog.hasRoutine(packageName, name)) {
                return new Target(packageName, name);
            }
            if (catalog.hasRoutine(null, name)) {
                return new Target(null, name);
            }
            if (Builtins.isBuiltin(parts) || !statementForm) {
                return null;
            }
            return new Target(null, name);
        }
        String qualifier = parts.get(n - 2);
        if (catalog.hasRoutine(qualifier, name)) {
            return new Target(qualifier, name);
        }
        boolean schemaQualified = n == 2 && !catalog.hasPackage(qualifier) && catalog.isSchema(qualifier);
        if (schemaQualified && catalog.hasRoutine(null, name)) {
            return new Target(null, name);
        }
        if (Builtins.isBuiltin(parts) || !statementForm) {
            return null;
        }
        return schemaQualified ? new Target(null, name) : new Target(qualifier, name);
    }

    // ------------------------------------------------------------------ SQL 구문

    private void sql(Routine.Sql q, VarScope scope, Map<String, Object> attrs) {
        Statement st = q.statement();
        if (st instanceof Statement.Empty || st instanceof Statement.Other) {
            return;
        }
        if (st instanceof Statement.Unparsed u) {
            report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                    "지원하지 않는 구문: " + u.keyword() + " (" + ownerLabel + ")", env.locator().locate(q.pos())));
            return;
        }
        List<ParseProblem> problems = new ArrayList<>();
        for (Routine.Problem p : q.problems()) {
            problems.add(new ParseProblem(p.message(), p.pos(), p.minor()));
        }
        ParseResult result = new ParseResult(st, problems, q.salvagedReads(), q.pos());
        StatementFacts facts = new FactExtractor(env.catalog()).syntax(env.syntax()).variables(scope::isVariable)
                .scopeComplete(scopeReliable && !scope.isOpen())
                .inPackage(packageName).transientRelations(transientRelations::contains).extract(result);
        sink.statement(q.pos(), sourceText(q.tokens()), attrs, w -> w.add(facts, env.locator()));
        reportProblems(problems, q.pos());
    }

    /** 토큰 구간의 원문(공백 정리, 길이 제한). 원문을 모르면 토큰을 이어 붙인다. */
    private String sourceText(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return "";
        }
        String text = null;
        if (env.sourceText() != null) {
            int from = tokens.get(0).offset() - env.sourceOffset();
            int to = tokens.get(tokens.size() - 1).endOffset() - env.sourceOffset();
            if (from >= 0 && to > from && to <= env.sourceText().length()) {
                text = env.sourceText().substring(from, to);
            }
        }
        return normalize(text != null ? text : TokenCursor.joinTokens(tokens));
    }

    static String normalize(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > SQL_ATTR_LIMIT ? s.substring(0, SQL_ATTR_LIMIT) : s;
    }

    // ------------------------------------------------------------------ 동적 SQL

    /**
     * 동적 SQL 지점 하나. 식이 리터럴로 완전히 풀리면 그 SQL을 파싱해 일반 구문처럼 기록하고, 아니면 미해결로 남긴다.
     *
     * @param expression SQL 문자열을 만드는 식의 토큰
     * @param statementPos 동적 SQL을 실행·준비하는 문장의 범위(구문 ID의 줄)
     */
    private void dynamicPoint(List<Token> expression, Pos statementPos, String kind, VarScope scope) {
        dynamicSeen = true;
        if (expression.isEmpty()) {
            return;
        }
        DynamicSql.Resolution r = dynamicSql.resolve(expression);
        String text = normalize(r.text());
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("dynamic", true);
        attrs.put("dynamicKind", kind);
        SourceLocation where = env.locator().locate(statementPos);
        Locator fixed = new Locator() {
            @Override
            public SourceLocation locate(Pos pos) {
                return where;
            }

            @Override
            public boolean exact() {
                return false;
            }
        };
        if (r.resolved() && !text.isEmpty()) {
            sink.statement(statementPos, text, attrs, w -> resolvedDynamic(w, r.text(), fixed));
            return;
        }
        StatementFacts facts = new StatementFacts();
        String keyword = firstKeyword(r.text());
        switch (keyword) {
            case "INSERT":
            case "UPDATE":
            case "DELETE":
            case "MERGE":
                facts.noteType(keyword);
                facts.dynamicStatement(true, keyword, text, statementPos);
                break;
            case "TRUNCATE":
                facts.noteType("DELETE");
                facts.dynamicStatement(true, "DELETE", text, statementPos);
                break;
            case "SELECT":
            case "WITH":
                facts.noteType("SELECT");
                facts.dynamicStatement(false, null, text, statementPos);
                break;
            default:
                facts.noteType("OTHER");
                facts.dynamicStatement(false, null, text, statementPos);
                break;
        }
        sink.statement(statementPos, text, attrs, w -> w.add(facts, env.locator()));
    }

    /** 풀린 SQL의 첫 단어(대문자). 리터럴로 시작하지 않으면 빈 문자열. */
    private static String firstKeyword(String sql) {
        String s = sql.stripLeading();
        int i = 0;
        while (i < s.length() && Character.isLetter(s.charAt(i))) {
            i++;
        }
        return s.substring(0, i).toUpperCase(Locale.ROOT);
    }

    /** 완전히 풀린 동적 SQL을 파싱해 사실을 더한다. 위치는 모두 실행 문장의 위치다. */
    private void resolvedDynamic(FactsWriter writer, String sqlText, Locator fixed) {
        StatementFacts base = new StatementFacts();
        base.noteType("OTHER");
        base.markDynamic();
        writer.add(base, fixed);
        Syntax syntax = env.dynamicSyntax();
        for (ScriptUnit unit : ScriptSplitter.splitEmbedded(sqlText, syntax)) {
            List<Token> tokens = new ArrayList<>(unit.tokens());
            if (unit.kind() == ScriptUnit.Kind.BLOCK) {
                if (dynamicNesting < MAX_DYNAMIC_NESTING) {
                    Env inner = new Env(env.graph(), env.catalog(), env.dialect(), fixed, unit.text(), tokens.get(0).offset(), true, env.reported(),
                            env.interpret(), syntax);
                    BodyWalker walker = new BodyWalker(inner, packageName, new BlockSink(writer, fixed), ownerLabel, dynamicNesting + 1);
                    RoutineLanguage language = RoutineLanguage.of(syntax, RoutineBodyHandler.blockIsSqlPl(tokens, env.interpret()));
                    Routine.Block body = new RoutineParser(tokens, language).parseBody(false);
                    walker.walkBody(body, new VarScope(null), Set.of());
                }
                continue;
            }
            ParseResult result = DdlParser.isDdl(tokens) ? new DdlParser(tokens, syntax).parse() : new SqlParser(tokens, syntax).parseStatement();
            if (result.statement() instanceof Statement.Empty) {
                continue;
            }
            // 동적 SQL 안의 이름은 실행 시점에 SQL로 해석된다: 본문의 변수가 아니다
            writer.add(new FactExtractor(env.catalog()).syntax(syntax).inPackage(packageName).extract(result), fixed);
        }
    }

    // ------------------------------------------------------------------ 진단

    private void report(Diagnostic d) {
        if (d.message().contains(io.codeatlas.sql.lexer.Token.DYNAMIC)) {
            d = new Diagnostic(d.severity(), d.code(), io.codeatlas.sql.analysis.DynamicText.display(d.message()), d.location());
        }
        if (env.reported().add(d.code() + "|" + d.message() + "|" + d.location())) {
            env.graph().report(d);
        }
    }

    /** SQL 구문에서 파서가 복구한 문제를 PARSE_FAILED(WARN) 하나로 보고한다. lenient면 가벼운 문제는 세지 않는다. */
    void reportProblems(List<ParseProblem> problems, Pos fallback) {
        ParseProblem first = null;
        int count = 0;
        for (ParseProblem p : problems) {
            if (env.lenient() && p.minor()) {
                continue;
            }
            if (first == null) {
                first = p;
            }
            count++;
        }
        if (first == null) {
            return;
        }
        SourceLocation location = env.locator().locate(first.pos().known() ? first.pos() : fallback);
        report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED,
                "구문 일부를 해석하지 못함: " + first.message() + (count > 1 ? " (외 " + (count - 1) + "건)" : "") + " (" + ownerLabel + ")", location));
    }
}
