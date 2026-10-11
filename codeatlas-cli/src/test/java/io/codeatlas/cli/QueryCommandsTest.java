package io.codeatlas.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.export.StandardExporter;
import io.codeatlas.export.calltree.CallTreeOptions;
import io.codeatlas.export.calltree.CallTreeOptions.Direction;
import io.codeatlas.export.calltree.CallTrees;
import io.codeatlas.export.calltree.CrudMatrix;
import io.codeatlas.export.calltree.CrudMatrix.Layout;
import io.codeatlas.export.calltree.CrudMatrix.Subject;
import io.codeatlas.export.calltree.ImpactAnalysis;
import io.codeatlas.export.classdiagram.ClassDiagramFormat;
import io.codeatlas.export.classdiagram.ClassDiagramOptions;
import io.codeatlas.export.classdiagram.ClassDiagramOptions.MemberLevel;
import io.codeatlas.export.classdiagram.ClassDiagrams;
import io.codeatlas.export.erd.Erd;
import io.codeatlas.export.erd.ErdFormat;
import io.codeatlas.export.erd.ErdOptions;
import io.codeatlas.export.erd.ErdOptions.ColumnLevel;
import io.codeatlas.export.erd.TableSpecRenderer;
import io.codeatlas.export.report.SummaryReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 저장된 그래프를 읽는 조회용 하위 명령(erd, classdiagram, calltree, impact, crud, find, report, export). */
class QueryCommandsTest {

    @TempDir
    Path work;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private GraphModel graph;
    /** shop 그래프가 든 출력 폴더와 그 안의 graph.json. */
    private Path dir;
    private String file;
    /** DB 노드가 없는 그래프, 노드가 없는 그래프. */
    private String javaOnly;
    private String empty;

    @BeforeEach
    void storeGraphs() throws IOException {
        dir = work.resolve("out");
        file = ShopGraphFixture.save(ShopGraphFixture.shop(), dir).toString();
        // 명령이 읽는 것과 같은 그래프(저장했다가 복원한 것)로 기대값을 만든다.
        graph = GraphJson.read(Path.of(file));
        javaOnly = ShopGraphFixture.save(ShopGraphFixture.javaOnly(), work.resolve("plain")).toString();
        empty = ShopGraphFixture.save(ShopGraphFixture.empty(), work.resolve("empty")).toString();
        RecordingAnalyzer.lastOptions = null;
        RecordingExporter.lastOptions = null;
    }

    /** 표준 출력과 표준 오류를 비우고 명령을 실행한다. */
    private int run(String... args) {
        outBytes.reset();
        errBytes.reset();
        return Main.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8), new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private void assertOk(String... args) {
        assertThat(run(args)).as("%s%n%s", List.of(args), err()).isZero();
    }

    // ---------------------------------------------------------------- 공통

    @Test
    @DisplayName("F-001-AC29 그래프는 첫 인자(graph.json 또는 출력 폴더)나 --graph로 받고, 둘 다 주면 사용법 오류(2)다")
    void graphLocation() throws IOException {
        String expected = SummaryReport.render(graph);

        assertOk("report", file);
        assertThat(out()).isEqualTo(expected);
        assertOk("report", dir.toString());
        assertThat(out()).isEqualTo(expected);
        assertOk("report", "--graph", dir.toString());
        assertThat(out()).isEqualTo(expected);
        assertOk("report", "--graph=" + file);
        assertThat(out()).isEqualTo(expected);
        assertThat(err()).isEmpty();

        assertThat(run("report", file, "--graph", file)).isEqualTo(2);
        assertThat(run("report", file, file)).isEqualTo(2);
    }

