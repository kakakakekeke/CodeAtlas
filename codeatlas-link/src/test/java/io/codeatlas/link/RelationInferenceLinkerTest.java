package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RelationInferenceLinkerTest extends LinkTestBase {

    private static final List<String> MEMBER_ID = List.of("MEMBER_ID");

    private List<Edge> inferredRefs() {
        return g.edges(EdgeKind.REFERENCES).stream().filter(e -> "INFERRED".equals(e.attrString("source"))).toList();
    }

    private Edge onlyInferred() {
        assertThat(inferredRefs()).hasSize(1);
        return inferredRefs().get(0);
    }

    @Test
    @DisplayName("F-018-AC1 조인 근거를 테이블 쌍·컬럼 대응별로 모아 자식→부모 REFERENCES(INFERRED)를 만든다")
    void infersChildToParent() {
        String order = table("TB_ORDER", "ORDER_ID");
        String member = table("TB_MEMBER", "MEMBER_ID");
        Edge first = joins(order, member, MEMBER_ID, MEMBER_ID, "mybatis:stmt:a.one");
        joins(member, order, MEMBER_ID, MEMBER_ID, "mybatis:stmt:a.two");   // 좌우 반대
        joins(order, member, MEMBER_ID, MEMBER_ID, "mybatis:stmt:a.one");   // 같은 구문의 다른 위치

        run(new RelationInferenceLinker());

        Edge e = onlyInferred();
        assertThat(e.from()).isEqualTo(order);
        assertThat(e.to()).isEqualTo(member);
        assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(e.attrList("columns")).containsExactly("MEMBER_ID");
        assertThat(e.attrList("refColumns")).containsExactly("MEMBER_ID");
        assertThat(e.attrInt("evidenceCount", 0)).isEqualTo(2);
        assertThat(e.attrList("evidence")).containsExactlyInAnyOrder("mybatis:stmt:a.one", "mybatis:stmt:a.two");
        assertThat(e.attrString("cardinality")).isEqualTo("N:1");
        assertThat(e.attr("directionKnown")).isEqualTo(true);
        assertThat(e.location()).isEqualTo(first.location());
        assertThat(e.attrs()).doesNotContainKey("typeMismatch");
    }

    @Test
    @DisplayName("F-018-AC2 evidence는 최대 10개, evidenceCount는 전체 근거 구문 수")
    void evidenceCapped() {
        String order = table("TB_ORDER", "ORDER_ID");
        String member = table("TB_MEMBER", "MEMBER_ID");
        for (int i = 0; i < 12; i++) {
            joins(order, member, MEMBER_ID, MEMBER_ID, "mybatis:stmt:a.s" + i);
        }

        run(new RelationInferenceLinker());

        assertThat(onlyInferred().attrInt("evidenceCount", 0)).isEqualTo(12);
        assertThat(onlyInferred().attrList("evidence")).hasSize(10);
    }

    @Test
    @DisplayName("F-018-AC3 복합 PK의 일부인 쪽은 부모가 아니고, 다른 쪽이 PK 전체면 그쪽이 부모다")
    void partialCompositeKeyIsNotParent() {
        String item = table("TB_ORDER_ITEM", "ORDER_ID", "ITEM_SEQ");
        String order = table("TB_ORDER", "ORDER_ID");
        joins(order, item, List.of("ORDER_ID"), List.of("ORDER_ID"), "s1");

        run(new RelationInferenceLinker());

        Edge e = onlyInferred();
        assertThat(e.from()).isEqualTo(item);
        assertThat(e.to()).isEqualTo(order);
        assertThat(e.attrString("cardinality")).isEqualTo("N:1");
    }

    @Test
    @DisplayName("F-018-AC3 복합 PK 전체로 조인하면 그쪽이 부모이고 컬럼 대응 순서를 지킨다")
    void compositeKeyParent() {
        String item = table("TB_ORDER_ITEM", "ORDER_ID", "ITEM_SEQ");
        String opt = table("TB_ITEM_OPTION", "OPTION_ID");
        joins(opt, item, List.of("ORD_ID", "SEQ"), List.of("ORDER_ID", "ITEM_SEQ"), "s1");
        joins(item, opt, List.of("ITEM_SEQ", "ORDER_ID"), List.of("SEQ", "ORD_ID"), "s2");   // 순서만 다른 같은 대응

        run(new RelationInferenceLinker());

        Edge e = onlyInferred();
        assertThat(e.from()).isEqualTo(opt);
        assertThat(e.to()).isEqualTo(item);
        assertThat(e.attrInt("evidenceCount", 0)).isEqualTo(2);
        List<String> columns = e.attrList("columns");
        List<String> refColumns = e.attrList("refColumns");
        assertThat(columns).containsExactlyInAnyOrder("ORD_ID", "SEQ");
        assertThat(refColumns.get(columns.indexOf("ORD_ID"))).isEqualTo("ORDER_ID");
        assertThat(refColumns.get(columns.indexOf("SEQ"))).isEqualTo("ITEM_SEQ");
    }

    @Test
    @DisplayName("F-018-AC3 UNIQUE 컬럼도 키로 보고, PK는 컬럼의 primaryKey 속성에서도 얻는다")
    void uniqueAndColumnLevelKeys() {
        String member = table("TB_MEMBER");
        g.putAttrs(column("TB_MEMBER", "MEMBER_ID", "NUMBER"), Map.of("primaryKey", true));
        g.putAttrs(column("TB_MEMBER", "LOGIN_ID", "VARCHAR2(20)"), Map.of("unique", true));
        String log = table("TB_LOGIN_LOG", "LOG_ID");
        String order = table("TB_ORDER", "ORDER_ID");
        joins(log, member, List.of("LOGIN_ID"), List.of("LOGIN_ID"), "s1");
        joins(member, order, List.of("MEMBER_ID"), List.of("MEMBER_ID"), "s2");

        run(new RelationInferenceLinker());

        assertThat(inferredRefs()).hasSize(2).allMatch(e -> e.to().equals(member) && "N:1".equals(e.attrString("cardinality")));
        assertThat(inferredRefs()).extracting(Edge::from).containsExactlyInAnyOrder(log, order);
    }

    @Test
    @DisplayName("F-018-AC4 양쪽 다 PK면 1:1, 어느 쪽도 아니거나 PK 정보가 없으면 unknown, 방향은 ID 사전순")
    void undirected() {
        String member = table("TB_MEMBER", "MEMBER_ID");
        String detail = table("TB_MEMBER_DETAIL", "MEMBER_ID");
        String a = table("TB_A", "A_ID");
        String b = table("TB_B", "B_ID");
        String x = stubTable("X_LOG");
        String y = stubTable("Y_LOG");
        joins(detail, member, MEMBER_ID, MEMBER_ID, "s1");
        joins(b, a, List.of("CODE"), List.of("CODE"), "s2");
        joins(y, x, List.of("K"), List.of("K"), "s3");

        run(new RelationInferenceLinker());

        Edge oneToOne = only(member, EdgeKind.REFERENCES);
        assertThat(oneToOne.to()).isEqualTo(detail);
        assertThat(oneToOne.attrString("cardinality")).isEqualTo("1:1");
        assertThat(oneToOne.attr("directionKnown")).isEqualTo(false);
        Edge none = only(a, EdgeKind.REFERENCES);
        assertThat(none.to()).isEqualTo(b);
        assertThat(none.attrString("cardinality")).isEqualTo("unknown");
        assertThat(none.attr("directionKnown")).isEqualTo(false);
        Edge noInfo = only(x, EdgeKind.REFERENCES);
        assertThat(noInfo.to()).isEqualTo(y);
        assertThat(noInfo.attrString("cardinality")).isEqualTo("unknown");
        assertThat(noInfo.attr("directionKnown")).isEqualTo(false);
        assertThat(inferredRefs()).hasSize(3).allMatch(RelationInferenceLinkerTest::inferred);
    }

    @Test
    @DisplayName("F-018-AC5 같은 테이블 쌍·같은 컬럼 대응의 DDL REFERENCES가 있으면 추론 엣지를 만들지 않는다")
    void ddlWins() {
        String order = table("TB_ORDER", "ORDER_ID");
        String member = table("TB_MEMBER", "MEMBER_ID");
        String item = table("TB_ORDER_ITEM", "ORDER_ID", "ITEM_SEQ");
        g.addEdge(Edge.builder(order, member, EdgeKind.REFERENCES)
                .attr("source", "DDL").attr("columns", MEMBER_ID).attr("refColumns", MEMBER_ID).build());
        g.addEdge(Edge.builder(item, order, EdgeKind.REFERENCES)   // refColumns 없음: 부모의 PK로 본다
                .attr("source", "DDL").attr("columns", List.of("ORDER_ID")).build());
        joins(member, order, MEMBER_ID, MEMBER_ID, "s1");
        joins(order, member, List.of("REG_ID"), MEMBER_ID, "s2");
        joins(order, item, List.of("ORDER_ID"), List.of("ORDER_ID"), "s3");

        run(new RelationInferenceLinker());

        Edge e = onlyInferred();
        assertThat(e.from()).isEqualTo(order);
        assertThat(e.to()).isEqualTo(member);
        assertThat(e.attrList("columns")).containsExactly("REG_ID");
        assertThat(g.edges(EdgeKind.REFERENCES)).hasSize(3);
    }

    @Test
    @DisplayName("F-018-AC6 자기 조인은 자기 참조 관계가 되고, 같은 컬럼끼리의 자기 조인은 관계가 아니다")
    void selfJoin() {
        String category = table("TB_CATEGORY", "CATEGORY_ID");
        joins(category, category, List.of("PARENT_ID"), List.of("CATEGORY_ID"), "s1");
        joins(category, category, List.of("CATEGORY_ID"), List.of("PARENT_ID"), "s2");   // 좌우 반대
        joins(category, category, List.of("CATEGORY_ID"), List.of("CATEGORY_ID"), "s3");

        run(new RelationInferenceLinker());

        Edge e = onlyInferred();
        assertThat(e.from()).isEqualTo(category);
        assertThat(e.to()).isEqualTo(category);
        assertThat(e.attrList("columns")).containsExactly("PARENT_ID");
        assertThat(e.attrList("refColumns")).containsExactly("CATEGORY_ID");
        assertThat(e.attrInt("evidenceCount", 0)).isEqualTo(2);
        assertThat(e.attrString("cardinality")).isEqualTo("N:1");
    }

    @Test
    @DisplayName("F-018-AC6 declared=false 테이블과의 조인도 관계를 만든다. 뷰와의 조인은 기본으로 만들지 않는다(F-018-AC12)")
    void viewsAndUndeclaredTables() {
        String member = table("TB_MEMBER", "MEMBER_ID");
        String summary = view("VW_ORDER_SUMMARY");
        String ext = stubTable("EXT_POINT");
        joins(summary, member, MEMBER_ID, MEMBER_ID, "s1");
        joins(member, ext, MEMBER_ID, List.of("MBR_ID"), "s2");

        run(new RelationInferenceLinker());

        assertThat(out(summary, EdgeKind.REFERENCES)).isEmpty();
        assertThat(g.incoming(summary, EdgeKind.REFERENCES)).isEmpty();
        Edge fromStub = only(ext, EdgeKind.REFERENCES);
        assertThat(fromStub.to()).isEqualTo(member);
        assertThat(fromStub.attrList("columns")).containsExactly("MBR_ID");
        assertThat(fromStub.attrList("refColumns")).containsExactly("MEMBER_ID");
    }

    @Test
    @DisplayName("F-018-AC7 조인 근거가 없으면(컬럼 이름만 같거나 컬럼 목록이 비면) 관계를 만들지 않는다")
    void noEvidenceNoRelation() {
        String order = table("TB_ORDER", "ORDER_ID");
        String member = table("TB_MEMBER", "MEMBER_ID");
        column("TB_ORDER", "MEMBER_ID", "NUMBER");
        column("TB_MEMBER", "MEMBER_ID", "NUMBER");
        g.addEdge(Edge.builder(order, member, EdgeKind.JOINS).attr("statement", "s1").build());
        joins(order, "unresolved:sql-dynamic:s2", MEMBER_ID, MEMBER_ID, "s2");

        run(new RelationInferenceLinker());

        assertThat(g.edges(EdgeKind.REFERENCES)).isEmpty();
    }

    @Test
    @DisplayName("F-018-AC8 근거가 1건이어도 만들고, link.minJoinEvidence보다 적으면 만들지 않는다")
    void minEvidence() {
        buildEvidenceGraph();
        run(new RelationInferenceLinker());
        assertThat(inferredRefs()).extracting(e -> e.attrInt("evidenceCount", 0)).containsExactlyInAnyOrder(1, 2);

        project.options(AnalysisOptions.builder().extra("link.minJoinEvidence", "2").build());
        rescan();
        buildEvidenceGraph();
        run(new RelationInferenceLinker());
        assertThat(onlyInferred().attrInt("evidenceCount", 0)).isEqualTo(2);
        assertThat(onlyInferred().from()).isEqualTo(Ids.table("TB_ORDER"));
    }

    @Test
    @DisplayName("F-018-AC8 link.minJoinEvidence가 숫자가 아니면 WARN 진단을 남기고 1로 본다")
    void invalidMinEvidence() {
        project.options(AnalysisOptions.builder().extra("link.minJoinEvidence", "many").build());
        rescan();
        buildEvidenceGraph();

        run(new RelationInferenceLinker());

        assertThat(inferredRefs()).hasSize(2);
        assertThat(diagnostics("INVALID_OPTION")).hasSize(1).allMatch(d -> d.severity() == Severity.WARN);
    }

    private void buildEvidenceGraph() {
        String order = table("TB_ORDER", "ORDER_ID");
        String member = table("TB_MEMBER", "MEMBER_ID");
        String pay = table("TB_PAYMENT", "PAYMENT_ID");
        joins(order, member, MEMBER_ID, MEMBER_ID, "s1");
        joins(order, member, MEMBER_ID, MEMBER_ID, "s2");
        joins(pay, order, List.of("ORDER_ID"), List.of("ORDER_ID"), "s3");
    }

    @Test
    @DisplayName("F-018-AC9 자료형 계열이 다른 컬럼 간 조인은 typeMismatch=true")
    void typeMismatch() {
        String order = table("TB_ORDER", "ORDER_ID");
        String member = table("TB_MEMBER", "MEMBER_ID");
        String pay = table("TB_PAYMENT", "PAYMENT_ID");
        column("TB_ORDER", "MEMBER_ID", "VARCHAR2(10)");
        column("TB_MEMBER", "MEMBER_ID", "NUMBER(10)");
        column("TB_ORDER", "ORDER_ID", "NUMBER(10)");
        column("TB_PAYMENT", "ORDER_ID", "INTEGER");
        joins(order, member, MEMBER_ID, MEMBER_ID, "s1");
        joins(pay, order, List.of("ORDER_ID"), List.of("ORDER_ID"), "s2");

        run(new RelationInferenceLinker());

        assertThat(only(order, EdgeKind.REFERENCES).attr("typeMismatch")).isEqualTo(true);
        assertThat(only(pay, EdgeKind.REFERENCES).attrs()).doesNotContainKey("typeMismatch");
    }

    @Test
    @DisplayName("F-018-AC6 한 테이블 쌍에 컬럼 대응이 다른 관계가 여럿이어도 각각 기록된다")
    void multipleRelationsBetweenSamePair() {
        String order = table("TB_ORDER", "ORDER_ID");
        String member = table("TB_MEMBER", "MEMBER_ID");
        // 같은 위치(같은 구문의 같은 줄)에서 나온 두 조인
        var where = loc();
        g.addEdge(Edge.builder(order, member, EdgeKind.JOINS).location(where)
                .attr("leftColumns", List.of("BUYER_ID")).attr("rightColumns", MEMBER_ID).attr("statement", "s1").build());
        g.addEdge(Edge.builder(member, order, EdgeKind.JOINS).location(where)
                .attr("leftColumns", MEMBER_ID).attr("rightColumns", List.of("SELLER_ID")).attr("statement", "s1").build());
        joins(order, member, List.of("SELLER_ID"), MEMBER_ID, "s2");

        run(new RelationInferenceLinker());

        assertThat(inferredRefs()).hasSize(2);
        assertThat(inferredRefs()).extracting(e -> e.attrList("columns").get(0)).containsExactlyInAnyOrder("BUYER_ID", "SELLER_ID");
    }

    @Test
    @DisplayName("F-018-AC6 정규화 링커 뒤에 실행되어 시노님 경유 조인이 대상 테이블의 관계가 된다")
    void runsAfterNormalization() {
        project.options(AnalysisOptions.builder().extra("link.inferViewRelations", "true").build());
        rescan();
        String order = table("TB_ORDER", "ORDER_ID");
        String member = table("TB_MEMBER", "MEMBER_ID");
        stubTable("SYN_ORDER");
        synonym("SYN_ORDER", order);
        view("VW_MEMBER");
        stubTable("VW_MEMBER");
        joins(Ids.table("SYN_ORDER"), member, MEMBER_ID, MEMBER_ID, "s1");
        joins(Ids.table("VW_MEMBER"), order, MEMBER_ID, MEMBER_ID, "s2");

        run(new RelationInferenceLinker(), new DbObjectNormalizer());

        assertThat(g.edges(order, EdgeKind.REFERENCES, member)).hasSize(1);
        assertThat(g.edges(EdgeKind.REFERENCES)).hasSize(2)
                .anyMatch(e -> e.from().equals(Ids.view("VW_MEMBER")) || e.to().equals(Ids.view("VW_MEMBER")));
        assertThat(g.nodes(NodeKind.DB_TABLE)).extracting(Node::id).containsExactlyInAnyOrder(order, member);
    }

    // ------------------------------------------------------------------ F-018-R9: 복합 키의 일부를 상수로 고정한 조인

    /** 고정 컬럼 attrs가 붙은 JOINS(F-006-R13). values의 원소는 COL=값 형태다. */
    private Edge joinsFixed(String left, String right, List<String> leftColumns, List<String> rightColumns, String statement,
            List<String> leftFixedValues, List<String> rightFixedValues) {
        Edge.Builder b = Edge.builder(left, right, EdgeKind.JOINS).location(loc())
                .attr("leftColumns", leftColumns).attr("rightColumns", rightColumns).attr("statement", statement);
        if (!leftFixedValues.isEmpty()) {
            b.attr("leftFixedColumns", leftFixedValues.stream().map(v -> v.substring(0, v.indexOf('='))).distinct().toList());
            b.attr("leftFixedValues", leftFixedValues);
        }
        if (!rightFixedValues.isEmpty()) {
            b.attr("rightFixedColumns", rightFixedValues.stream().map(v -> v.substring(0, v.indexOf('='))).distinct().toList());
            b.attr("rightFixedValues", rightFixedValues);
        }
        return g.addEdge(b.build());
    }

    @Test
    @DisplayName("F-018-AC10 공통코드 테이블: 조인 컬럼과 상수로 고정된 컬럼이 부모의 복합 PK 전체를 채우면 N:1로 확정하고 fixedColumns·fixedValues를 남긴다")
    void compositeKeyCompletedByConstants() {
        String order = table("TB_ORDER", "ORDER_ID");
        String code = table("TB_CODE", "GROUP_CD", "CODE");
        Edge first = joinsFixed(order, code, List.of("ORDER_STATUS"), List.of("CODE"), "s1",
                List.of(), List.of("GROUP_CD=ORDER_STATUS", "USE_YN=Y"));
        // 좌우가 반대인 근거, 값이 다른 근거, 바인드로 고정한 근거
        joinsFixed(code, order, List.of("CODE"), List.of("ORDER_STATUS"), "s2", List.of("GROUP_CD=ORD_ST_OLD"), List.of("ORDER_ID=?"));
        joinsFixed(order, code, List.of("ORDER_STATUS"), List.of("CODE"), "s3", List.of(), List.of("GROUP_CD=?"));

        run(new RelationInferenceLinker());

        Edge e = onlyInferred();
        assertThat(e.from()).isEqualTo(order);
        assertThat(e.to()).isEqualTo(code);
        assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(e.attrList("columns")).containsExactly("ORDER_STATUS");
        assertThat(e.attrList("refColumns")).containsExactly("CODE");
        assertThat(e.attrString("cardinality")).isEqualTo("N:1");
        assertThat(e.attr("directionKnown")).isEqualTo(true);
        // 2026-10-11 고쳐 씀(F-018-R12): 바인드로만 고정한 근거(s3)는 N:1 근거로 세지 않는다(전에는 3건, GROUP_CD=? 포함)
        assertThat(e.attrInt("evidenceCount", 0)).isEqualTo(2);
        // 부모 키를 채운 고정 컬럼만 적는다(USE_YN은 키가 아니다)
        assertThat(e.attrList("fixedColumns")).containsExactly("GROUP_CD");
        assertThat(e.attrList("fixedValues")).containsExactly("GROUP_CD=ORDER_STATUS", "GROUP_CD=ORD_ST_OLD");
        assertThat(e.location()).isEqualTo(first.location());
    }

    @Test
    @DisplayName("F-018-AC10 근거 구문마다 고정 컬럼이 다르면 조건을 채운 구문만 N:1 근거로 세고, 하나도 없으면 전과 같이 unknown이다")
    void onlyStatementsWithConstantsCount() {
        String payment = table("TB_PAYMENT", "PAYMENT_ID");
        String code = table("TB_CODE", "GROUP_CD", "CODE");
        String hist = table("TB_ORDER_HIST", "HIST_ID");
        joins(payment, code, List.of("PAY_METHOD_CD"), List.of("CODE"), "noConstant");
        Edge counted = joinsFixed(code, payment, List.of("CODE"), List.of("PAY_METHOD_CD"), "withConstant", List.of("GROUP_CD=PAY_METHOD"), List.of());
        joinsFixed(payment, code, List.of("PAY_METHOD_CD"), List.of("CODE"), "otherColumn", List.of(), List.of("USE_YN=Y"));
        joinsFixed(hist, code, List.of("ORDER_STATUS"), List.of("CODE"), "histOnly", List.of(), List.of("USE_YN=Y"));

        run(new RelationInferenceLinker());

        Edge pay = only(payment, EdgeKind.REFERENCES);
        assertThat(pay.to()).isEqualTo(code);
        assertThat(pay.attrString("cardinality")).isEqualTo("N:1");
        assertThat(pay.attrInt("evidenceCount", 0)).isEqualTo(1);
        assertThat(pay.attrList("evidence")).containsExactly("withConstant");
        assertThat(pay.attrList("fixedValues")).containsExactly("GROUP_CD=PAY_METHOD");
        assertThat(pay.location()).isEqualTo(counted.location());

        Edge unknown = g.edges(EdgeKind.REFERENCES).stream().filter(e -> e.from().equals(hist) || e.to().equals(hist)).findFirst().orElseThrow();
        assertThat(unknown.attrString("cardinality")).isEqualTo("unknown");
        assertThat(unknown.attr("directionKnown")).isEqualTo(false);
        assertThat(unknown.attrs()).doesNotContainKeys("fixedColumns", "fixedValues");
        assertThat(inferredRefs()).hasSize(2);
    }

    @Test
    @DisplayName("F-018-AC10 상수 없이도 PK 전체인 쪽이 있으면 그쪽이 부모다(자식의 복합 PK를 상수로 채운 조인이 방향을 흐리지 않는다). 양쪽 다 상수로 채워야 키면 1:1이다")
    void plainKeyWinsOverConstantCompletedKey() {
        String order = table("TB_ORDER", "ORDER_ID");
        String item = table("TB_ORDER_ITEM", "ORDER_ID", "ITEM_SEQ");
        joinsFixed(order, item, List.of("ORDER_ID"), List.of("ORDER_ID"), "firstItem", List.of(), List.of("ITEM_SEQ=1"));
        String a = table("TB_A", "K", "TYPE_CD");
        String b = table("TB_B");
        g.putAttrs(b, Map.of("uniqueKeys", List.of("K,KIND_CD")));
        joinsFixed(a, b, List.of("K"), List.of("K"), "both", List.of("TYPE_CD=X"), List.of("KIND_CD=Y"));

        run(new RelationInferenceLinker());

        Edge itemToOrder = only(item, EdgeKind.REFERENCES);
        assertThat(itemToOrder.to()).isEqualTo(order);
        assertThat(itemToOrder.attrString("cardinality")).isEqualTo("N:1");
        assertThat(itemToOrder.attr("directionKnown")).isEqualTo(true);
        assertThat(itemToOrder.attrs()).doesNotContainKeys("fixedColumns", "fixedValues");

        Edge oneToOne = only(a, EdgeKind.REFERENCES);
        assertThat(oneToOne.to()).isEqualTo(b);
        assertThat(oneToOne.attrString("cardinality")).isEqualTo("1:1");
        assertThat(oneToOne.attr("directionKnown")).isEqualTo(false);
    }

    @Test
    @DisplayName("F-018-AC10 link.minJoinEvidence는 조건을 채운 근거 구문 수로 따진다")
    void minEvidenceCountsQualifyingStatements() {
        project.options(AnalysisOptions.builder().extra("link.minJoinEvidence", "2").build());
        rescan();
        String order = table("TB_ORDER", "ORDER_ID");
        String code = table("TB_CODE", "GROUP_CD", "CODE");
        joinsFixed(order, code, List.of("ORDER_STATUS"), List.of("CODE"), "s1", List.of(), List.of("GROUP_CD=ORDER_STATUS"));
        joins(order, code, List.of("ORDER_STATUS"), List.of("CODE"), "s2");

        run(new RelationInferenceLinker());

        // 조건을 채운 구문은 1건뿐이라 N:1로 확정하지 못한다. 전체 근거(2건)로 방향 미상 관계만 남는다
        Edge e = onlyInferred();
        assertThat(e.attrString("cardinality")).isEqualTo("unknown");
        assertThat(e.attrInt("evidenceCount", 0)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ F-018-R10: 형제 조인

    @Test
    @DisplayName("F-018-AC11 양쪽 조인 컬럼이 같은 부모의 같은 컬럼을 참조하는 형제 조인(둘 다 키가 아님)은 관계로 만들지 않는다")
    void siblingJoinIsNotARelation() {
        String order = table("TB_ORDER", "ORDER_ID");
        String item = table("TB_ORDER_ITEM", "ORDER_ID", "ITEM_SEQ");
        String hist = table("TB_ORDER_HIST", "HIST_ID");
        String product = table("TB_PRODUCT", "PRODUCT_ID");
        String sales = table("TB_DAILY_SALES", "SALES_DT", "PRODUCT_ID");
        // 형제 조인이 부모 조인보다 먼저 나와도(추론 순서) 걸러야 한다
        joins(hist, item, List.of("ORDER_ID"), List.of("ORDER_ID"), "siblings1");
        joins(sales, item, List.of("PRODUCT_ID"), List.of("PRODUCT_ID"), "siblings2");
        g.addEdge(Edge.builder(item, order, EdgeKind.REFERENCES)   // DDL FK, refColumns 생략(부모의 PK)
                .attr("source", "DDL").attr("columns", List.of("ORDER_ID")).build());
        g.addEdge(Edge.builder(item, product, EdgeKind.REFERENCES)
                .attr("source", "DDL").attr("columns", List.of("PRODUCT_ID")).attr("refColumns", List.of("PRODUCT_ID")).build());
        joins(order, hist, List.of("ORDER_ID"), List.of("ORDER_ID"), "parent1");      // 이번 실행에서 N:1로 확정되는 추론
        joins(product, sales, List.of("PRODUCT_ID"), List.of("PRODUCT_ID"), "parent2");

        run(new RelationInferenceLinker());

        assertThat(inferredRefs()).hasSize(2).allMatch(e -> "N:1".equals(e.attrString("cardinality")));
        assertThat(g.edges(hist, EdgeKind.REFERENCES, order)).hasSize(1);
        assertThat(g.edges(sales, EdgeKind.REFERENCES, product)).hasSize(1);
        assertThat(g.edges(hist, EdgeKind.REFERENCES, item)).isEmpty();
        assertThat(g.edges(item, EdgeKind.REFERENCES, hist)).isEmpty();
        assertThat(g.edges(sales, EdgeKind.REFERENCES, item)).isEmpty();
        assertThat(g.edges(item, EdgeKind.REFERENCES, sales)).isEmpty();
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-018-AC11 부모가 둘 다 추론(공통코드 포함)이어도 형제 조인을 거르고, 여러 컬럼 조인은 모든 컬럼이 같은 부모를 가리킬 때만 거른다")
    void siblingsThroughInferredParents() {
        String code = table("TB_CODE", "GROUP_CD", "CODE");
        String order = table("TB_ORDER", "ORDER_ID");
        String hist = table("TB_ORDER_HIST", "HIST_ID");
        joinsFixed(order, code, List.of("ORDER_STATUS"), List.of("CODE"), "p1", List.of(), List.of("GROUP_CD=ORDER_STATUS"));
        joinsFixed(hist, code, List.of("ORDER_STATUS"), List.of("CODE"), "p2", List.of(), List.of("GROUP_CD=ORDER_STATUS"));
        joins(order, hist, List.of("ORDER_STATUS"), List.of("ORDER_STATUS"), "siblings");
        // 두 컬럼 조인: STATUS는 같은 부모를 가리키지만 REG_ID는 아니다 → 형제 조인이라고 할 수 없다
        String claim = table("TB_CLAIM", "CLAIM_ID");
        joinsFixed(claim, code, List.of("ORDER_STATUS"), List.of("CODE"), "p3", List.of(), List.of("GROUP_CD=ORDER_STATUS"));
        joins(claim, hist, List.of("ORDER_STATUS", "REG_ID"), List.of("ORDER_STATUS", "REG_ID"), "notSiblings");

        run(new RelationInferenceLinker());

        assertThat(g.edges(order, EdgeKind.REFERENCES, hist)).isEmpty();
        assertThat(g.edges(hist, EdgeKind.REFERENCES, order)).isEmpty();
        assertThat(inferredRefs()).filteredOn(e -> e.to().equals(code)).hasSize(3);
        List<Edge> kept = g.edges(claim, EdgeKind.REFERENCES, hist);
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).attrString("cardinality")).isEqualTo("unknown");
        assertThat(kept.get(0).attrList("columns")).containsExactlyInAnyOrder("ORDER_STATUS", "REG_ID");
    }

    @Test
    @DisplayName("F-018-AC11 한쪽만 부모를 참조하거나 서로 다른 부모·다른 컬럼을 참조하면 전과 같이 방향 미상 관계로 둔다")
    void notSiblingsStayUnknown() {
        String order = table("TB_ORDER", "ORDER_ID");
        String member = table("TB_MEMBER", "MEMBER_ID");
        String item = table("TB_ORDER_ITEM", "ORDER_ID", "ITEM_SEQ");
        String memo = table("TB_MEMO", "MEMO_ID");
        String log = table("TB_LOG", "LOG_ID");
        g.addEdge(Edge.builder(item, order, EdgeKind.REFERENCES)
                .attr("source", "DDL").attr("columns", List.of("ORDER_ID")).attr("refColumns", List.of("ORDER_ID")).build());
        g.addEdge(Edge.builder(log, member, EdgeKind.REFERENCES)
                .attr("source", "DDL").attr("columns", List.of("REF_ID")).attr("refColumns", List.of("MEMBER_ID")).build());
        joins(memo, item, List.of("ORDER_ID"), List.of("ORDER_ID"), "oneSide");          // TB_MEMO.ORDER_ID는 부모를 모른다
        joins(log, item, List.of("REF_ID"), List.of("ORDER_ID"), "differentParents");    // TB_MEMBER vs TB_ORDER

        run(new RelationInferenceLinker());

        assertThat(inferredRefs()).hasSize(2).allMatch(e -> "unknown".equals(e.attrString("cardinality")));
        assertThat(inferredRefs()).allMatch(e -> e.from().equals(item) || e.to().equals(item));
    }

    // ------------------------------------------------------------------ F-018-R11: 뷰가 낀 조인

    private void buildViewGraph() {
        String policy = table("LETTNLOGINPOLICY", "EMPLYR_ID");
        String master = view("COMVNUSERMASTER");
        String bbs = table("LETTNBBS", "NTT_ID");
        joins(master, policy, List.of("USER_ID"), List.of("EMPLYR_ID"), "s1");
        joins(bbs, master, List.of("FRST_REGISTER_ID"), List.of("ESNTL_ID"), "s2");
    }

    @Test
    @DisplayName("F-018-AC12 뷰가 낀 조인은 기본으로 관계를 만들지 않고, link.inferViewRelations=true일 때만 방향 미상(directionKnown=false)으로 만든다")
    void viewRelationsOnlyWithOption() {
        buildViewGraph();
        run(new RelationInferenceLinker());
        assertThat(inferredRefs()).as("키 정보가 없는 뷰와의 조인으로는 관계를 만들지 않는다").isEmpty();

        project.options(AnalysisOptions.builder().extra("link.inferViewRelations", "true").build());
        rescan();
        buildViewGraph();
        run(new RelationInferenceLinker());

        assertThat(inferredRefs()).hasSize(2).allMatch(e -> !e.attrBoolean("directionKnown") && "unknown".equals(e.attrString("cardinality")));
        Edge withPolicy = inferredRefs().stream().filter(e -> e.from().equals(Ids.table("LETTNLOGINPOLICY"))).findFirst().orElseThrow();
        assertThat(withPolicy.to()).as("방향은 ID 사전순이다(뷰를 자식으로 단정하지 않는다)").isEqualTo(Ids.view("COMVNUSERMASTER"));
        assertThat(withPolicy.attrList("columns")).containsExactly("EMPLYR_ID");
        assertThat(withPolicy.attrList("refColumns")).containsExactly("USER_ID");
    }

    @Test
    @DisplayName("F-018-AC12 키 정보가 없는 테이블은 유일 쪽(부모)으로 판정하지 않는다: 상대가 PK 전체면 그쪽이 부모, 아니면 unknown")
    void keylessTableIsNeverParent() {
        String member = table("TB_MEMBER", "MEMBER_ID");
        String ext = stubTable("EXT_POINT");
        String log = stubTable("EXT_LOG");
        joinsFixed(ext, member, List.of("MBR_ID"), MEMBER_ID, "s1", List.of("KIND=A"), List.of());
        joinsFixed(ext, log, List.of("MBR_ID"), List.of("MBR_ID"), "s2", List.of("KIND=A"), List.of("KIND=B"));

        run(new RelationInferenceLinker());

        Edge toMember = only(ext, EdgeKind.REFERENCES).to().equals(member) ? only(ext, EdgeKind.REFERENCES) : null;
        assertThat(toMember).isNotNull();
        assertThat(toMember.attrString("cardinality")).isEqualTo("N:1");
        assertThat(toMember.attrBoolean("directionKnown")).isTrue();
        Edge between = inferredRefs().stream().filter(e -> !e.to().equals(member)).findFirst().orElseThrow();
        assertThat(between.attrBoolean("directionKnown")).isFalse();
        assertThat(between.attrString("cardinality")).isEqualTo("unknown");
    }

    // ------------------------------------------------------------------ F-018-R12~R14: 방향을 잘못 확정하던 경우(실전 재검증 N14)

    @Test
    @DisplayName("F-018-AC13 바인드와의 등치(COL=?)로만 키의 나머지를 채운 조인은 N:1로 확정하지 않는다: 방향 미상(unknown)이고 fixedColumns가 없다")
    void bindEqualityDoesNotCompleteKey() {
        String bbs = table("COMTNBBS", "NTT_ID", "BBS_ID");
        String file = table("COMTNFILEDETAIL", "ATCH_FILE_ID", "FILE_SN");
        joinsFixed(bbs, file, List.of("ATCH_FILE_ID"), List.of("ATCH_FILE_ID"), "s1", List.of(), List.of("FILE_SN=?"));
        // 리터럴과 바인드가 섞여도 키의 나머지 컬럼마다 리터럴이 있어야 한다
        String a = table("TB_A", "A_ID");
        String code = table("TB_CODE", "GROUP_CD", "LANG", "CODE");
        joinsFixed(a, code, List.of("ST"), List.of("CODE"), "s2", List.of(), List.of("GROUP_CD=ST", "LANG=?"));

        run(new RelationInferenceLinker());

        assertThat(inferredRefs()).hasSize(2).allSatisfy(e -> {
            assertThat(e.attrString("cardinality")).isEqualTo("unknown");
            assertThat(e.attr("directionKnown")).isEqualTo(false);
            assertThat(e.attrs()).doesNotContainKeys("fixedColumns", "fixedValues");
        });
    }

    @Test
    @DisplayName("F-018-AC16 같은 테이블 쌍에 DDL REFERENCES가 한 방향으로만 있고 추론 관계의 방향을 정하지 못하면 DDL FK의 방향을 따른다(directionKnown=true, directionFrom=DDL). 컬럼 대응이 DDL과 같으면 전처럼 만들지 않는다")
    void undirectedInferenceFollowsDeclaredDirection() {
        String bbs = table("COMTNBBS", "NTT_ID", "BBS_ID");
        String comment = table("COMTNCOMMENT", "NTT_ID", "BBS_ID", "ANSWER_NO");
        // DDL: COMMENT(NTT_ID, BBS_ID) → BBS(NTT_ID, BBS_ID)
        g.addEdge(Edge.builder(comment, bbs, EdgeKind.REFERENCES).location(loc())
                .attr("columns", List.of("NTT_ID", "BBS_ID")).attr("refColumns", List.of("NTT_ID", "BBS_ID")).build());
        // 어느 쪽도 키가 아닌 조인(NTT_ID만): 방향 미상이라 사전순(BBS → COMMENT)으로 뒤집혀 나오던 것
        joins(bbs, comment, List.of("NTT_ID"), List.of("NTT_ID"), "s1");
        // DDL과 컬럼 대응이 같은 조인은 추론 엣지를 만들지 않는다
        joins(comment, bbs, List.of("NTT_ID", "BBS_ID"), List.of("NTT_ID", "BBS_ID"), "s2");
        // DDL이 없는 쌍의 방향 미상은 전과 같다
        String a = table("TB_A", "A_ID");
        String b = table("TB_B", "B_ID");
        joins(b, a, List.of("X"), List.of("Y"), "s3");

        run(new RelationInferenceLinker());

        assertThat(inferredRefs()).hasSize(2);
        Edge followed = inferredRefs().stream().filter(e -> e.attrList("columns").equals(List.of("NTT_ID"))).findFirst().orElseThrow();
        assertThat(followed.from()).isEqualTo(comment);
        assertThat(followed.to()).isEqualTo(bbs);
        assertThat(followed.attr("directionKnown")).isEqualTo(true);
        assertThat(followed.attrString("directionFrom")).isEqualTo("DDL");
        assertThat(followed.attrString("cardinality")).isEqualTo("unknown");
        Edge plain = inferredRefs().stream().filter(e -> e != followed).findFirst().orElseThrow();
        assertThat(plain.attr("directionKnown")).isEqualTo(false);
        assertThat(plain.attrs()).doesNotContainKey("directionFrom");
    }

    @Test
    @DisplayName("F-018-AC14 같은 테이블 쌍에 DDL REFERENCES가 있으면(컬럼 대응이 달라도) 반대 방향의 추론은 확정하지 않고(directionKnown=false, declaredOpposite=true), 같은 방향이면 그대로 만든다")
    void declaredRelationBlocksOppositeInference() {
        String order = table("TB_ORDER", "ORDER_ID");
        String item = table("TB_ITEM", "ITEM_ID");
        // DDL: ITEM.ORDER_ID → ORDER.ORDER_ID
        g.addEdge(Edge.builder(item, order, EdgeKind.REFERENCES).location(loc())
                .attr("columns", List.of("ORDER_ID")).attr("refColumns", List.of("ORDER_ID")).build());
        // 반대 방향으로 판정될 조인: ORDER.LAST_ITEM_ID = ITEM.ITEM_ID(ITEM의 PK 전체) → ORDER → ITEM
        joins(order, item, List.of("LAST_ITEM_ID"), List.of("ITEM_ID"), "s1");
        // 같은 방향으로 판정될 조인: ITEM.PREV_ORDER_ID = ORDER.ORDER_ID → ITEM → ORDER
        joins(item, order, List.of("PREV_ORDER_ID"), List.of("ORDER_ID"), "s2");

        run(new RelationInferenceLinker());

        Edge opposite = inferredRefs().stream().filter(e -> e.attrList("columns").contains("LAST_ITEM_ID")
                || e.attrList("refColumns").contains("LAST_ITEM_ID")).findFirst().orElseThrow();
        assertThat(opposite.attr("directionKnown")).isEqualTo(false);
        assertThat(opposite.attrString("cardinality")).isEqualTo("unknown");
        assertThat(opposite.attrBoolean("declaredOpposite")).isTrue();
        Edge same = inferredRefs().stream().filter(e -> e.attrList("columns").contains("PREV_ORDER_ID")).findFirst().orElseThrow();
        assertThat(same.from()).isEqualTo(item);
        assertThat(same.to()).isEqualTo(order);
        assertThat(same.attr("directionKnown")).isEqualTo(true);
        assertThat(same.attrs()).doesNotContainKey("declaredOpposite");
        assertThat(inferredRefs()).hasSize(2);
    }

    @Test
    @DisplayName("F-018-AC15 키가 아닌데 세 개 이상의 테이블과 방향 미상으로 조인되는 컬럼(사실상 키)은 자식 쪽으로 확정하지 않는다: directionKnown=false, pseudoKey=true")
    void pseudoKeyColumnIsNotAChild() {
        String employee = table("TB_EMP", "EMP_ID");
        String member = table("TB_MEMBER", "ESNTL_ID");
        String bbs = table("TB_BBS", "BBS_ID");
        String note = table("TB_NOTE", "NOTE_ID");
        String memo = table("TB_MEMO", "MEMO_ID");
        // EMP.ESNTL_ID는 EMP의 키가 아니지만 여러 테이블이 등록자 ID로 조인한다
        joins(bbs, employee, List.of("REGISTER_ID"), List.of("ESNTL_ID"), "s1");
        joins(note, employee, List.of("REGISTER_ID"), List.of("ESNTL_ID"), "s2");
        joins(memo, employee, List.of("REGISTER_ID"), List.of("ESNTL_ID"), "s3");
        // 상대가 PK 전체라 전에는 EMP → MEMBER(N:1)로 확정됐다
        joins(employee, member, List.of("ESNTL_ID"), List.of("ESNTL_ID"), "s4");
        // 보통의 FK 컬럼(조인 상대가 하나)은 그대로 확정된다
        String order = table("TB_ORDER", "ORDER_ID");
        joins(order, member, List.of("MEMBER_ID"), List.of("ESNTL_ID"), "s5");

        run(new RelationInferenceLinker());

        Edge pseudo = inferredRefs().stream().filter(e -> (e.from().equals(employee) && e.to().equals(member))
                || (e.from().equals(member) && e.to().equals(employee))).findFirst().orElseThrow();
        assertThat(pseudo.attr("directionKnown")).isEqualTo(false);
        assertThat(pseudo.attrString("cardinality")).isEqualTo("unknown");
        assertThat(pseudo.attrBoolean("pseudoKey")).isTrue();
        Edge normal = only(order, EdgeKind.REFERENCES);
        assertThat(normal.to()).isEqualTo(member);
        assertThat(normal.attr("directionKnown")).isEqualTo(true);
        assertThat(normal.attrs()).doesNotContainKey("pseudoKey");
        assertThat(inferredRefs()).hasSize(5);
    }
}
