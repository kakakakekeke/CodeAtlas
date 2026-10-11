package io.codeatlas.export.calltree;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 트리거 노드의 {@code attrs.triggerEvent}(예: {@code AFTER UPDATE OF ORDER_STATUS}, {@code BEFORE INSERT OR DELETE})를
 * 사건별로 나눈 것. 시점({@code BEFORE}, {@code AFTER}, {@code INSTEAD OF}, {@code NO CASCADE})은 읽고 버린다.
 *
 * @param clauses {@code OR}로 이은 사건들(적힌 순서). 사건을 하나도 알아보지 못했으면 비어 있다
 */
record TriggerEvent(List<Clause> clauses) {

    /**
     * 사건 하나.
     *
     * @param event {@code INSERT}, {@code UPDATE}, {@code DELETE}
     * @param columns {@code UPDATE OF} 뒤의 컬럼 이름(따옴표 없는 것은 대문자). 컬럼 지정이 없으면 비어 있다
     */
    record Clause(String event, Set<String> columns) {
        Clause {
            columns = Set.copyOf(columns);
        }

        /** 이 사건이 컬럼을 가리지 않거나 주어진 컬럼 가운데 하나를 가리키는지(대소문자 무시). */
        boolean coversAny(Set<String> written) {
            if (columns.isEmpty()) {
                return true;
            }
            for (String column : columns) {
                for (String w : written) {
                    if (column.equalsIgnoreCase(w)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    TriggerEvent {
        clauses = List.copyOf(clauses);
    }

    /** 사건을 하나라도 알아봤는지. false면 어떤 쓰기에 일어나는지 알 수 없는 트리거다. */
    boolean known() {
        return !clauses.isEmpty();
    }

    static TriggerEvent parse(String text) {
        List<Clause> clauses = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new TriggerEvent(clauses);
        }
        List<String> tokens = new ArrayList<>();
        for (String token : text.replace(",", " ").strip().split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            String event = tokens.get(i).toUpperCase(Locale.ROOT);
            if (!event.equals("INSERT") && !event.equals("UPDATE") && !event.equals("DELETE")) {
                continue;
            }
            Set<String> columns = new LinkedHashSet<>();
            if (event.equals("UPDATE") && i + 1 < tokens.size() && tokens.get(i + 1).equalsIgnoreCase("OF")) {
                int k = i + 2;
                for (; k < tokens.size() && !endsColumnList(tokens.get(k)); k++) {
                    columns.add(columnName(tokens.get(k)));
                }
                i = k - 1;
            }
            clauses.add(new Clause(event, columns));
        }
        return new TriggerEvent(clauses);
    }

    private static boolean endsColumnList(String token) {
        return switch (token.toUpperCase(Locale.ROOT)) {
            case "OR", "ON", "FOR", "REFERENCING", "WHEN" -> true;
            default -> false;
        };
    }

    /** 따옴표로 감싼 식별자는 원문 그대로, 아니면 대문자(그래프의 DB 이름 규칙과 같다). */
    private static String columnName(String token) {
        if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
            return token.substring(1, token.length() - 1);
        }
        return token.toUpperCase(Locale.ROOT);
    }
}