    @Test
    @DisplayName("F-001-AC29 그래프 파일이 없거나 graph.json이 아니면 종료 코드 1과 이유를 내고 표준 출력은 비어 있다")
    void graphNotReadable() throws IOException {
        assertThat(run("report", work.resolve("nope").toString())).isEqualTo(1);
        assertThat(err()).contains("nope");
        assertThat(out()).isEmpty();

        Files.writeString(work.resolve("bad.json"), "{\"schemaVersion\": 99}");
        assertThat(run("erd", work.resolve("bad.json").toString())).isEqualTo(1);
        assertThat(err()).contains("bad.json");
        assertThat(out()).isEmpty();

        assertThat(run("calltree", "find", "--graph", work.resolve("nope").toString())).isEqualTo(1);
        assertThat(run("export", work.resolve("nope").toString(), "--out", work.resolve("x").toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("F-001-AC30 --out <파일>을 주면 결과를 그 파일(UTF-8)에 쓰고 표준 출력은 비운다. 없는 상위 폴더는 만든다")
    void outFile() throws IOException {
        Path target = work.resolve("docs/diagrams/erd.mmd");

        assertOk("erd", file, "--out", target.toString());

        assertThat(out()).isEmpty();
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(Erd.render(graph, ErdOptions.defaults(), ErdFormat.MERMAID));
        assertThat(err()).contains(target.toString());

        // 대상이 없어 실패하면 파일을 만들지 않는다
        Path never = work.resolve("never.md");
        assertThat(run("impact", "NOPE", "--graph", file, "--out", never.toString())).isEqualTo(1);
        assertThat(never).doesNotExist();
    }

    @Test
    @DisplayName("F-001-AC30 알 수 없는 옵션, 빠진 값, 잘못된 값이면 종료 코드 2와 그 명령의 사용법을 표준 오류에 낸다")
    void usageErrors() {
        for (String command : List.of("erd", "classdiagram", "crud", "report", "export")) {
            assertThat(run(command, file, "--bogus")).as(command).isEqualTo(2);
            assertThat(err()).as(command).contains("--bogus").contains("codeatlas " + command).doesNotContain("codeatlas analyze");
            assertThat(out()).isEmpty();
        }
        for (String command : List.of("calltree", "impact", "find")) {
            assertThat(run(command, "ORDERS", "--graph", file, "--bogus")).as(command).isEqualTo(2);
            assertThat(err()).as(command).contains("--bogus").contains("codeatlas " + command).doesNotContain("codeatlas analyze");
            assertThat(run(command, "--graph", file)).as(command + " 검색어 없음").isEqualTo(2);
            assertThat(run(command, "a", "b", "--graph", file)).as(command + " 검색어 둘").isEqualTo(2);
        }
        assertThat(run("erd", file, "--out")).isEqualTo(2);
        assertThat(run("erd", file, "--depth")).isEqualTo(2);
        assertThat(run("erd", file, "--views=true")).isEqualTo(2);
    }

    @Test
    @DisplayName("F-001-AC31 help <명령>과 <명령> --help는 그 명령의 사용법과 옵션 설명만 표준 출력에 찍고 0으로 끝난다")
    void commandHelp() {
        for (String command : List.of("analyze", "summary", "erd", "classdiagram", "calltree", "impact", "crud", "find", "report", "export")) {
            assertOk("help", command);
            String help = out();
            assertThat(help).as(command).startsWith("사용법:\n  codeatlas " + command);
            assertOk(command, "--help");
            assertThat(out()).as(command).isEqualTo(help);
            assertOk(command, "-h");
            assertThat(out()).as(command).isEqualTo(help);
        }
        assertOk("help", "erd");
        assertThat(out()).contains("--min-evidence").contains("--spec").contains("--out").contains("--graph")
                .doesNotContain("codeatlas analyze").doesNotContain("--classpath");
        assertOk("help", "calltree");
        assertThat(out()).contains("--pick").contains("--callers").contains("--no-inject");

        assertThat(run("help", "nope")).isEqualTo(2);
        assertThat(err()).contains("nope");
    }

    @Test
    @DisplayName("F-001-AC31 --help는 모든 명령의 사용법 항목과 help <명령> 안내를 찍는다")
    void topLevelHelp() {
        assertOk("--help");
        String usage = out();
        for (String command : List.of("analyze", "summary", "erd", "classdiagram", "calltree", "impact", "crud", "find", "report", "export")) {
            assertThat(usage).contains("codeatlas " + command);
        }
        assertThat(usage).contains("codeatlas help <명령>");
        assertOk("help");
        assertThat(out()).isEqualTo(usage);
    }

    // ---------------------------------------------------------------- erd

    /** erd를 실행해 출력이 조회 API에 expected 옵션을 준 결과와 같은지 본다. */
    private void assertErd(ErdOptions expected, ErdFormat format, String... options) {
        String[] args = new String[options.length + 2];
        args[0] = "erd";
        args[1] = file;
        System.arraycopy(options, 0, args, 2, options.length);
        assertOk(args);
        assertThat(out()).as(String.join(" ", options)).isEqualTo(Erd.render(graph, expected, format));
    }

    /** 옵션이 실제로 출력을 바꿨는지(기본 Mermaid 출력과 다른지)까지 본다. */
    private void assertErdChanged(ErdOptions expected, String... options) {
        assertErd(expected, ErdFormat.MERMAID, options);
        assertThat(out()).as(String.join(" ", options) + " 는 기본 출력과 달라야 한다")
                .isNotEqualTo(Erd.render(graph, ErdOptions.defaults(), ErdFormat.MERMAID));
    }

    @Test
    @DisplayName("F-001-AC32 erd는 저장된 그래프의 ERD를 Mermaid(기본), PlantUML, DBML로 표준 출력에 낸다")
    void erdFormats() {
        assertErd(ErdOptions.defaults(), ErdFormat.MERMAID);
        assertThat(out()).startsWith("erDiagram\n").contains("ORDERS").contains("CUSTOMER").contains("ORDER_HIST").doesNotContain("V_ORDERS");
        assertThat(err()).isEmpty();

        assertErd(ErdOptions.defaults(), ErdFormat.MERMAID, "--format", "mermaid");
        assertErd(ErdOptions.defaults(), ErdFormat.PLANTUML, "--format", "plantuml");
        assertThat(out()).startsWith("@startuml\n");
        assertErd(ErdOptions.defaults(), ErdFormat.DBML, "--format=DBML");
        assertThat(out()).contains("Table ");
    }

    @Test
    @DisplayName("F-001-AC32 erd의 범위·표시 옵션은 ErdOptions에 그대로 전달된다")
    void erdOptions() {
        assertErdChanged(ErdOptions.builder().tables("CUSTOMER").build(), "--table", "CUSTOMER");
        assertThat(out()).contains("ORDERS").doesNotContain("ORDER_HIST");
        assertErdChanged(ErdOptions.builder().tables("CUSTOMER").neighborDepth(0).build(), "--table", "CUSTOMER", "--depth", "0");
        assertThat(out()).doesNotContain("ORDERS");
        assertErdChanged(ErdOptions.builder().tables("CUSTOMER", "ORDER_HIST").neighborDepth(0).build(),
                "--table", "CUSTOMER", "--table=ORDER_HIST", "--depth=0");
        assertErdChanged(ErdOptions.builder().patterns("ORDER*").build(), "--pattern", "ORDER*");
        assertThat(out()).contains("ORDER_HIST").doesNotContain("NAME");
        assertErdChanged(ErdOptions.builder().patterns("CUST*", "*_HIST").build(), "--pattern", "CUST*", "--pattern", "*_HIST");
        assertErdChanged(ErdOptions.builder().includeInferred(false).build(), "--no-inferred");
        assertErdChanged(ErdOptions.builder().minEvidence(2).build(), "--min-evidence", "2");
        assertErdChanged(ErdOptions.builder().includeViews(true).build(), "--views");
        assertThat(out()).contains("V_ORDERS");
        assertErdChanged(ErdOptions.builder().columnLevel(ColumnLevel.KEYS).build(), "--columns", "keys");
        assertErdChanged(ErdOptions.builder().columnLevel(ColumnLevel.NONE).build(), "--columns", "none");
        assertErdChanged(ErdOptions.builder().showComments(false).build(), "--no-comments");
        assertErd(ErdOptions.builder().tables("ORDERS").neighborDepth(2).patterns("V_*").includeInferred(false).includeViews(true)
                .columnLevel(ColumnLevel.KEYS).showComments(false).build(), ErdFormat.PLANTUML,
                "--table", "ORDERS", "--depth", "2", "--pattern", "V_*", "--no-inferred", "--views", "--columns", "keys", "--no-comments",
                "--format", "plantuml");

        assertThat(run("erd", file, "--format", "svg")).isEqualTo(2);
        assertThat(err()).contains("svg").contains("mermaid");
        assertThat(run("erd", file, "--columns", "some")).isEqualTo(2);
        assertThat(run("erd", file, "--depth", "x")).isEqualTo(2);
        assertThat(run("erd", file, "--depth", "-1")).isEqualTo(2);
        assertThat(run("erd", file, "--min-evidence", "many")).isEqualTo(2);
    }

    @Test
    @DisplayName("F-001-AC33 erd --spec은 테이블 명세(Markdown)를 내고, 표시·형식 옵션과 같이 주면 사용법 오류(2)다")
    void erdSpec() {
        assertOk("erd", file, "--spec");
        assertThat(out()).isEqualTo(TableSpecRenderer.render(graph, ErdOptions.defaults())).startsWith("# 테이블 명세");

        for (String[] option : List.of(new String[] {"--format", "dbml"}, new String[] {"--columns", "keys"}, new String[] {"--no-comments"})) {
            String[] args = new String[3 + option.length];
            args[0] = "erd";
            args[1] = file;
            args[2] = "--spec";
            System.arraycopy(option, 0, args, 3, option.length);
            assertThat(run(args)).as(option[0]).isEqualTo(2);
            assertThat(err()).contains("--spec").contains(option[0]);
        }
    }

    @Test
    @DisplayName("F-001-AC48 erd --spec은 범위 옵션(--table, --depth, --pattern, --views)과 관계 옵션을 ERD와 같은 규칙으로 받는다")
    void erdSpecWithScope() {
        assertOk("erd", file, "--spec", "--table", "ORDERS", "--depth", "1");
        assertThat(out()).isEqualTo(TableSpecRenderer.render(graph, ErdOptions.builder().tables("ORDERS").neighborDepth(1).build()))
                .contains("## ORDERS\n").contains("- 범위: 테이블 ORDERS, 이웃 깊이 1");
        assertThat(out()).isNotEqualTo(TableSpecRenderer.render(graph, ErdOptions.defaults()));
        assertThat(err()).isEmpty();

        assertOk("erd", file, "--spec", "--table", "orders", "--depth", "0");
        assertThat(out()).isEqualTo(TableSpecRenderer.render(graph, ErdOptions.builder().tables("orders").neighborDepth(0).build()));
        assertOk("erd", file, "--spec", "--pattern", "ORDER*", "--no-inferred", "--min-evidence", "2");
        assertThat(out()).isEqualTo(TableSpecRenderer.render(graph,
                ErdOptions.builder().patterns("ORDER*").includeInferred(false).minEvidence(2).build()));
        // 뷰는 ERD처럼 --views를 줘야 든다
        assertOk("erd", file, "--spec", "--views");
        assertThat(out()).isEqualTo(TableSpecRenderer.render(graph, ErdOptions.builder().includeViews(true).build())).contains("V_ORDERS (VIEW)");
        assertOk("erd", file, "--spec");
        assertThat(out()).doesNotContain("V_ORDERS (VIEW)");
        assertOk("erd", file, "--spec", "--table", "V_ORDERS", "--depth", "0", "--views");
        assertThat(out()).contains("## V_ORDERS (VIEW)\n").doesNotContain("## ORDERS\n");

        // 찾지 못한 이름은 ERD와 같은 방식으로 알린다
        assertThat(run("erd", file, "--spec", "--table", "NOPE")).isEqualTo(1);
        assertThat(err()).contains("NOPE");
        assertThat(out()).isEmpty();
        assertThat(run("erd", file, "--spec", "--table", "V_ORDERS")).isEqualTo(1);
        assertThat(err()).contains("--views");
        assertOk("erd", file, "--spec", "--table", "NOPE", "--table", "customer", "--depth", "0");
        assertThat(err()).contains("경고").contains("NOPE").doesNotContain("customer");
        assertThat(out()).contains("## CUSTOMER\n");
        assertThat(run("erd", file, "--spec", "--depth", "x")).isEqualTo(2);
    }

    @Test
    @DisplayName("F-001-AC33 erd는 그릴 테이블이 없으면 종료 코드 1과 안내를 내고, 일부 --table만 없으면 그 이름을 알리고 나머지를 그린다")
    void erdWithoutTables() {
        assertThat(run("erd", javaOnly)).isEqualTo(1);
        assertThat(err()).contains("테이블").doesNotContain("Exception");
        assertThat(out()).isEmpty();
        assertThat(run("erd", javaOnly, "--spec")).isEqualTo(1);
        assertThat(out()).isEmpty();

        assertThat(run("erd", file, "--table", "NOPE")).isEqualTo(1);
        assertThat(err()).contains("NOPE");
        assertThat(out()).isEmpty();
        // 뷰는 --views를 줘야 범위에 든다
        assertThat(run("erd", file, "--table", "V_ORDERS", "--depth", "0")).isEqualTo(1);
        assertThat(err()).contains("--views");

        assertOk("erd", file, "--table", "NOPE", "--table", "customer", "--depth", "0");
        assertThat(err()).contains("NOPE").doesNotContain("customer");
        assertThat(out()).contains("CUSTOMER");
    }

    // ---------------------------------------------------------------- classdiagram

    private void assertClassDiagram(ClassDiagramOptions expected, ClassDiagramFormat format, String... options) {
        String[] args = new String[options.length + 2];
        args[0] = "classdiagram";
        args[1] = file;
        System.arraycopy(options, 0, args, 2, options.length);
        assertOk(args);
        assertThat(out()).as(String.join(" ", options)).isEqualTo(ClassDiagrams.render(graph, expected, format));
    }

    @Test
    @DisplayName("F-001-AC34 classdiagram은 저장된 그래프의 클래스 다이어그램을 Mermaid(기본)나 PlantUML로 내고 옵션을 ClassDiagramOptions에 전달한다")
    void classDiagram() {
        assertClassDiagram(ClassDiagramOptions.defaults(), ClassDiagramFormat.MERMAID);
        assertThat(out()).startsWith("classDiagram\n").contains("OrderController").contains("AuditHelper");
        assertThat(err()).isEmpty();
        assertClassDiagram(ClassDiagramOptions.defaults(), ClassDiagramFormat.PLANTUML, "--format", "plantuml");
        assertThat(out()).startsWith("@startuml\n");

        assertClassDiagram(ClassDiagramOptions.builder().packagePrefix("com.shop.common").build(), ClassDiagramFormat.MERMAID,
                "--package", "com.shop.common");
        assertThat(out()).contains("AuditHelper").doesNotContain("OrderService");
        assertClassDiagram(ClassDiagramOptions.builder().packagePrefix("com.shop.order").includeSubpackages(false).maxTypes(0).build(),
                ClassDiagramFormat.MERMAID, "--package", "com.shop.order", "--no-subpackages", "--max-types", "0");
        assertClassDiagram(ClassDiagramOptions.builder().types("OrderService").build(), ClassDiagramFormat.MERMAID, "--type", "OrderService");
        assertThat(out()).contains("OrderController").contains("OrderMapper").doesNotContain("AuditHelper");
        assertClassDiagram(ClassDiagramOptions.builder().types("OrderService", "AuditHelper").neighborDepth(0).build(), ClassDiagramFormat.MERMAID,
                "--type", "OrderService", "--type", "AuditHelper", "--depth", "0");
        assertThat(out()).contains("AuditHelper").doesNotContain("OrderController");
        assertClassDiagram(ClassDiagramOptions.builder().members(MemberLevel.NONE).build(), ClassDiagramFormat.MERMAID, "--members", "none");
        assertThat(out()).doesNotContain("selectOrder");
        assertClassDiagram(ClassDiagramOptions.builder().members(MemberLevel.PUBLIC).hideAccessors(true).build(), ClassDiagramFormat.MERMAID,
                "--members", "public", "--hide-accessors");
        assertClassDiagram(ClassDiagramOptions.builder().relationKinds(EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS).build(), ClassDiagramFormat.MERMAID,
                "--relations", "extends,implements");
        assertClassDiagram(ClassDiagramOptions.builder()
                .relationKinds(EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.ASSOCIATES, EdgeKind.DEPENDS_ON, EdgeKind.INJECTS).build(),
                ClassDiagramFormat.MERMAID, "--relations", "extends, implements,associates,DEPENDS,injects");
        assertClassDiagram(ClassDiagramOptions.builder().includeExternal(true).showExternalSupertypes(true).includeNested(false)
                .includeAnonymous(true).maxTypes(3).groupByPackage(true).build(), ClassDiagramFormat.PLANTUML,
                "--external", "--external-supertypes", "--no-nested", "--anonymous", "--max-types", "3", "--group-by-package", "--format=plantuml");
    }

    @Test
    @DisplayName("F-001-AC34 classdiagram은 타입 수 상한을 넘겨 멤버를 생략하면 그 경고를 표준 오류에 알린다")
    void classDiagramWarnings() {
        assertOk("classdiagram", file, "--max-types", "1");
        assertThat(err()).contains("상한");
        assertThat(out()).startsWith("classDiagram\n");
    }

    @Test
    @DisplayName("F-001-AC35 classdiagram은 범위에 드는 타입이 없으면 종료 코드 1, 잘못된 옵션 값이면 2로 끝난다")
    void classDiagramErrors() {
        assertThat(run("classdiagram", empty)).isEqualTo(1);
        assertThat(err()).contains("타입");
        assertThat(out()).isEmpty();
        assertThat(run("classdiagram", file, "--package", "no.such")).isEqualTo(1);
        assertThat(err()).contains("no.such");
        // com.shop에는 하위 패키지에만 타입이 있다
        assertThat(run("classdiagram", file, "--package", "com.shop", "--no-subpackages")).isEqualTo(1);
        assertThat(run("classdiagram", file, "--type", "Nope")).isEqualTo(1);
        assertThat(err()).contains("Nope");
        assertOk("classdiagram", file, "--type", "Nope", "--type", "AuditHelper");
        assertThat(err()).contains("Nope").doesNotContain("AuditHelper");
        assertOk("classdiagram", javaOnly);
        assertThat(out()).contains("Tool");

        assertThat(run("classdiagram", file, "--relations", "extends,friends")).isEqualTo(2);
        assertThat(err()).contains("friends");
        assertThat(run("classdiagram", file, "--members", "private")).isEqualTo(2);
        assertThat(run("classdiagram", file, "--format", "dbml")).isEqualTo(2);
        assertThat(run("classdiagram", file, "--max-types", "x")).isEqualTo(2);
    }

    // ---------------------------------------------------------------- calltree

    @Test
    @DisplayName("F-001-AC36 calltree는 검색어나 노드 ID로 찾은 노드의 콜트리를 text(기본), json, mermaid로 내고 옵션을 CallTreeOptions에 전달한다")
    void callTree() {
        assertOk("calltree", "GET /orders/{id}", "--graph", file);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(graph, ShopGraphFixture.ENDPOINT, CallTreeOptions.callees())));
        assertThat(out().lines().findFirst().orElseThrow()).contains("GET /orders/{id}");
        assertThat(out()).contains("OrderController#detail").contains("selectOrder").contains("ORDERS").contains("OrderController.java:12");
        assertThat(err()).isEmpty();

        assertOk("calltree", ShopGraphFixture.SERVICE_CREATE, "--graph", dir.toString());
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(graph, ShopGraphFixture.SERVICE_CREATE, CallTreeOptions.callees())));
        assertThat(out()).contains("insertOrder");

