package io.codeatlas.export.calltree;

import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.common.Json;
import io.codeatlas.export.common.Texts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 테이블·컬럼 영향도 리포트. 계층마다 항목은 노드 ID 순이다. 추론한 관계를 거쳐서만 닿은 항목은 Markdown에서 이름 앞에 {@code ~}가,
 * 경로 끝에 {@code (추론: <엣지>: <까닭>)}이 붙고 JSON에는 {@code inferred}·{@code inference}가 들어간다.
 *
 * @param targetId 대상 노드 ID. 찾지 못했으면 요청한 문자열 그대로
 * @param targetKind 대상 종류. 찾지 못했으면 null
 * @param targetLabel 표시 이름. 예: {@code ORDERS}, {@code ORDERS.STATUS}
 * @param tableId 대상이 컬럼일 때 소속 테이블 ID. 아니면 null
 * @param statements 대상을 읽고 쓰는 구문(컬럼이 대상이면 그 컬럼에 엣지가 있는 구문)
 * @param tableLevelStatements 컬럼이 대상일 때, 테이블은 참조하지만 컬럼을 하나도 해석하지 못한 구문("테이블 수준에서만 확인됨")
 * @param executors 구문을 직접 실행하는 메서드, 루틴, 배치 스텝
 * @param callers 호출 사슬 위의 메서드·루틴 등(실행 주체와 엔드포인트 사이)
 * @param endpoints 호출 사슬로 닿는 엔드포인트
 * @param screens 그 엔드포인트를 요청하는 화면: 요청 지점, JS 함수, JS 모듈, JSP
 * @param batch 배치 스텝과 잡
 * @param suggestions 대상을 찾지 못했을 때, 이름이 비슷한 테이블·뷰·컬럼({@link NodeFinder}의 규칙, ID 순). 찾았으면 비어 있다
 * @param dynamicStatements 대상 테이블을 정적으로 알 수 없는 동적 구문(그래프 전체, 구문 ID 순). 이 대상을 읽거나 쓸 수도 있지만
 *        알 수 없어서 위의 계층에는 넣지 않는다. 대상을 찾지 못했으면 비어 있다
 * @param dataLoadExcluded 구문 목록에서 뺀 데이터 적재 구문({@code dataLoad=true}: 스크립트의 리터럴 INSERT 묶음) 수. 넣었거나 없으면 0
 */
