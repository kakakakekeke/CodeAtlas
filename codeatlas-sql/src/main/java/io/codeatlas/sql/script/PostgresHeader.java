package io.codeatlas.sql.script;

import io.codeatlas.core.model.DbName;
import io.codeatlas.sql.ast.QName;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import io.codeatlas.sql.parser.TokenCursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PostgreSQL의 루틴 헤더(F-019-R24·R29): {@code CREATE [OR REPLACE] FUNCTION|PROCEDURE 이름(매개변수, ..) [RETURNS ..] 속성..}과
 * {@code CREATE [OR REPLACE] [CONSTRAINT] TRIGGER 이름 .. ON 테이블 .. EXECUTE FUNCTION|PROCEDURE 함수(..)}.
 * PostgreSQL 읽기 방식의 단위에서만 쓴다(ADR-0012). 본문은 해석하지 않고 어디에 있는지만 알린다.
 */
final class PostgresHeader {

    private PostgresHeader() {
    }

    /** 헤더의 속성이 시작하는 낱말: {@code RETURNS} 타입과 {@code SET} 값이 여기서 끝난다. */
    private static final Set<String> ATTRIBUTES = Set.of(
            "LANGUAGE", "AS", "RETURNS", "RETURN", "BEGIN", "IMMUTABLE", "STABLE", "VOLATILE", "LEAKPROOF", "NOT", "CALLED", "STRICT",
            "SECURITY", "EXTERNAL", "PARALLEL", "COST", "ROWS", "SUPPORT", "SET", "WINDOW", "TRANSFORM");

    private static final Set<String> MODES = Set.of("IN", "OUT", "INOUT", "VARIADIC");

    private static final Set<String> INTERVAL_UNITS = Set.of("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND");

    /** @return PostgreSQL의 프로시저·함수·트리거 헤더. 그런 단위가 아니면 null(기존 헤더 파서에 맡긴다) */
    static RoutineHeader parse(List<Token> tokens) {
        int n = tokens.size();
        int i = 0;
        if (n == 0 || !tokens.get(0).is("CREATE")) {
            return null;
        }
        i++;
        if (i + 1 < n && tokens.get(i).is("OR") && tokens.get(i + 1).is("REPLACE")) {
            i += 2;
        }
        if (i < n && tokens.get(i).is("CONSTRAINT")) {
            i++;
        }
        if (i + 1 >= n || !tokens.get(i).isAny("PROCEDURE", "FUNCTION", "TRIGGER") || !tokens.get(i + 1).isIdentifier()) {
            return null;
        }
        RoutineHeader.Kind kind = tokens.get(i).is("PROCEDURE") ? RoutineHeader.Kind.PROCEDURE
                : tokens.get(i).is("FUNCTION") ? RoutineHeader.Kind.FUNCTION : RoutineHeader.Kind.TRIGGER;
        int[] cursor = {i + 1};
        QName name = RoutineHeader.qualifiedName(tokens, cursor);
        i = cursor[0];
        return kind == RoutineHeader.Kind.TRIGGER ? trigger(tokens, name, i) : routine(tokens, kind, name, i);
    }

    // ------------------------------------------------------------------ 함수·프로시저

