package io.codeatlas.export.erd;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.export.StandardExporter;
import io.codeatlas.export.erd.ErdModel.Cardinality;
import io.codeatlas.export.erd.ErdModel.Relation;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.MermaidSyntax;
import io.codeatlas.export.report.SummaryReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 4차 재검증 후속(5차 수정): 추론 관계의 부모 쪽 필수 표기, 시스템 카탈로그 제외. */
class ErdRound7Test {

    @TempDir
    Path dir;

    private static GraphFixture orders() {
        GraphFixture f = GraphFixture.create();
        f.table("CUSTOMER");
        f.column("CUSTOMER", "CUSTOMER_ID", "NUMBER", 1, "pk", "notnull");
        f.table("ORDERS");
        f.column("ORDERS", "ORDER_ID", "NUMBER", 1, "pk", "notnull");
        f.column("ORDERS", "CUSTOMER_ID", "NUMBER", 2, "notnull");
        f.column("ORDERS", "COUPON_ID", "NUMBER", 3, "nullable");
        f.fk("ORDERS", "CUSTOMER", "FK_ORDERS_CUSTOMER", List.of("CUSTOMER_ID"), List.of("CUSTOMER_ID"));
        f.table("COUPON");
        f.column("COUPON", "COUPON_ID", "NUMBER", 1, "pk", "notnull");
        f.fk("ORDERS", "COUPON", "FK_ORDERS_COUPON", List.of("COUPON_ID"), List.of("COUPON_ID"));
        // FK 제약 없이 조인에서 추론한 관계: 자식 컬럼이 NOT NULL이어도 부모가 반드시 있다고는 할 수 없다
        f.table("ORDER_HIST");
        f.column("ORDER_HIST", "HIST_ID", "NUMBER", 1, "pk", "notnull");
        f.column("ORDER_HIST", "ORDER_ID", "NUMBER", 2, "notnull");
        f.inferredFk("ORDER_HIST", "ORDERS", List.of("ORDER_ID"), List.of("ORDER_ID"), 2, true);
        // 추론한 1:1(자식 컬럼이 자식의 PK 전체)
        f.table("ORDER_EXT");
        f.column("ORDER_EXT", "ORDER_ID", "NUMBER", 1, "pk", "notnull");
        f.inferredFk("ORDER_EXT", "ORDERS", List.of("ORDER_ID"), List.of("ORDER_ID"), 1, true);
        return f;
    }

    @Test
    @DisplayName("F-013-AC31 추론(INFERRED) 관계는 부모 쪽을 '0 또는 1'로 그린다(|o..o{, |o..o|). DDL 외래 키는 전과 같이 FK 컬럼의 NOT NULL로 정한다(||--o{, |o--o{)")
    void inferredRelationsDoNotClaimMandatoryParent() {
        GraphModel g = orders().graph();

        ErdModel model = Erd.model(g, ErdOptions.defaults());
        Relation inferred = model.relations().stream().filter(r -> r.child().equals("ORDER_HIST")).findFirst().orElseThrow();
        assertThat(inferred.parentSide()).isEqualTo(Cardinality.ZERO_OR_ONE);
        assertThat(inferred.childSide()).isEqualTo(Cardinality.MANY);
        Relation declared = model.relations().stream().filter(r -> "FK_ORDERS_CUSTOMER".equals(r.constraint())).findFirst().orElseThrow();
        assertThat(declared.parentSide()).isEqualTo(Cardinality.ONE);

        String mermaid = Erd.render(g, ErdOptions.defaults(), ErdFormat.MERMAID);
        assertThat(mermaid).contains("    ORDERS |o..o{ ORDER_HIST : \"ORDER_ID (추론 2건)\"\n")
                .contains("    ORDERS |o..o| ORDER_EXT : \"ORDER_ID (추론 1건)\"\n")
                .contains("    CUSTOMER ||--o{ ORDERS : \"FK_ORDERS_CUSTOMER\"\n")
                .contains("    COUPON |o--o{ ORDERS : \"FK_ORDERS_COUPON\"\n")
                .doesNotContain("||..");
        assertThat(mermaid).contains("    %% 실선(--)은 DDL 외래 키, 점선(..)은 SQL에서 추론한 관계다. "
                + "추론 관계는 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 '0 또는 1'(|o)로 그린다.\n");
        assertThat(MermaidSyntax.checkEr(mermaid)).isEmpty();

        String puml = Erd.render(g, ErdOptions.defaults(), ErdFormat.PLANTUML);
        assertThat(puml).contains("ORDERS |o..o{ ORDER_HIST : ORDER_ID (추론 2건)\n").contains("ORDERS |o..o| ORDER_EXT : ORDER_ID (추론 1건)\n")
                .contains("CUSTOMER ||--o{ ORDERS : FK_ORDERS_CUSTOMER\n").doesNotContain("||..")
                .contains("' 실선은 DDL 외래 키, 점선은 SQL에서 추론한 관계다. 추론 관계는 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 '0 또는 1'(|o)로 그린다.\n");

        // DBML에는 필수 여부 표기가 없다: 관계 줄은 그대로이고 머리 주석으로만 알린다
        String dbml = Erd.render(g, ErdOptions.defaults(), ErdFormat.DBML);
        assertThat(dbml).contains("Ref: ORDER_HIST.ORDER_ID > ORDERS.ORDER_ID // 추론 2건\n")
                .contains("// 추론 관계(줄 끝 주석 '추론')는 제약이 없어 부모가 반드시 있는지 알 수 없다.\n");
        // 추론 관계가 없으면 그 문장도 없다
        String noInferred = Erd.render(g, ErdOptions.builder().includeInferred(false).build(), ErdFormat.DBML);
        assertThat(noInferred).doesNotContain("반드시 있는지");
    }

