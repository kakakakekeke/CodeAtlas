package io.codeatlas.mybatis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <if test>}의 조건식에서 "식 == 리터럴" 꼴만 읽어 내는 작은 파서. OGNL 전체를 해석하지 않는다.
 *
 * <p>읽는 꼴:
 * <ul>
 *   <li>{@code v == 'A'}, {@code v == "A"}, {@code v == 1}, {@code v == true}, {@code v eq 'A'}, 좌우가 바뀐 것({@code 'A' == v})</li>
 *   <li>{@code v.equals('A')}, {@code v.equalsIgnoreCase('A')}, {@code 'A'.equals(v)}, {@code v == 'A'.toString()}</li>
 *   <li>식은 변수나 프로퍼티 경로({@code cond.type}, {@code vo.getType()}, {@code list.get(0).dbName}, {@code arr[0].type})</li>
 *   <li>{@code and}/{@code &&}로 덧붙은 다른 조건({@code v != null and v == 'A'})은 무시한다. 본문이 들어가려면 등치가 참이어야 한다는 점은 같다</li>
 *   <li>같은 식을 여러 리터럴과 {@code or}/{@code ||}로 비교한 것({@code v == 'A' or v == 'B'})은 값 집합으로 읽는다</li>
 * </ul>
 * 그 밖의 식(null 검사, 부등호, {@code !=}, 변수끼리 비교, 서로 다른 식을 {@code or}로 묶은 것)은 읽지 않는다(빈 목록).
 */
final class IfTest {

    /**
     * 식 하나를 리터럴(들)과 등치 비교하는 조건.
     *
     * @param expr 비교하는 식(공백 제거)
     * @param values 리터럴 값들(따옴표를 뗀 것). 식의 값이 이 중 하나면 참
     */
    record Equality(String expr, Set<String> values) {
    }

    /** 이름 하나. 인자가 없거나 단순한(숫자, 이름) 메서드 호출과 첨자를 허용한다. */
    private static final String NAME = "[A-Za-z_$][\\w$]*(?:\\(\\s*[\\w$.]*\\s*\\))?(?:\\[\\s*\\w+\\s*\\])?";
    private static final String EXPR = NAME + "(?:\\s*\\.\\s*" + NAME + ")*";
    private static final String STRING = "'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"";
    private static final String QUOTED = "(?:" + STRING + ")(?:\\s*\\.\\s*toString\\(\\s*\\))?";
    private static final String LITERAL = QUOTED + "|[-+]?\\d+(?:\\.\\d+)?[lLdDfF]?|true|false";
    private static final String OPERATOR = "(?:\\s*==\\s*|\\s+eq\\s+)";

    private static final Pattern EXPR_OP_LITERAL = Pattern.compile("(" + EXPR + ")" + OPERATOR + "(" + LITERAL + ")");
    private static final Pattern LITERAL_OP_EXPR = Pattern.compile("(" + LITERAL + ")" + OPERATOR + "(" + EXPR + ")");
    private static final String EQUALS = "\\s*\\.\\s*equals(?:IgnoreCase)?\\s*\\(\\s*";
    private static final Pattern EXPR_EQUALS_LITERAL = Pattern.compile("(" + EXPR + ")" + EQUALS + "(" + LITERAL + ")\\s*\\)");
    private static final Pattern LITERAL_EQUALS_EXPR = Pattern.compile("(" + QUOTED + ")" + EQUALS + "(" + EXPR + ")\\s*\\)");
    private static final Set<String> KEYWORDS = Set.of("null", "true", "false", "and", "or", "not", "eq", "neq", "in");

    private IfTest() {
    }

    /** 조건식에 AND로 걸린 등치 조건들(식마다 하나). 읽을 수 있는 꼴이 아니면 빈 목록. */
    static List<Equality> parse(String test) {
        if (test == null || test.isBlank()) {
            return List.of();
        }
        Map<String, Equality> byExpr = new LinkedHashMap<>();
        return collect(test, byExpr, 0) ? List.copyOf(byExpr.values()) : List.of();
    }