    private static RoutineHeader routine(List<Token> tokens, RoutineHeader.Kind kind, QName name, int from) {
        int n = tokens.size();
        int i = from;
        List<String> parameters = new ArrayList<>();
        List<String> variables = new ArrayList<>();
        if (i < n && tokens.get(i).isSymbol("(")) {
            int close = Math.min(RoutineHeader.matching(tokens, i), n);
            int start = i + 1;
            int depth = 0;
            for (int k = i + 1; k <= close; k++) {
                Token t = k < close ? tokens.get(k) : null;
                if (t != null && (t.isSymbol("(") || t.isSymbol("["))) {
                    depth++;
                } else if (t != null && (t.isSymbol(")") || t.isSymbol("]"))) {
                    depth--;
                }
                if (t == null || (depth == 0 && t.isSymbol(","))) {
                    parameter(tokens, start, k, parameters, variables);
                    start = k + 1;
                }
            }
            i = Math.min(n, close + 1);
        }
        String language = null;
        String returnType = null;
        int bodyToken = -1;
        int bodyStart = -1;
        while (i < n && !tokens.get(i).isSymbol(";")) {
            Token t = tokens.get(i);
            if (t.is("RETURNS") && returnType == null) {
                if (i + 1 < n && tokens.get(i + 1).is("NULL")) {
                    i += 2; // RETURNS NULL ON NULL INPUT
                    continue;
                }
                if (i + 2 < n && tokens.get(i + 1).is("TABLE") && tokens.get(i + 2).isSymbol("(")) {
                    int close = RoutineHeader.matching(tokens, i + 2);
                    List<String> columns = new ArrayList<>();
                    tableColumns(tokens, i + 3, Math.min(close, n), columns, variables);
                    returnType = "TABLE(" + String.join(", ", columns) + ")";
                    i = Math.min(n, close + 1);
                    continue;
                }
                int end = i + 1;
                int depth = 0;
                while (end < n && !tokens.get(end).isSymbol(";")) {
                    Token x = tokens.get(end);
                    if (x.isSymbol("(")) {
                        depth++;
                    } else if (x.isSymbol(")")) {
                        depth--;
                    } else if (depth == 0 && end > i + 1 && (x.type() == TokenType.STRING || (x.type() == TokenType.IDENT && ATTRIBUTES.contains(x.upper())))) {
                        break;
                    }
                    end++;
                }
                returnType = end > i + 1 ? join(tokens, i + 1, end) : null;
                i = end;
                continue;
            }
            if (t.is("LANGUAGE") && i + 1 < n) {
                Token x = tokens.get(i + 1);
                if (x.type() == TokenType.IDENT) {
                    language = x.upper();
                } else if (x.type() == TokenType.STRING) {
                    language = stripQuotes(x.text()).toUpperCase(Locale.ROOT); // 옛 표기 LANGUAGE 'plpgsql'
                }
                i += 2;
                continue;
            }
            if (t.is("AS") && i + 1 < n && tokens.get(i + 1).type() == TokenType.STRING && bodyToken < 0) {
                bodyToken = i + 1;
                i += 2;
                // C 함수의 AS '객체 파일', '심볼'
                while (i + 1 < n && tokens.get(i).isSymbol(",") && tokens.get(i + 1).type() == TokenType.STRING) {
                    i += 2;
                }
                continue;
            }
            if (t.is("SET") && i + 1 < n) {
                // SET 설정 { TO 값 | = 값 | FROM CURRENT }: 값의 낱말이 속성으로 읽히지 않게 넘긴다
                i += 2;
                if (i < n && (tokens.get(i).isSymbol("=") || tokens.get(i).is("TO"))) {
                    i++;
                    while (i < n && !tokens.get(i).isSymbol(";")) {
                        i++;
                        if (i < n && tokens.get(i).isSymbol(",")) {
                            i++;
                        } else {
                            break;
                        }
                    }
                } else if (i + 1 < n && tokens.get(i).is("FROM") && tokens.get(i + 1).is("CURRENT")) {
                    i += 2;
                }
                continue;
            }
            if ((t.is("RETURN") || (t.is("BEGIN") && i + 1 < n && tokens.get(i + 1).is("ATOMIC"))) && bodyToken < 0) {
                bodyStart = i; // SQL 표준 본문: 단위의 끝까지다
                break;
            }
            if (t.isSymbol("(")) {
                i = Math.min(n, RoutineHeader.matching(tokens, i) + 1);
                continue;
            }
            i++;
        }
        if (bodyToken >= 0) {
            bodyStart = bodyToken;
        } else if (bodyStart >= 0 && language == null) {
            language = "SQL"; // SQL 표준 본문은 LANGUAGE를 적지 않아도 sql이다
        }
        return new RoutineHeader(kind, name, parameters, null, null, List.of(), bodyStart,
                new RoutineHeader.Postgres(language, bodyToken, returnType, variables, null));
    }

    /**
     * 매개변수 하나: {@code [IN|OUT|INOUT|VARIADIC] [이름] 타입 [DEFAULT 식 | = 식]}(이름 뒤에 모드가 오는 꼴도 받는다).
     * 표기는 기존 규칙대로 {@code 이름 [모드] 타입 [기본값]}이고, 이름이 없으면 타입부터다.
     */
    private static void parameter(List<Token> tokens, int from, int to, List<String> parameters, List<String> variables) {
        if (to <= from) {
            return;
        }
        int i = from;
        String mode = null;
        if (i + 1 < to && tokens.get(i).type() == TokenType.IDENT && MODES.contains(tokens.get(i).upper()) && tokens.get(i + 1).isIdentifier()) {
            mode = tokens.get(i).upper();
            i++;
            if (mode.equals("IN") && i + 1 < to && tokens.get(i).is("OUT")) {
                mode = "INOUT";
                i++;
            }
        }
        int typeEnd = to;
        int depth = 0;
        for (int k = i; k < to; k++) {
            Token t = tokens.get(k);
            if (t.isSymbol("(") || t.isSymbol("[")) {
                depth++;
            } else if (t.isSymbol(")") || t.isSymbol("]")) {
                depth--;
            } else if (depth == 0 && k > i && (t.is("DEFAULT") || t.isSymbol("="))) {
                typeEnd = k;
                break;
            }
        }
        String name = null;
        if (typeEnd - i >= 2 && tokens.get(i).isIdentifier() && tokens.get(i + 1).isIdentifier() && !startsMultiWordType(tokens, i)) {
            name = DbName.normalize(tokens.get(i).text());
            i++;
            if (mode == null && typeEnd - i >= 2 && tokens.get(i).type() == TokenType.IDENT && MODES.contains(tokens.get(i).upper())) {
                mode = tokens.get(i).upper(); // 이름 모드 타입
                i++;
                if (mode.equals("IN") && typeEnd - i >= 2 && tokens.get(i).is("OUT")) {
                    mode = "INOUT";
                    i++;
                }
            }
        }
        StringBuilder text = new StringBuilder();
        if (name != null) {
            text.append(name);
            variables.add(name);
        }
        if (mode != null) {
            text.append(text.length() == 0 ? "" : " ").append(mode);
        }
        if (typeEnd > i) {
            text.append(text.length() == 0 ? "" : " ").append(join(tokens, i, typeEnd));
        }
        if (to > typeEnd) {
            text.append(text.length() == 0 ? "" : " ").append(join(tokens, typeEnd, to));
        }
        if (text.length() > 0) {
            parameters.add(text.toString());
        }
    }