    @Test
    @DisplayName("F-013-AC31 추론 관계의 cardinality 속성이 부모 필수(N:1)를 말해도 부모 쪽은 0..1이고, 방향만 아는 관계·방향 미상 관계의 표기는 그대로다")
    void explicitCardinalityDoesNotMakeInferredParentMandatory() {
        GraphFixture f = orders();
        f.table("PAY");
        f.column("PAY", "ORDER_ID", "NUMBER", 1, "notnull");
        f.edge(Edge.builder(Ids.table("PAY"), Ids.table("ORDERS"), EdgeKind.REFERENCES).inferred().attr("columns", List.of("ORDER_ID"))
                .attr("refColumns", List.of("ORDER_ID")).attr("source", "INFERRED").attr("evidenceCount", 3).attr("directionKnown", true)
                .attr("cardinality", "N:1").build());
        f.table("LOG_A");
        f.column("LOG_A", "TX_ID", "NUMBER", 1);
        f.table("LOG_B");
        f.column("LOG_B", "TX_ID", "NUMBER", 1);
        f.inferredFk("LOG_A", "LOG_B", List.of("TX_ID"), List.of("TX_ID"), 2, false);

        String mermaid = Erd.render(f.graph(), ErdOptions.defaults(), ErdFormat.MERMAID);
        assertThat(mermaid).contains("    ORDERS |o..o{ PAY : \"ORDER_ID (추론 3건)\"\n")
                .contains("    LOG_B }o..o{ LOG_A : \"TX_ID (추론 2건, 방향 미상)\"\n");
    }

    private static GraphFixture withCatalogs() {
        GraphFixture f = orders();
        String userTables = f.undeclaredTable("USER_TABLES");
        String userColumns = f.undeclaredTable("USER_TAB_COLUMNS");
        f.attr(userTables, "systemCatalog", true);
        f.attr(userColumns, "systemCatalog", true);
        f.inferredFk("USER_TAB_COLUMNS", "USER_TABLES", List.of("TABLE_NAME"), List.of("TABLE_NAME"), 2, true);
        f.undeclaredTable("TMP_GHOST");
        f.reads(f.statement("com.g.GenMapper", "tables", "SELECT"), "USER_TABLES");
        f.reads(f.statement("com.g.GenMapper", "ghost", "SELECT"), "TMP_GHOST");
        return f;
    }

