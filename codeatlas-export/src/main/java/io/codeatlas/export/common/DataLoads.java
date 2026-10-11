package io.codeatlas.export.common;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 데이터 적재 구문: sql 분석기가 스크립트의 리터럴 INSERT를 (파일, 테이블) 단위로 묶어 만든 {@code SQL_STATEMENT} 노드
 * ({@code attrs.dataLoad=true}, {@code attrs.rowCount}=묶은 행 수). 애플리케이션이 실행하는 구문이 아니므로 구문 단위 집계
 * (CRUD 매트릭스, 테이블 명세의 구문 수, 리포트의 테이블 점검, 콜트리)에서는 기본으로 빼고 따로 요약한다.
 * 속성이 없는 그래프에서는 적재 구문이 하나도 없는 것으로 본다.
 */
public final class DataLoads {

    private DataLoads() {
    }

    public static boolean isDataLoad(Node node) {
        return node != null && node.kind() == NodeKind.SQL_STATEMENT && node.attrBoolean("dataLoad");
    }

    /**
     * 다른 방언 파일에서 남긴 적재 묶음의 표시. sql 분석기는 적재 묶음을 테이블 단위로 고른다: 선택한 방언의 파일에 그 테이블의 적재가 없으면
     * 다른 방언 파일의 묶음을 남기고 {@code attrs.borrowedFrom=<방언>}을 붙인다. 그런 묶음이면 {@code 다른 방언 파일: mysql}, 아니면 null.
     */
    public static String borrowedNote(Node statement) {
        if (!isDataLoad(statement)) {
            return null;
        }
        String from = statement.attrString(BORROWED_FROM);
        return from == null || from.isBlank() ? null : "다른 방언 파일: " + Texts.oneLine(from);
    }

    /** 다른 방언 파일에서 남긴 적재 묶음의 방언을 적는 attr(sql). 매퍼 구문의 보충과 같은 이름이다. */
    public static final String BORROWED_FROM = "borrowedFrom";

    /** 그래프의 데이터 적재 구문 ID. */
    public static Set<String> ids(GraphModel graph) {
        Set<String> ids = new HashSet<>();
        for (Node node : graph.nodes(NodeKind.SQL_STATEMENT)) {
            if (isDataLoad(node)) {
                ids.add(node.id());
            }
        }
        return ids;
    }

    /**
     * 적재 대상 하나(테이블 또는 파일)의 집계.
     *
     * @param statements 적재 구문 수
     * @param rows 행 수를 아는 구문의 행 수 합
     * @param unknownRows 행 수({@code rowCount})를 모르는 구문 수
     */
    public record Load(int statements, long rows, int unknownRows) {

        Load plus(long moreRows, boolean unknown) {
            return new Load(statements + 1, rows + moreRows, unknownRows + (unknown ? 1 : 0));
        }

        /** {@code 120행}, {@code 30행 이상}(모르는 구문이 섞임), {@code 행 수 모름}. */
        public String rowsText(String unit) {
            if (unknownRows == 0) {
                return rows + unit;
            }
            return unknownRows == statements && rows == 0 ? "행 수 모름" : rows + unit + " 이상";
        }
    }

    /**
     * 요약.
     *
     * @param byFile 스크립트 파일 → (그 파일이 적재하는 테이블 ID, 집계). 파일 경로순
     * @param byTable 테이블 ID → 집계
     * @param total 전체 집계
     * @param borrowed 다른 방언 파일에서 남긴 묶음({@code borrowedFrom})의 방언 → 묶음 수(방언 이름순)
     */
    public record Summary(Map<String, FileLoad> byFile, Map<String, Load> byTable, Load total, Map<String, Integer> borrowed) {

        public Summary {
            borrowed = borrowed == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(borrowed));
        }

        public Summary(Map<String, FileLoad> byFile, Map<String, Load> byTable, Load total) {
            this(byFile, byTable, total, Map.of());
        }

        public boolean isEmpty() {
            return total.statements() == 0;
        }

        /** 다른 방언 파일에서 남긴 묶음({@code borrowedFrom}) 수. */
        public int borrowedStatements() {
            return borrowed.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * 파일 하나가 적재하는 테이블과 집계.
     *
     * @param borrowedFrom 이 파일의 묶음이 다른 방언 파일에서 남긴 것이면 그 방언들(이름순). 아니면 비어 있다
     */
    public record FileLoad(Set<String> tables, Load load, Set<String> borrowedFrom) {

        public FileLoad(Set<String> tables, Load load) {
            this(tables, load, Set.of());
        }

        /** {@code 다른 방언 파일: mysql}. 다른 방언 파일의 묶음이 아니면 null. */
        public String borrowedNote() {
            return borrowedFrom.isEmpty() ? null : "다른 방언 파일: " + String.join(", ", borrowedFrom);
        }
    }

    /** 적재 구문을 파일별·테이블별로 모은다. 테이블은 그 구문의 WRITES(컬럼 엣지는 테이블로 접는다) 대상이다. */
    public static Summary summarize(GraphModel graph) {
        List<Node> loads = new ArrayList<>();
        for (Node node : graph.nodes(NodeKind.SQL_STATEMENT)) {
            if (isDataLoad(node)) {
                loads.add(node);
            }
        }
        loads.sort(java.util.Comparator.comparing(Node::id));
        Map<String, FileLoad> byFile = new TreeMap<>();
        Map<String, Load> byTable = new TreeMap<>();
        Load total = new Load(0, 0, 0);
        Map<String, Integer> borrowedByDialect = new TreeMap<>();
        for (Node statement : loads) {
            boolean unknown = !(statement.attr("rowCount") instanceof Number);
            long rows = unknown ? 0 : Math.max(0, ((Number) statement.attr("rowCount")).longValue());
            Set<String> tables = new TreeSet<>();
            for (Edge e : graph.outgoing(statement.id(), EdgeKind.WRITES)) {
                DbGraph.accessedTable(graph, e).ifPresent(tables::add);
            }
            String file = fileOf(statement);
            FileLoad before = byFile.getOrDefault(file, new FileLoad(new TreeSet<>(), new Load(0, 0, 0)));
            Set<String> fileTables = new TreeSet<>(before.tables());
            fileTables.addAll(tables);
            Set<String> borrowed = new TreeSet<>(before.borrowedFrom());
            String from = statement.attrString(BORROWED_FROM);
            if (from != null && !from.isBlank()) {
                borrowed.add(Texts.oneLine(from));
                borrowedByDialect.merge(Texts.oneLine(from), 1, Integer::sum);
            }
            byFile.put(file, new FileLoad(fileTables, before.load().plus(rows, unknown), borrowed));
            for (String table : tables) {
                byTable.put(table, byTable.getOrDefault(table, new Load(0, 0, 0)).plus(rows, unknown));
            }
            total = total.plus(rows, unknown);
        }
        return new Summary(byFile, byTable, total, borrowedByDialect);
    }

    /** 구문이 든 파일: 노드의 위치, 없으면 구문 ID({@code sql:stmt:<파일>:<줄>})에서 읽는다. */
    private static String fileOf(Node statement) {
        if (statement.location() != null && statement.location().file() != null) {
            return statement.location().file();
        }
        String rest = Ids.remainder(statement.id());
        int colon = rest.lastIndexOf(':');
        return colon > 0 ? rest.substring(0, colon) : rest;
    }
}
