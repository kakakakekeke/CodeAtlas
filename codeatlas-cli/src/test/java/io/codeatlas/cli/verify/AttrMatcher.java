package io.codeatlas.cli.verify;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 정답의 attrs와 그래프의 attrs 비교. 정답에 적힌 키만 본다.
 *
 * <ul>
 *   <li>문자열: 같아야 한다. {@code dataType}만 공백을 지우고 대문자로 바꿔 비교한다.</li>
 *   <li>숫자: 값이 같으면 된다(Integer와 Long 구분 없음).</li>
 *   <li>불리언: 그래프에 속성이 없으면 false로 본다({@code HasAttrs.attrBoolean}과 같은 규칙).</li>
 *   <li>목록: 순서를 무시한다. 짝을 이루는 목록({@code leftColumns}/{@code rightColumns}, {@code columns}/{@code refColumns})은
 *       같은 자리의 원소끼리 짝을 지은 뒤 짝의 집합으로 비교한다.</li>
 * </ul>
 */
final class AttrMatcher {

    private static final String[][] PAIRED_KEYS = {{"leftColumns", "rightColumns"}, {"columns", "refColumns"}};

    private AttrMatcher() {
    }

    /** 다른 키와 그 차이를 돌려준다(키 → "기대 X, 실제 Y"). 전부 같으면 빈 맵. */
    static Map<String, String> differences(Map<String, Object> expected, Map<String, Object> actual) {
        return differences(expected, actual, false);
    }

    /**
     * @param swapSides true면 그래프의 엣지가 정답과 반대 방향이라, 그래프 쪽의 leftColumns와 rightColumns를 서로 바꿔서 본다
     */
    static Map<String, String> differences(Map<String, Object> expected, Map<String, Object> actual, boolean swapSides) {
        Map<String, String> diff = new TreeMap<>();
        Set<String> handled = new HashSet<>();
        for (String[] pair : PAIRED_KEYS) {
            String left = pair[0];
            String right = pair[1];
            boolean swap = swapSides && left.equals("leftColumns");
            Object actualLeft = actual.get(swap ? right : left);
            Object actualRight = actual.get(swap ? left : right);
            if (expected.get(left) instanceof List<?> el && expected.get(right) instanceof List<?> er && el.size() == er.size()
                    && actualLeft instanceof List<?> al && actualRight instanceof List<?> ar && al.size() == ar.size()) {
                handled.add(left);
                handled.add(right);
                if (!pairs(el, er).equals(pairs(al, ar))) {
                    diff.put(left + "/" + right, "기대 " + el + "/" + er + ", 실제 " + al + "/" + ar);
                }
            } else if (swap) {
                // 짝으로 비교하지 못해도 방향은 맞춰서 따로 비교한다
                for (String key : pair) {
                    if (expected.containsKey(key)) {
                        handled.add(key);
                        Object other = actual.get(key.equals(left) ? right : left);
                        if (!valueMatches(key, expected.get(key), other)) {
                            diff.put(key, describe(expected.get(key), other));
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String key = entry.getKey();
            if (!handled.contains(key) && !valueMatches(key, entry.getValue(), actual.get(key))) {
                diff.put(key, describe(entry.getValue(), actual.get(key)));
            }
        }
        return diff;
    }

    private static String describe(Object expected, Object actual) {
        return "기대 " + expected + ", 실제 " + (actual == null ? "(없음)" : actual);
    }

    private static Set<String> pairs(List<?> left, List<?> right) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < left.size(); i++) {
            result.add(left.get(i) + "=" + right.get(i));
        }
        return result;
    }

    static boolean valueMatches(String key, Object expected, Object actual) {
        if (expected instanceof Boolean b) {
            return actual == null ? !b : b.equals(actual);
        }
        if (actual == null) {
            return false;
        }
        if (expected instanceof Number n) {
            return actual instanceof Number a && Double.compare(n.doubleValue(), a.doubleValue()) == 0;
        }
        if (expected instanceof List<?> list) {
            List<?> other = actual instanceof List<?> l ? l : List.of(actual);
            return sorted(list).equals(sorted(other));
        }
        String e = String.valueOf(expected);
        String a = String.valueOf(actual);
        return key.equals("dataType") ? normalizeDataType(e).equals(normalizeDataType(a)) : e.equals(a);
    }

    /** 데이터 타입 표기 정규화: 공백을 지우고 대문자로. 예: {@code number(15, 2)} → {@code NUMBER(15,2)}. */
    static String normalizeDataType(String dataType) {
        return dataType.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static List<String> sorted(List<?> values) {
        List<String> result = new ArrayList<>();
        values.forEach(v -> result.add(String.valueOf(v)));
        result.sort(null);
        return result;
    }
}