        assertOk("calltree", "orders", "--callers", "--graph", file);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(graph, Ids.table("ORDERS"), CallTreeOptions.callers())));
        assertThat(out()).contains("GET /orders/{id}");

        assertOk("calltree", "GET /orders/{id}", "--graph", file, "--format", "json");
        assertThat(out()).isEqualTo(CallTrees.toJson(CallTrees.build(graph, ShopGraphFixture.ENDPOINT, CallTreeOptions.callees()))).startsWith("{");
        assertOk("calltree", "GET /orders/{id}", "--graph", file, "--format", "mermaid");
        assertThat(out()).isEqualTo(CallTrees.toMermaid(CallTrees.build(graph, ShopGraphFixture.ENDPOINT, CallTreeOptions.callees())))
                .startsWith("flowchart TD");

        CallTreeOptions all = CallTreeOptions.builder().direction(Direction.CALLERS).maxDepth(2).maxNodes(3).expandImplementations(false)
                .narrowByInjection(false).collapseRepeated(false).hideExternal(true).expandRenderedViews(false).build();
        assertOk("calltree", "ORDERS", "--graph", file, "--callers", "--depth", "2", "--max-nodes", "3", "--no-impl", "--no-inject",
                "--no-collapse", "--hide-external", "--no-views", "--no-location");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(graph, Ids.table("ORDERS"), all), false));
        assertThat(out()).doesNotContain(".xml:").contains("생략");

        assertThat(run("calltree", "ORDERS", "--graph", file, "--format", "xml")).isEqualTo(2);
        assertThat(run("calltree", "ORDERS", "--graph", file, "--depth", "deep")).isEqualTo(2);
        assertThat(run("calltree", "ORDERS", "--graph", file, "--max-nodes", "0")).isEqualTo(2);
    }

    @Test
    @DisplayName("F-001-AC37 calltree는 후보가 여럿이면 번호·종류·ID·위치 목록과 좁히는 방법을 알리고 종료 코드 1로 끝난다. --pick, --kind로 고를 수 있다")
    void callTreeCandidates() {
        assertThat(run("calltree", "find", "--graph", file)).isEqualTo(1);
        assertThat(out()).isEmpty();
        assertThat(err()).contains("2개").contains("--pick").contains("--kind")
                .containsPattern("1\\s+JAVA_METHOD\\s+" + java.util.regex.Pattern.quote(ShopGraphFixture.AUDIT_FIND)
                        + "\\s+src/main/java/com/shop/common/AuditHelper.java:5")
                .containsPattern("2\\s+JAVA_METHOD\\s+" + java.util.regex.Pattern.quote(ShopGraphFixture.SERVICE_FIND)
                        + "\\s+src/main/java/com/shop/order/OrderService.java:21");

        assertOk("calltree", "find", "--graph", file, "--pick", "2");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(graph, ShopGraphFixture.SERVICE_FIND, CallTreeOptions.callees())));
        assertThat(run("calltree", "find", "--graph", file, "--pick", "3")).isEqualTo(1);
        assertThat(err()).contains("--pick").contains(ShopGraphFixture.SERVICE_FIND);
        assertThat(run("calltree", "find", "--graph", file, "--pick", "0")).isEqualTo(2);

        // 메서드와 매퍼 구문의 이름이 같다
        assertThat(run("calltree", "selectOrder", "--graph", file)).isEqualTo(1);
        assertThat(err()).contains("SQL_STATEMENT").contains("JAVA_METHOD");
        assertOk("calltree", "selectOrder", "--graph", file, "--kind", "sql_statement");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(graph, ShopGraphFixture.STMT_SELECT, CallTreeOptions.callees())));
        assertThat(run("calltree", "selectOrder", "--graph", file, "--kind", "NOPE")).isEqualTo(2);
        assertThat(err()).contains("NOPE").contains("JAVA_METHOD");

        assertThat(run("calltree", "no-such-thing", "--graph", file)).isEqualTo(1);
        assertThat(err()).contains("no-such-thing").contains("codeatlas find");
        assertThat(out()).isEmpty();
    }

    @Test
    @DisplayName("F-001-AC55 calltree는 기본으로 컨트롤러가 그리는 JSP 아래를 펼치지 않고 접근자 호출을 접으며 --views, --show-accessors로 켠다. --no-views는 받되 효과가 없다")
    void callTreeDefaultsFoldViewsAndAccessors() throws IOException {
        String screens = ShopGraphFixture.save(ShopGraphFixture.screens(), work.resolve("screens")).toString();
        GraphModel g = GraphJson.read(Path.of(screens));
        CallTreeOptions folded = CallTreeOptions.builder().hideAccessors(true).hideExternal(true).build();

        assertOk("calltree", ShopGraphFixture.ENDPOINT, "--graph", screens);
        String byDefault = out();
        assertThat(byDefault).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT, folded)));
        assertThat(byDefault).contains("detail.jsp").contains("selectOrder").doesNotContain("getName").doesNotContain("POST /orders");

        assertOk("calltree", ShopGraphFixture.ENDPOINT, "--graph", screens, "--views");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT,
                CallTreeOptions.builder().hideAccessors(true).hideExternal(true).expandRenderedViews(true).build())));
        assertThat(out()).contains("POST /orders").contains("OrderController#save").doesNotContain("getName");

        assertOk("calltree", ShopGraphFixture.ENDPOINT, "--graph", screens, "--show-accessors");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT,
                CallTreeOptions.builder().hideExternal(true).build())));
        assertThat(out()).contains("OrderVO#getName").doesNotContain("POST /orders");

        // 하위 호환: 전에 쓰던 --no-views는 오류가 아니고 기본과 같은 결과다. 도움말에는 없다
        assertOk("calltree", ShopGraphFixture.ENDPOINT, "--graph", screens, "--no-views");
        assertThat(out()).isEqualTo(byDefault);
        assertOk("help", "calltree");
        assertThat(out()).contains("--views ").contains("--show-accessors").contains("--include-data-load").doesNotContain("--no-views");
    }

    @Test
    @DisplayName("F-001-AC64 calltree는 기본으로 소스 없는 메서드의 호출 줄을 접고(표준 산출물과 같다) --show-external로 편다. 전에 쓰던 --hide-external은 받되 효과가 없고 도움말에 없다")
    void callTreeFoldsExternalByDefault() throws IOException {
        String screens = ShopGraphFixture.save(ShopGraphFixture.screens(), work.resolve("screens")).toString();
        GraphModel g = GraphJson.read(Path.of(screens));

        assertOk("calltree", ShopGraphFixture.ENDPOINT, "--graph", screens);
        String byDefault = out();
        assertThat(byDefault).doesNotContain("Strings#trim").contains("OrderService#find");

        assertOk("calltree", ShopGraphFixture.ENDPOINT, "--graph", screens, "--show-external");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT,
                CallTreeOptions.builder().hideAccessors(true).build())));
        assertThat(out()).contains("Strings#trim(String)");

        assertOk("calltree", ShopGraphFixture.ENDPOINT, "--graph", screens, "--hide-external");
        assertThat(out()).isEqualTo(byDefault);
        assertOk("help", "calltree");
        assertThat(out()).contains("--show-external").doesNotContain("--hide-external");
        assertOk("--help");
        assertThat(out()).contains("[--show-external]").doesNotContain("--hide-external");
    }

    @Test
    @DisplayName("F-001-AC65 calltree는 검색어와 표시 이름이 정확히 같은 후보가 종류 우선순위에서 가장 앞선 종류에 하나뿐이면 그것을 고르고 다른 후보의 수를 표준 오류에 한 줄로 알린다. find는 전부 보인다")
    void callTreePrefersExactMatchOfLeadingKind() throws IOException {
        GraphModel screensGraph = ShopGraphFixture.screens();
        // 같은 화면의 다른 줄에서 나가는 같은 요청 하나 더
        String second = Ids.httpRequest("POST", "/orders", "src/main/webapp/WEB-INF/views/order/detail.jsp", 30);
        screensGraph.declare(Node.builder(second, NodeKind.HTTP_REQUEST).name("POST /orders")
                .location(SourceLocation.of("src/main/webapp/WEB-INF/views/order/detail.jsp", 30))
                .attr("httpMethod", "POST").attr("path", "/orders").attr("api", "form").build());
        String screens = ShopGraphFixture.save(screensGraph, work.resolve("screens")).toString();
        GraphModel g = GraphJson.read(Path.of(screens));
        CallTreeOptions defaults = CallTreeOptions.builder().hideAccessors(true).hideExternal(true).build();

        // 엔드포인트 1개 + 같은 글자의 요청 2개: 엔드포인트를 고른다
        assertOk("calltree", "POST /orders", "--graph", screens);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT_SAVE, defaults)));
        assertThat(err().strip().lines()).hasSize(1);
        assertThat(err()).contains("HTTP_ENDPOINT").contains("다른 후보 2개").contains("--kind");
        // find는 전부 보인다
        assertOk("find", "POST /orders", "--graph", screens);
        assertThat(out().lines()).hasSize(3);
        // --kind, --pick은 전과 같다
        assertThat(run("calltree", "POST /orders", "--graph", screens, "--kind", "HTTP_REQUEST")).isEqualTo(1);
        assertThat(err()).contains("2개").contains("--pick");
        assertOk("calltree", "POST /orders", "--graph", screens, "--kind", "HTTP_REQUEST", "--pick", "1");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.REQUEST_SAVE, defaults)));
        assertThat(err()).isEmpty();
        // 표시 이름이 정확히 같지 않은 후보들(이름의 일부)은 전처럼 목록을 낸다
        assertThat(run("calltree", "find", "--graph", screens)).isEqualTo(1);
        assertThat(run("calltree", "selectOrder", "--graph", screens)).isEqualTo(1);
        assertThat(err()).contains("SQL_STATEMENT").contains("JAVA_METHOD");
    }

    @Test
    @DisplayName("F-001-AC69 calltree는 [METHOD ]<경로> 꼴의 검색어를 URL로 보고 엔드포인트를 찾는다: ANY 엔드포인트, 쿼리·fragment, 경로 변수, 컨텍스트 경로를 맞추고 엔드포인트가 하나면 요청이 몇 개든 그것을 고른다")
    void callTreeFindsEndpointByUrl() throws IOException {
        String urls = ShopGraphFixture.save(ShopGraphFixture.urls(), work.resolve("urls")).toString();
        GraphModel g = GraphJson.read(Path.of(urls));
        CallTreeOptions defaults = CallTreeOptions.builder().hideAccessors(true).hideExternal(true).build();
        String fileDown = CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT_FILE_DOWN, defaults));
        String userList = CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT_USER_LIST, defaults));

        // (2) 메서드를 줘도 ANY 엔드포인트에 맞는다. (5) 같은 경로의 요청이 둘 있어도 엔드포인트를 고르고 한 줄로 알린다
        assertOk("calltree", "GET /cmm/fms/FileDown.do", "--graph", urls);
        assertThat(out()).isEqualTo(fileDown).startsWith("[endpoint] ANY /cmm/fms/FileDown.do");
        assertThat(err().strip().lines()).hasSize(1);
        assertThat(err()).contains("HTTP_ENDPOINT").contains(ShopGraphFixture.ENDPOINT_FILE_DOWN).contains("다른 후보 2개").contains("--kind");
        // 메서드가 없으면 그 경로의 엔드포인트 전부가 후보다(여기서는 하나)
        assertOk("calltree", "/cmm/fms/FileDown.do", "--graph", urls);
        assertThat(out()).isEqualTo(fileDown);
        assertThat(err()).contains("다른 후보 2개");
        assertOk("calltree", "post /cmm/fms/FileDown.do", "--graph", urls);
        assertThat(out()).isEqualTo(fileDown);
        assertThat(err()).isEmpty();

        // (1) 쿼리·fragment를 떼고, (4) 못 찾으면 컨텍스트 경로(맨 앞 1~2 세그먼트)를 떼고 다시 찾는다
        assertOk("calltree", "/cmm/fms/FileDown.do?atchFileId=FILE_01&fileSn=0#top", "--graph", urls);
        assertThat(out()).isEqualTo(fileDown);
        assertOk("calltree", "POST /egov/cmm/fms/FileDown.do?atchFileId=FILE_01", "--graph", urls);
        assertThat(out()).isEqualTo(fileDown);
        assertThat(err().strip().lines()).hasSize(1);
        assertThat(err()).contains("컨텍스트 경로").contains("/egov").contains(ShopGraphFixture.ENDPOINT_FILE_DOWN);
        assertOk("calltree", "http://localhost:8080/portal/egov/cmm/fms/FileDown.do?atchFileId=FILE_01", "--graph", urls);
        assertThat(out()).isEqualTo(fileDown);
        assertThat(err()).contains("/portal/egov");
        // 세 세그먼트는 떼지 않는다
        assertThat(run("calltree", "/a/b/c/cmm/fms/FileDown.do", "--graph", urls)).isEqualTo(1);
        assertThat(err()).contains("찾지 못했다");

        // 프로퍼티 자리표시자를 푼 경로(resolvedPath)로 찾는다. 메서드, 컨텍스트 경로, 쿼리가 붙어도 같다
        assertOk("calltree", "/a/sys/user/list", "--graph", urls);
        assertThat(out()).isEqualTo(userList);
        assertThat(err()).isEmpty();
        assertOk("calltree", "GET /a/sys/user/list", "--graph", urls);
        assertThat(out()).isEqualTo(userList);
        assertOk("calltree", "/jeesite/a/sys/user/list?pageNo=2&pageSize=30", "--graph", urls);
        assertThat(out()).isEqualTo(userList);
        assertThat(err()).contains("/jeesite");
        assertOk("calltree", "/${adminPath}/sys/user/list", "--graph", urls);
        assertThat(out()).isEqualTo(userList);

        // (3) 경로 변수 패턴에 구체 경로를 줘도 맞춘다. 글자 그대로 같은 경로가 있으면 그쪽이 먼저다
        assertOk("calltree", "GET /orders/15", "--graph", urls);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT, defaults)));
        assertThat(err().strip().lines()).hasSize(1);
        assertThat(err()).contains(ShopGraphFixture.ENDPOINT).contains("경로 변수");
        assertOk("calltree", "DELETE /shop/orders/15?force=true", "--graph", urls);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT_DELETE, defaults)));
        assertOk("calltree", "/orders/new", "--graph", urls);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT_NEW, defaults)));
        assertThat(err()).isEmpty();
        assertOk("calltree", "/orders/15/items/3", "--graph", urls);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT_ITEM, defaults)));
        // 엔드포인트가 둘이면(GET, DELETE /orders/{id}) 전처럼 목록을 내고 --pick으로 고른다
        assertThat(run("calltree", "/orders/15", "--graph", urls)).isEqualTo(1);
        assertThat(out()).isEmpty();
        assertThat(err()).contains("2개").contains("--pick").contains(ShopGraphFixture.ENDPOINT).contains(ShopGraphFixture.ENDPOINT_DELETE);
        assertOk("calltree", "/orders/15", "--graph", urls, "--pick", "1");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT_DELETE, defaults)));
        // 메서드가 다르면 맞지 않는다
        assertThat(run("calltree", "PUT /orders/15", "--graph", urls)).isEqualTo(1);
        assertThat(err()).contains("찾지 못했다");
        // 같은 경로에 GET과 ANY 엔드포인트가 함께 있으면 둘 다 후보다. 검색어가 하나의 표시 이름과 같으면 그것을 고른다(F-001-R46)
        assertOk("calltree", "GET /health", "--graph", urls);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.ENDPOINT_HEALTH_GET, defaults)));
        assertThat(err()).contains(ShopGraphFixture.ENDPOINT_HEALTH_GET).contains("다른 후보 1개");
        assertThat(run("calltree", "/ctx/health?verbose=1", "--graph", urls)).isEqualTo(1);
        assertThat(err()).contains("2개").contains("http:endpoint:ANY /health").contains(ShopGraphFixture.ENDPOINT_HEALTH_GET);

        // --kind HTTP_ENDPOINT를 줘도 같은 규칙이고, HTTP_REQUEST로 좁히면 전처럼 요청만 찾는다. find는 그대로다
        assertOk("calltree", "GET /egov/cmm/fms/FileDown.do?x=1", "--graph", urls, "--kind", "HTTP_ENDPOINT");
        assertThat(out()).isEqualTo(fileDown);
        assertThat(err()).doesNotContain("다른 후보");
        assertThat(run("calltree", "GET /cmm/fms/FileDown.do", "--graph", urls, "--kind", "HTTP_REQUEST")).isEqualTo(1);
        assertThat(err()).contains("2개").contains("HTTP_REQUEST").doesNotContain("HTTP_ENDPOINT");
        assertOk("calltree", "GET /cmm/fms/FileDown.do", "--graph", urls, "--pick", "2");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.REQUEST_FILE_DOWN, defaults)));
        assertOk("find", "GET /cmm/fms/FileDown.do", "--graph", urls);
        assertThat(out().lines()).hasSize(2).allMatch(line -> line.contains("HTTP_REQUEST"));
        assertThat(run("impact", "/cmm/fms/FileDown.do", "--graph", urls)).isEqualTo(1);

        assertOk("help", "calltree");
        assertThat(out()).contains("URL").contains("컨텍스트 경로").contains("ANY");
    }

    @Test
    @DisplayName("F-001-AC75 calltree의 URL 검색은 메서드 후보가 있는 화면 요청(httpMethod=ANY, httpMethods=[POST, PUT])을 그 후보의 메서드로만 찾고, 엔드포인트가 없거나 --kind HTTP_REQUEST로 좁혀도 URL로 요청을 찾는다")
    void callTreeUrlMatchesRequestMethodCandidates() throws IOException {
        CallTreeOptions defaults = CallTreeOptions.builder().hideAccessors(true).hideExternal(true).build();

        // 프런트만 분석한 그래프(엔드포인트 없음). 요청 하나: ANY /api/schedule, httpMethods=[POST, PUT]
        GraphModel single = new GraphModel();
        single.project(new ProjectInfo("front", "/work/front", Instant.parse("2026-10-11T00:00:00Z")));
        single.declare(Node.builder(EndpointLookupTest.REQUEST_CANDIDATES, NodeKind.HTTP_REQUEST).name("ANY /api/schedule")
                .location(SourceLocation.of(EndpointLookupTest.EDIT, 146))
                .attr("api", "fetch").attr("httpMethod", "ANY").attr("httpMethods", List.of("POST", "PUT")).attr("path", "/api/schedule").build());
        String front = ShopGraphFixture.save(single, work.resolve("front")).toString();
        GraphModel f = GraphJson.read(Path.of(front));
        String requestTree = CallTrees.toText(CallTrees.build(f, EndpointLookupTest.REQUEST_CANDIDATES, defaults));
        for (String query : List.of("PUT /api/schedule", "post /api/schedule?id=3", "/api/schedule")) {
            assertOk("calltree", query, "--graph", front);
            assertThat(out()).as(query).isEqualTo(requestTree);
            assertThat(err()).as(query).isEmpty();
        }
        assertOk("calltree", "PUT /api/schedule", "--graph", front, "--kind", "HTTP_REQUEST");
        assertThat(out()).isEqualTo(requestTree);
        // 후보에 없는 메서드는 맞지 않는다
        assertThat(run("calltree", "GET /api/schedule", "--graph", front)).isEqualTo(1);
        assertThat(err()).contains("찾지 못했다");
        assertThat(run("calltree", "GET /api/schedule", "--graph", front, "--kind", "HTTP_REQUEST")).isEqualTo(1);
        assertThat(err()).contains("찾지 못했다");

        // 엔드포인트(POST, GET)와 요청 셋(메서드 후보, 후보 없는 ANY, GET)이 함께 있는 그래프
        String both = ShopGraphFixture.save(EndpointLookupTest.frontAndBack(), work.resolve("both")).toString();
        GraphModel b = GraphJson.read(Path.of(both));
        assertOk("calltree", "POST /api/schedule", "--graph", both);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(b, EndpointLookupTest.ENDPOINT_POST, defaults)));
        assertThat(err()).contains(EndpointLookupTest.ENDPOINT_POST).contains("다른 후보 2개").contains("--kind HTTP_REQUEST");
        // GET의 다른 후보에는 메서드 후보가 [POST, PUT]인 요청이 없다(후보 없는 ANY 요청과 GET 요청 둘)
        assertOk("calltree", "GET /api/schedule", "--graph", both);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(b, EndpointLookupTest.ENDPOINT_GET, defaults)));
        assertThat(err()).contains("다른 후보 2개");
        // 요청으로 좁히면 후보는 그 둘이고, 검색어와 이름이 정확히 같은 GET 요청을 고른다(F-001-R46)
        assertOk("calltree", "GET /api/schedule", "--graph", both, "--kind", "HTTP_REQUEST");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(b, EndpointLookupTest.REQUEST_GET, defaults)));
        assertThat(err()).contains(EndpointLookupTest.REQUEST_GET).contains("다른 후보 1개");
        assertOk("calltree", "GET /api/schedule", "--graph", both, "--kind", "HTTP_REQUEST", "--pick", "1");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(b, EndpointLookupTest.REQUEST_ANY, defaults)));
        assertThat(run("calltree", "GET /api/schedule", "--graph", both, "--kind", "HTTP_REQUEST", "--pick", "3")).isEqualTo(1);
        assertThat(err()).contains("후보는 2개").doesNotContain(EndpointLookupTest.REQUEST_CANDIDATES);
        // 안내대로 --kind HTTP_REQUEST로 좁히면 URL로 요청을 찾는다. 후보 목록의 줄 끝에 메서드 후보가 보인다(ID에는 ANY로 적혀 있다)
        assertThat(run("calltree", "POST /api/schedule", "--graph", both, "--kind", "HTTP_REQUEST")).isEqualTo(1);
        assertThat(err()).contains("2개").contains("--pick").doesNotContain("HTTP_ENDPOINT")
                .contains(EndpointLookupTest.REQUEST_CANDIDATES + "  " + EndpointLookupTest.EDIT + ":146  [POST|PUT]")
                .contains(EndpointLookupTest.REQUEST_ANY);
        assertOk("calltree", "POST /api/schedule", "--graph", both, "--kind", "HTTP_REQUEST", "--pick", "2");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(b, EndpointLookupTest.REQUEST_CANDIDATES, defaults)));
        // 그 메서드의 엔드포인트가 없으면 요청이 후보다(PUT: 메서드 후보 요청과 후보 없는 ANY 요청)
        assertThat(run("calltree", "PUT /api/schedule", "--graph", both)).isEqualTo(1);
        assertThat(err()).contains("2개").contains(EndpointLookupTest.REQUEST_CANDIDATES).contains(EndpointLookupTest.REQUEST_ANY)
                .doesNotContain(EndpointLookupTest.REQUEST_GET);
        // --kind로 엔드포인트만 고르면 요청은 후보가 아니다
        assertThat(run("calltree", "PUT /api/schedule", "--graph", both, "--kind", "HTTP_ENDPOINT")).isEqualTo(1);
        assertThat(err()).contains("찾지 못했다");

        assertOk("help", "calltree");
        assertThat(out()).contains("메서드 후보").contains("POST|PUT").contains("같은 경로의 화면 요청");
    }

    @Test
    @DisplayName("F-001-AC83 calltree의 URL 검색은 엔드포인트가 없는 그래프에서 구체 경로를 화면 요청의 경로 패턴({*})에 맞추고, --kind HTTP_REQUEST로 좁혀도 같은 경로의 요청을 패턴으로 찾는다")
    void callTreeUrlMatchesRequestPatterns() throws IOException {
        CallTreeOptions defaults = CallTreeOptions.builder().hideAccessors(true).hideExternal(true).build();
        String front = ShopGraphFixture.save(EndpointLookupTest.frontWithPatterns(), work.resolve("patterns")).toString();
        GraphModel f = GraphJson.read(Path.of(front));
        String detailTree = CallTrees.toText(CallTrees.build(f, EndpointLookupTest.REQUEST_DETAIL, defaults));

        assertOk("calltree", "PUT /api/schedule/1", "--graph", front);
        assertThat(out()).isEqualTo(detailTree);
        assertThat(err()).contains(EndpointLookupTest.REQUEST_DETAIL).contains("경로 변수 패턴에 맞춤");
        assertOk("calltree", "PUT /api/schedule/1", "--graph", front, "--kind", "HTTP_REQUEST");
        assertThat(out()).isEqualTo(detailTree);
        // 패턴 꼴로 준 검색어는 전처럼 알림 없이 찾는다
        assertOk("calltree", "PUT /api/schedule/{*}", "--graph", front);
        assertThat(out()).isEqualTo(detailTree);
        assertThat(err()).isEmpty();
        // 컨텍스트 경로를 뗀 것도 알린다
        assertOk("calltree", "PUT /ctx/api/schedule/1", "--graph", front);
        assertThat(out()).isEqualTo(detailTree);
        assertThat(err()).contains("컨텍스트 경로 /ctx를 떼고").contains("경로 변수 패턴에 맞춤");

        // 엔드포인트가 있는 그래프: 엔드포인트는 컨텍스트 경로(/api)를 떼고 패턴으로 찾고, 요청으로 좁히면 {*} 요청을 찾는다
        GraphModel graph = EndpointLookupTest.frontWithPatterns();
        graph.declare(Node.builder(EndpointLookupTest.ENDPOINT_DETAIL_PUT, NodeKind.HTTP_ENDPOINT).name("PUT /schedule/{schdulId}")
                .attr("httpMethod", "PUT").attr("path", "/schedule/{schdulId}").build());
        String both = ShopGraphFixture.save(graph, work.resolve("patterns-both")).toString();
        GraphModel b = GraphJson.read(Path.of(both));
        assertOk("calltree", "PUT /api/schedule/5", "--graph", both);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(b, EndpointLookupTest.ENDPOINT_DETAIL_PUT, defaults)));
        assertThat(err()).contains("다른 후보 1개(같은 경로의 화면 요청)");
        assertOk("calltree", "PUT /api/schedule/5", "--graph", both, "--kind", "HTTP_REQUEST");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(b, EndpointLookupTest.REQUEST_DETAIL, defaults)));
    }

    @Test
    @DisplayName("F-001-AC84 calltree의 URL 검색이 메서드가 달라 실패하면 같은 경로의 다른 메서드 엔드포인트(없으면 화면 요청)를 알리고, 찾지 못한 URL에는 패턴 꼴({*})로도 찾을 수 있다고 안내한다")
    void callTreeUrlSuggestsOtherMethodsAndPatternForm() throws IOException {
        GraphModel graph = new GraphModel();
        graph.project(new ProjectInfo("back", "/work/back", Instant.parse("2026-10-11T00:00:00Z")));
        graph.declare(Node.builder(Ids.httpEndpoint("POST", "/cop/insert.do"), NodeKind.HTTP_ENDPOINT).name("POST /cop/insert.do")
                .attr("httpMethod", "POST").attr("path", "/cop/insert.do").build());
        graph.declare(Node.builder(Ids.httpEndpoint("PUT", "/cop/{id}"), NodeKind.HTTP_ENDPOINT).name("PUT /cop/{id}")
                .attr("httpMethod", "PUT").attr("path", "/cop/{id}").build());
        String back = ShopGraphFixture.save(graph, work.resolve("back")).toString();

        assertThat(run("calltree", "GET /cop/insert.do", "--graph", back)).isEqualTo(1);
        assertThat(err()).contains("'GET /cop/insert.do'에 맞는 노드를 찾지 못했다")
                .contains("같은 경로에 다른 메서드의 엔드포인트가 있다: POST /cop/insert.do")
                .contains("경로 변수가 든 URL은 패턴 꼴('/orders/{id}', 화면 요청은 '/api/orders/{*}')로도 찾는다");
        // 패턴에 맞는 다른 메서드도 알린다(컨텍스트 경로를 뗀 뒤에도)
        assertThat(run("calltree", "DELETE /ctx/cop/15", "--graph", back)).isEqualTo(1);
        assertThat(err()).contains("같은 경로에 다른 메서드의 엔드포인트가 있다: PUT /cop/{id}");
        // 같은 경로가 아예 없으면 다른 메서드 안내는 없고 패턴 안내만 있다
        assertThat(run("calltree", "GET /none/here", "--graph", back)).isEqualTo(1);
        assertThat(err()).contains("찾지 못했다").doesNotContain("다른 메서드").contains("패턴 꼴");
        // URL 꼴이 아닌 검색어에는 둘 다 없다
        assertThat(run("calltree", "NoSuchService", "--graph", back)).isEqualTo(1);
        assertThat(err()).contains("찾지 못했다").doesNotContain("다른 메서드").doesNotContain("패턴 꼴");

        // 엔드포인트가 없는 그래프: 다른 메서드의 화면 요청을 알린다
        GraphModel single = new GraphModel();
        single.project(new ProjectInfo("front", "/work/front", Instant.parse("2026-10-11T00:00:00Z")));
        single.declare(Node.builder(EndpointLookupTest.REQUEST_GET, NodeKind.HTTP_REQUEST).name("GET /api/schedule")
                .attr("api", "fetch").attr("httpMethod", "GET").attr("path", "/api/schedule").build());
        String front = ShopGraphFixture.save(single, work.resolve("single-get")).toString();
        assertThat(run("calltree", "POST /api/schedule", "--graph", front)).isEqualTo(1);
        assertThat(err()).contains("같은 경로에 다른 메서드의 화면 요청이 있다: GET /api/schedule");
        assertOk("help", "calltree");
        assertThat(out()).contains("화면 요청의 경로 패턴").contains("다른 메서드");
    }

    @Test
    @DisplayName("F-001-AC76 action 없는 폼의 요청({self})은 URL 검색의 후보가 아니고, 그 엔드포인트를 URL로 찾으면 엔드포인트만 나온다")
    void callTreeUrlIgnoresSelfSubmitRequests() throws IOException {
        String page = "src/main/webapp/WEB-INF/jsp/user/form.jsp";
        String self = Ids.httpRequest("POST", "{self}", page, 95);
        String endpoint = Ids.httpEndpoint("POST", "/user/form");
        GraphModel selfGraph = ShopGraphFixture.javaOnly();
        selfGraph.declare(Node.builder(self, NodeKind.HTTP_REQUEST).name("POST {self}").location(SourceLocation.of(page, 95))
                .attr("api", "form").attr("httpMethod", "POST").attr("path", "{self}").attr("selfSubmit", true).attr("url", "").build());
        selfGraph.declare(Node.builder(endpoint, NodeKind.HTTP_ENDPOINT).name("POST /user/form")
                .attr("httpMethod", "POST").attr("path", "/user/form").build());
        selfGraph.addEdge(io.codeatlas.core.model.Edge.builder(self, endpoint, EdgeKind.MATCHES).inferred().attr("selfSubmit", true).build());
        String stored = ShopGraphFixture.save(selfGraph, work.resolve("self")).toString();
        GraphModel g = GraphJson.read(Path.of(stored));
        CallTreeOptions defaults = CallTreeOptions.builder().hideAccessors(true).hideExternal(true).build();

        // 엔드포인트만 고른다: 다른 후보(같은 경로의 화면 요청)가 없으니 표준 오류도 없다
        assertOk("calltree", "POST /user/form", "--graph", stored);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, endpoint, defaults)));
        assertThat(err()).isEmpty();
        assertThat(run("calltree", "POST /user/form", "--graph", stored, "--kind", "HTTP_REQUEST")).isEqualTo(1);
        assertThat(err()).contains("찾지 못했다");
        // {self}를 경로로 준 것은 어느 URL에도 맞지 않는다
        assertThat(run("calltree", "/{self}", "--graph", stored)).isEqualTo(1);
        assertThat(err()).contains("찾지 못했다");
        // 노드 ID로는 전처럼 시작할 수 있다
        assertOk("calltree", self, "--graph", stored);
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, self, defaults)));
    }

    @Test
    @DisplayName("F-001-AC56 calltree와 crud는 데이터 적재 구문을 기본으로 빼고 --include-data-load를 주면 넣는다")
    void includeDataLoad() throws IOException {
        String screens = ShopGraphFixture.save(ShopGraphFixture.screens(), work.resolve("screens")).toString();
        GraphModel g = GraphJson.read(Path.of(screens));
        String table = Ids.table("ORDER_HIST");

        assertOk("calltree", table, "--graph", screens, "--callers");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, table,
                CallTreeOptions.builder().direction(Direction.CALLERS).hideAccessors(true).build())));
        assertThat(out()).doesNotContain("db/data.sql");
        assertOk("calltree", table, "--graph", screens, "--callers", "--include-data-load");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, table,
                CallTreeOptions.builder().direction(Direction.CALLERS).hideAccessors(true).includeDataLoad(true).build())));
        assertThat(out()).contains("db/data.sql");

        assertOk("crud", screens);
        assertThat(out()).isEqualTo(CrudMatrix.of(g, Subject.STATEMENT).toCsv(Layout.MATRIX, false)).doesNotContain("ORDER_HIST");
        assertOk("crud", screens, "--include-data-load");
        assertThat(out()).isEqualTo(CrudMatrix.of(g, Subject.STATEMENT, CrudMatrix.DEFAULT_MAX_DEPTH, true).toCsv(Layout.MATRIX, false))
                .contains("ORDER_HIST");
        assertOk("crud", screens, "--by", "endpoint", "--include-data-load", "--format", "md");
        assertThat(out()).isEqualTo(CrudMatrix.of(g, Subject.ENDPOINT, CrudMatrix.DEFAULT_MAX_DEPTH, true).toMarkdown(Layout.MATRIX));

        // --depth의 뜻은 기준마다 다르다: 엔드포인트는 호출 깊이, 그 밖에는 루틴 호출 걸음 수
        assertOk("help", "crud");
        assertThat(out()).contains("--include-data-load").contains("루틴 호출").doesNotContain("--by endpoint에서 엔드포인트부터 구문까지 따라갈 최대 걸음 수. 기본값 20");
    }

    @Test
    @DisplayName("F-001-AC71 도움말과 README는 crud의 forward·redirect 처리와 칸 끝의 ?, calltree의 --show-accessors(부수 효과 없는 생성자, *Example 빌더)와 --layouts([shared] 공통 JSP)를 적는다")
    void helpExplainsCrudMarksAndCallTreeFolds() throws IOException {
        assertOk("help", "crud");
        assertThat(out()).contains("forward").contains("redirect").contains("별개의 요청").contains("끝의 ?").contains("추론");

        assertOk("help", "calltree");
        assertThat(out()).contains("<init>").contains("생성자").contains("*Example").contains("*Example$Criteria")
                .contains("[shared]").contains("포함하는 화면 n개");

        String readme = Files.readString(Path.of("..", "README.md"), StandardCharsets.UTF_8);
        assertThat(readme).contains("끝의 `?`").contains("`*Example`").contains("[shared]");

        // 옵션은 CallTreeOptions의 hideAccessors, expandLayouts로 전달된다
        String screens = ShopGraphFixture.save(ShopGraphFixture.screens(), work.resolve("screens")).toString();
        GraphModel g = GraphJson.read(Path.of(screens));
        assertOk("calltree", ShopGraphFixture.DETAIL_PAGE, "--graph", screens, "--callers", "--layouts", "--show-accessors");
        assertThat(out()).isEqualTo(CallTrees.toText(CallTrees.build(g, ShopGraphFixture.DETAIL_PAGE, CallTreeOptions.builder()
                .direction(Direction.CALLERS).hideAccessors(false).hideExternal(true).expandLayouts(true).build())));
    }

    @Test
    @DisplayName("F-001-AC78 도움말은 콜트리 줄 끝의 (인자 구문)·(접두 일치)·(동적 뷰 이름 후보), 요청 이름의 POST|PUT·(자기 페이지로 제출), 후보가 둘 이상일 때만 붙는 [ambiguous n]을 적는다")
    void helpExplainsInferenceDetailsAndRequestNames() {
        assertOk("help", "calltree");
        String callTree = out();
        assertThat(callTree).contains("(인자 구문)").contains("구문 ID를 인자로 받는 메서드").contains("(접두 일치)").contains("(동적 뷰 이름 후보)")
                .contains("POST|PUT").contains("(자기 페이지로 제출)").contains("action");
        // [ambiguous n]은 후보가 둘 이상일 때만 나온다. 요청 하나가 후보 엔드포인트 여럿과 맞은 것도 같은 표기다
        assertThat(callTree).contains("[ambiguous n]").contains("n은 2 이상").contains("후보 엔드포인트").doesNotContain("[ambiguous 1]");

        assertOk("help", "crud");
        assertThat(out()).contains("끝의 ?").contains("인자 구문").contains("접두 일치").contains("동적 뷰 이름");
    }

    // ---------------------------------------------------------------- impact

    @Test
    @DisplayName("F-001-AC38 impact는 테이블, 테이블.컬럼, DB 노드 ID의 영향도 리포트를 md(기본)나 json으로 낸다")
    void impact() {
        assertOk("impact", "ORDERS", "--graph", file);
        assertThat(out()).isEqualTo(ImpactAnalysis.analyze(graph, "ORDERS").toMarkdown()).startsWith("# 영향도: ORDERS");
        assertThat(out()).contains("selectOrder").contains("insertOrder").contains("GET /orders/{id}");
        assertThat(err()).isEmpty();

        assertOk("impact", "orders.status", "--graph", file, "--format", "json");
        assertThat(out()).isEqualTo(ImpactAnalysis.analyze(graph, "ORDERS.STATUS").toJson()).startsWith("{");

        assertOk("impact", "db:table:CUSTOMER", "--graph", file, "--depth", "1");
        assertThat(out()).isEqualTo(ImpactAnalysis.analyze(graph, "db:table:CUSTOMER", 1).toMarkdown());
        assertThat(out()).isNotEqualTo(ImpactAnalysis.analyze(graph, "db:table:CUSTOMER").toMarkdown());
    }

    @Test
    @DisplayName("F-001-AC74 impact는 데이터 적재 구문을 기본으로 빼고 뺀 수를 알리며 --include-data-load를 주면 넣는다. analyze 도움말은 대상 외 방언을 --opt mybatis.dialectVariant로 안내한다")
    void impactIncludeDataLoad() throws IOException {
        String screens = ShopGraphFixture.save(ShopGraphFixture.screens(), work.resolve("screens")).toString();
        GraphModel g = GraphJson.read(Path.of(screens));

        assertOk("impact", "ORDER_HIST", "--graph", screens);
        assertThat(out()).isEqualTo(ImpactAnalysis.analyze(g, "ORDER_HIST", ImpactAnalysis.DEFAULT_MAX_DEPTH, false).toMarkdown())
                .contains("데이터 적재 구문 1건 제외").doesNotContain("db/data.sql");
        assertOk("impact", "ORDER_HIST", "--graph", screens, "--include-data-load");
        assertThat(out()).isEqualTo(ImpactAnalysis.analyze(g, "ORDER_HIST", ImpactAnalysis.DEFAULT_MAX_DEPTH, true).toMarkdown())
                .contains("db/data.sql").doesNotContain("건 제외");
        assertOk("impact", "ORDER_HIST", "--graph", screens, "--include-data-load", "--format", "json", "--depth", "2");
        assertThat(out()).isEqualTo(ImpactAnalysis.analyze(g, "ORDER_HIST", 2, true).toJson());
        assertThat(run("impact", "ORDER_HIST", "--graph", screens, "--include-data-load=yes")).isEqualTo(2);

        assertOk("help", "impact");
        assertThat(out()).contains("--include-data-load").contains("데이터 적재");
        assertOk("--help");
        assertThat(out()).contains("codeatlas impact <테이블 | 테이블.컬럼 | 노드 ID> [--depth <n>] [--include-data-load]");

        // --dialect는 oracle, db2, auto만 받는다: 그 밖의 방언은 --opt mybatis.dialectVariant로 안내한다
        assertOk("help", "analyze");
        assertThat(out()).contains("--dialect oracle|db2|auto").contains("--opt mybatis.dialectVariant=<방언>").contains("프로젝트 설정");
        assertThat(run("analyze", work.toString(), "--dialect", "mysql")).isEqualTo(2);
        assertThat(err()).contains("mysql").contains("mybatis.dialectVariant");
    }

    @Test
    @DisplayName("F-001-AC87 impact는 forward로 넘어간 핸들러를 거쳐서만 닿은 항목을 [forward]로 구분해 내고(json은 viaForward), 도움말과 README가 그 표시와 redirect를 따라가지 않는다는 것을 적는다")
    void impactMarksForwardOnlyItems() throws IOException {
        // POST /orders/copy의 핸들러는 forward:/orders/1로 detail에 넘어갈 뿐 ORDERS에 직접 닿지 않는다
        GraphModel forwarding = ShopGraphFixture.shop();
        String copy = Ids.javaMethod(ShopGraphFixture.CONTROLLER, "copy");
        String copyEndpoint = Ids.httpEndpoint("POST", "/orders/copy");
        SourceLocation at = SourceLocation.of("src/main/java/com/shop/order/OrderController.java", 40);
        forwarding.declare(Node.builder(copy, NodeKind.JAVA_METHOD).name("copy").location(at).build());
        forwarding.declare(Node.builder(copyEndpoint, NodeKind.HTTP_ENDPOINT).name("POST /orders/copy")
                .attr("httpMethod", "POST").attr("path", "/orders/copy").location(at).build());
        forwarding.addEdge(Edge.of(copyEndpoint, copy, EdgeKind.ROUTES_TO));
        forwarding.addEdge(Edge.builder(copy, ShopGraphFixture.CONTROLLER_DETAIL, EdgeKind.CALLS).inferred()
                .attr("callType", "forward").attr("path", "/orders/1").attr("viewName", "forward:/orders/1").location(at).build());
        String saved = ShopGraphFixture.save(forwarding, work.resolve("forwarding")).toString();
        GraphModel g = GraphJson.read(Path.of(saved));

        assertOk("impact", "ORDERS", "--graph", saved);
        assertThat(out()).isEqualTo(ImpactAnalysis.analyze(g, "ORDERS").toMarkdown())
                .contains("엔드포인트 2개(직접 1, forward 경유 1)").contains("| [forward] `POST /orders/copy` | endpoint |")
                .contains("(forward: /orders/1)").contains("| `GET /orders/{id}` | endpoint |");
        assertOk("impact", "ORDERS", "--graph", saved, "--format", "json");
        assertThat(out()).isEqualTo(ImpactAnalysis.analyze(g, "ORDERS").toJson()).contains("\"viaForward\": true")
                .contains("\"target\": \"/orders/1\"");
        // forward가 없는 그래프의 출력에는 표시가 없다
        assertOk("impact", "ORDERS", "--graph", file);
        assertThat(out()).doesNotContain("forward");

        assertOk("help", "impact");
        assertThat(out()).contains("[forward]").contains("forward 경유").contains("viaForward").contains("redirect").contains("별개의 요청");
        String readme = Files.readString(Path.of("..", "README.md"), StandardCharsets.UTF_8);
        String section = readme.substring(readme.indexOf("### 4. 테이블 영향도"), readme.indexOf("CRUD 매트릭스의 칸은"));
        assertThat(section).contains("`[forward]`").contains("forward 경유").contains("`viaForward`").contains("redirect");
    }

    @Test
    @DisplayName("F-001-AC38 impact는 대상을 찾지 못하면 종료 코드 1과 안내(비슷한 이름 후보)를 낸다")
    void impactNotFound() {
        assertThat(run("impact", "NOPE", "--graph", file)).isEqualTo(1);
        assertThat(err()).contains("NOPE").contains("codeatlas find");
        assertThat(out()).isEmpty();

        // 일부만 맞는 이름이면 후보를 보여 준다
        assertThat(run("impact", "ORDER", "--graph", file)).isEqualTo(1);
        assertThat(err()).contains("db:table:ORDERS").contains("db:table:ORDER_HIST");

        // 테이블·뷰·컬럼이 아닌 노드는 대상이 아니다
        assertThat(run("impact", ShopGraphFixture.SERVICE_FIND, "--graph", file)).isEqualTo(1);
        assertThat(err()).contains("calltree");

        assertThat(run("impact", "ORDERS", "--graph", javaOnly)).isEqualTo(1);
        assertThat(err()).doesNotContain("Exception");
        assertThat(run("impact", "ORDERS", "--graph", file, "--format", "xml")).isEqualTo(2);
        assertThat(run("impact", "ORDERS", "--graph", file, "--depth", "0")).isEqualTo(2);
    }

    // ---------------------------------------------------------------- crud

    @Test
    @DisplayName("F-001-AC39 crud는 CRUD 매트릭스를 주체(--by), 모양(--layout), 형식(--format)에 맞춰 낸다")
    void crud() {
        assertOk("crud", file);
        assertThat(out()).isEqualTo(CrudMatrix.of(graph, Subject.STATEMENT).toCsv(Layout.MATRIX, false)).contains("ORDERS");
        assertThat(err()).isEmpty();

        assertOk("crud", file, "--by", "method", "--layout", "list", "--format", "md");
        assertThat(out()).isEqualTo(CrudMatrix.of(graph, Subject.METHOD).toMarkdown(Layout.LIST)).startsWith("|");
        assertOk("crud", file, "--by", "statement", "--layout", "by-table", "--format", "csv");
        assertThat(out()).isEqualTo(CrudMatrix.of(graph, Subject.STATEMENT).toCsv(Layout.MATRIX_BY_TABLE, false));
        assertOk("crud", file, "--by", "endpoint", "--depth", "5", "--layout", "matrix");
        assertThat(out()).isEqualTo(CrudMatrix.of(graph, Subject.ENDPOINT, 5).toCsv(Layout.MATRIX, false)).contains("GET /orders/{id}");
        assertOk("crud", file, "--bom");
        assertThat(out()).isEqualTo(CrudMatrix.of(graph, Subject.STATEMENT).toCsv(Layout.MATRIX, true)).startsWith("\uFEFF");
    }

    @Test
    @DisplayName("F-001-AC39 crud는 테이블에 접근하는 주체가 없으면 종료 코드 1과 안내를 내고, 잘못된 옵션 값이면 2로 끝난다")
    void crudErrors() {
        assertThat(run("crud", file, "--by", "routine")).isEqualTo(1);
        assertThat(err()).contains("루틴");
        assertThat(out()).isEmpty();
        assertThat(run("crud", javaOnly)).isEqualTo(1);
        assertThat(out()).isEmpty();
        // 엔드포인트에서 한 걸음으로는 구문에 닿지 않는다
        assertThat(run("crud", file, "--by", "endpoint", "--depth", "1")).isEqualTo(1);
        assertThat(err()).contains("--depth");

        assertThat(run("crud", file, "--by", "table")).isEqualTo(2);
        assertThat(run("crud", file, "--layout", "pivot")).isEqualTo(2);
        assertThat(run("crud", file, "--format", "xlsx")).isEqualTo(2);
        assertThat(run("crud", file, "--format", "md", "--bom")).isEqualTo(2);
        assertThat(err()).contains("--bom");
        assertThat(run("crud", file, "--depth", "0")).isEqualTo(2);
    }

    // ---------------------------------------------------------------- find

    @Test
    @DisplayName("F-001-AC40 find는 검색어에 맞는 노드를 번호·종류·ID·위치 목록으로 내고 --kind, --limit으로 좁힌다")
    void find() {
        assertOk("find", "find", "--graph", file);
        assertThat(out().lines()).hasSize(2);
        assertThat(out()).containsPattern("(?m)^\\s*1\\s+JAVA_METHOD\\s+" + java.util.regex.Pattern.quote(ShopGraphFixture.AUDIT_FIND)
                + "\\s+src/main/java/com/shop/common/AuditHelper.java:5$")
                .containsPattern("(?m)^\\s*2\\s+JAVA_METHOD\\s+" + java.util.regex.Pattern.quote(ShopGraphFixture.SERVICE_FIND));
        assertThat(err()).isEmpty();

        assertOk("find", ShopGraphFixture.ENDPOINT, "--graph", file);
        assertThat(out().lines()).singleElement().asString().contains("HTTP_ENDPOINT").contains(ShopGraphFixture.ENDPOINT);

        assertOk("find", "selectOrder", "--graph", file, "--kind", "SQL_STATEMENT");
        assertThat(out().lines()).singleElement().asString().contains(ShopGraphFixture.STMT_SELECT).contains("OrderMapper.xml:5");
        assertOk("find", "order", "--graph", file, "--kind", "java_type,db_table");
        assertThat(out().lines()).allMatch(line -> line.contains("JAVA_TYPE") || line.contains("DB_TABLE")).hasSizeGreaterThan(3);
        assertOk("find", "order", "--graph", file, "--kind", "JAVA_TYPE", "--kind", "DB_TABLE");
        long total = out().lines().count();

        assertOk("find", "order", "--graph", file, "--kind", "JAVA_TYPE", "--kind", "DB_TABLE", "--limit", "2");
        assertThat(out().lines()).hasSize(2);
        assertThat(err()).contains(total + "개").contains("--limit");
    }

    @Test
    @DisplayName("F-001-AC70 find는 이름의 일부, 대소문자가 다른 이름, * 와일드카드로 찾고, 이름이 같은 노드가 있어 부분 일치를 보이지 않았으면 그 수와 와일드카드 검색을 표준 오류에 알린다")
    void findPartialCaseInsensitiveAndWildcard() {
        // 부분 일치(이름이 정확히 같은 노드가 없을 때)와 대소문자 무시
        assertOk("find", "OrderServ", "--graph", file);
        assertThat(out().lines()).hasSizeGreaterThan(1).allMatch(line -> line.contains("OrderService"));
        assertThat(err()).isEmpty();
        assertOk("find", "orderserv", "--graph", file);
        assertThat(out()).contains(Ids.javaType(ShopGraphFixture.SERVICE)).contains(ShopGraphFixture.SERVICE_FIND);
        assertOk("find", "ORDERSERVICE", "--graph", file);
        assertThat(out().lines()).singleElement().asString().contains("JAVA_TYPE").contains(Ids.javaType(ShopGraphFixture.SERVICE));

        // 이름이 같은 노드가 있으면 거기서 멈춘다(전과 같다). 가려진 부분 일치의 수와 와일드카드 검색을 알린다
        assertOk("find", "orders", "--graph", file);
        assertThat(out().lines()).singleElement().asString().contains("DB_TABLE").contains(Ids.table("ORDERS"));
        assertThat(err().strip().lines()).hasSize(1);
        assertThat(err()).contains("'*orders*'").contains("이름이 같은");

        // * 와일드카드: 이름 전체에 맞춘다(대소문자 무시). 앞뒤에 *를 붙이면 부분 일치다
        assertOk("find", "*orders*", "--graph", file);
        assertThat(out()).contains(Ids.table("ORDERS")).contains(Ids.view("V_ORDERS")).contains(Ids.column("ORDERS", "STATUS"))
                .contains(ShopGraphFixture.ENDPOINT);
        assertThat(out().lines().count()).isGreaterThan(4);
        assertThat(err()).isEmpty();
        assertOk("find", "order*", "--graph", file, "--kind", "DB_TABLE");
        assertThat(out().lines().map(line -> line.strip().split("\\s+")[2]))
                .containsExactly(Ids.table("ORDERS"), Ids.table("ORDER_HIST"));
        assertOk("find", "*Service#f*", "--graph", file);
        assertThat(out().lines()).singleElement().asString().contains(ShopGraphFixture.SERVICE_FIND);
        assertOk("find", "*.order.*#*Order", "--graph", file, "--kind", "JAVA_METHOD");
        assertThat(out().lines()).hasSize(2).allMatch(line -> line.contains("OrderMapper#"));
        assertOk("find", "GET /orders/*", "--graph", file);
        assertThat(out().lines()).singleElement().asString().contains(ShopGraphFixture.ENDPOINT);
        assertOk("find", "*", "--graph", file, "--kind", "HTTP_ENDPOINT", "--limit", "1");
        assertThat(out().lines()).singleElement().asString().contains(ShopGraphFixture.ENDPOINT);

        assertThat(run("find", "zzz*", "--graph", file)).isEqualTo(1);
        assertThat(err()).contains("zzz*");
        assertOk("help", "find");
        assertThat(out()).contains("와일드카드").contains("'*order*'");
    }

    @Test
    @DisplayName("F-001-AC40 find는 맞는 노드가 없으면 종료 코드 1, 잘못된 옵션 값이면 2로 끝난다")
    void findErrors() {
        assertThat(run("find", "zzz-nothing", "--graph", file)).isEqualTo(1);
        assertThat(err()).contains("zzz-nothing");
        assertThat(out()).isEmpty();
        assertThat(run("find", "ORDERS", "--graph", javaOnly)).isEqualTo(1);

        assertThat(run("find", "order", "--graph", file, "--limit", "0")).isEqualTo(2);
        assertThat(run("find", "order", "--graph", file, "--kind", "bogus")).isEqualTo(2);
        assertThat(err()).contains("bogus").contains(NodeKind.JAVA_METHOD.name());
    }

    // ---------------------------------------------------------------- report

    @Test
    @DisplayName("F-001-AC41 report는 저장된 그래프의 분석 요약(SummaryReport)을 내고 --max-items를 전달한다")
    void report() {
        assertOk("report", file);
        assertThat(out()).isEqualTo(SummaryReport.render(graph)).startsWith("# CodeAtlas 분석 요약");
        assertOk("report", file, "--max-items", "0");
        assertThat(out()).isEqualTo(SummaryReport.render(graph, 0));
        assertOk("report", empty);
        assertThat(out()).isEqualTo(SummaryReport.render(ShopGraphFixture.empty()));

        assertThat(run("report", file, "--max-items", "x")).isEqualTo(2);
        assertThat(run("report", file, "--max-items", "-1")).isEqualTo(2);
    }

    // ---------------------------------------------------------------- export

    @Test
    @DisplayName("F-001-AC42 export는 분석을 다시 하지 않고 저장된 그래프로 등록된 출력기를 실행해 --out 폴더에 산출물을 다시 만든다")
    void export() throws IOException {
        Path target = work.resolve("regen/out");

        assertOk("export", dir.toString(), "--out", target.toString(), "--opt", "export.report.maxItems=1", "--opt=export.crud=false",
                "--opt", "viewer.title=주문 = 관리");

        assertThat(RecordingAnalyzer.lastOptions).as("분석기는 실행하지 않는다").isNull();
        assertThat(RecordingExporter.lastOptions).containsEntry("export.report.maxItems", "1").containsEntry("export.crud", "false")
                .containsEntry("viewer.title", "주문 = 관리").hasSize(3);
        assertThat(target.resolve("cli-test-exporter.txt")).hasContent("nodes=" + graph.nodeCount());
        assertThat(target.resolve("graph.json")).hasSameTextualContentAs(dir.resolve("graph.json"));
        assertThat(target.resolve("diagnostics.md")).content().startsWith("# 진단 리포트").contains("PARSE_FAILED");
        assertThat(target.resolve("report.md")).hasContent(SummaryReport.render(graph, 1));
        assertThat(target.resolve("erd/erd.mmd")).exists();
        assertThat(target.resolve("class-diagram/index.md")).exists();
        assertThat(target.resolve("calltree/endpoints.md")).exists();
        assertThat(target.resolve("viewer.html")).exists();
        assertThat(target.resolve("crud")).doesNotExist();
        assertThat(out()).contains(target.toString());
        assertThat(err()).contains("[codeatlas] ").contains("출력기 cli-test-exporter");
    }

    @Test
    @DisplayName("F-001-AC42 export를 그래프가 있는 폴더에 그대로 하면 graph.json은 건드리지 않고 산출물만 다시 쓴다. --quiet는 메시지를 찍지 않는다")
    void exportInPlace() throws IOException {
        byte[] before = Files.readAllBytes(dir.resolve("graph.json"));

        assertOk("export", file, "--out", dir.toString(), "--quiet");

        assertThat(Files.readAllBytes(dir.resolve("graph.json"))).isEqualTo(before);
        assertThat(dir.resolve("report.md")).exists();
        assertThat(dir.resolve("cli-test-exporter.txt")).exists();
        assertThat(out()).isEmpty();
        assertThat(err()).isEmpty();
    }

    @Test
    @DisplayName("F-001-AC46 export는 graph.json에 저장된 analyze 때의 --opt를 기본으로 쓰고 export의 --opt로 덮어쓴다")
    void exportInheritsStoredOptions() throws IOException {
        GraphModel stored = ShopGraphFixture.shop();
        stored.project(new ProjectInfo("shop", "/work/shop", Instant.parse("2026-10-11T00:00:00Z"), AnalysisOptions.builder()
                .extra("export.report.maxItems", "1").extra("export.crud", "false").extra("java.externalCalls", "all").build()));
        Path source = work.resolve("with-options");
        ShopGraphFixture.save(stored, source);

        // --opt 없이: 저장된 값을 그대로 이어받는다
        Path inherited = work.resolve("inherited");
        assertOk("export", source.toString(), "--out", inherited.toString());
        assertThat(RecordingExporter.lastOptions).containsOnly(Map.entry("export.report.maxItems", "1"),
                Map.entry("export.crud", "false"), Map.entry("java.externalCalls", "all"));
        assertThat(inherited.resolve("crud")).doesNotExist();
        assertThat(inherited.resolve("report.md")).hasContent(SummaryReport.render(GraphJson.read(source.resolve("graph.json")), 1));

        // --opt를 주면 같은 키는 덮어쓰고 나머지는 이어받는다
        Path overridden = work.resolve("overridden");
        assertOk("export", source.toString(), "--out", overridden.toString(), "--opt", "export.crud=true", "--opt", "export.erd.columns=keys");
        assertThat(RecordingExporter.lastOptions).containsOnly(Map.entry("export.report.maxItems", "1"), Map.entry("export.crud", "true"),
                Map.entry("java.externalCalls", "all"), Map.entry("export.erd.columns", "keys"));
        assertThat(overridden.resolve("crud/table-statement.csv")).exists();
        // 새 폴더의 graph.json에는 원래 분석 때의 옵션이 그대로 남는다(export의 --opt로 바꾸지 않는다)
        assertThat(GraphJson.read(overridden.resolve("graph.json")).project().options().extra())
                .containsOnly(Map.entry("export.report.maxItems", "1"), Map.entry("export.crud", "false"), Map.entry("java.externalCalls", "all"));
        assertThat(overridden.resolve("graph.json")).hasSameTextualContentAs(source.resolve("graph.json"));

        // 옵션이 저장되지 않은 그래프(예전 graph.json)는 --opt만 쓴다
        assertOk("export", dir.toString(), "--out", work.resolve("plain-out").toString(), "--opt", "export.crud=false");
        assertThat(RecordingExporter.lastOptions).containsOnly(Map.entry("export.crud", "false"));

        assertOk("help", "export");
        assertThat(out()).contains("이어받").contains("덮어");
    }

    @Test
    @DisplayName("F-001-AC47 도움말, 힌트, README에 나오는 --opt 키 예시는 모두 design 3.8의 키이고, 출력기가 읽는 키 목록은 design 3.8과 같다")
    void optionKeyExamplesAreRealKeys() throws IOException {
        // design.md 3.8의 두 표에서 키를 읽는다
        String design = Files.readString(Path.of("..", "docs", "specs", "F-001-analysis-pipeline", "design.md"), StandardCharsets.UTF_8);
        String section = design.substring(design.indexOf("### 3.8 옵션 키 목록"), design.indexOf("### 3.9 "));
        Set<String> designKeys = new TreeSet<>();
        for (String line : section.lines().filter(l -> l.startsWith("| `")).toList()) {
            Matcher m = Pattern.compile("`([A-Za-z]+(?:\\.[A-Za-z]+)+)`").matcher(line.substring(0, line.indexOf('|', 1)));
            while (m.find()) {
                designKeys.add(m.group(1));
            }
        }
        assertThat(designKeys).contains("java.externalCalls", "link.minJoinEvidence", "export.calltree.depth").hasSizeGreaterThan(20);
        Set<String> exportKeys = new TreeSet<>(designKeys);
        exportKeys.removeIf(key -> !key.startsWith("export."));
        assertThat(exportKeys).as("design 3.8의 출력기 키 = StandardExporter.OPTION_KEYS")
                .containsExactlyInAnyOrderElementsOf(StandardExporter.OPTION_KEYS);

        // 도움말: 모든 명령의 help에서 --opt <key>=<value> 예시의 키
        Pattern optExample = Pattern.compile("--opt[ =]([A-Za-z][A-Za-z0-9_.]*)=");
        Set<String> helpKeys = new TreeSet<>();
        for (Command command : Main.commands()) {
            assertOk("help", command.name());
            Matcher m = optExample.matcher(out());
            while (m.find()) {
                helpKeys.add(m.group(1));
            }
        }
        assertThat(helpKeys).as("도움말의 --opt 키 예시").isNotEmpty().contains("export.calltree.depth").isSubsetOf(designKeys);
        assertOk("help", "analyze");
        assertThat(out()).doesNotContain("--opt calltree.depth");

        // 힌트: analyze 요약 끝에 나오는 안내 문구의 --opt 키
        Set<String> hintKeys = new TreeSet<>();
        for (String hint : Hints.of(HintsTest.graphWithEveryHint())) {
            Matcher h = optExample.matcher(hint);
            while (h.find()) {
                hintKeys.add(h.group(1));
            }
        }
        assertThat(hintKeys).as("힌트의 --opt 키").contains("mybatis.dialectVariant", "export.includeDataLoad", "web.skipVendor")
                .isSubsetOf(designKeys);

        // README: --opt 예시의 키와 옵션 표의 키
        String readme = Files.readString(Path.of("..", "README.md"), StandardCharsets.UTF_8);
        Set<String> readmeKeys = new TreeSet<>();
        Matcher m = optExample.matcher(readme);
        while (m.find()) {
            readmeKeys.add(m.group(1));
        }
        m = Pattern.compile("`((?:export|java|link|mybatis|web)\\.[A-Za-z]+(?:\\.[A-Za-z]+)*)(?:=[^`]*)?`").matcher(readme);
        while (m.find()) {
            readmeKeys.add(m.group(1));
        }
        assertThat(readmeKeys).as("README의 옵션 키").hasSizeGreaterThan(5).isSubsetOf(designKeys);
    }

    @Test
    @DisplayName("F-001-AC42 export는 --out이 없거나 --opt 값이 <key>=<value>가 아니면 사용법 오류(2)다")
    void exportErrors() {
        assertThat(run("export", file)).isEqualTo(2);
        assertThat(err()).contains("--out");
        assertThat(run("export", file, "--out", work.resolve("x").toString(), "--opt", "novalue")).isEqualTo(2);
        assertThat(err()).contains("novalue");
        assertThat(run("export", file, "--out", work.resolve("x").toString(), "--opt", "=v")).isEqualTo(2);
        assertThat(work.resolve("x")).doesNotExist();
        assertThat(RecordingExporter.lastOptions).isNull();
    }

    @Test
    @DisplayName("하위 명령은 레지스트리에 등록된 순서대로 사용법에 나온다")
    void registryOrder() {
        assertThat(Main.commands()).extracting(Command::name)
                .containsExactly("analyze", "summary", "erd", "classdiagram", "calltree", "impact", "crud", "find", "report", "export");
    }
}
