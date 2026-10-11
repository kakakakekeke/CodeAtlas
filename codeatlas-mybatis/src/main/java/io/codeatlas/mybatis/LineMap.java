package io.codeatlas.mybatis;

import java.util.Arrays;

/** 문자열 오프셋과 줄·열(1부터)을 서로 바꾼다. 줄 구분은 XML 파서와 같이 {@code \n}, {@code \r\n}, 홀로 있는 {@code \r}다. 열은 UTF-16 단위로 센다. */
final class LineMap {

    private final int[] lineStarts;
    private final int length;

    LineMap(String text) {
        int[] starts = new int[Math.max(16, text.length() / 32)];
        int count = 0;
        starts[count++] = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            boolean lineBreak = c == '\n' || (c == '\r' && (i + 1 >= n || text.charAt(i + 1) != '\n'));
            if (lineBreak) {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, count * 2);
                }
                starts[count++] = i + 1;
            }
        }
        this.lineStarts = Arrays.copyOf(starts, count);
        this.length = n;
    }

    /** 줄·열의 오프셋. 범위를 벗어나면 -1. */
    int offset(int line, int column) {
        if (line < 1 || line > lineStarts.length || column < 1) {
            return -1;
        }
        int offset = lineStarts[line - 1] + column - 1;
        return offset > length ? -1 : offset;
    }

    int line(int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);
        return index >= 0 ? index + 1 : -index - 1;
    }

    int column(int offset) {
        return offset - lineStarts[line(offset) - 1] + 1;
    }
}
