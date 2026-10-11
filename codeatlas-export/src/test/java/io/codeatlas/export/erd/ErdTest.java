package io.codeatlas.export.erd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.erd.ErdModel.Cardinality;
import io.codeatlas.export.erd.ErdModel.Column;
import io.codeatlas.export.erd.ErdModel.Entity;
import io.codeatlas.export.erd.ErdModel.Relation;
import io.codeatlas.export.erd.ErdOptions.ColumnLevel;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.MermaidSyntax;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErdTest {

    /** CUSTOMER ← ORDERS ← ORDER_ITEM, 컬럼은 일부러 ordinal 역순으로 넣는다. */
    static GraphFixture shop() {
        GraphFixture f = GraphFixture.create();
        f.table("ORDERS", "주문");
        f.column("ORDERS", "STATUS", "VARCHAR2(20 CHAR)", 3, "nullable", "comment=주문 상태");
        f.column("ORDERS", "CUSTOMER_ID", "NUMBER(10)", 2, "notnull");
        f.column("ORDERS", "ORDER_ID", "NUMBER(10)", 1, "pk", "notnull", "comment=주문 ID");
        f.table("CUSTOMER", "고객");
        f.column("CUSTOMER", "CUSTOMER_ID", "NUMBER(10)", 1, "pk", "notnull");
        f.column("CUSTOMER", "NAME", "VARCHAR2(100)", 2, "notnull");
        f.fk("ORDERS", "CUSTOMER", "FK_ORDERS_CUSTOMER", List.of("CUSTOMER_ID"), List.of("CUSTOMER_ID"));
        return f;
    }

    @Test
    @DisplayName("F-013-AC1 테이블·컬럼·관계가 중간 모델로 나온다 (이름순, ordinal순)")
    void buildsModel() {
        ErdModel model = Erd.model(shop().graph(), ErdOptions.defaults());

        assertThat(model.entities()).extracting(Entity::name).containsExactly("CUSTOMER", "ORDERS");
        Entity orders = model.entity("ORDERS").orElseThrow();
        assertThat(orders.comment()).isEqualTo("주문");
        assertThat(orders.declared()).isTrue();
        assertThat(orders.columns()).extracting(Column::name).containsExactly("ORDER_ID", "CUSTOMER_ID", "STATUS");
        assertThat(orders.columns().get(0).primaryKey()).isTrue();
        assertThat(orders.columns().get(1).foreignKey()).isTrue();
        assertThat(orders.columns().get(2).nullable()).isTrue();
        assertThat(orders.columns().get(2).dataType()).isEqualTo("VARCHAR2(20 CHAR)");

        assertThat(model.relations()).hasSize(1);
        Relation r = model.relations().get(0);
        assertThat(r.child()).isEqualTo("ORDERS");
        assertThat(r.parent()).isEqualTo("CUSTOMER");
        assertThat(r.columns()).containsExactly("CUSTOMER_ID");
        assertThat(r.refColumns()).containsExactly("CUSTOMER_ID");
        assertThat(r.constraint()).isEqualTo("FK_ORDERS_CUSTOMER");
        assertThat(r.inferred()).isFalse();
    }

    @Test
    @DisplayName("F-013-AC2 Mermaid erDiagram에 타입, 이름, PK/FK, 주석, NOT NULL이 나온다")
    void rendersMermaid() {
        String out = Erd.render(shop().graph(), ErdOptions.defaults(), ErdFormat.MERMAID);

        assertThat(out).startsWith("erDiagram\n");
        assertThat(out).contains("    ORDERS {\n");
        assertThat(out).contains("        NUMBER(10) ORDER_ID PK \"주문 ID\"\n");
        assertThat(out).contains("        NUMBER(10) CUSTOMER_ID FK \"NOT NULL\"\n");
        assertThat(out).contains("        VARCHAR2(20_CHAR) STATUS \"주문 상태\"\n");
        assertThat(out).contains("    CUSTOMER ||--o{ ORDERS : \"FK_ORDERS_CUSTOMER\"\n");
        assertThat(MermaidSyntax.checkEr(out)).isEmpty();

        String noComments = Erd.render(shop().graph(), ErdOptions.builder().showComments(false).build(), ErdFormat.MERMAID);
        assertThat(noComments).doesNotContain("주문 ID").contains("NUMBER(10) CUSTOMER_ID FK \"NOT NULL\"");
    }

    @Test
    @DisplayName("F-013-AC3 PlantUML entity에 NOT NULL(*)과 PK 구분선이 나온다")
    void rendersPlantUml() {
        String out = Erd.render(shop().graph(), ErdOptions.defaults(), ErdFormat.PLANTUML);

        assertThat(out).startsWith("@startuml\n").endsWith("@enduml\n");
        assertThat(out).contains("entity \"ORDERS\\n(주문)\" as ORDERS {\n"
                + "  * ORDER_ID : NUMBER(10) <<PK>> -- 주문 ID\n"
                + "  --\n"
                + "  * CUSTOMER_ID : NUMBER(10) <<FK>>\n"
                + "  STATUS : VARCHAR2(20 CHAR) -- 주문 상태\n"
                + "}\n");
        assertThat(out).contains("CUSTOMER ||--o{ ORDERS : FK_ORDERS_CUSTOMER\n");
    }

    @Test
    @DisplayName("F-013-AC4 DBML에 Table, pk/not null, Ref(복합 FK 포함)가 나온다")
    void rendersDbml() {
        GraphFixture f = shop();
        f.table("ORDER_ITEM");
        f.column("ORDER_ITEM", "ORDER_ID", "NUMBER(10)", 1, "pk", "notnull");
        f.column("ORDER_ITEM", "SEQ", "NUMBER(3)", 2, "pk", "notnull");
        f.table("ITEM_OPTION");
        f.column("ITEM_OPTION", "ORDER_ID", "NUMBER(10)", 1, "notnull");
        f.column("ITEM_OPTION", "SEQ", "NUMBER(3)", 2, "notnull");
        f.fk("ITEM_OPTION", "ORDER_ITEM", "FK_OPT_ITEM", List.of("ORDER_ID", "SEQ"), List.of("ORDER_ID", "SEQ"));

        String out = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.DBML);

        assertThat(out).contains("Table ORDERS [note: '주문'] {\n");
        assertThat(out).contains("  ORDER_ID NUMBER(10) [pk, not null, note: '주문 ID']\n");
        assertThat(out).contains("  STATUS \"VARCHAR2(20 CHAR)\" [note: '주문 상태']\n");
        assertThat(out).contains("Ref FK_ORDERS_CUSTOMER: ORDERS.CUSTOMER_ID > CUSTOMER.CUSTOMER_ID\n");
        assertThat(out).contains("Ref FK_OPT_ITEM: ITEM_OPTION.(ORDER_ID, SEQ) > ORDER_ITEM.(ORDER_ID, SEQ)\n");
    }

    @Test
    @DisplayName("F-013-AC5 DDL 관계는 실선, 추론 관계는 점선과 (추론 n건), 방향 미상은 카디널리티 없는 선")
    void distinguishesInferredRelations() {
        GraphFixture f = shop();
        f.table("COUPON");
        f.column("COUPON", "COUPON_ID", "NUMBER", 1, "pk", "notnull");
        f.column("COUPON", "CUSTOMER_ID", "NUMBER", 2, "notnull");
        f.inferredFk("COUPON", "CUSTOMER", List.of("CUSTOMER_ID"), List.of("CUSTOMER_ID"), 3, true);
        f.table("LOG_A");
        f.column("LOG_A", "TX_ID", "NUMBER", 1);
        f.table("LOG_B");
        f.column("LOG_B", "TX_ID", "NUMBER", 1);
        f.inferredFk("LOG_A", "LOG_B", List.of("TX_ID"), List.of("TX_ID"), 2, false);

        String mermaid = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.MERMAID);
        assertThat(mermaid).contains("CUSTOMER ||--o{ ORDERS : \"FK_ORDERS_CUSTOMER\"");
        assertThat(mermaid).contains("CUSTOMER |o..o{ COUPON : \"CUSTOMER_ID (추론 3건)\"");
        assertThat(mermaid).contains("LOG_B }o..o{ LOG_A : \"TX_ID (추론 2건, 방향 미상)\"");
        assertThat(MermaidSyntax.checkEr(mermaid)).isEmpty();

        String puml = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.PLANTUML);
        assertThat(puml).contains("CUSTOMER ||--o{ ORDERS : FK_ORDERS_CUSTOMER");
        assertThat(puml).contains("CUSTOMER |o..o{ COUPON : CUSTOMER_ID (추론 3건)");
        assertThat(puml).contains("LOG_B .. LOG_A : TX_ID (추론 2건, 방향 미상)");

        String dbml = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.DBML);
        assertThat(dbml).contains("Ref: COUPON.CUSTOMER_ID > CUSTOMER.CUSTOMER_ID // 추론 3건");
        assertThat(dbml).contains("Ref: LOG_A.TX_ID <> LOG_B.TX_ID // 추론 2건, 방향 미상");
    }

    @Test
    @DisplayName("F-013-AC6 카디널리티: PK 전체·UNIQUE면 1:1, nullable이면 부모 0..1, 아니면 N:1, cardinality 속성 우선")
    void computesCardinality() {
        GraphFixture f = GraphFixture.create();
        f.table("P");
        f.column("P", "ID", "NUMBER", 1, "pk", "notnull");
        f.table("ONE_TO_ONE");
        f.column("ONE_TO_ONE", "ID", "NUMBER", 1, "pk", "notnull");
        f.fk("ONE_TO_ONE", "P", "FK1", List.of("ID"), List.of("ID"));
        f.table("UNIQ");
        f.column("UNIQ", "ID", "NUMBER", 1, "pk", "notnull");
        f.column("UNIQ", "P_ID", "NUMBER", 2, "unique", "notnull");
        f.fk("UNIQ", "P", "FK2", List.of("P_ID"), List.of("ID"));
        f.table("OPTIONAL");
        f.column("OPTIONAL", "ID", "NUMBER", 1, "pk", "notnull");
        f.column("OPTIONAL", "P_ID", "NUMBER", 2, "nullable");
        f.fk("OPTIONAL", "P", "FK3", List.of("P_ID"), List.of("ID"));
        f.table("MANY");
        f.column("MANY", "ID", "NUMBER", 1, "pk", "notnull");
        f.column("MANY", "P_ID", "NUMBER", 2, "notnull");
        f.fk("MANY", "P", "FK4", List.of("P_ID"), List.of("ID"));
        f.table("PART_OF_PK");
        f.column("PART_OF_PK", "P_ID", "NUMBER", 1, "pk", "notnull");
        f.column("PART_OF_PK", "SEQ", "NUMBER", 2, "pk", "notnull");
        f.fk("PART_OF_PK", "P", "FK5", List.of("P_ID"), List.of("ID"));
        f.table("EXPLICIT");
        f.column("EXPLICIT", "P_ID", "NUMBER", 1, "notnull");
        f.edge(Edge.builder(Ids.table("EXPLICIT"), Ids.table("P"), EdgeKind.REFERENCES)
                .attr("columns", List.of("P_ID")).attr("refColumns", List.of("ID")).attr("source", "DDL")
                .attr("cardinality", "1:1").build());
        // DDL 관계의 cardinality가 unknown이면 컬럼 정보로 계산한다(외래 키는 부모 쪽이 유일하다).
        // 추론 관계의 unknown은 계산하지 않는다: F-013-AC30
        f.table("UNKNOWN_CARD");
        f.column("UNKNOWN_CARD", "P_ID", "NUMBER", 1, "nullable");
        f.edge(Edge.builder(Ids.table("UNKNOWN_CARD"), Ids.table("P"), EdgeKind.REFERENCES)
                .attr("columns", List.of("P_ID")).attr("refColumns", List.of("ID")).attr("source", "DDL")
                .attr("cardinality", "unknown").build());
        // 테이블 속성의 primaryKey, uniqueKeys(복합 UNIQUE)
        String composite = f.table("COMPOSITE_UK");
        f.column("COMPOSITE_UK", "K1", "NUMBER", 1);
        f.column("COMPOSITE_UK", "A", "NUMBER", 2, "notnull");
        f.column("COMPOSITE_UK", "B", "NUMBER", 3, "notnull");
        f.attr(composite, "primaryKey", List.of("K1")).attr(composite, "uniqueKeys", List.of("A,B"));
        f.table("P2");
        f.column("P2", "A", "NUMBER", 1, "pk");
        f.column("P2", "B", "NUMBER", 2, "pk");
        f.fk("COMPOSITE_UK", "P2", "FK6", List.of("A", "B"), List.of("A", "B"));

        ErdModel model = Erd.model(f.graph(), ErdOptions.defaults());
        assertThat(sides(model, "UNKNOWN_CARD")).containsExactly(Cardinality.MANY, Cardinality.ZERO_OR_ONE);
        assertThat(sides(model, "COMPOSITE_UK")).containsExactly(Cardinality.ZERO_OR_ONE, Cardinality.ONE);
        assertThat(model.entity("COMPOSITE_UK").orElseThrow().column("K1").orElseThrow().primaryKey()).isTrue();

        assertThat(sides(model, "ONE_TO_ONE")).containsExactly(Cardinality.ZERO_OR_ONE, Cardinality.ONE);
        assertThat(sides(model, "UNIQ")).containsExactly(Cardinality.ZERO_OR_ONE, Cardinality.ONE);
        assertThat(sides(model, "OPTIONAL")).containsExactly(Cardinality.MANY, Cardinality.ZERO_OR_ONE);
        assertThat(sides(model, "MANY")).containsExactly(Cardinality.MANY, Cardinality.ONE);
        assertThat(sides(model, "PART_OF_PK")).containsExactly(Cardinality.MANY, Cardinality.ONE);
        assertThat(sides(model, "EXPLICIT")).containsExactly(Cardinality.ZERO_OR_ONE, Cardinality.ONE);

        String mermaid = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.MERMAID);
        assertThat(mermaid).contains("P ||--o| ONE_TO_ONE : \"FK1\"");
        assertThat(mermaid).contains("P |o--o{ OPTIONAL : \"FK3\"");
        String dbml = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.DBML);
        assertThat(dbml).contains("Ref FK1: ONE_TO_ONE.ID - P.ID");
    }

    private static List<Cardinality> sides(ErdModel model, String child) {
        Relation r = model.relations().stream().filter(x -> x.child().equals(child)).findFirst().orElseThrow();
        return List.of(r.childSide(), r.parentSide());
    }

    @Test
    @DisplayName("F-013-AC7 declared=false 테이블은 표식과 함께, 참조된 컬럼만 나온다")
    void marksUndeclaredTables() {
        GraphFixture f = shop();
        String stmt = f.statement("com.shop.OrderMapper", "selectHist", "SELECT");
        f.reads(stmt, "ORDERS_HIST", "ORDER_ID");
        f.reads(stmt, "ORDERS", "ORDER_ID");

        ErdModel model = Erd.model(f.graph(), ErdOptions.defaults());
        Entity hist = model.entity("ORDERS_HIST").orElseThrow();
        assertThat(hist.declared()).isFalse();
        assertThat(hist.columns()).extracting(Column::name).containsExactly("ORDER_ID");
        assertThat(hist.columns().get(0).referencedOnly()).isTrue();

        String mermaid = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.MERMAID);
        assertThat(mermaid).contains("    \"ORDERS_HIST (DDL 없음)\" {\n        unknown ORDER_ID \"참조된 컬럼\"\n    }\n");
        assertThat(MermaidSyntax.checkEr(mermaid)).isEmpty();
        assertThat(Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.PLANTUML))
                .contains("entity \"ORDERS_HIST (DDL 없음)\" as ORDERS_HIST <<undeclared>> {\n  ORDER_ID : unknown\n}");
        assertThat(Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.DBML))
                .contains("Table ORDERS_HIST [note: 'DDL 없음 (SQL에서만 참조)'] {\n  ORDER_ID unknown [note: '참조된 컬럼']\n}");
    }

    /** A → B → C 사슬과 무관한 D. */
    private static GraphFixture chain() {
        GraphFixture f = GraphFixture.create();
        for (String t : List.of("A_T", "B_T", "C_T", "D_T")) {
            f.table(t);
            f.column(t, "ID", "NUMBER", 1, "pk", "notnull");
        }
        f.column("A_T", "B_ID", "NUMBER", 2);
        f.column("B_T", "C_ID", "NUMBER", 2);
        f.fk("A_T", "B_T", "FK_A_B", List.of("B_ID"), List.of("ID"));
        f.fk("B_T", "C_T", "FK_B_C", List.of("C_ID"), List.of("ID"));
        return f;
    }

    @Test
    @DisplayName("F-013-AC8 범위: 테이블 목록 + 이웃 깊이, 이름 glob")
    void scopesByTablesAndPatterns() {
        GraphModel g = chain().graph();

        assertThat(names(Erd.model(g, ErdOptions.builder().tables("a_t").neighborDepth(1).build()))).containsExactly("A_T", "B_T");
        assertThat(names(Erd.model(g, ErdOptions.builder().tables("A_T").neighborDepth(2).build()))).containsExactly("A_T", "B_T", "C_T");
        assertThat(names(Erd.model(g, ErdOptions.builder().tables("db:table:C_T").neighborDepth(0).build()))).containsExactly("C_T");
        assertThat(names(Erd.model(g, ErdOptions.builder().patterns("C*", "d_?").build()))).containsExactly("C_T", "D_T");
        assertThat(names(Erd.model(g, ErdOptions.defaults()))).containsExactly("A_T", "B_T", "C_T", "D_T");
        // 범위 밖 테이블로 가는 관계는 그리지 않는다
        assertThat(Erd.model(g, ErdOptions.builder().tables("A_T").neighborDepth(1).build()).relations())
                .extracting(Relation::constraint).containsExactly("FK_A_B");
    }

    private static List<String> names(ErdModel model) {
        return model.entities().stream().map(Entity::name).toList();
    }

    @Test
    @DisplayName("F-013-AC9 옵션: 추론 관계 제외, 최소 evidenceCount, 뷰 포함, 컬럼 표시 수준")
    void filtersByOptions() {
        GraphFixture f = chain();
        f.inferredFk("D_T", "A_T", List.of("ID"), List.of("ID"), 1, true);
        f.inferredFk("D_T", "C_T", List.of("ID"), List.of("ID"), 3, true);
        f.view("V_ALL");
        f.column("V_ALL", "ID", "NUMBER", 1);
        GraphModel g = f.graph();

        assertThat(Erd.model(g, ErdOptions.defaults()).relations()).hasSize(4);
        assertThat(Erd.model(g, ErdOptions.builder().includeInferred(false).build()).relations()).hasSize(2)
                .allMatch(r -> !r.inferred());
        assertThat(Erd.model(g, ErdOptions.builder().minEvidence(2).build()).relations())
                .filteredOn(Relation::inferred).extracting(Relation::parent).containsExactly("C_T");
        // 추론 관계를 빼면 이웃 탐색도 그 관계를 타지 않는다
        assertThat(names(Erd.model(g, ErdOptions.builder().tables("D_T").neighborDepth(1).minEvidence(2).build())))
                .containsExactly("C_T", "D_T");

        assertThat(names(Erd.model(g, ErdOptions.defaults()))).doesNotContain("V_ALL");
        ErdModel withViews = Erd.model(g, ErdOptions.builder().includeViews(true).build());
        assertThat(withViews.entity("V_ALL").orElseThrow().kind()).isEqualTo(ErdModel.EntityKind.VIEW);
        assertThat(Erd.render(g, ErdOptions.builder().includeViews(true).build(), ErdFormat.PLANTUML))
                .contains("entity \"V_ALL\" as V_ALL <<view>> {");
        String viewMermaid = Erd.render(g, ErdOptions.builder().includeViews(true).build(), ErdFormat.MERMAID);
        assertThat(viewMermaid).contains("    \"V_ALL (VIEW)\" {\n");
        assertThat(MermaidSyntax.checkEr(viewMermaid)).isEmpty();
        assertThat(Erd.render(g, ErdOptions.builder().includeViews(true).build(), ErdFormat.DBML)).contains("Table V_ALL [note: 'VIEW'] {");

        ErdModel keys = Erd.model(g, ErdOptions.builder().columnLevel(ColumnLevel.KEYS).build());
        assertThat(keys.entity("A_T").orElseThrow().columns()).extracting(Column::name).containsExactly("ID", "B_ID");
        assertThat(keys.entity("C_T").orElseThrow().columns()).extracting(Column::name).containsExactly("ID");
        ErdModel none = Erd.model(g, ErdOptions.builder().columnLevel(ColumnLevel.NONE).build());
        assertThat(none.entities()).allMatch(e -> e.columns().isEmpty());
        String mermaid = Erd.render(g, ErdOptions.builder().columnLevel(ColumnLevel.NONE).build(), ErdFormat.MERMAID);
        assertThat(mermaid).contains("    A_T\n").doesNotContain("{\n        ");
        assertThat(MermaidSyntax.checkEr(mermaid)).isEmpty();
    }

    @Test
    @DisplayName("F-013-AC10 한글·$·#·공백 식별자와 괄호·쉼표·공백이 든 타입을 형식별로 이스케이프한다")
    void escapesIdentifiersAndTypes() {
        GraphFixture f = GraphFixture.create();
        f.table("\"주문 내역\"", "따옴표 \"안\" 주석");
        f.column("\"주문 내역\"", "\"주문번호\"", "NUMBER(10,2)", 1, "pk", "notnull");
        f.column("\"주문 내역\"", "REG_DT", "TIMESTAMP WITH TIME ZONE", 2, "notnull");
        f.column("\"주문 내역\"", "MEMO", "VARCHAR2(20 CHAR)", 3, "comment=쉼표, \"따옴표\"\n줄바꿈");
        f.table("SYS$LOG#1");
        f.column("SYS$LOG#1", "COL#1", "NUMBER(10, 2)", 1, "pk", "notnull");
        f.column("SYS$LOG#1", "REF$ID", "NUMBER(10,2)", 2);
        f.column("SYS$LOG#1", "1ST", "9TYPE", 3);
        f.fk("SYS$LOG#1", "\"주문 내역\"", "FK \"X\"", List.of("REF$ID"), List.of("주문번호"));

        String mermaid = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.MERMAID);
        assertThat(MermaidSyntax.checkEr(mermaid)).isEmpty();
        assertThat(mermaid).contains("    \"주문 내역\" {\n");
        assertThat(mermaid).contains("NUMBER(10_2) col1 PK \"주문번호\"");
        assertThat(mermaid).contains("TIMESTAMP_WITH_TIME_ZONE REG_DT \"NOT NULL\"");
        assertThat(mermaid).contains("VARCHAR2(20_CHAR) MEMO \"쉼표, '따옴표' 줄바꿈\"");
        assertThat(mermaid).contains("    \"SYS$LOG#1\" {\n");
        assertThat(mermaid).contains("NUMBER(10_2) COL_1 PK \"COL#1\"");
        assertThat(mermaid).contains("NUMBER(10_2) REF_ID FK \"REF$ID\"");
        assertThat(mermaid).contains("_9TYPE _1ST \"1ST\"");
        assertThat(mermaid).contains("\"주문 내역\" ||--o{ \"SYS$LOG#1\" : \"FK 'X'\"");

        String puml = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.PLANTUML);
        assertThat(puml).contains("entity \"주문 내역\\n(따옴표 '안' 주석)\" as 주문_내역 {");
        assertThat(puml).contains("  * 주문번호 : NUMBER(10,2) <<PK>>");
        assertThat(puml).contains("entity \"SYS$LOG#1\" as SYS_LOG_1 {");
        assertThat(puml).contains("주문_내역 ||--o{ SYS_LOG_1 : FK 'X'");

        String dbml = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.DBML);
        assertThat(dbml).contains("Table \"주문 내역\" [note: '따옴표 \"안\" 주석'] {");
        assertThat(dbml).contains("  \"주문번호\" NUMBER(10,2) [pk, not null]");
        assertThat(dbml).contains("  REG_DT \"TIMESTAMP WITH TIME ZONE\" [not null]");
        assertThat(dbml).contains("  MEMO \"VARCHAR2(20 CHAR)\" [note: '쉼표, \"따옴표\" 줄바꿈']");
        assertThat(dbml).contains("Table \"SYS$LOG#1\" {");
        assertThat(dbml).contains("  \"COL#1\" \"NUMBER(10, 2)\" [pk, not null]");
        assertThat(dbml).contains("Ref \"FK 'X'\": \"SYS$LOG#1\".\"REF$ID\" > \"주문 내역\".\"주문번호\"");
    }

    @Test
    @DisplayName("F-013-AC11 입력 순서가 달라도 세 형식의 출력이 같다")
    void deterministic() {
        GraphFixture f = shop();
        f.inferredFk("ORDERS", "CUSTOMER", List.of("STATUS"), List.of("NAME"), 2, true);
        f.undeclaredTable("ZZ_TEMP");
        GraphModel reversed = f.reversedCopy();

        for (ErdFormat format : ErdFormat.values()) {
            assertThat(Erd.render(reversed, ErdOptions.defaults(), format))
                    .as(format.name())
                    .isEqualTo(Erd.render(f.graph(), ErdOptions.defaults(), format));
        }
        assertThat(TableSpecRenderer.render(reversed)).isEqualTo(TableSpecRenderer.render(f.graph()));
    }

    @Test
    @DisplayName("F-013-AC12 속성이 없는 컬럼·관계, 끝이 없는 관계에서도 예외 없이 그린다")
    void toleratesMissingAttributes() {
        GraphFixture f = GraphFixture.create();
        f.table("T1");
        f.bareColumn("T1", "C1");
        f.table("T2");
        f.edge(Ids.table("T1"), Ids.table("T2"), EdgeKind.REFERENCES);
        f.edge(Ids.table("T1"), Ids.table("NOWHERE"), EdgeKind.REFERENCES);
        f.edge(Ids.table("T2"), "unresolved:table:X", EdgeKind.REFERENCES);
        GraphModel g = f.graph();

        for (ErdFormat format : ErdFormat.values()) {
            assertThatCode(() -> Erd.render(g, ErdOptions.defaults(), format)).as(format.name()).doesNotThrowAnyException();
        }
        assertThatCode(() -> TableSpecRenderer.render(g)).doesNotThrowAnyException();
        ErdModel model = Erd.model(g, ErdOptions.defaults());
        assertThat(model.relations()).hasSize(1);
        assertThat(model.relations().get(0).columns()).isEmpty();
        String mermaid = Erd.render(g, ErdOptions.defaults(), ErdFormat.MERMAID);
        assertThat(mermaid).contains("unknown C1\n").contains("T2 ||--o{ T1 : \"\"");
        assertThat(MermaidSyntax.checkEr(mermaid)).isEmpty();
        assertThat(Erd.render(g, ErdOptions.defaults(), ErdFormat.DBML)).contains("// Ref(컬럼 미상): T1 > T2");
        assertThat(Erd.render(g, ErdOptions.defaults(), ErdFormat.PLANTUML)).contains("T2 ||--o{ T1\n");
    }

    @Test
    @DisplayName("F-013-AC13 테이블 명세에 컬럼 표, PK, 관계, 참조하는 구문 수가 있다")
    void rendersTableSpec() {
        GraphFixture f = shop();
        String s1 = f.statement("com.shop.OrderMapper", "selectOrder", "SELECT");
        String s2 = f.statement("com.shop.OrderMapper", "updateStatus", "UPDATE");
        f.reads(s1, "ORDERS", "ORDER_ID", "STATUS");
        f.reads(s1, "CUSTOMER");
        f.writes(s2, "ORDERS", "UPDATE", "STATUS");
        f.undeclaredTable("TMP_X");

        String md = TableSpecRenderer.render(f.graph());

        assertThat(md).startsWith("# 테이블 명세\n");
        assertThat(md).contains("## ORDERS\n");
        assertThat(md).contains("| 1 | ORDER_ID | NUMBER(10) | N | PK |  | 주문 ID |");
        assertThat(md).contains("| 2 | CUSTOMER_ID | NUMBER(10) | N | FK |  |  |");
        assertThat(md).contains("| 3 | STATUS | VARCHAR2(20 CHAR) | Y |  |  | 주문 상태 |");
        assertThat(md).contains("- PK: ORDER_ID");
        assertThat(md).contains("- 참조하는 구문: 2개 (읽기 1, 쓰기 1)");
        assertThat(md).contains("- → CUSTOMER (CUSTOMER_ID → CUSTOMER_ID) `FK_ORDERS_CUSTOMER`");
        assertThat(md).contains("- ← ORDERS (CUSTOMER_ID → CUSTOMER_ID) `FK_ORDERS_CUSTOMER`");
        assertThat(md).contains("## TMP_X (DDL 없음)\n");

        // 정의 위치는 다른 산출물과 같이 file:line으로 적는다(열은 적지 않는다)
        f.graph().declare(Node.builder(Ids.table("LOCATED"), NodeKind.DB_TABLE).name("LOCATED")
                .location(SourceLocation.of("db/oracle/01_tables.sql", 113, 1, 120, 3)).build());
        assertThat(TableSpecRenderer.render(f.graph())).contains("## LOCATED\n\n- 정의: `db/oracle/01_tables.sql:113`\n");
    }

    @Test
    @DisplayName("F-013-AC16 테이블 명세의 범위: 테이블 목록 + 이웃 깊이, 이름 glob, 뷰 포함을 ERD와 같은 규칙으로 적용한다")
    void scopesTableSpec() {
        GraphFixture f = chain();
        f.view("V_A");
        f.column("V_A", "ID", "NUMBER", 1);
        GraphModel g = f.graph();

        String one = TableSpecRenderer.render(g, ErdOptions.builder().tables("a_t").neighborDepth(1).build());
        assertThat(sections(one)).containsExactly("A_T", "B_T");
        assertThat(one).contains("- 범위: 테이블 a_t, 이웃 깊이 1").contains("- 테이블 2개").contains("- → B_T");
        // 범위 밖 테이블(C_T)로 가는 관계는 적지 않고, 그 사실을 머리에 알린다
        assertThat(one).doesNotContain("C_T").contains("범위 밖 테이블과의 관계는 적지 않는다");

        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.builder().tables("A_T").neighborDepth(0).build()))).containsExactly("A_T");
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.builder().tables("A_T").neighborDepth(2).build())))
                .containsExactly("A_T", "B_T", "C_T");
        String byPattern = TableSpecRenderer.render(g, ErdOptions.builder().patterns("C*", "d_?").build());
        assertThat(sections(byPattern)).containsExactly("C_T", "D_T");
        assertThat(byPattern).contains("- 범위: 이름 패턴 C*, d_?");

        // 뷰는 includeViews를 줘야 범위에 든다(ERD와 같다)
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.defaults()))).containsExactly("A_T", "B_T", "C_T", "D_T");
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.builder().includeViews(true).build())))
                .containsExactly("A_T", "B_T", "C_T", "D_T", "V_A (VIEW)");
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.builder().tables("V_A").includeViews(true).build())))
                .containsExactly("V_A (VIEW)");
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.builder().tables("V_A").build()))).isEmpty();

        // 컬럼 표시 수준은 명세에 적용하지 않는다(컬럼 표는 항상 전부)
        assertThat(TableSpecRenderer.render(g, ErdOptions.builder().tables("A_T").columnLevel(ColumnLevel.NONE).build()))
                .contains("| # | 컬럼 | 타입 |");
        // 옵션 없는 render(graph)는 그대로 전체 테이블과 뷰다
        assertThat(TableSpecRenderer.render(g)).isEqualTo(TableSpecRenderer.render(g, ErdOptions.builder().includeViews(true).build()))
                .doesNotContain("- 범위:");
        assertThat(TableSpecRenderer.render(g, null)).isEqualTo(TableSpecRenderer.render(g, ErdOptions.defaults()));
    }

    private static List<String> sections(String markdown) {
        return markdown.lines().filter(line -> line.startsWith("## ")).map(line -> line.substring(3)).toList();
    }

    @Test
    @DisplayName("F-013-AC17 시작 테이블 이름 중 그래프에서 찾지 못한 것을 모델과 함께 돌려준다. 뷰라서 빠진 이름은 따로 알 수 있다")
    void reportsUnresolvedTableNames() {
        GraphFixture f = chain();
        f.view("V_A");
        GraphModel g = f.graph();

        ErdModel model = Erd.model(g, ErdOptions.builder().tables("a_t", "NOPE", "db:table:GONE", "V_A", " ", "NOPE").neighborDepth(0).build());
        assertThat(names(model)).containsExactly("A_T");
        assertThat(model.unresolvedNames()).containsExactly("NOPE", "db:table:GONE", "V_A");
        assertThat(model.excludedViewNames()).containsExactly("V_A");

        ErdModel withViews = Erd.model(g, ErdOptions.builder().tables("NOPE", "v_a").neighborDepth(0).includeViews(true).build());
        assertThat(names(withViews)).containsExactly("V_A");
        assertThat(withViews.unresolvedNames()).containsExactly("NOPE");
        assertThat(withViews.excludedViewNames()).isEmpty();

        assertThat(Erd.model(g, ErdOptions.defaults()).unresolvedNames()).isEmpty();
        assertThat(Erd.model(g, ErdOptions.builder().patterns("ZZ*").build()).unresolvedNames()).isEmpty();
        // 예전 생성자로 만든 모델은 빈 목록이다
        assertThat(new ErdModel(List.of(), List.of()).unresolvedNames()).isEmpty();
        assertThat(new ErdModel(List.of(), List.of()).excludedViewNames()).isEmpty();
    }

    @Test
    @DisplayName("F-013-AC21 방향을 거둔 추론 관계(directionKnown=false와 함께 온 declaredOpposite·pseudoKey)는 라벨에 까닭을 적는다")
    void labelsWhyDirectionIsUnknown() {
        GraphFixture f = GraphFixture.create();
        for (String t : List.of("A_T", "B_T", "C_T", "D_T")) {
            f.table(t);
            f.column(t, "ID", "NUMBER", 1, "pk");
        }
        for (String[] r : new String[][] {{"A_T", "B_T", "declaredOpposite", "2"}, {"C_T", "D_T", "pseudoKey", "3"}}) {
            f.edge(Edge.builder(Ids.table(r[0]), Ids.table(r[1]), EdgeKind.REFERENCES).inferred().attr("columns", List.of("ID"))
                    .attr("refColumns", List.of("ID")).attr("source", "INFERRED").attr("evidenceCount", Integer.parseInt(r[3]))
                    .attr("directionKnown", false).attr("cardinality", "unknown").attr(r[2], true).build());
        }
        f.inferredFk("A_T", "C_T", List.of("ID"), List.of("ID"), 1, false);

        String mermaid = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.MERMAID);

        assertThat(mermaid).contains("\"ID (추론 2건, 방향 미상: 선언된 FK와 반대)\"").contains("\"ID (추론 3건, 방향 미상: 유일하지 않은 키)\"")
                .contains("\"ID (추론 1건, 방향 미상)\"");
    }

    @Test
    @DisplayName("F-013-AC23 upgrade 스크립트의 ALTER TABLE에서 온 외래 키(fromUpgrade=true)는 DDL 관계(실선)로 그리되 라벨과 테이블 명세에 (upgrade 스크립트)를 적는다")
    void marksForeignKeysFromUpgradeScripts() {
        GraphFixture f = GraphFixture.create();
        for (String t : List.of("ORDERS", "CUSTOMER", "COUPON", "STORE")) {
            f.table(t);
            f.column(t, t + "_ID", "NUMBER", 1, "pk");
        }
        f.column("ORDERS", "CUSTOMER_ID", "NUMBER", 2, "notnull");
        f.column("ORDERS", "COUPON_ID", "NUMBER", 3);
        f.column("ORDERS", "STORE_ID", "NUMBER", 4);
        f.fk("ORDERS", "CUSTOMER", "FK_ORDERS_CUSTOMER", List.of("CUSTOMER_ID"), List.of("CUSTOMER_ID"));
        f.edge(Edge.builder(Ids.table("ORDERS"), Ids.table("COUPON"), EdgeKind.REFERENCES).attr("columns", List.of("COUPON_ID"))
                .attr("refColumns", List.of("COUPON_ID")).attr("constraint", "FK_ORDERS_COUPON").attr("source", "DDL")
                .attr("fromUpgrade", true).build());
        // 제약 이름이 없는 것
        f.edge(Edge.builder(Ids.table("ORDERS"), Ids.table("STORE"), EdgeKind.REFERENCES).attr("columns", List.of("STORE_ID"))
                .attr("refColumns", List.of("STORE_ID")).attr("source", "DDL").attr("fromUpgrade", true).build());
        GraphModel g = f.graph();

        String mermaid = Erd.render(g, ErdOptions.defaults(), ErdFormat.MERMAID);
        assertThat(mermaid).contains("--o{ ORDERS : \"FK_ORDERS_COUPON (upgrade 스크립트)\"").contains("--o{ ORDERS : \"STORE_ID (upgrade 스크립트)\"")
                .contains("--o{ ORDERS : \"FK_ORDERS_CUSTOMER\"").doesNotContain("..o{");
        assertThat(MermaidSyntax.checkEr(mermaid)).isEmpty();
        assertThat(Erd.model(g, ErdOptions.defaults()).relations()).extracting(ErdModel.Relation::fromUpgrade).containsExactly(true, false, true);
        assertThat(Erd.render(g, ErdOptions.defaults(), ErdFormat.PLANTUML)).contains("FK_ORDERS_COUPON (upgrade 스크립트)");
        assertThat(Erd.render(g, ErdOptions.defaults(), ErdFormat.DBML)).contains("// upgrade 스크립트").containsOnlyOnce("Ref FK_ORDERS_COUPON");
        String spec = TableSpecRenderer.render(g);
        assertThat(spec).contains("- → COUPON (COUPON_ID → COUPON_ID) `FK_ORDERS_COUPON` (upgrade 스크립트)")
                .contains("- ← ORDERS (COUPON_ID → COUPON_ID) `FK_ORDERS_COUPON` (upgrade 스크립트)")
                .contains("- → STORE (STORE_ID → STORE_ID) (upgrade 스크립트)")
                .contains("- → CUSTOMER (CUSTOMER_ID → CUSTOMER_ID) `FK_ORDERS_CUSTOMER`\n");
    }

    @Test
    @DisplayName("F-013-AC28 테이블 명세는 시작 테이블(ErdOptions.tables)을 준 순서대로 맨 앞에, 이웃을 그 뒤에 이름순으로 낸다. 범위가 전체이거나 패턴뿐이면 이름순이다")
    void putsStartTablesFirstInTableSpec() {
        GraphFixture f = chain();
        f.view("V_A");
        GraphModel g = f.graph();

        // C_T의 이웃은 B_T, A_T의 이웃도 B_T다: 시작 테이블 C_T, A_T가 준 순서대로 앞에 오고 이웃 B_T가 뒤에 온다
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.builder().tables("C_T", "A_T").neighborDepth(1).build())))
                .containsExactly("C_T", "A_T", "B_T");
        // 대소문자를 달리 적거나 노드 ID로 줘도, 같은 테이블을 두 번 줘도 같다. 찾지 못한 이름은 건너뛴다
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.builder().tables("NOPE", "c_t", "db:table:B_T", "C_T").neighborDepth(1).build())))
                .containsExactly("C_T", "B_T", "A_T");
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.builder().tables("D_T", "B_T").neighborDepth(1).build())))
                .containsExactly("D_T", "B_T", "A_T", "C_T");
        // 뷰를 시작 테이블로 주면(뷰 포함) 뷰가 앞에 온다
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.builder().tables("V_A", "A_T").neighborDepth(0).includeViews(true).build())))
                .containsExactly("V_A (VIEW)", "A_T");
        // 시작 테이블과 패턴을 함께 주면 시작 테이블이 앞, 나머지(이웃과 패턴에 맞은 것)는 이름순
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.builder().tables("C_T").neighborDepth(0).patterns("A*", "D*").build())))
                .containsExactly("C_T", "A_T", "D_T");
        // 범위가 전체이거나 패턴뿐이면 전처럼 이름순이다
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.builder().patterns("C*", "A*").build()))).containsExactly("A_T", "C_T");
        assertThat(sections(TableSpecRenderer.render(g, ErdOptions.defaults()))).containsExactly("A_T", "B_T", "C_T", "D_T");
        // ERD 본체(모델)의 순서는 그대로 이름순이다
        assertThat(names(Erd.model(g, ErdOptions.builder().tables("C_T", "A_T").neighborDepth(1).build()))).containsExactly("A_T", "B_T", "C_T");
    }

    @Test
    @DisplayName("F-013-AC29 현재 정의에 없는 upgrade 스크립트의 외래 키(fromUpgrade=true이면서 INFERRED)는 점선으로 그리고 라벨과 테이블 명세에 (upgrade 스크립트, 현재 정의에 없음)을 적는다")
    void marksUpgradeForeignKeysMissingFromCurrentDefinition() {
        GraphFixture f = GraphFixture.create();
        for (String t : List.of("ORDERS", "CUSTOMER", "COUPON", "STORE", "REGION")) {
            f.table(t);
            f.column(t, t + "_ID", "NUMBER", 1, "pk");
        }
        f.column("ORDERS", "CUSTOMER_ID", "NUMBER", 2, "notnull");
        f.column("ORDERS", "COUPON_ID", "NUMBER", 3);
        f.column("ORDERS", "STORE_ID", "NUMBER", 4);
        f.column("ORDERS", "REGION_ID", "NUMBER", 5);
        f.fk("ORDERS", "CUSTOMER", "FK_ORDERS_CUSTOMER", List.of("CUSTOMER_ID"), List.of("CUSTOMER_ID"));
        // source=INFERRED로 온 것(제약 이름 있음)
        f.edge(Edge.builder(Ids.table("ORDERS"), Ids.table("COUPON"), EdgeKind.REFERENCES).inferred().attr("columns", List.of("COUPON_ID"))
                .attr("refColumns", List.of("COUPON_ID")).attr("constraint", "FK_ORDERS_COUPON").attr("source", "INFERRED")
                .attr("fromUpgrade", true).build());
        // source 없이 엣지 신뢰도만 INFERRED인 것(제약 이름 없음)
        f.edge(Edge.builder(Ids.table("ORDERS"), Ids.table("STORE"), EdgeKind.REFERENCES).inferred().attr("columns", List.of("STORE_ID"))
                .attr("refColumns", List.of("STORE_ID")).attr("fromUpgrade", true).build());
        // source는 DDL인데 엣지 신뢰도가 INFERRED인 것
        f.edge(Edge.builder(Ids.table("ORDERS"), Ids.table("REGION"), EdgeKind.REFERENCES).inferred().attr("columns", List.of("REGION_ID"))
                .attr("refColumns", List.of("REGION_ID")).attr("constraint", "FK_ORDERS_REGION").attr("source", "DDL")
                .attr("fromUpgrade", true).build());
        GraphModel g = f.graph();

        String mermaid = Erd.render(g, ErdOptions.defaults(), ErdFormat.MERMAID);
        assertThat(mermaid).contains("COUPON |o..o{ ORDERS : \"FK_ORDERS_COUPON (upgrade 스크립트, 현재 정의에 없음)\"")
                .contains("STORE |o..o{ ORDERS : \"STORE_ID (upgrade 스크립트, 현재 정의에 없음)\"")
                .contains("REGION |o..o{ ORDERS : \"FK_ORDERS_REGION (upgrade 스크립트, 현재 정의에 없음)\"")
                .contains("--o{ ORDERS : \"FK_ORDERS_CUSTOMER\"").doesNotContain("(추론)").doesNotContain("건)").doesNotContain("건,");
        assertThat(MermaidSyntax.checkEr(mermaid)).isEmpty();
        // 현재 정의의 FK가 아니므로 컬럼에 FK 표시를 하지 않는다
        assertThat(mermaid).contains("NUMBER CUSTOMER_ID FK").contains("NUMBER COUPON_ID\n").contains("NUMBER REGION_ID\n");
        assertThat(Erd.model(g, ErdOptions.defaults()).relations()).extracting(r -> r.parent() + " " + r.inferred() + " " + r.fromUpgrade())
                .containsExactly("COUPON true true", "CUSTOMER false false", "REGION true true", "STORE true true");
        assertThat(Erd.render(g, ErdOptions.defaults(), ErdFormat.PLANTUML)).contains("..o{ ORDERS : FK_ORDERS_COUPON (upgrade 스크립트, 현재 정의에 없음)");
        assertThat(Erd.render(g, ErdOptions.defaults(), ErdFormat.DBML)).contains("// upgrade 스크립트, 현재 정의에 없음").doesNotContain("Ref FK_ORDERS_COUPON");
        String spec = TableSpecRenderer.render(g);
        assertThat(spec).contains("- → COUPON (COUPON_ID → COUPON_ID) `FK_ORDERS_COUPON` (upgrade 스크립트, 현재 정의에 없음)")
                .contains("- ← ORDERS (COUPON_ID → COUPON_ID) `FK_ORDERS_COUPON` (upgrade 스크립트, 현재 정의에 없음)")
                .contains("- → STORE (STORE_ID → STORE_ID) (upgrade 스크립트, 현재 정의에 없음)")
                .contains("- → CUSTOMER (CUSTOMER_ID → CUSTOMER_ID) `FK_ORDERS_CUSTOMER`\n").doesNotContain("(추론)").doesNotContain("건)");
        // 근거 수로 거르는 옵션은 적용하지 않는다(추론한 것이 아니라 스크립트에 적힌 것이다). 추론 관계를 빼면 함께 빠진다
        assertThat(Erd.model(g, ErdOptions.builder().minEvidence(3).build()).relations()).hasSize(4);
        assertThat(Erd.model(g, ErdOptions.builder().includeInferred(false).build()).relations()).extracting(Relation::parent).containsExactly("CUSTOMER");
        // 같은 컬럼의 FK가 현재 정의에도 있으면 그것만 그린다
        f.fk("ORDERS", "COUPON", "FK_ORDERS_COUPON", List.of("COUPON_ID"), List.of("COUPON_ID"));
        assertThat(Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.MERMAID)).contains("--o{ ORDERS : \"FK_ORDERS_COUPON\"")
                .doesNotContain("FK_ORDERS_COUPON (upgrade");
    }

    /**
     * 공통컴포넌트의 COMTNCOMMENT–COMTNBBS 모양: 부모의 PK는 (NTT_ID, BBS_ID)이고 DDL FK도 그 둘이다. SQL은 NTT_ID만으로 조인해서
     * 링커가 방향은 DDL FK를 따라 정하고(directionFrom=DDL) 카디널리티는 정하지 못했다(unknown).
     */
    private static GraphFixture directionOnly() {
        GraphFixture f = GraphFixture.create();
        f.table("BBS");
        f.column("BBS", "NTT_ID", "NUMBER", 1, "pk", "notnull");
        f.column("BBS", "BBS_ID", "CHAR(20)", 2, "pk", "notnull");
        f.table("COMMENT_T");
        f.column("COMMENT_T", "COMMENT_NO", "NUMBER", 1, "pk", "notnull");
        f.column("COMMENT_T", "NTT_ID", "NUMBER", 2, "notnull");
        f.column("COMMENT_T", "BBS_ID", "CHAR(20)", 3, "notnull");
        f.fk("COMMENT_T", "BBS", "COMMENT_FK1", List.of("NTT_ID", "BBS_ID"), List.of("NTT_ID", "BBS_ID"));
        f.edge(Edge.builder(Ids.table("COMMENT_T"), Ids.table("BBS"), EdgeKind.REFERENCES).inferred().attr("columns", List.of("NTT_ID"))
                .attr("refColumns", List.of("NTT_ID")).attr("source", "INFERRED").attr("evidenceCount", 2)
                .attr("directionKnown", true).attr("directionFrom", "DDL").attr("cardinality", "unknown").build());
        // 견줄 것: 방향 미상(directionKnown=false), 확정된 N:1
        f.table("LOG_A");
        f.column("LOG_A", "TX_ID", "NUMBER", 1);
        f.table("LOG_B");
        f.column("LOG_B", "TX_ID", "NUMBER", 1);
        f.edge(Edge.builder(Ids.table("LOG_A"), Ids.table("LOG_B"), EdgeKind.REFERENCES).inferred().attr("columns", List.of("TX_ID"))
                .attr("refColumns", List.of("TX_ID")).attr("source", "INFERRED").attr("evidenceCount", 2)
                .attr("directionKnown", false).attr("cardinality", "unknown").build());
        f.table("SCRAP");
        f.column("SCRAP", "SCRAP_ID", "NUMBER", 1, "pk", "notnull");
        f.column("SCRAP", "COMMENT_NO", "NUMBER", 2, "notnull");
        f.edge(Edge.builder(Ids.table("SCRAP"), Ids.table("COMMENT_T"), EdgeKind.REFERENCES).inferred()
                .attr("columns", List.of("COMMENT_NO")).attr("refColumns", List.of("COMMENT_NO")).attr("source", "INFERRED")
                .attr("evidenceCount", 3).attr("directionKnown", true).attr("cardinality", "N:1").build());
        return f;
    }

    private static Relation relation(ErdModel model, String child, String parent, boolean inferred) {
        return model.relations().stream().filter(x -> x.child().equals(child) && x.parent().equals(parent) && x.inferred() == inferred)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("F-013-AC30 방향만 DDL로 알고 카디널리티는 모르는 추론 관계(directionKnown=true, directionFrom=DDL, cardinality=unknown)는 "
            + "자식→부모 방향을 지키되 N:1로 단정하지 않고 '카디널리티 모름'으로 적는다. 방향 미상과는 다르게 나온다")
    void drawsDirectionWithoutCardinalityWhenOnlyDirectionIsKnown() {
        GraphModel g = directionOnly().graph();

        // 모델: 방향은 알고(자식 COMMENT_T → 부모 BBS) 양쪽 개수는 모른다. 자식 컬럼의 유일 여부로 N:1을 지어내지 않는다
        ErdModel model = Erd.model(g, ErdOptions.defaults());
        Relation r = relation(model, "COMMENT_T", "BBS", true);
        assertThat(r.directionKnown()).isTrue();
        assertThat(List.of(r.childSide(), r.parentSide())).containsExactly(Cardinality.UNKNOWN, Cardinality.UNKNOWN);
        assertThat(r.cardinalityUnknown()).isTrue();
        assertThat(r.directionFrom()).isEqualTo("DDL");
        // 방향 미상은 다른 것이다
        assertThat(relation(model, "LOG_A", "LOG_B", true)).extracting(Relation::directionKnown, Relation::cardinalityUnknown, Relation::directionFrom)
                .containsExactly(false, false, null);
        assertThat(relation(model, "SCRAP", "COMMENT_T", true).cardinalityUnknown()).isFalse();
        // 추론 관계의 부모 쪽은 0..1이다(제약이 없다. F-013-R28)
        assertThat(sides(model, "SCRAP")).containsExactly(Cardinality.MANY, Cardinality.ZERO_OR_ONE);
        Relation ddl = relation(model, "COMMENT_T", "BBS", false);
        assertThat(List.of(ddl.childSide(), ddl.parentSide())).containsExactly(Cardinality.MANY, Cardinality.ONE);

        // Mermaid: 부모를 왼쪽에 둔 채(방향 유지) 가장 약한 끝 표시와 라벨. 방향 미상(라벨 `방향 미상`)과 라벨로 갈린다
        String mermaid = Erd.render(g, ErdOptions.defaults(), ErdFormat.MERMAID);
        assertThat(mermaid).contains("    BBS }o..o{ COMMENT_T : \"NTT_ID (추론 2건, 방향은 DDL, 카디널리티 모름)\"\n")
                .doesNotContain("BBS ||..o{ COMMENT_T").doesNotContain("BBS ||..o| COMMENT_T")
                .contains("    LOG_B }o..o{ LOG_A : \"TX_ID (추론 2건, 방향 미상)\"\n")
                .contains("    COMMENT_T |o..o{ SCRAP : \"COMMENT_NO (추론 3건)\"\n")
                .contains("    BBS ||--o{ COMMENT_T : \"COMMENT_FK1\"\n");
        // 범례(머리 주석): 그런 관계가 있을 때만 한 줄 더 적는다
        assertThat(mermaid).contains("    %% 실선(--)은 DDL 외래 키, 점선(..)은 SQL에서 추론한 관계다. " + ErdLabels.INFERRED_PARENT_OPTIONAL + "\n"
                + "    %% '카디널리티 모름'은 방향(왼쪽이 부모)만 DDL 외래 키로 확인하고 카디널리티는 정하지 못한 추론 관계다. 양쪽 끝 표시는 N:M이라는 뜻이 아니다.\n");
        assertThat(MermaidSyntax.checkEr(mermaid)).isEmpty();
        assertThat(Erd.render(shop().graph(), ErdOptions.defaults(), ErdFormat.MERMAID)).doesNotContain("카디널리티 모름");
        // 뷰어 테스트(erd-direction-only.test.js)가 같은 그래프로 같은 글자를 단언한다
        assertThat(mermaid).isEqualTo(String.join("\n",
                "erDiagram",
                "    %% CodeAtlas ERD: 엔티티 5개, 관계 4개 (추론 3개)",
                "    %% 실선(--)은 DDL 외래 키, 점선(..)은 SQL에서 추론한 관계다. 추론 관계는 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 '0 또는 1'(|o)로 그린다.",
                "    %% '카디널리티 모름'은 방향(왼쪽이 부모)만 DDL 외래 키로 확인하고 카디널리티는 정하지 못한 추론 관계다. 양쪽 끝 표시는 N:M이라는 뜻이 아니다.",
                "    BBS {",
                "        NUMBER NTT_ID PK",
                "        CHAR(20) BBS_ID PK",
                "    }",
                "    COMMENT_T {",
                "        NUMBER COMMENT_NO PK",
                "        NUMBER NTT_ID FK \"NOT NULL\"",
                "        CHAR(20) BBS_ID FK \"NOT NULL\"",
                "    }",
                "    LOG_A {",
                "        NUMBER TX_ID",
                "    }",
                "    LOG_B {",
                "        NUMBER TX_ID",
                "    }",
                "    SCRAP {",
                "        NUMBER SCRAP_ID PK",
                "        NUMBER COMMENT_NO \"NOT NULL\"",
                "    }",
                "    BBS }o..o{ COMMENT_T : \"NTT_ID (추론 2건, 방향은 DDL, 카디널리티 모름)\"",
                "    BBS ||--o{ COMMENT_T : \"COMMENT_FK1\"",
                "    LOG_B }o..o{ LOG_A : \"TX_ID (추론 2건, 방향 미상)\"",
                "    COMMENT_T |o..o{ SCRAP : \"COMMENT_NO (추론 3건)\"",
                ""));

        // PlantUML: 카디널리티 표시 없이 자식에서 부모로 가는 점선 화살표. 방향 미상은 전처럼 화살표 없는 `..`
        String puml = Erd.render(g, ErdOptions.defaults(), ErdFormat.PLANTUML);
        assertThat(puml).contains("BBS <.. COMMENT_T : NTT_ID (추론 2건, 방향은 DDL, 카디널리티 모름)\n")
                .contains("LOG_B .. LOG_A : TX_ID (추론 2건, 방향 미상)\n")
                .contains("COMMENT_T |o..o{ SCRAP : COMMENT_NO (추론 3건)\n")
                .contains("' 화살표(<..)는 방향(자식 → 부모)만 DDL 외래 키로 확인하고 카디널리티는 정하지 못한 추론 관계다.\n");
        assertThat(Erd.render(shop().graph(), ErdOptions.defaults(), ErdFormat.PLANTUML)).doesNotContain("화살표");

        // DBML: 자식 > 부모(N:1)로 단정하지 않는다. 자식을 앞에 둔 `<>`와 줄 끝 주석
        String dbml = Erd.render(g, ErdOptions.defaults(), ErdFormat.DBML);
        assertThat(dbml).contains("Ref: COMMENT_T.NTT_ID <> BBS.NTT_ID // 추론 2건, 방향은 DDL, 카디널리티 모름\n")
                .contains("Ref: LOG_A.TX_ID <> LOG_B.TX_ID // 추론 2건, 방향 미상\n")
                .contains("Ref: SCRAP.COMMENT_NO > COMMENT_T.COMMENT_NO // 추론 3건\n");

        // 테이블 명세: 방향(→ 부모, ← 자식)은 그대로, 관계 설명에 카디널리티를 모른다고 적는다
        String spec = TableSpecRenderer.render(g);
        assertThat(spec).contains("- → BBS (NTT_ID → NTT_ID) (추론 2건, 방향은 DDL, 카디널리티 모름)\n")
                .contains("- ← COMMENT_T (NTT_ID → NTT_ID) (추론 2건, 방향은 DDL, 카디널리티 모름)\n")
                .contains("- → LOG_B (TX_ID → TX_ID) (추론 2건, 방향 미상)\n");
    }

    @Test
    @DisplayName("F-013-AC30 directionFrom이 없어도 추론 관계의 cardinality=unknown은 단정하지 않는다. directionFrom=DDL이어도 cardinality가 1:1이면 그대로 그린다")
    void keepsKnownCardinalityAndTreatsAnyInferredUnknownAsUnknown() {
        GraphFixture f = GraphFixture.create();
        for (String t : List.of("P", "C1", "C2")) {
            f.table(t);
            f.column(t, "ID", "NUMBER", 1, "pk", "notnull");
            f.column(t, "P_ID", "NUMBER", 2, "notnull");
        }
        f.edge(Edge.builder(Ids.table("C1"), Ids.table("P"), EdgeKind.REFERENCES).inferred().attr("columns", List.of("P_ID"))
                .attr("refColumns", List.of("ID")).attr("source", "INFERRED").attr("evidenceCount", 1)
                .attr("directionKnown", true).attr("cardinality", "unknown").build());
        f.edge(Edge.builder(Ids.table("C2"), Ids.table("P"), EdgeKind.REFERENCES).inferred().attr("columns", List.of("ID"))
                .attr("refColumns", List.of("ID")).attr("source", "INFERRED").attr("evidenceCount", 1)
                .attr("directionKnown", true).attr("directionFrom", "DDL").attr("cardinality", "1:1").build());

        String mermaid = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.MERMAID);

        assertThat(mermaid).contains("    P }o..o{ C1 : \"P_ID (추론 1건, 카디널리티 모름)\"\n").contains("    P |o..o| C2 : \"ID (추론 1건)\"\n");
        assertThat(sides(Erd.model(f.graph(), ErdOptions.defaults()), "C1")).containsExactly(Cardinality.UNKNOWN, Cardinality.UNKNOWN);
    }
}
