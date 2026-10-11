package io.codeatlas.jsp;

import java.util.Arrays;

/**
 * 문자열 오프셋을 줄·열(둘 다 1부터)로 바꾼다. 줄 끝은 LF, CRLF, 그리고 CR 단독(옛 Mac 형식)이다. CRLF의 CR은 그 줄에 속한다.
 */
final class LineMap {

    private final int[] lineStarts;
    private final int length;

    LineMap(String text) {
        int[] starts = new int[16];
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, count * 2);
                }
                starts[count++] = i + 1;
            }
        }
        this.lineStarts = Arrays.copyOf(starts, count);
        this.length = text.length();
    }

    /**
     * CR 단독 줄 끝을 LF로 바꾼다(길이는 그대로라 오프셋이 어긋나지 않는다). 스캐너와 스크립트 치환, 내장 소스를 받는 쪽(web)이
     * 모두 줄을 같은 방식으로 세게 하려는 것이다. CR 단독이 없으면 같은 문자열을 돌려준다.
     */
    static String loneCrToLf(String text) {
        StringBuilder sb = null;
        for (int i = text.indexOf('\r'); i >= 0; i = text.indexOf('\r', i + 1)) {
            if (i + 1 >= text.length() || text.charAt(i + 1) != '\n') {
                if (sb == null) {
                    sb = new StringBuilder(text);
                }
                sb.setCharAt(i, '\n');
            }
        }
        return sb == null ? text : sb.toString();
    }

    int line(int offset) {
        int index = Arrays.binarySearch(lineStarts, clamp(offset));
        return index >= 0 ? index + 1 : -index - 1;
    }

    int column(int offset) {
        int clamped = clamp(offset);
        return clamped - lineStarts[line(clamped) - 1] + 1;
    }

    private int clamp(int offset) {
        return Math.max(0, Math.min(offset, length));
    }
}