    /** @return 읽다가 다룰 수 없는 모양(괄호·따옴표가 안 맞음, 같은 식을 두 번 비교)을 만나면 false */
    private static boolean collect(String text, Map<String, Equality> byExpr, int depth) {
        String s = stripParens(text);
        if (s == null || depth > 8) {
            return false;
        }
        List<String> ors = splitTop(s, "or", "||");
        if (ors == null) {
            return false;
        }
        if (ors.size() > 1) {
            Equality any = anyOf(ors, depth);
            // 서로 다른 것을 or로 묶은 조건은 값으로 가를 수 없다. 최상위면 전체를 포기하고, AND의 한 항이면 그 항만 무시한다.
            return any == null ? depth > 0 : add(any, byExpr);
        }
        List<String> ands = splitTop(s, "and", "&&");
        if (ands == null) {
            return false;
        }
        if (ands.size() > 1) {
            for (String part : ands) {
                if (!collect(part, byExpr, depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        Equality atom = atom(s);
        return atom == null || add(atom, byExpr);
    }

    private static boolean add(Equality equality, Map<String, Equality> byExpr) {
        return byExpr.putIfAbsent(equality.expr(), equality) == null;
    }

    /** {@code or}로 묶인 항들이 모두 같은 식의 등치면 값을 합친 조건 하나. 아니면 null. */
    private static Equality anyOf(List<String> parts, int depth) {
        String expr = null;
        Set<String> values = new LinkedHashSet<>();
        for (String part : parts) {
            Map<String, Equality> one = new LinkedHashMap<>();
            if (!collect(part, one, depth + 1) || one.size() != 1) {
                return null;
            }
            Equality equality = one.values().iterator().next();
            if (expr != null && !expr.equals(equality.expr())) {
                return null;
            }
            expr = equality.expr();
            values.addAll(equality.values());
        }
        return expr == null ? null : new Equality(expr, values);
    }

    private static Equality atom(String s) {
        Matcher m = EXPR_OP_LITERAL.matcher(s);
        if (m.matches()) {
            return equality(m.group(1), m.group(2));
        }
        m = LITERAL_OP_EXPR.matcher(s);
        if (m.matches()) {
            return equality(m.group(2), m.group(1));
        }
        m = EXPR_EQUALS_LITERAL.matcher(s);
        if (m.matches()) {
            return equality(m.group(1), m.group(2));
        }
        m = LITERAL_EQUALS_EXPR.matcher(s);
        if (m.matches()) {
            return equality(m.group(2), m.group(1));
        }
        return null;
    }

    private static Equality equality(String expr, String literal) {
        String name = expr.replaceAll("\\s+", "");
        if (KEYWORDS.contains(name)) {
            return null;
        }
        return new Equality(name, Set.of(literalValue(literal)));
    }

    /** 따옴표와 {@code .toString()}, 숫자 접미사를 뗀 값. {@code '1'}과 {@code 1}은 같은 값으로 본다(OGNL도 그렇게 비교한다). */
    private static String literalValue(String literal) {
        String s = literal.trim();
        char first = s.charAt(0);
        if (first == '\'' || first == '"') {
            return s.substring(1, s.lastIndexOf(first));
        }
        if (s.equals("true") || s.equals("false")) {
            return s;
        }
        return Character.isLetter(s.charAt(s.length() - 1)) ? s.substring(0, s.length() - 1) : s;
    }

    /** 양끝 공백과, 식 전체를 감싼 괄호를 벗긴다. 괄호나 따옴표가 맞지 않으면 null. */
    private static String stripParens(String text) {
        String s = text.trim();
        while (s.length() >= 2 && s.charAt(0) == '(') {
            int close = matchingParen(s);
            if (close < 0) {
                return null;
            }
            if (close != s.length() - 1) {
                break;
            }
            s = s.substring(1, close).trim();
        }
        return s;
    }

    /** 맨 앞 여는 괄호와 짝이 되는 닫는 괄호의 위치. 없으면 -1. */
    private static int matchingParen(String s) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** 괄호와 따옴표 밖의 연산자(단어 또는 기호)에서 나눈다. 괄호나 따옴표가 맞지 않으면 null. */
    private static List<String> splitTop(String s, String word, String symbol) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0) {
                int length = s.startsWith(symbol, i) ? symbol.length() : wordAt(s, i, word) ? word.length() : 0;
                if (length > 0) {
                    parts.add(s.substring(start, i));
                    start = i + length;
                    i = start - 1;
                }
            }
        }
        if (depth != 0 || quote != 0) {
            return null;
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static boolean wordAt(String s, int at, String word) {
        if (!s.startsWith(word, at)) {
            return false;
        }
        int end = at + word.length();
        return (at == 0 || !partOfName(s.charAt(at - 1))) && (end == s.length() || !partOfName(s.charAt(end)));
    }

    private static boolean partOfName(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
    }
}
