package io.codeatlas.sql.routine;

import io.codeatlas.core.model.DbName;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.sql.analysis.Catalog;
import io.codeatlas.sql.analysis.DialectDetector;
import io.codeatlas.sql.analysis.FactsWriter;
import io.codeatlas.sql.analysis.Locator;
import io.codeatlas.sql.analysis.SqlIds;
import io.codeatlas.sql.analysis.StatementFacts;
import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.ast.Routine;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import io.codeatlas.sql.parser.ParseProblem;
import io.codeatlas.sql.parser.RoutineParser;
import io.codeatlas.sql.parser.TokenCursor;
import io.codeatlas.sql.script.RoutineContext;
import io.codeatlas.sql.script.RoutineHeader;
import io.codeatlas.sql.script.RoutineUnit;
import io.codeatlas.sql.script.RoutineUnitHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 루틴 단위의 본문을 분석하는 핸들러(F-008). {@code SqlAnalyzer}의 기본 핸들러다.
 *
 * <ul>
 *   <li>프로시저·함수·트리거: 본문을 파싱해 {@code ROUTINE -CONTAINS→ SQL_STATEMENT -READS/WRITES→ ..}, {@code ROUTINE -CALLS→ ROUTINE}을 쓴다.</li>
 *   <li>패키지 본문: 멤버마다 따로 파싱한다(멤버 하나가 깨져도 나머지는 분석한다). 패키지 수준 선언과 초기화부는 패키지 노드가 주인이다.</li>
 *   <li>패키지 명세: 본문이 없는 멤버에 {@code bodyMissing}을 붙인다.</li>
 *   <li>익명 블록은 {@link #analyzeBlock}으로 분석해 블록의 구문 노드 하나에 모은다.</li>
 * </ul>
 */
public final class RoutineBodyHandler implements RoutineUnitHandler {

    private static final String PLSQL = "PLSQL";
    private static final String SQLPL = "SQLPL";

    @Override
    public void handle(RoutineUnit unit, RoutineContext context) {
        RoutineHeader header = unit.header();
        if (header.name() == null || unit.unit().tokens().isEmpty()) {
            return;
        }
        List<Token> tokens = unit.unit().tokens();
        Dialect dialect = context.dialect() != null ? context.dialect() : DialectDetector.fromTokens(tokens);
        // 구문 노드에 적는 방언(dialect)은 파일·단위에서 판정한 것이고, 판정이 없을 때 문법을 고르는 데만 --dialect를 쓴다(F-006-R37)
        BodyWalker.Env env = new BodyWalker.Env(context.graph(), context.catalog(), dialect, context.locator(),
                unit.unit().text(), tokens.get(0).offset(), false, new HashSet<>(), dialect != null ? dialect : context.fallbackDialect());
        switch (header.kind()) {
            case PROCEDURE:
            case FUNCTION:
                standalone(unit, env);
                break;
            case TRIGGER:
                trigger(unit, env);
                break;
            case PACKAGE:
            case PACKAGE_BODY:
                packageUnit(unit, env);
                break;
            default:
                env.graph().report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                        "이 종류의 루틴 본문은 분석하지 않음: " + header.kind() + " " + header.name().display(),
                        env.locator().locate(unit.unit().pos())));
                break;
        }
    }

    // ------------------------------------------------------------------ 독립 프로시저·함수

    private void standalone(RoutineUnit unit, BodyWalker.Env env) {
        RoutineHeader header = unit.header();
        List<Token> tokens = unit.unit().tokens();
        int bodyStart = header.bodyStart();
        if (bodyStart < 0 || bodyStart >= tokens.size() || tokens.get(bodyStart).isAny("LANGUAGE", "EXTERNAL")) {
            return; // 본문이 없다(외부 루틴 등)
        }
        boolean plsql = bodyStart > 0 && tokens.get(bodyStart - 1).isAny("IS", "AS");
        String name = header.name().name();
        Routine.Block body = null;
        RoutineParser parser = new RoutineParser(tokens.subList(bodyStart, tokens.size()), !plsql);
        body = parser.parseBody(plsql);
        VarScope root = new VarScope(null);
        Set<String> parameters = new LinkedHashSet<>();
        for (String p : header.parameters()) {
            parameters.add(parameterName(p));
        }
        root.declareAll(parameters);
        RoutineSink sink = new RoutineSink(env, unit.nodeId(), name, name);
        BodyWalker walker = new BodyWalker(env, null, sink, name);
        walker.scopeReliable(parser.problems().isEmpty());
        walker.walkBody(body, root, parameters);
        reportParserProblems(env, parser.problems(), unit.unit().pos(), name);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("language", plsql ? PLSQL : SQLPL);
        attrs.put("statementCount", sink.statementIds().size());
        attrs.put("dynamicSql", walker.dynamicSeen());
        if (!header.parameters().isEmpty()) {
            List<String> normalized = new ArrayList<>();
            header.parameters().forEach(p -> normalized.add(parameterText(p)));
            attrs.put("parameters", normalized);
        }
        if (header.kind() == RoutineHeader.Kind.FUNCTION) {
            attrs.put("returnType", returnType(tokens, bodyStart));
        }
        Set<String> locals = declaredNames(body);
        locals.addAll(parameters);
        putTypeRefs(attrs, typeRefs(tokens, locals, env.catalog()));
        env.graph().putAttrs(unit.nodeId(), attrs);
    }

    // ------------------------------------------------------------------ %TYPE, %ROWTYPE

    /**
     * 토큰 구간에서 {@code %TYPE}·{@code %ROWTYPE}이 가리키는 테이블·컬럼을 나온 순서대로 모은다(F-008-R14).
     * {@code TB_ORDER.ORDER_STATUS%TYPE} → {@code TB_ORDER.ORDER_STATUS}, {@code SHOP.TB_X%ROWTYPE} → {@code TB_X}(스키마 제거·대문자).
     * 변수·레코드·커서를 기준으로 한 것({@code v%TYPE}, {@code rec.col%TYPE}, {@code c%ROWTYPE})과 패키지 변수·커서({@code pkg.v%TYPE})는 뺀다.
     *
     * @param locals 그 루틴에서 선언된 이름(매개변수, 변수, 커서, 루프 변수, 패키지 수준 변수)
     */
    static List<String> typeRefs(List<Token> tokens, Set<String> locals, Catalog catalog) {
        Set<String> refs = new LinkedHashSet<>();
        for (int i = 1; i + 1 < tokens.size(); i++) {
            if (!tokens.get(i).isSymbol("%") || !tokens.get(i + 1).isAny("TYPE", "ROWTYPE") || !tokens.get(i - 1).isIdentifier()) {
                continue;
            }
            boolean rowType = tokens.get(i + 1).is("ROWTYPE");
            List<String> parts = new ArrayList<>();
            int j = i - 1;
            parts.add(DbName.normalize(tokens.get(j).text()));
            while (j - 2 >= 0 && tokens.get(j - 1).isSymbol(".") && tokens.get(j - 2).isIdentifier()) {
                j -= 2;
                parts.add(0, DbName.normalize(tokens.get(j).text()));
            }
            if (j - 2 >= 0 && tokens.get(j - 1).isSymbol(".") && tokens.get(j - 2).type() == TokenType.BIND) {
                continue; // :NEW.COL%TYPE
            }
            String first = parts.get(0);
            if (locals.contains(first)) {
                continue;
            }
            int n = parts.size();
            if (rowType) {
                if (n == 1) {
                    refs.add(first);
                } else if (n == 2 && !(catalog.hasPackage(first) && catalog.table(parts.get(1)) == null)) {
                    refs.add(parts.get(1)); // 스키마.테이블
                }
            } else if (n == 2 && !(catalog.hasPackage(first) && catalog.table(first) == null)) {
                refs.add(first + "." + parts.get(1)); // 테이블.컬럼
            } else if (n == 3 && !catalog.hasPackage(first)) {
                refs.add(parts.get(1) + "." + parts.get(2)); // 스키마.테이블.컬럼
            }
        }
        return new ArrayList<>(refs);
    }

    private static void putTypeRefs(Map<String, Object> attrs, Collection<String> refs) {
        if (!refs.isEmpty()) {
            attrs.put("typeRefs", new ArrayList<>(refs));
        }
    }

    /** 본문에서 선언된 이름: 변수·상수·커서, 지역 서브프로그램의 매개변수, 루프 변수. */
    private static Set<String> declaredNames(Routine.Block body) {
        Set<String> names = new HashSet<>();
        if (body == null) {
            return names;
        }
        Routine.visit(body, new Routine.Visitor() {
            @Override
            public void declaration(Routine.Decl d) {
                if (d instanceof Routine.Variable v) {
                    names.addAll(v.names());
                } else if (d instanceof Routine.Cursor c) {
                    add(c.name());
                    c.params().forEach(p -> add(p.name()));
                } else if (d instanceof Routine.Subprogram sp) {
                    sp.params().forEach(p -> add(p.name()));
                }
            }

            @Override
            public void statement(Routine.Stmt st) {
                if (st instanceof Routine.Loop l) {
                    add(l.variable());
                    add(l.cursor());
                } else if (st instanceof Routine.ForAll f) {
                    add(f.index());
                }
            }

            private void add(String name) {
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
        });
        return names;
    }

    /** 헤더의 매개변수 원문({@code P_ID IN NUMBER}, DB2 {@code IN P_ID INT})에서 이름을 얻는다. */
    static String parameterName(String parameter) {
        String[] words = parameter.trim().split("\\s+");
        int i = 0;
        if (words.length >= 2 && (words[0].equals("IN") || words[0].equals("OUT") || words[0].equals("INOUT"))
                && !(words[1].equals("IN") || words[1].equals("OUT") || words[1].equals("NOCOPY"))) {
            i = 1;
        }
        return DbName.normalize(words[i]);
    }

    /** 매개변수 표기를 {@code 이름 모드 타입} 순서로 맞춘다(DB2의 {@code IN P_ID INT} → {@code P_ID IN INT}). */
    static String parameterText(String parameter) {
        String[] words = parameter.trim().split("\\s+", 3);
        if (words.length >= 2 && (words[0].equals("IN") || words[0].equals("OUT") || words[0].equals("INOUT"))
                && !(words[1].equals("IN") || words[1].equals("OUT") || words[1].equals("NOCOPY"))) {
            return words[1] + " " + words[0] + (words.length > 2 ? " " + words[2] : "");
        }
        return parameter;
    }

    /** 헤더에서 {@code RETURN|RETURNS 타입}의 타입 표기를 얻는다. 없으면 null. */
    private static String returnType(List<Token> tokens, int bodyStart) {
        int parens = 0;
        for (int i = 0; i < bodyStart && i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0 && t.isAny("RETURN", "RETURNS") && i + 1 < bodyStart) {
                int j = i + 1;
                if (!tokens.get(j).isIdentifier()) {
                    return null;
                }
                j++;
                while (j + 1 < bodyStart && tokens.get(j).isSymbol(".") && tokens.get(j + 1).isIdentifier()) {
                    j += 2;
                }
                if (j + 1 < bodyStart && tokens.get(j).isSymbol("%") && tokens.get(j + 1).isIdentifier()) {
                    j += 2;
                }
                if (j < bodyStart && tokens.get(j).isSymbol("(")) {
                    int d = 0;
                    while (j < bodyStart) {
                        if (tokens.get(j).isSymbol("(")) {
                            d++;
                        } else if (tokens.get(j).isSymbol(")")) {
                            d--;
                            if (d == 0) {
                                j++;
                                break;
                            }
                        }
                        j++;
                    }
                }
                return TokenCursor.joinTokens(tokens.subList(i + 1, Math.min(j, bodyStart))).replace(" %", "%");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 트리거

    private void trigger(RoutineUnit unit, BodyWalker.Env env) {
        RoutineHeader header = unit.header();
        List<Token> tokens = unit.unit().tokens();
        String name = header.name().name();
        int bodyStart = header.bodyStart();
        boolean sqlPl;
        if (bodyStart >= 0 && bodyStart < tokens.size()) {
            sqlPl = triggerIsSqlPl(tokens, bodyStart, env.interpret());
        } else {
            bodyStart = singleStatementStart(tokens);
            if (bodyStart < 0) {
                return;
            }
            sqlPl = true;
        }
        VarScope root = new VarScope(null);
        root.declare("NEW");
        root.declare("OLD");
        Set<String> transitionTables = new LinkedHashSet<>();
        root.declareAll(correlationNames(tokens, bodyStart, transitionTables));
        RoutineSink sink = new RoutineSink(env, unit.nodeId(), name, name);
        BodyWalker walker = new BodyWalker(env, null, sink, name);
        walker.transientRelations(transitionTables);
        // WHEN (조건): 서브쿼리와 함수 호출이 있을 수 있다
        int parens = 0;
        for (int i = 0; i < bodyStart; i++) {
            Token t = tokens.get(i);
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0 && t.is("WHEN") && tokens.get(i + 1).isSymbol("(")) {
                int close = matching(tokens, i + 1, bodyStart);
                RoutineParser conditionParser = new RoutineParser(tokens.subList(i + 2, Math.max(i + 2, close)), sqlPl);
                walker.walkExpression(conditionParser.parseExpression(), root);
                break;
            }
        }
        RoutineParser parser = new RoutineParser(tokens.subList(bodyStart, tokens.size()), sqlPl);
        Routine.Block body = parser.parseBody(false);
        boolean dynamicInCondition = walker.dynamicSeen();
        walker.scopeReliable(parser.problems().isEmpty());
        walker.walkBody(body, root, Set.of());
        reportParserProblems(env, parser.problems(), unit.unit().pos(), name);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("language", sqlPl ? SQLPL : PLSQL);
        attrs.put("statementCount", sink.statementIds().size());
        attrs.put("dynamicSql", walker.dynamicSeen() || dynamicInCondition);
        Set<String> locals = declaredNames(body);
        locals.add("NEW");
        locals.add("OLD");
        locals.addAll(correlationNames(tokens, bodyStart, new LinkedHashSet<>()));
        putTypeRefs(attrs, typeRefs(tokens, locals, env.catalog()));
        env.graph().putAttrs(unit.nodeId(), attrs);
    }

    private static int matching(List<Token> tokens, int open, int limit) {
        int d = 0;
        for (int i = open; i < limit; i++) {
            if (tokens.get(i).isSymbol("(")) {
                d++;
            } else if (tokens.get(i).isSymbol(")")) {
                d--;
                if (d == 0) {
                    return i;
                }
            }
        }
        return limit;
    }

    /** 트리거 본문이 SQL PL 문법인가. */
    private static boolean triggerIsSqlPl(List<Token> tokens, int bodyStart, Dialect dialect) {
        if (tokens.get(bodyStart).is("DECLARE")) {
            return false; // DB2 트리거 본문은 DECLARE로 시작하지 않는다
        }
        if (bodyStart + 1 < tokens.size() && tokens.get(bodyStart + 1).is("ATOMIC")) {
            return true;
        }
        for (int i = 0; i + 1 < bodyStart; i++) {
            if (tokens.get(i).is("MODE") && tokens.get(i + 1).is("DB2SQL")) {
                return true;
            }
        }
        return bodyIsSqlPl(tokens, bodyStart, dialect);
    }

    /** {@code :=}나 {@code :NEW}/{@code :OLD}가 있으면 PL/SQL, DB2 핸들러 선언이 있으면 SQL PL, 그 밖에는 파일 방언을 따른다. */
    private static boolean bodyIsSqlPl(List<Token> tokens, int from, Dialect dialect) {
        for (int i = from; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isSymbol(":=")) {
                return false;
            }
            if (t.type() == TokenType.BIND && (t.text().equalsIgnoreCase(":NEW") || t.text().equalsIgnoreCase(":OLD"))) {
                return false;
            }
            if (t.is("HANDLER") && i > 0 && tokens.get(i - 1).isAny("CONTINUE", "EXIT", "UNDO")) {
                return true;
            }
        }
        return dialect == Dialect.DB2;
    }

    /** 익명 블록이 SQL PL 문법인가. */
    static boolean blockIsSqlPl(List<Token> tokens, Dialect dialect) {
        if (tokens.isEmpty() || tokens.get(0).is("DECLARE")) {
            return false;
        }
        if (tokens.size() > 1 && tokens.get(1).is("ATOMIC")) {
            return true;
        }
        return bodyIsSqlPl(tokens, 0, dialect);
    }

    /** BEGIN이 없는 DB2 트리거의 단일 문장이 시작하는 토큰 인덱스. 못 찾으면 -1. */
    private static int singleStatementStart(List<Token> tokens) {
        int n = tokens.size();
        int start = -1;
        int parens = 0;
        for (int i = 0; i + 1 < n; i++) {
            Token t = tokens.get(i);
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0 && t.is("EACH") && tokens.get(i + 1).isAny("ROW", "STATEMENT")) {
                start = i + 2;
                break;
            }
        }
        if (start < 0) {
            return -1;
        }
        boolean moved = true;
        while (moved && start < n) {
            moved = false;
            if (start + 1 < n && tokens.get(start).is("MODE") && tokens.get(start + 1).is("DB2SQL")) {
                start += 2;
                moved = true;
            } else if (start + 1 < n && tokens.get(start).is("WHEN") && tokens.get(start + 1).isSymbol("(")) {
                start = matching(tokens, start + 1, n) + 1;
                moved = true;
            }
        }
        return start < n ? start : -1;
    }

    /**
     * {@code REFERENCING NEW [ROW] [AS] n OLD [ROW] [AS] o NEW_TABLE AS nt ..}의 상관 이름.
     *
     * @param transitionTables 전이 테이블({@code NEW TABLE}, {@code NEW_TABLE}, {@code OLD TABLE}, {@code OLD_TABLE}) 이름을 여기에 더한다
     * @return 행 상관 이름(레코드처럼 쓰인다)
     */
    private static List<String> correlationNames(List<Token> tokens, int bodyStart, Set<String> transitionTables) {
        List<String> names = new ArrayList<>();
        int i = 0;
        while (i < bodyStart && !tokens.get(i).is("REFERENCING")) {
            i++;
        }
        i++;
        while (i < bodyStart) {
            Token t = tokens.get(i);
            if (!t.isAny("NEW", "OLD", "NEW_TABLE", "OLD_TABLE", "PARENT")) {
                break;
            }
            boolean table = t.isAny("NEW_TABLE", "OLD_TABLE");
            i++;
            if (i < bodyStart && tokens.get(i).isAny("ROW", "TABLE")) {
                table |= tokens.get(i).is("TABLE");
                i++;
            }
            if (i < bodyStart && tokens.get(i).is("AS")) {
                i++;
            }
            if (i < bodyStart && tokens.get(i).isIdentifier() && !tokens.get(i).isAny("FOR", "WHEN", "NEW", "OLD", "NEW_TABLE", "OLD_TABLE", "MODE")) {
                String name = DbName.normalize(tokens.get(i).text());
                if (table) {
                    transitionTables.add(name);
                } else {
                    names.add(name);
                }
                i++;
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ 패키지

    /** 한 루틴(오버로드 포함)에 모이는 것. */
    private static final class Accumulator {
        final RoutineSink sink;
        final Set<String> typeRefs = new LinkedHashSet<>();
        boolean dynamic;
        String returnType;

        Accumulator(RoutineSink sink) {
            this.sink = sink;
        }
    }

    private void packageUnit(RoutineUnit unit, BodyWalker.Env env) {
        RoutineHeader header = unit.header();
        List<Token> tokens = unit.unit().tokens();
        String pkg = header.name().name();
        Catalog catalog = env.catalog();
        Map<String, Accumulator> byRoutine = new LinkedHashMap<>();
        for (RoutineHeader.Member m : header.members()) {
            String memberId = SqlIds.routine(pkg, m.name());
            int from = Math.max(0, Math.min(m.startToken(), tokens.size()));
            int to = Math.max(from, Math.min(m.endToken(), tokens.size()));
            String label = pkg + "." + m.name();
            try {
                RoutineParser parser = new RoutineParser(tokens.subList(from, to), false);
                Routine.Subprogram sp = parser.parseSubprogram();
                Set<String> locals = declaredNames(sp.body());
                locals.addAll(catalog.packageVariables(pkg));
                sp.params().forEach(p -> locals.add(p.name()));
                List<String> memberTypeRefs = typeRefs(tokens.subList(from, to), locals, catalog);
                if (!m.hasBody() || sp.body() == null) {
                    if (header.kind() == RoutineHeader.Kind.PACKAGE && !catalog.hasRoutineBody(pkg, m.name())) {
                        Map<String, Object> attrs = new LinkedHashMap<>();
                        attrs.put("bodyMissing", true);
                        attrs.put("language", PLSQL);
                        if (sp.returnType() != null && !sp.returnType().isEmpty()) {
                            attrs.put("returnType", sp.returnType());
                        }
                        // 오버로드된 명세가 여럿이면 한 노드에 모은다
                        Set<String> merged = new LinkedHashSet<>(env.graph().node(memberId).map(x -> x.attrList("typeRefs")).orElse(List.of()));
                        merged.addAll(memberTypeRefs);
                        putTypeRefs(attrs, merged);
                        env.graph().putAttrs(memberId, attrs);
                    }
                    continue;
                }
                VarScope root = new VarScope(null);
                root.declareAll(catalog.packageVariables(pkg));
                VarScope scope = new VarScope(root);
                Set<String> parameters = new LinkedHashSet<>();
                for (Routine.Param p : sp.params()) {
                    parameters.add(p.name());
                }
                scope.declareAll(parameters);
                Accumulator acc = byRoutine.computeIfAbsent(memberId, k -> new Accumulator(new RoutineSink(env, memberId, m.name(), label)));
                BodyWalker walker = new BodyWalker(env, pkg, acc.sink, label);
                walker.scopeReliable(parser.problems().isEmpty());
                for (Routine.Param p : sp.params()) {
                    walker.walkExpression(p.defaultValue(), root);
                }
                walker.walkBody(sp.body(), scope, parameters);
                acc.dynamic |= walker.dynamicSeen();
                acc.typeRefs.addAll(memberTypeRefs);
                if (sp.returnType() != null && !sp.returnType().isEmpty()) {
                    acc.returnType = sp.returnType();
                }
                Token first = tokens.get(from);
                Token last = tokens.get(Math.max(from, to - 1));
                reportParserProblems(env, parser.problems(), Pos.span(first, last), label);
            } catch (RuntimeException | StackOverflowError e) {
                // 멤버 하나의 실패가 다른 멤버를 막지 않는다
                env.graph().report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "루틴 본문을 분석하지 못함(" + label + "): " + e,
                        env.locator().locate(m.pos())));
            }
        }
        for (Map.Entry<String, Accumulator> e : byRoutine.entrySet()) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("language", PLSQL);
            attrs.put("statementCount", e.getValue().sink.statementIds().size());
            attrs.put("dynamicSql", e.getValue().dynamic);
            if (e.getValue().returnType != null) {
                attrs.put("returnType", e.getValue().returnType);
            }
            putTypeRefs(attrs, e.getValue().typeRefs);
            env.graph().putAttrs(e.getKey(), attrs);
        }
        packageTypeRefs(unit, env);
        // 패키지 수준 선언(초기값, 커서 쿼리)과 초기화부: 패키지 노드가 주인이다
        RoutineSink packageSink = new RoutineSink(env, unit.nodeId(), pkg, pkg);
        BodyWalker walker = new BodyWalker(env, pkg, packageSink, pkg);
        VarScope root = new VarScope(null);
        root.declareAll(catalog.packageVariables(pkg));
        for (Routine.Block section : packageSections(tokens, header)) {
            walker.walkBody(section, root, Set.of());
        }
    }

    /** 패키지 수준 선언(멤버 밖)의 %TYPE·%ROWTYPE 참조를 패키지 노드에 남긴다. 명세와 본문이 따로 있으면 합친다. */
    private static void packageTypeRefs(RoutineUnit unit, BodyWalker.Env env) {
        RoutineHeader header = unit.header();
        List<Token> tokens = unit.unit().tokens();
        if (header.bodyStart() < 0) {
            return;
        }
        Set<String> locals = new HashSet<>(env.catalog().packageVariables(header.name().name()));
        Set<String> refs = new LinkedHashSet<>(env.graph().node(unit.nodeId()).map(x -> x.attrList("typeRefs")).orElse(List.of()));
        List<RoutineHeader.Member> members = new ArrayList<>(header.members());
        members.sort(Comparator.comparingInt(RoutineHeader.Member::startToken));
        int prev = header.bodyStart();
        int n = tokens.size();
        for (int k = 0; k <= members.size(); k++) {
            int end = k < members.size() ? Math.min(members.get(k).startToken(), n) : n;
            if (end > prev) {
                refs.addAll(typeRefs(tokens.subList(prev, end), locals, env.catalog()));
            }
            if (k < members.size()) {
                prev = Math.max(prev, Math.min(members.get(k).endToken(), n));
            }
        }
        if (!refs.isEmpty()) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            putTypeRefs(attrs, refs);
            env.graph().putAttrs(unit.nodeId(), attrs);
        }
    }

    /** 패키지 단위에서 멤버 사이의 구간(패키지 수준 선언, 마지막은 초기화부일 수 있다)을 파싱한다. */
    private static List<Routine.Block> packageSections(List<Token> tokens, RoutineHeader header) {
        List<Routine.Block> sections = new ArrayList<>();
        if (header.bodyStart() < 0) {
            return sections;
        }
        List<RoutineHeader.Member> members = new ArrayList<>(header.members());
        members.sort(Comparator.comparingInt(RoutineHeader.Member::startToken));
        int prev = header.bodyStart();
        int n = tokens.size();
        for (int k = 0; k <= members.size(); k++) {
            int end = k < members.size() ? Math.min(members.get(k).startToken(), n) : n;
            if (end > prev) {
                sections.add(new RoutineParser(tokens.subList(prev, end), false).parsePackageSection());
            }
            if (k < members.size()) {
                prev = Math.max(prev, Math.min(members.get(k).endToken(), n));
            }
        }
        return sections;
    }

    /**
     * 패키지 명세·본문의 패키지 수준 변수·상수·커서·예외 이름. {@code SqlAnalyzer}가 카탈로그에 넣어, 명세와 본문이 다른 파일이어도
     * 본문의 SQL에서 이 이름들을 컬럼으로 오인하지 않게 한다. 어떤 입력에도 예외를 던지지 않는다.
     */
    public static Set<String> packageVariableNames(List<Token> tokens, RoutineHeader header) {
        Set<String> names = new LinkedHashSet<>();
        try {
            for (Routine.Block section : packageSections(tokens, header)) {
                for (Routine.Decl d : section.declarations()) {
                    if (d instanceof Routine.Variable v) {
                        names.addAll(v.names());
                    } else if (d instanceof Routine.Cursor c) {
                        names.add(c.name());
                    }
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            // 이름을 모으지 못해도 분석은 계속한다
        }
        return names;
    }

    // ------------------------------------------------------------------ 익명 블록

    /**
     * 익명 블록({@code BEGIN .. END}, {@code DECLARE ..})을 분석해 안쪽 구문의 사실과 호출을 주인 구문 노드의 기록기에 모은다.
     * 그래프에는 진단만 쓴다(엣지는 호출한 쪽이 {@code writer.finish}로 쓴다).
     *
     * @param tokens 블록의 토큰
     * @param text 블록 원문(첫 토큰부터). 모르면 null
     * @param lenient 내장 소스면 true: 가벼운 파싱 문제는 보고하지 않는다
     * @param reported 같은 주인의 진단 중복을 막는 집합
     */
    public static void analyzeBlock(List<Token> tokens, String text, GraphModel graph, Catalog catalog, Dialect dialect,
            FactsWriter writer, Locator locator, boolean lenient, Set<String> reported) {
        StatementFacts base = new StatementFacts();
        base.noteType("OTHER");
        writer.add(base, locator);
        if (tokens.isEmpty()) {
            return;
        }
        BodyWalker.Env env = new BodyWalker.Env(graph, catalog, dialect, locator, text, tokens.get(0).offset(), lenient, reported);
        RoutineParser parser = new RoutineParser(tokens, blockIsSqlPl(tokens, dialect));
        Routine.Block body = parser.parseBody(false);
        BodyWalker walker = new BodyWalker(env, null, new BlockSink(writer, locator), "익명 블록");
        walker.scopeReliable(parser.problems().isEmpty());
        walker.walkBody(body, new VarScope(null), Set.of());
        reportParserProblems(env, parser.problems(), Pos.span(tokens.get(0), tokens.get(tokens.size() - 1)), "익명 블록");
    }

    // ------------------------------------------------------------------ 진단

    /** 본문 파서가 구조를 복구하며 남긴 문제를 PARSE_FAILED(WARN) 하나로 보고한다. */
    private static void reportParserProblems(BodyWalker.Env env, List<ParseProblem> problems, Pos fallback, String label) {
        if (problems.isEmpty()) {
            return;
        }
        ParseProblem first = problems.get(0);
        String message = io.codeatlas.sql.analysis.DynamicText.display("루틴 본문 일부를 해석하지 못함: " + first.message()
                + (problems.size() > 1 ? " (외 " + (problems.size() - 1) + "건)" : "") + " (" + label + ")");
        Diagnostic d = Diagnostic.warn(DiagnosticCodes.PARSE_FAILED, message, env.locator().locate(first.pos().known() ? first.pos() : fallback));
        if (env.reported().add(d.code() + "|" + d.message() + "|" + d.location())) {
            env.graph().report(d);
        }
    }
}
