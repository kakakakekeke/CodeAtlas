package io.codeatlas.mybatis;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis 매개변수 표기를 SQL 파서가 읽을 수 있는 토큰으로 바꾼다.
 * {@code #{...}}(옵션 포함)는 {@code ?}로, {@code ${...}}는 식별자 {@code __DYNAMIC__}으로.
 */
final class SqlParams {

    /** 줄바꿈을 낀 공백 묶음. */
    private static final java.util.regex.Pattern LINE_BREAK_SPACE = java.util.regex.Pattern.compile("\\s*\\R\\s*");

    /** {@code ${}} 자리에 넣는 식별자(그래프 계약 4.1). */
    static final String DYNAMIC = "__DYNAMIC__";

    private SqlParams() {
    }

    /** 내장 소스 attrs의 키: 본문의 {@code __DYNAMIC__} 자리에 있던 {@code ${…}} 원문 목록(F-009-R21, 그래프 계약 3.9). */
    static final String DYNAMIC_PARTS = "dynamicParts";

    /**
     * @param text 치환한 본문
     * @param dollars 본문에 있던 {@code ${...}}의 안쪽 식(나온 순서, 중복 포함)
     * @param originals 본문의 n번째 {@code __DYNAMIC__} 자리에 있던 {@code ${…}} 원문({@code ${}부터 {@code }}까지. 나온 순서, 중복 포함).
     *                  안에 줄바꿈이 있으면 줄바꿈을 낀 공백 묶음을 공백 한 칸으로 바꾼 것이다
     */
    record Result(String text, List<String> dollars, List<String> originals) {
    }

    /**
     * 치환 목록의 표기: 원문마다 {@code <원문 길이>:<원문>}을 구분자 없이 이어 붙인다(길이는 UTF-16 글자 수).
     * 예: {@code 13:${sqlMap.dsf}10:${orderBy}}. sql 분석기가 진단 메시지와 미해결 원문에서 n번째 {@code __DYNAMIC__}을 원문으로 되돌리는 데 쓴다.
     */
    static String encodeParts(List<String> originals) {
        StringBuilder out = new StringBuilder();
        for (String original : originals) {
            out.append(original.length()).append(':').append(original);
        }
        return out.toString();
    }

    /** {@link #encodeParts}로 만든 문자열을 원문 목록으로 되돌린다. 형식이 맞지 않으면 빈 목록. */
    static List<String> decodeParts(String encoded) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (encoded != null && i < encoded.length()) {
            int colon = encoded.indexOf(':', i);
            if (colon <= i) {
                return List.of();
            }
            int length;
            try {
                length = Integer.parseInt(encoded.substring(i, colon));
            } catch (NumberFormatException e) {
                return List.of();
            }
            if (length < 0 || colon + 1 + length > encoded.length()) {
                return List.of();
            }
            out.add(encoded.substring(colon + 1, colon + 1 + length));
            i = colon + 1 + length;
        }
        return out;
    }

    /** 본문의 n번째 {@code __DYNAMIC__}을 n번째 원문으로 되돌린다(노드의 {@code sql} 속성처럼 사람이 볼 글에 쓴다). 수가 맞지 않으면 그대로 둔다. */
    static String restore(String text, List<String> originals) {
        if (originals.isEmpty() || text.indexOf(DYNAMIC) < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int from = 0;
        int n = 0;
        while (true) {
            int at = text.indexOf(DYNAMIC, from);
            if (at < 0) {
                break;
            }
            if (n >= originals.size()) {
                return text;
            }
            out.append(text, from, at).append(originals.get(n++));
            from = at + DYNAMIC.length();
        }
        return n == originals.size() ? out.append(text, from, text.length()).toString() : text;
    }

    /** 길이를 신경 쓰지 않는 치환. */
    static Result plain(String sql) {
        return substitute(sql, false);
    }

    /**
     * 길이와 줄 구조를 보존하는 치환. {@code #{id}} → {@code "    ?"}처럼 남는 자리를 공백으로 채운다.
     * {@code ${...}}가 {@code __DYNAMIC__}보다 짧거나 양옆에 식별자가 붙어 있어 채울 수 없으면 null을 돌려준다.
     */
    static Result padded(String sql) {
        return substitute(sql, true);
    }

    private static Result substitute(String sql, boolean keepLength) {
        List<String> dollars = new ArrayList<>();
        List<String> originals = new ArrayList<>();
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            boolean hash = c == '#';
            if ((hash || c == '$') && i + 1 < n && sql.charAt(i + 1) == '{') {
                int close = sql.indexOf('}', i + 2);
                if (close < 0) {
                    out.append(sql, i, n);
                    break;
                }
                if (hash) {
                    if (keepLength) {
                        blank(sql, i, close, out);
                    }
                    out.append('?');
                } else {
                    dollars.add(sql.substring(i + 2, close).trim());
                    originals.add(LINE_BREAK_SPACE.matcher(sql.substring(i, close + 1)).replaceAll(" "));
                    if (!keepLength) {
                        out.append(DYNAMIC);
                    } else if (!appendPaddedDynamic(sql, i, close, out)) {
                        return null;
                    }
                }
                i = close + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return new Result(out.toString(), dollars, originals);
    }

    private static boolean appendPaddedDynamic(String sql, int start, int close, StringBuilder out) {
        int length = close + 1 - start;
        int pad = length - DYNAMIC.length();
        if (pad < 0 || sql.indexOf('\n', start) >= 0 && sql.indexOf('\n', start) < close
                || sql.indexOf('\r', start) >= 0 && sql.indexOf('\r', start) < close) {
            return false;
        }
        boolean left = start > 0 && sticky(sql.charAt(start - 1));
        boolean right = close + 1 < sql.length() && sticky(sql.charAt(close + 1));
        if (pad > 0 && left && right) {
            return false;
        }
        if (right) {
            // 뒤에 붙은 글자와 떨어지지 않게 공백을 앞에 둔다: ${schemaName}.TB → "  __DYNAMIC__.TB"
            out.append(" ".repeat(pad)).append(DYNAMIC);
        } else {
            out.append(DYNAMIC).append(" ".repeat(pad));
        }
        return true;
    }

    /** 옆에 공백이 끼면 뜻이 달라지는 글자(식별자의 일부, 한정자 점, 따옴표). */
    private static boolean sticky(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '.' || c == '\'' || c == '"' || c == '`';
    }

    private static void blank(String sql, int from, int to, StringBuilder out) {
        for (int i = from; i < to; i++) {
            char c = sql.charAt(i);
            out.append(c == '\n' || c == '\r' ? c : ' ');
        }
    }
}
