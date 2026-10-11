package io.codeatlas.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.testing.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * samples/sample-shop 의 루틴 관련 정답 대조(F-008-AC14). cli를 거치지 않고 {@link SqlAnalyzer}만으로 분석한다.
 * 비교는 엄격하게 한다: 루틴 안 구문은 정답의 구문 ID(줄 번호)까지 같아야 통과다.
 */
class SampleShopRoutineTest {

    private static Path sample;
    private static GraphModel graph;

    @BeforeAll
    static void analyze() {
        sample = Files.isDirectory(Path.of("samples/sample-shop")) ? Path.of("samples/sample-shop") : Path.of("../samples/sample-shop");
        assertThat(sample).isDirectory();
        try (TestProject project = TestProject.at(sample.toAbsolutePath().normalize())) {
            graph = project.run(new SqlAnalyzer()).graph();
        }
    }

    private static List<JsonNode> load(String file) {
        try {
            List<JsonNode> list = new ArrayList<>();
            new ObjectMapper().readTree(Files.readString(sample.resolve("expected").resolve(file))).forEach(list::add);
            return list;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    /** 루틴 본문 분석이 책임지는 정답 항목인가: 루틴 안 구문에서 나가는 엣지, 루틴 구문이 근거인 JOINS, 루틴→루틴 CALLS, TRIGGERS_ON. */
    private static boolean routineRelated(JsonNode e) {
        String from = text(e, "from");
        return e.hasNonNull("fromRoutine") || e.hasNonNull("statementRoutine") || (from != null && from.startsWith("db:routine:"));
    }

    private static List<String> strings(JsonNode array) {
        List<String> list = new ArrayList<>();
        if (array != null) {
            array.forEach(x -> list.add(x.asText()));
        }
        return list;
    }

    /** 정답 엣지가 그래프에 정확히(구문 ID, 속성까지) 있는가. 없으면 사유. */
    private static String missing(JsonNode e) {
        String from = text(e, "from");
        String to = text(e, "to");
        EdgeKind kind = EdgeKind.valueOf(text(e, "kind"));
        JsonNode attrs = e.get("attrs");
        List<Edge> found = new ArrayList<>(graph.edges(from, kind, to));
        boolean undirected = e.path("undirected").asBoolean(false);
        if (undirected) {
            found.addAll(graph.edges(to, kind, from));
        }
        found.removeIf(x -> x.confidence() == Confidence.UNRESOLVED);
        if (found.isEmpty()) {
            return "엣지 없음";
        }
        String statement = text(e, "statement");
        for (Edge x : found) {
            if (statement != null && !statement.equals(x.attrString("statement"))) {
                continue;
            }
            if (attrs != null && attrs.has("op") && !attrs.get("op").asText().equals(x.attrString("op"))) {
                continue;
            }
            if (attrs != null && attrs.has("leftColumns")) {
                boolean reversed = !x.from().equals(from);
                List<String> left = strings(attrs.get(reversed ? "rightColumns" : "leftColumns"));
                List<String> right = strings(attrs.get(reversed ? "leftColumns" : "rightColumns"));
                if (!left.equals(x.attrList("leftColumns")) || !right.equals(x.attrList("rightColumns"))) {
                    continue;
                }
            }
            return null;
        }
        return "엣지는 있으나 속성(op/statement/columns)이 다름: " + found;
    }

    private static boolean matches(String pattern, String id) {
        if (pattern.equals("*")) {
            return true;
        }
        return pattern.endsWith("*") ? id.startsWith(pattern.substring(0, pattern.length() - 1)) : pattern.equals(id);
    }

    /** 함정 규칙에 걸리는 엣지. */
    private static List<Edge> violations(JsonNode rule) {
        String from = text(rule, "from");
        String to = text(rule, "to");
        String kind = text(rule, "kind");
        String fromRoutine = text(rule, "fromRoutine");
        Set<String> routineStatements = fromRoutine == null ? null
                : graph.outgoing(fromRoutine, EdgeKind.CONTAINS).stream().map(Edge::to).collect(Collectors.toSet());
        JsonNode attrs = rule.get("attrs");
        List<Edge> bad = new ArrayList<>();
        for (Edge e : graph.edges()) {
            if (!kind.equals("*") && !e.kind().name().equals(kind)) {
                continue;
            }
            if (!matches(from, e.from()) || !matches(to, e.to())) {
                continue;
            }
            if (routineStatements != null && !routineStatements.contains(e.from())) {
                continue;
            }
            if (attrs != null) {
                boolean same = true;
                var names = attrs.fieldNames();
                while (names.hasNext()) {
                    String k = names.next();
                    same &= attrs.get(k).asText().equals(String.valueOf(e.attr(k)));
                }
                if (!same) {
                    continue;
                }
            }
            bad.add(e);
        }
        return bad;
    }

    @Test
    @DisplayName("F-008-AC14 must-have: 루틴 안 구문의 READS/WRITES, JOINS, 루틴→루틴 CALLS, TRIGGERS_ON이 정답의 구문 ID 그대로 있다")
    void mustHaveEdges() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String file : List.of("must-have-edges.json", "must-have-edges-extra.json")) {
            for (JsonNode e : load(file)) {
                if (!routineRelated(e)) {
                    continue;
                }
                checked++;
                String reason = missing(e);
                if (reason != null) {
                    failures.add(file + ": " + text(e, "from") + " -" + text(e, "kind") + "-> " + text(e, "to") + " : " + reason);
                }
            }
        }
        System.out.println("[sample-shop] 루틴 관련 must-have 엣지 " + checked + "개 중 " + (checked - failures.size()) + "개 일치");
        assertThat(checked).isEqualTo(45);
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("F-008-AC14 must-not: DB 객체와 루틴에 관한 함정 위반이 없다")
    void mustNotEdges() {
        List<String> found = new ArrayList<>();
        int checked = 0;
        for (String file : List.of("must-not-have-edges.json", "must-not-have-edges-extra.json")) {
            for (JsonNode rule : load(file)) {
                String from = text(rule, "from");
                String to = text(rule, "to");
                if (from == null || to == null || !(from.startsWith("db:") || to.startsWith("db:")) || from.startsWith("mybatis:")) {
                    continue; // 매퍼 구문에 관한 함정은 mybatis 분석기가 있어야 의미가 있다
                }
                checked++;
                List<Edge> bad = violations(rule);
                if (!bad.isEmpty()) {
                    found.add(file + ": " + from + " -" + text(rule, "kind") + "-> " + to + " 위반: " + bad);
                }
            }
        }
        System.out.println("[sample-shop] DB·루틴 관련 must-not 규칙 " + checked + "개 중 위반 " + found.size() + "개");
        assertThat(checked).isGreaterThanOrEqualTo(25);
        assertThat(found).isEmpty();
        // 규칙의 뜻을 직접 확인: 동적 SQL 조각과 문자열 인자는 테이블이 아니다
        assertThat(graph.nodes(NodeKind.DB_TABLE)).noneMatch(n -> n.id().startsWith("db:table:TB_ORDER_ARC_"));
        assertThat(graph.nodes()).noneMatch(n -> Set.of("db:table:R", "db:table:C_PAY", "db:table:NUMBER", "db:table:TB_SALES_TMP",
                "db:routine:CLOSE_ORDER", "db:routine:WRITE_HIST", "db:routine:GET_ORDER_AMT").contains(n.id()));
    }

    @Test
    @DisplayName("F-008-AC14 expected-unresolved U5·U6: 동적 SQL 지점이 미해결 엣지와 DYNAMIC_SQL 진단으로 남는다")
    void dynamicSqlPoints() {
        // U5: pkg_order.pkb:168 EXECUTE IMMEDIATE V_SQL (INSERT INTO <조립한 이름> SELECT ..)
        String u5 = "sql:stmt:PKG_ORDER.ARCHIVE_ORDERS:168";
        assertThat(graph.node(u5)).isPresent();
        assertThat(graph.node(u5).get().attrBoolean("dynamic")).isTrue();
        assertThat(graph.node(u5).get().attrString("sql")).startsWith("INSERT INTO TB_ORDER_ARC_{*} SELECT * FROM TB_ORDER WHERE");
        List<Edge> out5 = graph.outgoing(u5);
        assertThat(out5).hasSize(1);
        assertThat(out5.get(0).to()).isEqualTo(Ids.unresolved("sql-dynamic", u5));
        assertThat(out5.get(0).kind()).isEqualTo(EdgeKind.WRITES);
        assertThat(out5.get(0).attrString("op")).isEqualTo("INSERT");
        assertThat(out5.get(0).confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(graph.edges("db:routine:PKG_ORDER.ARCHIVE_ORDERS", EdgeKind.CONTAINS, u5)).hasSize(1);

        // U6: sp_batch_util.db2:42-43 PREPARE S1 FROM V_SQL; EXECUTE S1;
        String u6 = "sql:stmt:SP_PURGE_TABLE:42";
        assertThat(graph.node(u6)).isPresent();
        List<Edge> out6 = graph.outgoing(u6);
        assertThat(out6).hasSize(1);
        assertThat(out6.get(0).to()).isEqualTo(Ids.unresolved("sql-dynamic", u6));
        assertThat(out6.get(0).attrString("op")).isEqualTo("DELETE");
        assertThat(out6.get(0).confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(graph.outgoing("db:routine:SP_PURGE_TABLE", EdgeKind.CONTAINS)).extracting(Edge::to).containsExactly(u6);

        List<Diagnostic> dynamic = graph.diagnostics().stream().filter(d -> d.code().equals(DiagnosticCodes.DYNAMIC_SQL)).toList();
        assertThat(dynamic).hasSize(2);
        assertThat(dynamic).allMatch(d -> d.severity() == Severity.WARN);
        assertThat(dynamic).anyMatch(d -> d.location().file().equals("db/oracle/pkg_order.pkb") && d.location().startLine() == 168);
        assertThat(dynamic).anyMatch(d -> d.location().file().equals("db/db2/sp_batch_util.db2") && d.location().startLine() == 42);
        assertThat(graph.node("db:routine:PKG_ORDER.ARCHIVE_ORDERS").get().attrBoolean("dynamicSql")).isTrue();
        assertThat(graph.node("db:routine:SP_PURGE_TABLE").get().attrBoolean("dynamicSql")).isTrue();
    }

    @Test
    @DisplayName("F-008-AC14 샘플의 루틴 파일에서는 파싱 진단이 나오지 않고, 루틴 13개 모두 본문이 분석된다")
    void cleanParse() {
        List<Diagnostic> inDb = graph.diagnostics().stream()
                .filter(d -> d.location() != null && d.location().file().startsWith("db/"))
                .filter(d -> !d.code().equals(DiagnosticCodes.DYNAMIC_SQL)).toList();
        assertThat(inDb).isEmpty();
        assertThat(graph.nodes(NodeKind.DB_ROUTINE).stream().filter(n -> n.declared()).toList()).hasSize(13);
        assertThat(graph.nodes(NodeKind.DB_ROUTINE)).allMatch(n -> n.attrString("language") != null);
        assertThat(graph.node("db:routine:SP_SETTLE_MONTHLY").get().attrString("language")).isEqualTo("SQLPL");
        // SQL 구문 6개 + 시퀀스를 읽는 대입문 1개(62행, F-008-R15)
        assertThat(graph.node("db:routine:SP_SETTLE_MONTHLY").get().attrInt("statementCount", -1)).isEqualTo(7);
        assertThat(graph.node("db:routine:PKG_ORDER.CANCEL_ORDER").get().attrInt("statementCount", -1)).isEqualTo(5);
        assertThat(graph.node("db:routine:TRG_ORDER_STATUS_HIST").get().attrString("language")).isEqualTo("PLSQL");
    }

    @Test
    @DisplayName("F-008-AC16 샘플 sp_settle_monthly.db2:62 SET V_SETTLE_ID = NEXT VALUE FOR SEQ_SETTLE_ID 가 구문 노드와 시퀀스 READS로 남는다")
    void sequenceInSetStatement() {
        String stmt = "sql:stmt:SP_SETTLE_MONTHLY:62";
        assertThat(graph.node(stmt)).isPresent();
        assertThat(graph.node(stmt).get().attrBoolean("sequence")).isTrue();
        assertThat(graph.node(stmt).get().attrString("statementType")).isEqualTo("OTHER");
        assertThat(graph.node(stmt).get().attrString("sql")).isEqualTo("SET V_SETTLE_ID = NEXT VALUE FOR SEQ_SETTLE_ID");
        assertThat(graph.edges("db:routine:SP_SETTLE_MONTHLY", EdgeKind.CONTAINS, stmt)).hasSize(1);
        List<Edge> reads = graph.edges(stmt, EdgeKind.READS, "db:sequence:SEQ_SETTLE_ID");
        assertThat(reads).hasSize(1);
        assertThat(reads.get(0).attrBoolean("sequence")).isTrue();
        assertThat(reads.get(0).location().file()).isEqualTo("db/db2/sp_settle_monthly.db2");
        assertThat(reads.get(0).location().startLine()).isEqualTo(62);
        assertThat(graph.node("db:sequence:SEQ_SETTLE_ID").get().declared()).isTrue();
    }

    @Test
    @DisplayName("F-008-AC15 샘플의 %TYPE 참조가 루틴 attrs.typeRefs에 남는다 (pkg_order.pkb:44, fn_get_code_nm.fnc:7)")
    void typeRefsOfSampleRoutines() {
        assertThat(graph.node("db:routine:PKG_ORDER.CLOSE_ORDER").get().attrList("typeRefs")).containsExactly("TB_ORDER.ORDER_STATUS");
        assertThat(graph.node("db:routine:FN_GET_CODE_NM").get().attrList("typeRefs")).containsExactly("TB_CODE.CODE_NM");
        assertThat(graph.node("db:routine:SP_SETTLE_MONTHLY").get().attrs()).doesNotContainKey("typeRefs");
    }

    @Test
    @DisplayName("F-007-AC13 샘플 02_constraints_indexes.sql: 인덱스의 DESC와 함수 기반 식이 테이블 attrs.indexes에 보존된다")
    void indexSortDirectionOfSample() {
        assertThat(graph.node("db:table:TB_ORDER_HIST").get().attrList("indexes")).containsExactly("IX_ORDER_HIST_01(ORDER_ID,CHG_DT DESC)");
        assertThat(graph.node("db:table:TB_PRODUCT").get().attrList("indexes")).containsExactly("IX_PRODUCT_01(CATEGORY_ID,UPPER(PRODUCT_NM))");
        assertThat(graph.node("db:table:TB_DELIVERY").get().attrList("indexes")).containsExactly("UX_DELIVERY_01 UNIQUE(ORDER_ID)");
    }
}
