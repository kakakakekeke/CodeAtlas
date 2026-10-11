package io.codeatlas.web.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * jsp가 {@code js} 내장 소스에 실어 보내는 치환 목록({@code attrs.jspSubstitutions}, F-011-R14).
 * 내장 소스 본문에서 JSP 요소를 바꿔 넣은 구간과 그 자리의 원문을 담는다. 식의 원문(JSP에 쓴 그대로)을 되살리는 데 쓴다.
 *
 * <p>형식: 건마다 {@code <offset>,<length>,<원문 길이>:<원문>}을 구분자 없이 이어 붙인 문자열. offset과 length는 내장 소스
 * 본문의 문자 오프셋(0부터)과 길이다. 원문은 길이만큼 그대로 읽는다. 건은 오프셋 오름차순이고 서로 겹치지 않는다.
 */
final class Substitutions {

    static final String ATTR = "jspSubstitutions";

    private record Entry(int offset, int length, String original) {
        int end() {
            return offset + length;
        }
    }

    private final List<Entry> entries;

    private Substitutions(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * @param encoded attrs 값
     * @param textLength 내장 소스 본문의 길이
     * @return 값이 없거나 형식이 맞지 않으면 null(쓰지 않는다)
     */
    static Substitutions parse(String encoded, int textLength) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        List<Entry> entries = new ArrayList<>();
        int position = 0;
        int minOffset = 0;
        try {
            while (position < encoded.length()) {
                int colon = encoded.indexOf(':', position);
                if (colon < 0) {
                    return null;
                }
                String[] numbers = encoded.substring(position, colon).split(",", -1);
                if (numbers.length != 3) {
                    return null;
                }
                int offset = Integer.parseInt(numbers[0]);
                int length = Integer.parseInt(numbers[1]);
                int originalLength = Integer.parseInt(numbers[2]);
                int originalEnd = colon + 1 + originalLength;
                if (offset < minOffset || length < 0 || originalLength < 0 || offset + length > textLength
                        || originalEnd > encoded.length()) {
                    return null;
                }
                entries.add(new Entry(offset, length, encoded.substring(colon + 1, originalEnd)));
                minOffset = offset + length;
                position = originalEnd;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return new Substitutions(entries);
    }

    /**
     * 내장 소스 본문의 {@code [start, end)} 구간을 원문으로 되돌린다. 구간에 걸친 치환은 통째로 원문으로 바꾼다
     * (채움 공백만 있는 구간은 사라진다). 길이 0인 치환(문자열 안에서 흔적 없이 지운 요소)은 구간 안쪽에 있을 때만 넣는다.
     */
    String restore(String text, int start, int end) {
        int from = Math.max(0, Math.min(start, text.length()));
        int to = Math.max(from, Math.min(end, text.length()));
        StringBuilder sb = new StringBuilder(to - from + 16);
        int position = from;
        for (int i = firstEndingAfter(from); i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.offset() >= to) {
                break;
            }
            if (e.offset() > position) {
                sb.append(text, position, e.offset());
            }
            sb.append(e.original());
            position = Math.max(position, e.end());
        }
        if (position < to) {
            sb.append(text, position, to);
        }
        return sb.toString();
    }

    /** 내장 소스 본문의 이 오프셋을 덮는 치환의 원문. 그 자리가 치환한 것이 아니면 null. */
    String originalAt(int offset) {
        int i = firstEndingAfter(offset);
        if (i < entries.size() && entries.get(i).offset() <= offset) {
            return entries.get(i).original();
        }
        return null;
    }

    /** 끝이 offset보다 뒤인 첫 건의 순번. */
    private int firstEndingAfter(int offset) {
        int low = 0;
        int high = entries.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (entries.get(mid).end() <= offset) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
