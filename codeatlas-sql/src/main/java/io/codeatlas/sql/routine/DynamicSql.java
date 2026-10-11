package io.codeatlas.sql.routine;

import io.codeatlas.core.model.DbName;
import io.codeatlas.sql.ast.Routine;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 동적 SQL의 문자열 식을 푼다. 푸는 것은 문자열·숫자 리터럴, 리터럴끼리의 연결({@code ||}, DB2 {@code CONCAT}), 괄호,
 * 그리고 같은 본문 안에서 대입이 정확히 한 번인 변수(그 값이 다시 풀릴 때)뿐이다. 그 밖의 조각은 {@link #HOLE}로 남긴다.
 * 흐름(분기·루프)과 OUT 인자로 바뀌는 값은 보지 않는다.
 */
final class DynamicSql {

    /** 풀지 못한 조각의 자리 표시. */
    static final String HOLE = "{*}";

    private static final int MAX_DEPTH = 8;

    /** @param resolved 조각이 전부 풀렸으면 true */
    record Resolution(String text, boolean resolved) {
    }

    /** 변수 이름 → 대입된 식들. 식을 알 수 없는 대입(INTO, FETCH, 튜플)은 null로 센다. */
    private final Map<String, List<Routine.Expr>> assignments = new HashMap<>();
    private final Set<String> parameters = new HashSet<>();

    /**
     * @param body 대입을 모을 본문(중첩 블록과 지역 서브프로그램 포함)
     * @param parameterNames 매개변수 이름(값이 밖에서 오므로 풀지 않는다)
     */
    DynamicSql(Routine.Block body, Set<String> parameterNames) {
        parameters.addAll(parameterNames);
        if (body != null) {
            Routine.visit(body, new Collector());
        }
    }

    private final class Collector implements Routine.Visitor {

        @Override
        public void declaration(Routine.Decl d) {
            if (d instanceof Routine.Variable v && v.init() != null) {
                for (String name : v.names()) {
                    add(name, v.init());
                }
            } else if (d instanceof Routine.Subprogram sp) {
                for (Routine.Param p : sp.params()) {
                    parameters.add(p.name());
                }
            } else if (d instanceof Routine.Cursor c) {
                for (Routine.Param p : c.params()) {
                    parameters.add(p.name());
                }
            }
        }

        @Override
        public void statement(Routine.Stmt s) {
            if (s instanceof Routine.Assign a) {
                if (a.targets().size() == 1 && a.targets().get(0).size() == 1 && a.index() == null) {
                    add(a.targets().get(0).name(), a.value());
                } else {
                    for (var t : a.targets()) {
                        if (t.size() == 1) {
                            add(t.name(), null);
                        }
                    }
                }
            } else if (s instanceof Routine.Sql q) {
                q.intoTargets().forEach(n -> add(n, null));
            } else if (s instanceof Routine.Fetch f && f.into() != null) {
                for (Routine.NameRef r : f.into().refs()) {
                    if (r.name().size() == 1) {
                        add(r.name().name(), null);
                    }
                }
            } else if (s instanceof Routine.ExecuteImmediate e) {
                intoNames(e.rest());
            } else if (s instanceof Routine.Execute e) {
                intoNames(e.rest());
            } else if (s instanceof Routine.Loop l && l.variable() != null) {
                add(l.variable(), null);
            }
        }

        @Override
        public void sql(Routine.Sql q) {
            q.intoTargets().forEach(n -> add(n, null));
        }

        /** {@code INTO a, b} 뒤의 이름을 대입으로 센다. */
        private void intoNames(Routine.Expr rest) {
            if (rest == null) {
                return;
            }
            boolean into = false;
            Token prev = null;
            for (Token t : rest.tokens()) {
                if (t.is("INTO")) {
                    into = true;
                } else if (t.isAny("USING", "RETURNING", "RETURN")) {
                    into = false;
                } else if (into && t.isIdentifier() && (prev == null || !prev.isSymbol(".")) && !t.isAny("BULK", "COLLECT")) {
                    add(DbName.normalize(t.text()), null);
                }
                prev = t;
            }
        }
    }

    private void add(String name, Routine.Expr value) {
        assignments.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }

    Resolution resolve(Routine.Expr expr) {
        return expr == null ? new Resolution("", false) : resolve(expr.tokens());
    }

    Resolution resolve(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        boolean resolved = !tokens.isEmpty() && append(tokens, sb, 0, new HashSet<>());
        return new Resolution(sb.toString(), resolved);
    }

    /** @return 전부 풀렸으면 true */
    private boolean append(List<Token> tokens, StringBuilder sb, int depth, Set<String> visiting) {
        boolean resolved = true;
        for (List<Token> operand : splitConcat(tokens)) {
            List<Token> op = stripParens(operand);
            if (op.isEmpty()) {
                continue;
            }
            if (op.size() == 1) {
                Token t = op.get(0);
                if (t.type() == TokenType.STRING) {
                    sb.append(unquote(t.text()));
                } else if (t.type() == TokenType.NUMBER) {
                    sb.append(t.text());
                } else if (t.isIdentifier()) {
                    String name = DbName.normalize(t.text());
                    List<Routine.Expr> values = assignments.get(name);
                    if (depth < MAX_DEPTH && !parameters.contains(name) && values != null && values.size() == 1
                            && values.get(0) != null && visiting.add(name)) {
                        resolved &= append(values.get(0).tokens(), sb, depth + 1, visiting);
                        visiting.remove(name);
                    } else {
                        sb.append(HOLE);
                        resolved = false;
                    }
                } else {
                    sb.append(HOLE);
                    resolved = false;
                }
            } else if (op != operand || hasTopLevelConcat(op)) {
                if (hasTopLevelConcat(op) && depth < MAX_DEPTH) {
                    resolved &= append(op, sb, depth + 1, visiting);
                } else {
                    sb.append(HOLE);
                    resolved = false;
                }
            } else {
                sb.append(HOLE);
                resolved = false;
            }
        }
        return resolved;
    }

    private static boolean isConcat(Token t) {
        return t.isSymbol("||") || t.is("CONCAT");
    }

    private static boolean hasTopLevelConcat(List<Token> tokens) {
        int parens = 0;
        for (Token t : tokens) {
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0 && isConcat(t)) {
                return true;
            }
        }
        return false;
    }

    /** 괄호 밖의 연결 연산자에서 나눈다. */
    private static List<List<Token>> splitConcat(List<Token> tokens) {
        List<List<Token>> out = new ArrayList<>();
        int parens = 0;
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0 && isConcat(t) && !(t.is("CONCAT") && i + 1 < tokens.size() && tokens.get(i + 1).isSymbol("("))) {
                out.add(tokens.subList(start, i));
                start = i + 1;
            }
        }
        out.add(tokens.subList(start, tokens.size()));
        return out;
    }

    /** 식 전체를 감싼 괄호를 벗긴다. */
    private static List<Token> stripParens(List<Token> tokens) {
        List<Token> cur = tokens;
        while (cur.size() >= 2 && cur.get(0).isSymbol("(") && cur.get(cur.size() - 1).isSymbol(")")) {
            int parens = 0;
            boolean wraps = true;
            for (int i = 0; i < cur.size() - 1; i++) {
                if (cur.get(i).isSymbol("(")) {
                    parens++;
                } else if (cur.get(i).isSymbol(")")) {
                    parens--;
                }
                if (parens == 0) {
                    wraps = false;
                    break;
                }
            }
            if (!wraps) {
                break;
            }
            cur = cur.subList(1, cur.size() - 1);
        }
        return cur;
    }

    /** 문자열 리터럴 토큰의 값({@code ''} → {@code '}, {@code q'[..]'}, {@code N'..'} 접두사 처리). */
    static String unquote(String literal) {
        String s = literal;
        int quote = s.indexOf('\'');
        if (quote < 0) {
            return s;
        }
        String prefix = s.substring(0, quote).toUpperCase(Locale.ROOT);
        String body = s.substring(quote + 1);
        if (prefix.endsWith("Q") && body.length() >= 1) {
            // q'[..]'
            String inner = body.substring(1);
            if (inner.endsWith("'")) {
                inner = inner.substring(0, inner.length() - 1);
            }
            if (!inner.isEmpty()) {
                inner = inner.substring(0, inner.length() - 1);
            }
            return inner;
        }
        if (body.endsWith("'")) {
            body = body.substring(0, body.length() - 1);
        }
        return body.replace("''", "'");
    }
}