public record ImpactReport(String targetId, NodeKind targetKind, String targetLabel, String tableId, List<Item> statements,
        List<Item> tableLevelStatements, List<Item> executors, List<Item> callers, List<Item> endpoints, List<Item> screens,
        List<Item> batch, List<Suggestion> suggestions, List<DynamicStatement> dynamicStatements, int dataLoadExcluded) {

    /** Markdown에 보여 줄 비슷한 이름의 최대 개수. 나머지는 개수만 적는다. */
    public static final int MAX_SUGGESTIONS_SHOWN = 10;

    public ImpactReport {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        dynamicStatements = dynamicStatements == null ? List.of() : List.copyOf(dynamicStatements);
        statements = List.copyOf(statements);
        tableLevelStatements = List.copyOf(tableLevelStatements);
        executors = List.copyOf(executors);
        callers = List.copyOf(callers);
        endpoints = List.copyOf(endpoints);
        screens = List.copyOf(screens);
        batch = List.copyOf(batch);
    }

    /** 뺀 데이터 적재 구문이 없는 리포트. */
    public ImpactReport(String targetId, NodeKind targetKind, String targetLabel, String tableId, List<Item> statements,
            List<Item> tableLevelStatements, List<Item> executors, List<Item> callers, List<Item> endpoints, List<Item> screens,
            List<Item> batch, List<Suggestion> suggestions, List<DynamicStatement> dynamicStatements) {
        this(targetId, targetKind, targetLabel, tableId, statements, tableLevelStatements, executors, callers, endpoints, screens, batch,
                suggestions, dynamicStatements, 0);
    }

    /** 동적 구문이 없는 리포트. */
    public ImpactReport(String targetId, NodeKind targetKind, String targetLabel, String tableId, List<Item> statements,
            List<Item> tableLevelStatements, List<Item> executors, List<Item> callers, List<Item> endpoints, List<Item> screens,
            List<Item> batch, List<Suggestion> suggestions) {
        this(targetId, targetKind, targetLabel, tableId, statements, tableLevelStatements, executors, callers, endpoints, screens, batch,
                suggestions, List.of());
    }

    /** 비슷한 이름 후보가 없는 리포트. */
    public ImpactReport(String targetId, NodeKind targetKind, String targetLabel, String tableId, List<Item> statements,
            List<Item> tableLevelStatements, List<Item> executors, List<Item> callers, List<Item> endpoints, List<Item> screens,
            List<Item> batch) {
        this(targetId, targetKind, targetLabel, tableId, statements, tableLevelStatements, executors, callers, endpoints, screens, batch,
                List.of());
    }

    /**
     * 대상을 찾지 못했을 때의 비슷한 이름 후보.
     *
     * @param id 노드 ID. {@code ImpactAnalysis.analyze}에 그대로 줄 수 있다
     * @param kind {@code DB_TABLE}, {@code DB_VIEW}, {@code DB_COLUMN}
     * @param label 표시 이름. 예: {@code ORDERS}, {@code ORDERS.STATUS}
     * @param location {@code file:line}. 없으면 null
     */
    public record Suggestion(String id, NodeKind kind, String label, String location) {
    }

    /**
     * 리포트의 한 항목.
     *
     * @param type 종류 표시. 예: {@code method}, {@code sql}, {@code endpoint}, {@code jsp}
     * @param location {@code file:line}. 없으면 null
     * @param access 구문의 접근 종류(C/R/U/D). 구문이 아니면 null
     * @param tableLevelOnly 컬럼 대상일 때, 컬럼을 해석하지 못한 구문을 통해서만 닿는 항목이면 true
     * @param distance 구문에서 이 항목까지의 걸음 수(구문은 0)
     * @param path 이 항목에서 구문까지의 노드 ID(호출 방향 순서)
     * @param pathLabels path의 표시 이름
     * @param inference 추론한 관계를 거쳐서만 닿은 항목이면 그 근거: path에서 (이 항목 쪽부터) 처음 만나는 추론 엣지와 까닭.
     *        예: {@code OrderController#save() → Codec#encode(String): 후보 여럿(ambiguous)}. 확정 경로가 하나라도 있으면 null이고
     *        path는 그 확정 경로다
     */
    public record Item(String id, NodeKind kind, String label, String type, String location, String access, boolean tableLevelOnly,
            int distance, List<String> path, List<String> pathLabels, String inference) {
        public Item {
            path = List.copyOf(path);
            pathLabels = List.copyOf(pathLabels);
        }

        /** 추론 표시가 없는 항목. */
        public Item(String id, NodeKind kind, String label, String type, String location, String access, boolean tableLevelOnly,
                int distance, List<String> path, List<String> pathLabels) {
            this(id, kind, label, type, location, access, tableLevelOnly, distance, path, pathLabels, null);
        }

        /** 추론한 관계를 거쳐서만 닿은 항목인지(Markdown의 {@code ~}). */
        public boolean inferred() {
            return inference != null;
        }
    }

    /** 뺀 데이터 적재 구문 수를 실은 리포트. */
    ImpactReport withDataLoadExcluded(int count) {
        return new ImpactReport(targetId, targetKind, targetLabel, tableId, statements, tableLevelStatements, executors, callers, endpoints,
                screens, batch, suggestions, dynamicStatements, count);
    }

    /** 모든 계층의 항목. */
    public List<Item> all() {
        List<Item> all = new ArrayList<>();
        all.addAll(statements);
        all.addAll(tableLevelStatements);
        all.addAll(executors);
        all.addAll(callers);
        all.addAll(endpoints);
        all.addAll(screens);
        all.addAll(batch);
        return all;
    }

    public boolean found() {
        return targetKind != null;
    }

    private boolean columnTarget() {
        return targetKind == NodeKind.DB_COLUMN;
    }

    public String toMarkdown() {
        StringBuilder out = new StringBuilder("# 영향도: ").append(targetLabel).append("\n\n");
        if (!found()) {
            out.append("대상 `").append(targetId).append("`을 그래프에서 찾지 못했다.\n");
            if (!suggestions.isEmpty()) {
                out.append("\n## 비슷한 이름 (").append(suggestions.size()).append(")\n\n");
                for (Suggestion s : suggestions.subList(0, Math.min(MAX_SUGGESTIONS_SHOWN, suggestions.size()))) {
                    String kind = s.kind() == NodeKind.DB_COLUMN ? "컬럼" : s.kind() == NodeKind.DB_VIEW ? "뷰" : "테이블";
                    out.append("- ").append(Texts.mdCode(s.label())).append(" (").append(kind).append(", ").append(Texts.mdCode(s.id()))
                            .append(")\n");
                }
                if (suggestions.size() > MAX_SUGGESTIONS_SHOWN) {
                    out.append("\n외 ").append(suggestions.size() - MAX_SUGGESTIONS_SHOWN).append("개\n");
                }
            }
            return out.toString();
        }
        out.append("- 대상: ").append(Texts.mdCode(targetId)).append(" (");
        if (columnTarget()) {
            out.append("컬럼");
            if (tableId != null) {
                out.append(", 테이블 ").append(Texts.mdCode(tableId.substring(tableId.lastIndexOf(':') + 1)));
            }
        } else {
            out.append(targetKind == NodeKind.DB_VIEW ? "뷰" : "테이블");
        }
        out.append(")\n");
        out.append("- 구문 ").append(statements.size()).append("개");
        if (columnTarget()) {
            out.append(" (테이블 수준에서만 확인됨 ").append(tableLevelStatements.size()).append("개 별도)");
        }
        out.append(", 실행 주체 ").append(executors.size()).append("개, 호출 사슬 ").append(callers.size()).append("개, 엔드포인트 ")
                .append(endpoints.size()).append("개, 화면 ").append(screens.size()).append("개, 배치 ").append(batch.size())
                .append("개\n");
        if (!dynamicStatements.isEmpty()) {
            out.append("- 대상 테이블을 정적으로 알 수 없는 구문 ").append(dynamicStatements.size())
                    .append("개(아래 별도 절). 이 대상에 닿는지는 구문을 직접 봐야 안다\n");
        }
        if (dataLoadExcluded > 0) {
            out.append("- 데이터 적재 구문 ").append(dataLoadExcluded)
                    .append("건 제외(SQL 스크립트의 리터럴 INSERT 묶음. 애플리케이션이 실행하는 구문이 아니다)\n");
        }
        long inferred = all().stream().filter(Item::inferred).count();
        if (inferred > 0) {
            out.append("- `~`는 추론한 관계를 거쳐서만 닿은 항목이다(").append(inferred)
                    .append("개. 확정 경로가 하나라도 있으면 붙지 않는다). 경로 끝의 `추론:`은 그 경로에서 처음 만나는 추론 엣지와 까닭이다\n");
        }

        out.append("\n## 구문 (").append(statements.size()).append(")\n\n");
        statementTable(out, statements);
        if (columnTarget() && !tableLevelStatements.isEmpty()) {
            out.append("\n### 테이블 수준에서만 확인됨 (").append(tableLevelStatements.size()).append(")\n\n");
            out.append("테이블은 참조하지만 컬럼을 해석하지 못한 구문이다. 이 컬럼을 쓰는지는 구문을 직접 봐야 안다.\n\n");
            statementTable(out, tableLevelStatements);
        }
        layer(out, "실행하는 메서드·루틴·배치 스텝", executors);
        layer(out, "호출 사슬", callers);
        layer(out, "엔드포인트", endpoints);
        layer(out, "화면", screens);
        layer(out, "배치", batch);
        if (!dynamicStatements.isEmpty()) {
            out.append("\n## 대상 테이블을 정적으로 알 수 없는 구문 (").append(dynamicStatements.size()).append(")\n\n");
            out.append("동적 SQL이라 어느 테이블을 읽고 쓰는지 정적으로 알 수 없는 구문이다(그래프 전체). 위의 계층에는 들어 있지 않다.\n\n");
            out.append("| 구문 | CRUD | 위치 |\n|---|---|---|\n");
            for (DynamicStatement d : dynamicStatements) {
                out.append("| ").append(Texts.mdCode(d.label())).append(" | ").append(d.access()).append(" | ")
                        .append(Texts.mdCode(d.location())).append(" |\n");
            }
        }
        return out.toString();
    }

    private static void statementTable(StringBuilder out, List<Item> items) {
        if (items.isEmpty()) {
            out.append("없음\n");
            return;
        }
        out.append("| 구문 | CRUD | 위치 |\n|---|---|---|\n");
        for (Item item : items) {
            out.append("| ").append(Texts.mdCode(item.label())).append(" | ").append(Texts.nullToEmpty(item.access())).append(" | ")
                    .append(Texts.mdCode(item.location())).append(" |\n");
        }
    }

    private void layer(StringBuilder out, String title, List<Item> items) {
        out.append("\n## ").append(title).append(" (").append(items.size()).append(")\n\n");
        if (items.isEmpty()) {
            out.append("없음\n");
            return;
        }
        boolean column = columnTarget();
        out.append("| 이름 | 종류 | 위치 | 경로 |").append(column ? " 확인 수준 |" : "").append("\n|---|---|---|---|").append(column ? "---|" : "")
                .append('\n');
        for (Item item : items) {
            out.append("| ").append(item.inferred() ? "~ " : "").append(Texts.mdCode(item.label())).append(" | ").append(item.type()).append(" | ")
                    .append(Texts.mdCode(item.location())).append(" | ")
                    .append(item.pathLabels().stream().map(Texts::mdCode).collect(Collectors.joining(" → ")))
                    .append(item.inferred() ? " (추론: " + Texts.mdCell(item.inference()) + ")" : "").append(" |");
            if (column) {
                out.append(item.tableLevelOnly() ? " 테이블 수준 |" : " 컬럼 |");
            }
            out.append('\n');
        }
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("id", targetId);
        target.put("kind", targetKind == null ? null : targetKind.name());
        target.put("label", targetLabel);
        target.put("table", tableId);
        target.put("found", found());
        root.put("target", target);
        root.put("statements", json(statements));
        root.put("tableLevelStatements", json(tableLevelStatements));
        root.put("executors", json(executors));
        root.put("callers", json(callers));
        root.put("endpoints", json(endpoints));
        root.put("screens", json(screens));
        root.put("batch", json(batch));
        List<Object> similar = new ArrayList<>();
        for (Suggestion s : suggestions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("kind", s.kind().name());
            m.put("label", s.label());
            m.put("location", s.location());
            similar.add(m);
        }
        root.put("suggestions", similar);
        List<Object> dynamic = new ArrayList<>();
        for (DynamicStatement d : dynamicStatements) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("label", d.label());
            m.put("access", d.access());
            m.put("location", d.location());
            m.put("text", d.text());
            dynamic.add(m);
        }
        root.put("dynamicStatements", dynamic);
        root.put("dataLoadExcluded", dataLoadExcluded);
        return Json.write(root);
    }

    private static List<Object> json(List<Item> items) {
        List<Object> list = new ArrayList<>();
        for (Item item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.id());
            m.put("kind", item.kind().name());
            m.put("label", item.label());
            m.put("type", item.type());
            m.put("location", item.location());
            m.put("access", item.access());
            m.put("tableLevelOnly", item.tableLevelOnly());
            m.put("distance", item.distance());
            m.put("path", item.path());
            m.put("inferred", item.inferred());
            m.put("inference", item.inference());
            list.add(m);
        }
        return list;
    }
}