    @Test
    @DisplayName("F-013-AC32 시스템 카탈로그(systemCatalog=true) 테이블과 그 사이의 추론 관계는 ERD(Mermaid, PlantUML, DBML)에 그리지 않고 머리 주석에 뺀 수를 적는다. DDL에 없는 다른 테이블은 그대로 그린다")
    void systemCatalogsAreNotDrawn() {
        GraphModel g = withCatalogs().graph();

        ErdModel model = Erd.model(g, ErdOptions.defaults());
        assertThat(model.entities()).extracting(ErdModel.Entity::name).contains("TMP_GHOST", "ORDERS").doesNotContain("USER_TABLES", "USER_TAB_COLUMNS");
        assertThat(model.relations()).noneMatch(r -> r.parent().equals("USER_TABLES"));
        assertThat(model.systemCatalogNames()).containsExactly("USER_TABLES", "USER_TAB_COLUMNS");

        for (ErdFormat format : ErdFormat.values()) {
            String text = Erd.render(g, ErdOptions.defaults(), format);
            assertThat(text).as(format.name()).doesNotContain("USER_TABLES").doesNotContain("USER_TAB_COLUMNS").contains("TMP_GHOST")
                    .contains("시스템 카탈로그 2개(DB가 제공하는 데이터 사전)는 프로젝트 스키마가 아니라서 뺐다.");
        }
        assertThat(Erd.render(g, ErdOptions.defaults(), ErdFormat.MERMAID)).contains("엔티티 6개, 관계 4개 (추론 2개)");
        assertThat(MermaidSyntax.checkEr(Erd.render(g, ErdOptions.defaults(), ErdFormat.MERMAID))).isEmpty();
        // 카탈로그가 없으면 그 줄도 없다
        assertThat(Erd.render(orders().graph(), ErdOptions.defaults(), ErdFormat.MERMAID)).doesNotContain("시스템 카탈로그");
        assertThat(Erd.model(orders().graph(), ErdOptions.defaults()).systemCatalogNames()).isEmpty();
        // 이름을 직접 줘도 범위에 들지 않는다(찾지 못한 이름으로 돌려준다)
        ErdModel asked = Erd.model(g, ErdOptions.builder().tables("USER_TABLES").build());
        assertThat(asked.entities()).isEmpty();
        assertThat(asked.unresolvedNames()).containsExactly("USER_TABLES");
    }

    @Test
    @DisplayName("F-013-AC33 테이블 명세는 시스템 카탈로그를 따로 모은 절에 이름만 남기고, report.md 머리와 erd/index.md에 'ERD에서 제외'를 한 줄 적는다")
    void systemCatalogsAreListedByNameOnly() throws Exception {
        GraphModel g = withCatalogs().graph();

        String spec = TableSpecRenderer.render(g);
        assertThat(spec).contains("- 테이블 6개 (DDL에 없는 테이블 1개), 뷰 0개\n")
                .contains("- 시스템 카탈로그 2개는 명세와 ERD에서 뺐다(아래 \"시스템 카탈로그\" 절에 이름만 적는다)\n")
                .doesNotContain("## USER_TABLES").doesNotContain("## USER_TAB_COLUMNS").contains("## TMP_GHOST (DDL 없음)")
                .endsWith("""

                        ## 시스템 카탈로그 (2)

                        DB가 제공하는 데이터 사전이다. 프로젝트 스키마가 아니라서 이름만 적는다.

                        - `USER_TABLES`
                        - `USER_TAB_COLUMNS`
                        """);
        assertThat(TableSpecRenderer.render(orders().graph())).doesNotContain("시스템 카탈로그");
        // 범위를 좁힌 명세에는 카탈로그 절을 붙이지 않는다
        assertThat(TableSpecRenderer.render(g, ErdOptions.builder().tables("ORDERS").build())).doesNotContain("시스템 카탈로그");

        String report = SummaryReport.render(g);
        assertThat(report).contains("- 시스템 카탈로그 2개는 ERD에서 제외(이름은 `erd/tables.md`의 \"시스템 카탈로그\" 절)\n");
        assertThat(SummaryReport.render(orders().graph())).doesNotContain("ERD에서 제외");

        // 한도를 넘어 나눈 ERD의 색인에도 적는다
        new StandardExporter().export(g, dir, Map.of("export.mermaid.maxChars", "200"));
        String index = Files.readString(dir.resolve("erd/index.md"), StandardCharsets.UTF_8);
        assertThat(index).contains("- 시스템 카탈로그 2개는 ERD에서 제외했다(이름은 [tables.md](tables.md)의 \"시스템 카탈로그\" 절)\n");
        assertThat(Files.readString(dir.resolve("erd/erd-overview.mmd"), StandardCharsets.UTF_8)).doesNotContain("USER_TABLES");
    }
}
