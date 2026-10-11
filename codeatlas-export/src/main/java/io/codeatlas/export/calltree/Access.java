package io.codeatlas.export.calltree;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.NodeKind;
import java.util.List;
import java.util.Locale;

/** READS/WRITES 엣지를 C/R/U/D 글자로 바꾸는 규칙. */
final class Access {

    /** 글자의 표시 순서. W는 종류를 모르는 쓰기다. */
    static final String ORDER = "CRUDW";

    private Access() {
    }

    /**
     * READS는 R. WRITES는 attrs.op(INSERT→C, UPDATE→U, DELETE→D, MERGE→C와 U)로 정하고,
     * op가 없으면 구문의 statementType으로 정한다. 그래도 모르면 W.
     * 엣지의 대상이 컬럼이어도 같다(컬럼에서 시작하는 영향도가 쓴다).
     */
    static List<Character> letters(GraphModel graph, Edge edge, String statementId) {
        if (edge.kind() == EdgeKind.READS) {
            return List.of('R');
        }
        List<Character> byOp = lettersOf(writeOp(graph, edge, statementId));
        return byOp.isEmpty() ? List.of('W') : byOp;
    }

    /**
     * 테이블 단위로 접을 때의 글자. {@link #letters}와 같되 컬럼으로 가는 READS는 세지 않는다(빈 목록).
     * R은 테이블 수준 READS가 있을 때만 붙는다: sql 분석기는 UPDATE·DELETE·MERGE의 대상 테이블에 테이블 수준 READS를 만들지 않으므로
     * {@code DELETE FROM T WHERE x = ?}는 D이고, 조건절 컬럼의 READS 때문에 RD가 되지 않는다.
     */
    static List<Character> tableLetters(GraphModel graph, Edge edge, String statementId) {
        if (edge.kind() == EdgeKind.READS && isColumn(graph, edge.to())) {
            return List.of();
        }
        return letters(graph, edge, statementId);
    }

    static boolean isColumn(GraphModel graph, String id) {
        return graph.node(id).map(n -> n.kind() == NodeKind.DB_COLUMN).orElseGet(() -> id.startsWith("db:column:"));
    }

    /** WRITES의 종류: attrs.op, 없으면 구문의 statementType. 대문자 {@code INSERT}, {@code UPDATE}, {@code DELETE}, {@code MERGE} 가운데 하나이거나 null. */
    static String writeOp(GraphModel graph, Edge edge, String statementId) {
        String op = normalize(edge.attrString("op"));
        return op != null ? op : normalize(graph.node(statementId).map(n -> n.attrString("statementType")).orElse(null));
    }

    private static String normalize(String op) {
        if (op == null) {
            return null;
        }
        String upper = op.strip().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "INSERT", "UPDATE", "DELETE", "MERGE" -> upper;
            default -> null;
        };
    }

    private static List<Character> lettersOf(String op) {
        if (op == null) {
            return List.of();
        }
        return switch (op) {
            case "INSERT" -> List.of('C');
            case "UPDATE" -> List.of('U');
            case "DELETE" -> List.of('D');
            case "MERGE" -> List.of('C', 'U');
            default -> List.of();
        };
    }

    /**
     * 글자 집합을 C, R, U, D, W 순의 문자열로. 대문자(직접 접근)가 있으면 대문자, 소문자(루틴을 거친 간접 접근)만 있으면 소문자를 적는다.
     * 예: 직접 R과 간접 u → {@code Ru}.
     */
    static String format(Iterable<Character> letters) {
        StringBuilder out = new StringBuilder();
        for (char c : ORDER.toCharArray()) {
            char lower = Character.toLowerCase(c);
            boolean direct = false;
            boolean indirect = false;
            for (char l : letters) {
                direct |= l == c;
                indirect |= l == lower;
            }
            if (direct) {
                out.append(c);
            } else if (indirect) {
                out.append(lower);
            }
        }
        return out.toString();
    }
}
