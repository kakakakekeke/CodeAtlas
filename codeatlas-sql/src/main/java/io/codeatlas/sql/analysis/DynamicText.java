package io.codeatlas.sql.analysis;

import io.codeatlas.sql.lexer.Token;
import java.util.ArrayList;
import java.util.List;

/**
 * 내부 표식 {@code __DYNAMIC__}을 사용자에게 보이지 않게 한다(F-006-R26).
 *
 * <p>mybatis는 {@code ${…}} 치환 자리에 식별자 {@code __DYNAMIC__}을 넣은 본문을 내장 소스로 넘기고, 그 자리에 있던 원문을
 * {@code attrs.dynamicParts}(치환 목록)에 싣는다(F-009-R21). 형식: 본문의 n번째 {@code __DYNAMIC__} 자리의 원문을 등장 순서대로,
 * 건마다 {@code <원문 길이>:<원문>}으로 구분자 없이 이어 붙인 문자열(예: {@code 13:${sqlMap.dsf}10:${orderBy}}).
 *
 * <ul>
 *   <li>{@link #restore}: 렉싱하기 전에 본문의 n번째 {@code __DYNAMIC__}을 원문으로 되돌린다. 렉서는 {@code ${…}}를 {@code __DYNAMIC__}
 *       식별자와 같게 읽고 토큰의 원문만 {@code ${…}}로 두므로, 진단 메시지와 미해결 원문에 원문이 그대로 나온다.</li>
 *   <li>{@link #display}: 그러고도 남은 표식(치환 목록이 없는 내장 소스, 되돌리지 못한 자리)을 글로 내보낼 때 <code>${…}</code>로 바꾼다.</li>
 * </ul>
 */
public final class DynamicText {

    /** 내장 소스 attrs의 키: 치환 목록. */
    public static final String PARTS_KEY = "dynamicParts";

    /** 원문을 모르는 치환 자리를 글로 적을 때의 표기. */
    public static final String UNKNOWN = "${…}";

    private DynamicText() {
    }

    /** 글(진단 메시지, 미해결 원문, 고정 값)에 남은 내부 표식을 <code>${…}</code>로 바꾼다. */
    public static String display(String text) {
        return text == null || !text.contains(Token.DYNAMIC) ? text : text.replace(Token.DYNAMIC, UNKNOWN);
    }

    /** 치환 목록 문자열을 원문 목록으로 푼다. 없거나 형식이 맞지 않으면 빈 목록. */
    static List<String> decode(String encoded) {
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

    /**
     * 내장 소스 본문의 n번째 {@code __DYNAMIC__}을 치환 목록의 n번째 원문으로 되돌린다. 목록이 없거나 건수가 본문과 맞지 않으면 본문 그대로다.
     *
     * @param exact 본문이 호스트 파일의 위치를 보존한 것인가. 그러면 길이를 유지한다: mybatis가 채운 공백(표식의 뒤쪽, 뒤에 글자가 붙어
     *              있으면 앞쪽)까지 원문으로 바꾸고, 유지할 수 없는 자리는 표식 그대로 둔다
     */
    public static String restore(String text, String encodedParts, boolean exact) {
        if (text == null || encodedParts == null || text.indexOf(Token.DYNAMIC) < 0) {
            return text;
        }
        List<String> parts = decode(encodedParts);
        int count = 0;
        for (int at = text.indexOf(Token.DYNAMIC); at >= 0; at = text.indexOf(Token.DYNAMIC, at + Token.DYNAMIC.length())) {
            count++;
        }
        if (parts.isEmpty() || count != parts.size()) {
            return text;
        }
        int marker = Token.DYNAMIC.length();
        StringBuilder out = new StringBuilder(text.length() + 16);
        int from = 0;
        for (String original : parts) {
            int at = text.indexOf(Token.DYNAMIC, from);
            out.append(text, from, at);
            int end = at + marker;
            int pad = original.length() - marker;
            if (!original.startsWith("${") || original.indexOf('\n') >= 0 || (at > 0 && text.charAt(at - 1) == ':')) {
                out.append(Token.DYNAMIC); // 렉서가 다르게 읽을 자리(:name 바인드와 섞임 등)는 그대로 둔다
            } else if (!exact || pad == 0) {
                out.append(original);
            } else if (pad > 0 && spaces(text, end, end + pad)) {
                out.append(original);
                end += pad;
            } else if (pad > 0 && spaces(out, out.length() - pad, out.length())) {
                out.setLength(out.length() - pad);
                out.append(original);
            } else {
                out.append(Token.DYNAMIC);
            }
            from = end;
        }
        return out.append(text, from, text.length()).toString();
    }

    private static boolean spaces(CharSequence s, int from, int to) {
        if (from < 0 || to > s.length()) {
            return false;
        }
        for (int i = from; i < to; i++) {
            if (s.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }
}
