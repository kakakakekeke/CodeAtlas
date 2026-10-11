package io.codeatlas.export;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.Exporter;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.MermaidSyntax;
import io.codeatlas.export.fixture.ShopGraph;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandardExporterTest {

    @TempDir
    Path dir;

    private static Map<String, String> files(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                result.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private Map<String, String> export(GraphModel graph, String name, Map<String, String> options) throws Exception {
        Path out = Files.createDirectories(dir.resolve(name));
        new StandardExporter().export(graph, out, options);
        return files(out);
    }

    @Test
    @DisplayName("F-013-AC14 analyze 산출물: erd/erd.mmd, erd.puml, erd.dbml, tables.md (옵션으로 끌 수 있다)")
    void writesErd() throws Exception {
        GraphModel g = new ShopGraph().f.graph();

        Map<String, String> files = export(g, "erd", Map.of());
        assertThat(files).containsKeys("erd/erd.mmd", "erd/erd.puml", "erd/erd.dbml", "erd/tables.md");
        assertThat(files.get("erd/erd.mmd")).startsWith("erDiagram\n").contains("CUSTOMER ||--o{ ORDERS : \"FK_ORDERS_CUSTOMER\"")
                .contains("ORDERS |o..o{ ORDER_HIST : \"ORDER_ID (추론 2건)\"").contains("\"TMP_UNDECLARED (DDL 없음)\"");
        assertThat(MermaidSyntax.checkEr(files.get("erd/erd.mmd"))).isEmpty();
        assertThat(files.get("erd/erd.puml")).startsWith("@startuml\n");
        assertThat(files.get("erd/erd.dbml")).contains("Table ORDERS");
        assertThat(files.get("erd/tables.md")).contains("## ORDERS\n");

        Map<String, String> off = export(g, "erd-off", Map.of("export.erd", "false"));
        assertThat(off.keySet()).noneMatch(k -> k.startsWith("erd/"));
        assertThat(off).containsKey("report.md");

        Map<String, String> noInferred = export(g, "erd-opts", Map.of("export.erd.inferred", "false", "export.erd.columns", "keys"));
        assertThat(noInferred.get("erd/erd.mmd")).doesNotContain("(추론 2건)").doesNotContain("..o{").doesNotContain("VARCHAR2(20) STATUS").contains("NUMBER(10) ORDER_ID PK");
    }

    @Test
    @DisplayName("F-014-AC13 analyze 산출물: 패키지마다 class-diagram/<패키지>.mmd·.puml과 index.md, 패키지 상한을 넘으면 상위 패키지로 묶는다")
    void writesClassDiagrams() throws Exception {
        GraphModel g = new ShopGraph().f.graph();

        Map<String, String> files = export(g, "cd", Map.of());
        assertThat(files.keySet().stream().filter(k -> k.startsWith("class-diagram/"))).containsExactly(
                "class-diagram/com.shop.batch.mmd", "class-diagram/com.shop.batch.puml",
                "class-diagram/com.shop.common.mmd", "class-diagram/com.shop.common.puml",
                "class-diagram/com.shop.order.mmd", "class-diagram/com.shop.order.puml",
                "class-diagram/index.md");
        String order = files.get("class-diagram/com.shop.order.mmd");
        assertThat(order).startsWith("classDiagram\n").contains("class OrderServiceImpl {").contains("OrderService <|.. OrderServiceImpl")
                .doesNotContain("AuditHelper").doesNotContain("class List");
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getKey().startsWith("class-diagram/") && e.getKey().endsWith(".mmd")) {
                assertThat(MermaidSyntax.checkClass(e.getValue())).as(e.getKey()).isEmpty();
            }
        }
        assertThat(files.get("class-diagram/index.md")).contains("| `com.shop.order` | 5 | [mmd](com.shop.order.mmd) | [puml](com.shop.order.puml) |");

        Map<String, String> grouped = export(g, "cd-grouped", Map.of("export.classDiagram.maxPackages", "1"));
        assertThat(grouped.keySet().stream().filter(k -> k.startsWith("class-diagram/"))).containsExactly(
                "class-diagram/com.shop.mmd", "class-diagram/com.shop.puml", "class-diagram/index.md");
        assertThat(grouped.get("class-diagram/com.shop.mmd")).contains("namespace com_shop_order {").contains("namespace com_shop_batch {");
        assertThat(MermaidSyntax.checkClass(grouped.get("class-diagram/com.shop.mmd"))).isEmpty();
        assertThat(grouped.get("class-diagram/index.md")).contains("상위 패키지로 묶었다");

        assertThat(export(g, "cd-off", Map.of("export.classDiagram", "false")).keySet()).noneMatch(k -> k.startsWith("class-diagram/"));
    }

    @Test
    @DisplayName("F-015-AC15 analyze 산출물: crud CSV, calltree Markdown, report.md. ServiceLoader로 찾고 옵션으로 끈다")
    void writesCrudCallTreeReport() throws Exception {
        GraphModel g = new ShopGraph().f.graph();

        Map<String, String> files = export(g, "all", Map.of());
        assertThat(files).containsKeys("crud/table-statement.csv", "crud/table-method.csv", "crud/table-endpoint.csv",
                "crud/table-routine.csv", "calltree/endpoints.md", "calltree/batch-jobs.md", "report.md");
        assertThat(files.get("crud/table-statement.csv")).startsWith("﻿구문,위치,");
        assertThat(files.get("crud/table-endpoint.csv")).contains("POST /orders,");
        String endpoints = files.get("calltree/endpoints.md");
        assertThat(endpoints).startsWith("# 엔드포인트 콜트리\n").contains("## GET /orders/{id}\n\n```text\n[endpoint] GET /orders/{id}  src/main/java/")
                .contains("## POST /orders\n").contains("└─ [table] ORDERS (R)");
        assertThat(files.get("calltree/batch-jobs.md")).startsWith("# 배치 잡 콜트리\n").contains("## dailyJob\n\n```text\n[job] dailyJob")
                .contains("[procedure] SP_DAILY_SUM");
        assertThat(files.get("report.md")).startsWith("# CodeAtlas 분석 요약\n");

        List<String> names = new ArrayList<>();
        ServiceLoader.load(Exporter.class).forEach(e -> names.add(e.name()));
        assertThat(names).contains("export");
        assertThat(new StandardExporter().name()).isEqualTo("export");

        Map<String, String> off = export(g, "off", Map.of("export.crud", "false", "export.calltree", "false", "export.report", "false"));
        assertThat(off.keySet()).allMatch(k -> k.startsWith("erd/") || k.startsWith("class-diagram/"));

        // 표준 산출물은 컨트롤러가 그리는 JSP 아래(화면 이동)를 펼치지 않는다. 옵션으로 켠다.
        ShopGraph withLink = new ShopGraph();
        withLink.f.request("POST", "/orders", ShopGraph.DETAIL_JSP, 7, withLink.detailJsp, withLink.postOrder);
        assertThat(export(withLink.f.graph(), "views-off", Map.of()).get("calltree/endpoints.md")).doesNotContain("detail.jsp:7");
        assertThat(export(withLink.f.graph(), "views-on", Map.of("export.calltree.expandViews", "true")).get("calltree/endpoints.md"))
                .contains("detail.jsp:7");

        Map<String, String> tuned = export(g, "tuned", Map.of("export.crud.bom", "false", "export.calltree.depth", "1"));
        assertThat(tuned.get("crud/table-statement.csv")).startsWith("구문,위치,");
        assertThat(tuned.get("calltree/endpoints.md")).contains("OrderController#detail(Long)").contains("[depth limit]")
                .doesNotContain("OrderService#find");
    }

    @Test
    @DisplayName("F-015-AC15 잘못된 옵션 값은 INVALID_OPTION 진단을 남기고 기본값을 쓴다")
    void invalidOption() throws Exception {
        GraphModel g = new ShopGraph().f.graph();

        Map<String, String> files = export(g, "invalid", Map.of("export.calltree.depth", "many", "export.erd.columns", "some"));

        assertThat(files).containsKeys("calltree/endpoints.md", "erd/erd.mmd");
        assertThat(g.diagnostics()).filteredOn(d -> d.code().equals(DiagnosticCodes.INVALID_OPTION)).hasSize(2);
    }

    @Test
    @DisplayName("F-015-AC17 입력 순서가 달라도 표준 산출물 전체가 바이트까지 같다")
    void deterministic() throws Exception {
        ShopGraph shop = new ShopGraph();
        shop.f.graph().resolveDanglingEdges();

        Map<String, String> first = export(shop.f.graph(), "first", Map.of());
        Map<String, String> second = export(shop.f.reversedCopy(), "second", Map.of());
        Map<String, String> again = export(shop.f.graph(), "again", Map.of());

        assertThat(second.keySet()).isEqualTo(first.keySet());
        for (String name : first.keySet()) {
            assertThat(second.get(name)).as(name).isEqualTo(first.get(name));
            assertThat(again.get(name)).as(name).isEqualTo(first.get(name));
        }
        assertThat(first).hasSizeGreaterThan(15);
    }

    @Test
    @DisplayName("F-013-AC15 F-014-AC14 F-015-AC19 대상이 없는 산출물은 빈 파일로 쓰지 않고 생략하며 report.md에 이유를 한 줄 남긴다")
    void omitsArtifactsWithoutSubjects() throws Exception {
        // 빈 그래프: report.md만 쓴다
        Map<String, String> empty = export(new GraphModel(), "empty", Map.of());
        assertThat(empty.keySet()).containsExactly("report.md");
        assertThat(empty.get("report.md")).contains("- DB 객체 없음(DDL이나 SQL이 분석되지 않음)")
                .contains("- Java 타입 없음").contains("- 엔드포인트 없음").contains("- 배치 잡 없음");

        // Java 타입만 있는 그래프: class-diagram/만 쓴다
        GraphFixture javaOnly = GraphFixture.create();
        javaOnly.type("com.a.Service", "class", "public");
        javaOnly.method("com.a.Service", "run");
        Map<String, String> java = export(javaOnly.graph(), "java-only", Map.of());
        assertThat(java.keySet()).containsExactly("class-diagram/com.a.mmd", "class-diagram/com.a.puml", "class-diagram/index.md", "report.md");
        assertThat(java.get("report.md")).contains("- DB 객체 없음(DDL이나 SQL이 분석되지 않음)").doesNotContain("Java 타입 없음");

        // 테이블만 있고 그 테이블에 접근하는 구문이 없는 그래프: erd/만 쓴다. 행이 없는 CRUD 매트릭스는 머리글만 든 파일로 쓰지 않는다
        GraphFixture dbOnly = GraphFixture.create();
        dbOnly.table("ORDERS", "주문");
        dbOnly.column("ORDERS", "ORDER_ID", "NUMBER(10)", 1, "pk");
        Map<String, String> db = export(dbOnly.graph(), "db-only", Map.of());
        assertThat(db.keySet()).containsExactly("erd/erd.dbml", "erd/erd.mmd", "erd/erd.puml", "erd/tables.md", "report.md");
        assertThat(db.get("report.md")).doesNotContain("DB 객체 없음").contains("- Java 타입 없음");

        // 구문만 테이블에 접근하면(실행하는 메서드·엔드포인트·루틴 없음) 구문 매트릭스만 쓴다
        dbOnly.reads(dbOnly.statement("com.a.M", "selectOrders", "SELECT"), "ORDERS", "ORDER_ID");
        Map<String, String> withStatement = export(dbOnly.graph(), "db-statement", Map.of());
        assertThat(withStatement.keySet().stream().filter(k -> k.startsWith("crud/"))).containsExactly("crud/README.md", "crud/table-statement.csv");
        assertThat(withStatement.get("crud/table-statement.csv")).contains("ORDERS").contains(",R");

        // 뷰만 있어도 DB 객체가 있는 것이다
        GraphFixture viewOnly = GraphFixture.create();
        viewOnly.view("V_ORDERS");
        Map<String, String> views = export(viewOnly.graph(), "view-only", Map.of());
        assertThat(views).containsKeys("erd/erd.mmd", "erd/tables.md");
        assertThat(views.get("report.md")).doesNotContain("DB 객체 없음");

        // 엔드포인트만 있으면 endpoints.md만, 잡만 있으면 batch-jobs.md만 쓴다
        GraphFixture web = GraphFixture.create();
        web.type("com.a.C", "class", "public");
        web.endpoint("GET", "/a", web.method("com.a.C", "get"));
        Map<String, String> endpointOnly = export(web.graph(), "endpoint-only", Map.of());
        assertThat(endpointOnly.keySet()).contains("calltree/endpoints.md").doesNotContain("calltree/batch-jobs.md");
        assertThat(endpointOnly.get("report.md")).doesNotContain("엔드포인트 없음").contains("- 배치 잡 없음");
        GraphFixture batch = GraphFixture.create();
        batch.step("nightly", "load");
        Map<String, String> jobOnly = export(batch.graph(), "job-only", Map.of());
        assertThat(jobOnly.keySet()).contains("calltree/batch-jobs.md").doesNotContain("calltree/endpoints.md");
        assertThat(jobOnly.get("report.md")).contains("- 엔드포인트 없음").doesNotContain("배치 잡 없음");

        // 전부 있는 그래프의 리포트에는 이런 줄이 없다
        assertThat(export(new ShopGraph().f.graph(), "full", Map.of()).get("report.md"))
                .doesNotContain("DB 객체 없음").doesNotContain("Java 타입 없음").doesNotContain("엔드포인트 없음").doesNotContain("배치 잡 없음");
    }

    /** 엔드포인트 → m0 → … → m11 → 매퍼 메서드 → 구문 → 테이블. 테이블까지 15걸음이다. */
    private static GraphFixture deepChain() {
        GraphFixture f = GraphFixture.create();
        f.table("T");
        f.type("com.deep.C", "class", "public");
        String first = f.method("com.deep.C", "m00");
        String previous = first;
        for (int i = 1; i < 12; i++) {
            String next = f.method("com.deep.C", String.format("m%02d", i));
            f.calls(previous, next);
            previous = next;
        }
        f.attr(f.type("com.deep.M", "interface", "public"), "stereotype", "mapper");
        String mapper = f.method("com.deep.M", "select", List.of(), "int");
        f.calls(previous, mapper);
        String statement = f.statement("com.deep.M", "select", "SELECT");
        f.edge(mapper, statement, EdgeKind.EXECUTES);
        f.reads(statement, "T");
        f.endpoint("GET", "/deep", first);
        return f;
    }

    @Test
    @DisplayName("F-015-AC28 표준 산출물 콜트리의 기본 깊이(20)는 화면에서 테이블까지 잘리지 않고, 깊이 제한에 잘린 트리 끝에는 안내 한 줄이 붙는다")
    void callTreeDefaultDepthAndTruncationNote() throws Exception {
        GraphModel g = deepChain().graph();
        String note = "깊이 제한으로 잘림: `export.calltree.depth`로 조정";

        String byDefault = export(g, "deep", Map.of()).get("calltree/endpoints.md");
        assertThat(StandardExporter.DEFAULT_CALLTREE_DEPTH).isEqualTo(20);
        assertThat(byDefault).contains("엔드포인트 1개. 깊이 20, 트리당 노드 500개까지.").contains("└─ [table] T (R)")
                .doesNotContain("[depth limit]\n").doesNotContain(note);

        // 예전 기본값 8에서는 테이블에 닿기 전에 잘렸다
        String shallow = export(g, "deep-8", Map.of("export.calltree.depth", "8")).get("calltree/endpoints.md");
        assertThat(shallow).doesNotContain("[table] T").contains("[depth limit]\n```\n\n" + note + "\n");

        // 배치 잡 트리도 같다
        ShopGraph shop = new ShopGraph();
        assertThat(export(shop.f.graph(), "jobs", Map.of()).get("calltree/batch-jobs.md")).contains("깊이 20").doesNotContain(note);
        assertThat(export(shop.f.graph(), "jobs-1", Map.of("export.calltree.depth", "1")).get("calltree/batch-jobs.md"))
                .contains("[depth limit]\n```\n\n" + note + "\n");
    }

    @Test
    @DisplayName("F-015-AC29 calltree/batch-jobs.md는 스텝을 NEXT 흐름 순서로 낸다 (잡 안의 스텝, 잡을 모르는 스텝의 절 순서)")
    void batchJobsInFlowOrder() throws Exception {
        ShopGraph shop = new ShopGraph();
        String jobs = export(shop.f.graph(), "flow", Map.of()).get("calltree/batch-jobs.md");
        // dailyJob: sumStep ─NEXT→ cleanStep
        assertThat(jobs.indexOf("[step] sumStep")).isPositive().isLessThan(jobs.indexOf("[step] cleanStep"));

        GraphFixture loose = GraphFixture.create();
        String load = loose.step("?", "load");
        String extract = loose.step("?", "extract");
        String alone = loose.step("?", "alone");
        loose.edge(load, extract, EdgeKind.NEXT);
        String md = export(loose.graph(), "loose", Map.of()).get("calltree/batch-jobs.md");
        assertThat(alone).isNotNull();
        assertThat(md.indexOf("## (잡 미상) load")).isPositive().isLessThan(md.indexOf("## (잡 미상) extract"));
        assertThat(md.indexOf("## (잡 미상) extract")).isLessThan(md.indexOf("## (잡 미상) alone"));
    }

    @Test
    @DisplayName("F-015-AC31 표준 산출물: 동적 구문은 crud CSV의 (동적) 열과 report.md의 별도 절에 나온다")
    void dynamicStatementsInArtifacts() throws Exception {
        ShopGraph shop = new ShopGraph();
        String backup = shop.f.statement(ShopGraph.MAPPER, "selectBackup", "SELECT");
        shop.f.dynamicAccess(backup, EdgeKind.READS, null, "__DYNAMIC__");

        Map<String, String> files = export(shop.f.graph(), "dynamic", Map.of("export.crud.bom", "false"));

        String csv = files.get("crud/table-statement.csv");
        assertThat(csv.lines().findFirst().orElseThrow()).startsWith("구문,위치,AUDIT_LOG,").endsWith(",TMP_UNDECLARED,(동적)");
        assertThat(csv).containsPattern("com\\.shop\\.order\\.OrderMapper\\.selectBackup,[^,]+,,,,,,,,R\n");
        assertThat(files.get("report.md")).contains("## 대상 테이블을 정적으로 알 수 없는 구문 (1)")
                .contains("| `mybatis:stmt:com.shop.order.OrderMapper.selectBackup` | R |");
        // 동적 구문이 없으면 열이 없다
        assertThat(export(new ShopGraph().f.graph(), "static", Map.of()).get("crud/table-statement.csv")).doesNotContain("(동적)");
    }

    @Test
    @DisplayName("F-015-AC22 OPTION_KEYS는 출력기가 실제로 읽는 옵션 키와 같다")
    void optionKeysMatchWhatIsRead() throws Exception {
        // 목록의 키는 모두 읽는다: 해석할 수 없는 값을 주면 키마다 INVALID_OPTION이 남는다
        GraphModel g = new ShopGraph().f.graph();
        Map<String, String> invalid = new TreeMap<>();
        StandardExporter.OPTION_KEYS.forEach(key -> invalid.put(key, "###"));
        export(g, "all-invalid", invalid);
        List<String> messages = g.diagnostics().stream().filter(d -> d.code().equals(DiagnosticCodes.INVALID_OPTION))
                .map(d -> d.message()).toList();
        assertThat(messages).hasSize(StandardExporter.OPTION_KEYS.size());
        for (String key : StandardExporter.OPTION_KEYS) {
            assertThat(messages).as(key).anyMatch(m -> m.contains("출력 옵션 " + key + "의 값"));
        }

        // 소스에서 읽는 키는 모두 목록에 있다
        String source = Files.readString(Path.of("src/main/java/io/codeatlas/export/StandardExporter.java"), StandardCharsets.UTF_8);
        String body = source.substring(source.indexOf("public void export("));
        java.util.Set<String> read = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("opts\\.(?:flag|number|choice)\\(\\s*\"(export\\.[A-Za-z.]+)\"").matcher(body);
        while (m.find()) {
            read.add(m.group(1));
        }
        assertThat(read).containsExactlyInAnyOrderElementsOf(StandardExporter.OPTION_KEYS);
    }

    @Test
    @DisplayName("F-013-AC22 F-015-AC62 DDL에 없는데 SQL이 참조하는 컬럼(정의가 있는 테이블의 declared=false 컬럼)은 ERD 본체에 넣지 않고 테이블 명세와 report.md의 점검 목록에 따로 적는다")
    void undeclaredColumnsOfDeclaredTables() throws Exception {
        GraphFixture f = GraphFixture.create();
        f.table("T_USER");
        f.column("T_USER", "USER_ID", "VARCHAR2(20)", 1, "pk");
        String select = f.statement("com.u.UserMapper", "selectUser", "SELECT");
        f.reads(select, "T_USER", "USER_ID");
        // SQL만 아는 컬럼: sql 분석기가 declared=false 컬럼 노드를 만들고 테이블에 붙인다
        String ghost = Ids.column("T_USER", "SUB_DN");
        f.graph().reference(ghost, NodeKind.DB_COLUMN, "SUB_DN");
        f.edge(Ids.table("T_USER"), ghost, EdgeKind.CONTAINS);
        f.edge(Edge.builder(select, ghost, EdgeKind.READS).build());

        Path out = dir.resolve("ghost-columns");
        new StandardExporter().export(f.graph(), out, Map.of());

        assertThat(Files.readString(out.resolve("erd/erd.mmd"), StandardCharsets.UTF_8)).contains("USER_ID").doesNotContain("SUB_DN");
        assertThat(Files.readString(out.resolve("erd/tables.md"), StandardCharsets.UTF_8)).contains("""
                DDL에 없는데 SQL이 참조하는 컬럼:
                - `SUB_DN` — 구문 1개: `UserMapper.selectUser`
                """);
        assertThat(Files.readString(out.resolve("report.md"), StandardCharsets.UTF_8)).contains("""
                ## SQL과 DDL의 컬럼 불일치 (1)

                SQL이 참조하지만 그 테이블의 DDL에 없는 컬럼이다. DDL이 오래됐거나 다른 방언·버전의 DDL이 선택됐을 수 있다.

                | 테이블 | 컬럼 | 참조하는 구문 수 | 구문 (최대 5개) |
                |---|---|---|---|
                | `T_USER` | `SUB_DN` | 1 | `UserMapper.selectUser` |
                """);
        // 그런 컬럼이 없으면 절이 없다
        Path plain = dir.resolve("no-ghost");
        new StandardExporter().export(new ShopGraph().f.graph(), plain, Map.of());
        assertThat(Files.readString(plain.resolve("report.md"), StandardCharsets.UTF_8)).doesNotContain("컬럼 불일치");
    }

    @Test
    @DisplayName("F-015-AC65 표준 산출물: crud/README.md가 칸의 표기(대문자·소문자, ?, (동적) 열, 데이터 적재 제외)를 설명하고, 추론 경로로만 닿은 칸은 CSV에 ?와 함께 나온다")
    void crudReadmeExplainsNotation() throws Exception {
        GraphFixture f = GraphFixture.create();
        f.table("T_ORDER");
        String handler = f.method("com.r.OrderController", "list");
        f.endpoint("GET", "/orders", handler);
        String mapper = f.method("com.r.OrderMapper", "selectOrders");
        f.edge(Edge.builder(handler, mapper, EdgeKind.CALLS).inferred().attr("callType", "interface").attr("ambiguous", true).build());
        String select = f.statement("com.r.OrderMapper", "selectOrders", "SELECT");
        f.reads(select, "T_ORDER");
        f.edge(mapper, select, EdgeKind.EXECUTES);

        Map<String, String> files = export(f.graph(), "crud-readme", Map.of("export.crud.bom", "false"));

        assertThat(files.keySet().stream().filter(k -> k.startsWith("crud/"))).containsExactly("crud/README.md", "crud/table-endpoint.csv",
                "crud/table-method.csv", "crud/table-statement.csv");
        assertThat(files.get("crud/table-endpoint.csv")).isEqualTo("엔드포인트,위치,T_ORDER\nGET /orders," + "src/main/java/com/r/OrderController.java:"
                + f.graph().node(handler).orElseThrow().location().startLine() + ",R?\n");
        assertThat(files.get("crud/table-method.csv")).endsWith(",R\n");
        String readme = files.get("crud/README.md");
        assertThat(readme).startsWith("# CRUD 매트릭스\n")
                .contains("| `table-endpoint.csv` |").contains("| `table-statement.csv` |").contains("| `table-method.csv` |")
                .doesNotContain("table-routine.csv")
                .contains("대문자").contains("소문자").contains("`?`").contains("`R?`").contains("`(동적)`").contains("데이터 적재")
                .contains("`export.includeDataLoad=true`").contains("`W`");
        // 매트릭스 파일을 하나도 쓰지 않으면 README도 쓰지 않는다
        GraphFixture dbOnly = GraphFixture.create();
        dbOnly.table("ORDERS");
        assertThat(export(dbOnly.graph(), "crud-none", Map.of()).keySet()).noneMatch(k -> k.startsWith("crud/"));
        // 루틴 기준 파일이 있으면 README의 파일 표에 나온다. 같은 그래프에서는 바이트까지 같다
        Map<String, String> shop = export(new ShopGraph().f.graph(), "crud-shop", Map.of());
        assertThat(shop.get("crud/README.md")).contains("| `table-routine.csv` |")
                .isEqualTo(export(new ShopGraph().f.reversedCopy(), "crud-shop-reversed", Map.of()).get("crud/README.md"));
    }
}