    /** 이 자리의 낱말이 여러 낱말 타입의 시작인가({@code double precision}, {@code character varying}, {@code timestamp with time zone} ..). 그러면 이름이 아니다. */
    private static boolean startsMultiWordType(List<Token> tokens, int i) {
        Token a = tokens.get(i);
        Token b = tokens.get(i + 1);
        if (a.is("DOUBLE")) {
            return b.is("PRECISION");
        }
        if (a.isAny("CHARACTER", "CHAR", "NCHAR", "BIT")) {
            return b.is("VARYING");
        }
        if (a.is("NATIONAL")) {
            return b.isAny("CHARACTER", "CHAR");
        }
        if (a.isAny("TIMESTAMP", "TIME")) {
            return b.isAny("WITH", "WITHOUT");
        }
        return a.is("INTERVAL") && b.type() == TokenType.IDENT && INTERVAL_UNITS.contains(b.upper());
    }

    /** {@code RETURNS TABLE (이름 타입, ..)}의 컬럼: 표기와 이름(본문에서 변수다). */
    private static void tableColumns(List<Token> tokens, int from, int to, List<String> columns, List<String> variables) {
        int start = from;
        int depth = 0;
        for (int k = from; k <= to; k++) {
            boolean end = k >= to;
            Token t = end ? null : tokens.get(k);
            if (!end && (t.isSymbol("(") || t.isSymbol("["))) {
                depth++;
            } else if (!end && (t.isSymbol(")") || t.isSymbol("]"))) {
                depth--;
            }
            if (end || (depth == 0 && t.isSymbol(","))) {
                if (k > start) {
                    columns.add(join(tokens, start, k));
                    if (k - start >= 2 && tokens.get(start).isIdentifier()) {
                        variables.add(DbName.normalize(tokens.get(start).text()));
                    }
                }
                start = k + 1;
            }
        }
    }

    // ------------------------------------------------------------------ 트리거

    private static RoutineHeader trigger(List<Token> tokens, QName name, int from) {
        int n = tokens.size();
        int on = -1;
        int parens = 0;
        for (int k = from; k < n; k++) {
            Token t = tokens.get(k);
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0 && t.is("ON") && k + 1 < n && tokens.get(k + 1).isIdentifier()) {
                on = k;
                break;
            } else if (parens == 0 && t.is("EXECUTE")) {
                break;
            }
        }
        QName table = null;
        String event = null;
        int i = from;
        if (on >= 0) {
            event = on > from ? TokenCursor.joinTokens(tokens.subList(from, on)) : null;
            int[] cursor = {on + 1};
            table = RoutineHeader.qualifiedName(tokens, cursor);
            i = cursor[0];
        }
        QName function = null;
        parens = 0;
        for (int k = i; k + 2 < n; k++) {
            Token t = tokens.get(k);
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0 && t.is("EXECUTE") && tokens.get(k + 1).isAny("FUNCTION", "PROCEDURE") && tokens.get(k + 2).isIdentifier()) {
                int[] cursor = {k + 2};
                function = RoutineHeader.qualifiedName(tokens, cursor);
                break;
            }
        }
        return new RoutineHeader(RoutineHeader.Kind.TRIGGER, name, List.of(), table, event, List.of(), -1,
                new RoutineHeader.Postgres(null, -1, null, List.of(), function));
    }

    // ------------------------------------------------------------------ 도구

    private static String join(List<Token> tokens, int from, int to) {
        return TokenCursor.joinTokens(tokens.subList(from, Math.min(to, tokens.size()))).replace(" %", "%");
    }

    private static String stripQuotes(String literal) {
        int a = literal.indexOf('\'');
        int b = literal.lastIndexOf('\'');
        return a >= 0 && b > a ? literal.substring(a + 1, b) : literal;
    }
}
