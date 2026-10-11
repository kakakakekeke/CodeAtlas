package io.codeatlas.mybatis;

import java.util.List;

/**
 * 동적 태그가 없는 구문 본문을 원본 파일의 글자 위치를 그대로 유지한 SQL 텍스트로 바꾼다.
 *
 * <p>원본 구간을 그대로 복사하되 SQL이 아닌 부분을 같은 길이의 공백으로 바꾼다(줄바꿈은 유지).
 * <ul>
 *   <li>XML 주석, 처리 명령, 지워야 할 자식 요소({@code <selectKey>}, {@code <bind>}) → 공백</li>
 *   <li>{@code <![CDATA[}와 {@code ]]>} → 공백, 그 사이는 그대로</li>
 *   <li>{@code &lt;} 같은 엔티티 → 앞을 공백으로 채우고 끝에 푼 글자({@code &lt;=} → {@code "   <="})</li>
 * </ul>
 * 길이를 보존할 수 없는 내용(DTD에 정의한 엔티티, 따옴표로 풀리는 엔티티, 예상하지 못한 태그)을 만나면 null을 돌려준다.
 */
final class StaticBody {

    private StaticBody() {
    }

    /**
     * @param raw 원본 파일 전체
     * @param from 본문 시작 오프셋
     * @param to 본문 끝 오프셋(제외)
     * @param blanks 공백으로 지울 구간 {시작, 끝(제외)} 목록. 오프셋 순서
     * @return 길이가 {@code to - from}인 텍스트. 보존할 수 없으면 null
     */
    static String preserve(String raw, int from, int to, List<int[]> blanks) {
        StringBuilder out = new StringBuilder(to - from);
        int blankIndex = 0;
        int i = from;
        while (i < to) {
            while (blankIndex < blanks.size() && blanks.get(blankIndex)[1] <= i) {
                blankIndex++;
            }
            if (blankIndex < blanks.size() && blanks.get(blankIndex)[0] == i) {
                int end = Math.min(to, blanks.get(blankIndex)[1]);
                blank(raw, i, end, out);
                i = end;
                continue;
            }
            char c = raw.charAt(i);
            if (c == '<') {
                int end;
                if (raw.startsWith("<!--", i)) {
                    end = endOf(raw, "-->", i + 4, to);
                } else if (raw.startsWith("<![CDATA[", i)) {
                    int close = raw.indexOf("]]>", i + 9);
                    if (close < 0 || close + 3 > to) {
                        return null;
                    }
                    spaces(9, out);
                    out.append(raw, i + 9, close);
                    spaces(3, out);
                    i = close + 3;
                    continue;
                } else if (raw.startsWith("<?", i)) {
                    end = endOf(raw, "?>", i + 2, to);
                } else {
                    return null;
                }
                if (end < 0) {
                    return null;
                }
                blank(raw, i, end, out);
                i = end;
            } else if (c == '&') {
                // 이어진 엔티티는 한 덩어리로: &lt;&gt; → "      <>"
                int j = i;
                StringBuilder decoded = new StringBuilder();
                while (j < to && raw.charAt(j) == '&') {
                    int semi = raw.indexOf(';', j);
                    if (semi < 0 || semi >= to || semi - j > 12) {
                        return null;
                    }
                    String value = decodeEntity(raw.substring(j + 1, semi));
                    if (value == null || value.equals("'") || value.equals("\"")) {
                        return null;
                    }
                    decoded.append(value);
                    j = semi + 1;
                }
                int pad = (j - i) - decoded.length();
                if (pad < 0) {
                    return null;
                }
                spaces(pad, out);
                out.append(decoded);
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int endOf(String raw, String terminator, int from, int limit) {
        int at = raw.indexOf(terminator, from);
        return at < 0 || at + terminator.length() > limit ? -1 : at + terminator.length();
    }

    private static void blank(String raw, int from, int to, StringBuilder out) {
        for (int i = from; i < to; i++) {
            char c = raw.charAt(i);
            out.append(c == '\n' || c == '\r' ? c : ' ');
        }
    }

    private static void spaces(int count, StringBuilder out) {
        for (int i = 0; i < count; i++) {
            out.append(' ');
        }
    }

    /** XML 기본 엔티티와 문자 참조만 푼다. 그 밖의 이름은 null. */
    private static String decodeEntity(String name) {
        switch (name) {
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "amp":
                return "&";
            case "apos":
                return "'";
            case "quot":
                return "\"";
            default:
                break;
        }
        if (name.length() < 2 || name.charAt(0) != '#') {
            return null;
        }
        try {
            int codePoint = name.charAt(1) == 'x' || name.charAt(1) == 'X'
                    ? Integer.parseInt(name.substring(2), 16)
                    : Integer.parseInt(name.substring(1));
            if (!Character.isValidCodePoint(codePoint) || codePoint == '\n' || codePoint == '\r') {
                return null;
            }
            return new String(Character.toChars(codePoint));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
