package io.codeatlas.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisOptions;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 분석 요약 끝의 힌트({@link Hints}). */
class HintsTest {

    private static GraphModel graph(AnalysisOptions options) {
        GraphModel graph = new GraphModel();
        graph.project(new ProjectInfo("p", "/work/p", Instant.parse("2026-10-11T00:00:00Z"), options));
        graph.declare(Node.of("java:type:a.Caller", NodeKind.JAVA_TYPE, "Caller"));
        return graph;
    }

    private static void file(GraphModel graph, String path, String fileType) {
        graph.declare(Node.builder(Ids.file(path), NodeKind.FILE).name(path).attr("fileType", fileType).build());
    }

    private static void unresolvedCalls(GraphModel graph, int count, String file) {
        for (int i = 0; i < count; i++) {
            graph.addUnresolvedEdge("java:type:a.Caller", EdgeKind.CALLS, "java-call", file + ".call" + i + "(…)", SourceLocation.of(file, i + 1));
        }
    }

    /** 모든 힌트가 나오는 그래프. 힌트 문구의 옵션 키를 design 3.8과 대조하는 테스트(F-001-AC47)도 쓴다. */
    static GraphModel graphWithEveryHint() {
        GraphModel graph = graph(AnalysisOptions.defaults());
        graph.report(Diagnostic.info("DIALECT_VARIANT_MAPPERS", "방언별 매퍼 2벌 감지(oracle, mysql): oracle 파일을 사용, 제외한 파일 3개.", null));
        unresolvedCalls(graph, 30, "src/main/java/a/M.java");
        unresolvedCalls(graph, 31, "module-x/src/test/java/a/T.java");
        file(graph, "db/mysql/schema.sql", "SQL");
        file(graph, "db/data.sql", "SQL");
        file(graph, "db/upgrade/v2.sql", "SQL");
        file(graph, "src/main/resources/ctx.xml", "SPRING_XML");
        graph.report(Diagnostic.info("DUPLICATE_OBJECT_NAME", "같은 테이블 3개의 정의가 db/oracle와(과) db/mysql에 중복됨", SourceLocation.ofFile("db/mysql/schema.sql")));
        graph.report(Diagnostic.warn("DUPLICATE_OBJECT_NAME", "빈 a가 두 번 정의됨", SourceLocation.of("src/main/resources/ctx.xml", 3)));
        graph.report(Diagnostic.info("DATA_LOAD_GROUPED", "데이터 적재 구문 120건을 테이블별 2개로 묶음", SourceLocation.ofFile("db/data.sql")));
        graph.report(Diagnostic.info("DDL_NOT_APPLIED", "ALTER TABLE 4건을 적용하지 않음", SourceLocation.ofFile("db/upgrade/v2.sql")));
        graph.declare(Node.builder(Ids.jsModule("static/lib/jquery.js"), NodeKind.JS_MODULE).name("jquery.js").attr("skipped", "vendor").build());
        graph.declare(Node.builder(Ids.jsModule("static/js/app.min.js"), NodeKind.JS_MODULE).name("app.min.js").attr("skipped", "minified").build());
        graph.declare(Node.builder(Ids.jspPage("static/ckeditor/dialog.jsp"), NodeKind.JSP_PAGE).name("dialog.jsp").attr("skipped", "vendor").build());
        graph.report(Diagnostic.info("UNKNOWN_COLUMN", "DDL에 없는 컬럼이라 소속 테이블을 정하지 못함: SUB_DN", SourceLocation.of("m.xml", 3)));
        graph.declare(Node.builder(Ids.httpRequest("GET", "/api/a", "src/App.jsx", 3), NodeKind.HTTP_REQUEST).name("GET /api/a").build());
        return graph;
    }

    @Test
    @DisplayName("F-001-AC61 힌트는 방언별 매퍼, 많은 미해결 Java 호출, 중복 테이블 정의, 데이터 적재 묶음, 적용하지 않은 ALTER, 건너뛴 벤더 파일을 한 줄씩 알린다")
    void everyHint() {
        List<String> hints = Hints.of(graphWithEveryHint());

        assertThat(hints).hasSize(9).allSatisfy(hint -> assertThat(hint).doesNotContain("\n"));
        assertThat(hints.get(7)).contains("벤더");
        assertThat(hints.get(8)).contains("서버 요청 1건").contains("백엔드");
        assertThat(hints.get(0)).contains("oracle 파일을 사용").contains("--opt mybatis.dialectVariant=");
        assertThat(hints.get(1)).contains("미해결 Java 호출 61건").contains("--classpath");
        assertThat(hints.get(2)).contains("31건").contains("src/test").contains("--exclude-tests");
        // 빈 정의 중복(Spring XML)은 세지 않는다
        assertThat(hints.get(3)).contains("DUPLICATE_OBJECT_NAME 1건").contains("--ddl").contains("--exclude");
        assertThat(hints.get(4)).contains("데이터 적재").contains("1개 파일").contains("--opt export.includeDataLoad=true");
        assertThat(hints.get(5)).contains("DDL_NOT_APPLIED").contains("1개 파일");
        assertThat(hints.get(6)).contains("UNKNOWN_COLUMN 1건");
        // 압축 스크립트(minified)는 벤더 건수에 넣지 않는다
        assertThat(hints.get(7)).contains("벤더").contains("2개").contains("--opt web.skipVendor=false").contains("--opt web.vendorPatterns=");
    }

    @Test
    @DisplayName("F-001-AC61 해당하지 않으면 힌트가 없다: 미해결 Java 호출 50건 이하, --classpath를 준 분석, 테스트 비중이 절반 이하, 이미 뺀 테스트")
    void noHintWhenNotApplicable() {
        GraphModel few = graph(AnalysisOptions.defaults());
        unresolvedCalls(few, 50, "src/test/java/a/T.java");
        assertThat(Hints.of(few)).isEmpty();

        GraphModel withClasspath = graph(AnalysisOptions.builder().classpath(Path.of("lib/dep.jar")).build());
        unresolvedCalls(withClasspath, 26, "src/main/java/a/M.java");
        unresolvedCalls(withClasspath, 25, "src/test/java/a/T.java");
        assertThat(Hints.of(withClasspath)).isEmpty();

        GraphModel testsExcluded = graph(AnalysisOptions.builder().classpath(Path.of("lib/dep.jar")).exclude(AnalyzeCommand.TEST_SOURCES_GLOB).build());
        unresolvedCalls(testsExcluded, 60, "src/test/java/a/T.java");
        assertThat(Hints.of(testsExcluded)).isEmpty();

        // 옵션이 저장되지 않은 그래프(프로젝트 정보 없음)에서도 죽지 않는다
        GraphModel bare = new GraphModel();
        assertThat(Hints.of(bare)).isEmpty();
    }

    @Test
    @DisplayName("F-001-AC77 미해결 Java 호출 힌트는 같은 지점에서 다른 방법으로 풀린 참조(resolvedBy가 있는 UNRESOLVED 노드)로 가는 엣지를 세지 않는다")
    void unresolvedCallsResolvedElsewhereAreNotCounted() {
        // 미해결 60건 가운데 15건의 대상에 resolvedBy가 붙었다: 남는 45건은 문턱(50건)을 넘지 않는다
        GraphModel below = graph(AnalysisOptions.defaults());
        unresolvedCalls(below, 60, "src/main/java/a/M.java");
        for (int i = 0; i < 15; i++) {
            below.putAttrs(Ids.unresolved("java-call", "src/main/java/a/M.java.call" + i + "(…)"), java.util.Map.of("resolvedBy", "prefixMatch"));
        }
        assertThat(Hints.of(below)).isEmpty();

        // 문턱을 넘으면 풀린 것을 뺀 수를 적고, 테스트 코드 비중도 뺀 수로 따진다
        GraphModel above = graph(AnalysisOptions.defaults());
        unresolvedCalls(above, 40, "src/main/java/a/M.java");
        unresolvedCalls(above, 45, "src/test/java/a/T.java");
        for (int i = 0; i < 20; i++) {
            above.putAttrs(Ids.unresolved("java-call", "src/test/java/a/T.java.call" + i + "(…)"), java.util.Map.of("resolvedBy", "prefixMatch"));
        }
        // 남는 것: main 40건, test 25건. 합 65건이고 테스트는 절반을 넘지 않는다
        assertThat(Hints.of(above)).singleElement().asString().contains("미해결 Java 호출 65건").contains("--classpath");
    }

    @Test
    @DisplayName("F-001-AC79 벤더 힌트의 건수는 skipped=vendor 노드로 센다: FILE_SKIPPED 요약 메시지의 문구(벤더 스크립트 7개 건너뜀(이름 3, 라이선스 4): <폴더>)와 건수에 기대지 않는다")
    void vendorHintCountsNodesNotDiagnosticMessages() {
        GraphModel graph = graph(AnalysisOptions.defaults());
        endpoint(graph, "GET", "/a");
        request(graph, "/a", false);
        // 폴더마다 1건인 새 요약 메시지. 건수(7, 29)와 괄호 안 사유는 힌트가 읽지 않는다
        graph.report(Diagnostic.info("FILE_SKIPPED", "벤더 스크립트 7개 건너뜀(이름 3, 라이선스 4): static/lib", SourceLocation.ofFile("static/lib")));
        graph.report(Diagnostic.info("FILE_SKIPPED", "벤더 페이지 29개 건너뜀(경로): static/ckeditor", SourceLocation.ofFile("static/ckeditor")));
        graph.report(Diagnostic.info("FILE_SKIPPED", "압축된 스크립트라 내용을 분석하지 않았다: static/js/app.min.js", SourceLocation.ofFile("static/js/app.min.js")));
        // 진단만 있고 건너뛴 노드가 없으면 힌트가 없다
        assertThat(Hints.of(graph)).isEmpty();

        graph.declare(Node.builder(Ids.jsModule("static/lib/jquery.js"), NodeKind.JS_MODULE).name("jquery.js").attr("skipped", "vendor").build());
        graph.declare(Node.builder(Ids.jsModule("static/lib/moment.js"), NodeKind.JS_MODULE).name("moment.js").attr("skipped", "vendor").build());
        graph.declare(Node.builder(Ids.jspPage("static/ckeditor/dialog.jsp"), NodeKind.JSP_PAGE).name("dialog.jsp").attr("skipped", "vendor").build());
        graph.declare(Node.builder(Ids.jsModule("static/js/app.min.js"), NodeKind.JS_MODULE).name("app.min.js").attr("skipped", "minified").build());
        assertThat(Hints.of(graph)).singleElement().asString().contains("벤더").contains("스크립트·페이지 3개").contains("FILE_SKIPPED")
                .doesNotContain("7개").doesNotContain("29개").doesNotContain("36개");
    }

    private static void endpoint(GraphModel graph, String method, String path) {
        graph.declare(Node.builder(Ids.httpEndpoint(method, path), NodeKind.HTTP_ENDPOINT).name(method + " " + path).build());
    }

    private static void request(GraphModel graph, String path, boolean clientRoute) {
        graph.declare(Node.builder(Ids.httpRequest("GET", path, "src/App.jsx", 3), NodeKind.HTTP_REQUEST).name("GET " + path)
                .attr("clientRoute", clientRoute ? Boolean.TRUE : null).build());
    }

    @Test
    @DisplayName("F-001-AC66 화면 요청은 있는데 엔드포인트가 하나도 없으면 백엔드와 함께 분석하라는 힌트를, 엔드포인트는 있는데 요청이 없으면 프런트가 분석 범위에 없다는 힌트를 낸다")
    void frontAndBackHints() {
        GraphModel frontOnly = graph(AnalysisOptions.defaults());
        request(frontOnly, "/api/orders", false);
        request(frontOnly, "/api/users", false);
        request(frontOnly, "/login", true);
        assertThat(Hints.of(frontOnly)).singleElement().asString().contains("서버 요청 2건").contains("엔드포인트가 없다").contains("백엔드");

        GraphModel backOnly = graph(AnalysisOptions.defaults());
        endpoint(backOnly, "GET", "/api/orders");
        assertThat(Hints.of(backOnly)).singleElement().asString().contains("엔드포인트 1개").contains("화면 요청이 없다").contains("프런트");

        // 둘 다 있거나 둘 다 없으면 없다. 클라이언트 라우트뿐이면 요청으로 세지 않는다
        GraphModel both = graph(AnalysisOptions.defaults());
        endpoint(both, "GET", "/api/orders");
        request(both, "/api/orders", false);
        assertThat(Hints.of(both)).isEmpty();
        GraphModel routesOnly = graph(AnalysisOptions.defaults());
        request(routesOnly, "/login", true);
        assertThat(Hints.of(routesOnly)).isEmpty();
    }

    @Test
    @DisplayName("F-001-AC82 프런트만 분석한 그래프의 힌트는 report.md의 '화면 요청' 절과 같은 것을 같은 이름으로 센다: 서버 요청(외부 URL·정적 페이지 링크·클라이언트 라우트 제외)")
    void frontOnlyHintCountsServerRequestsLikeTheReport() {
        GraphModel frontOnly = graph(AnalysisOptions.defaults());
        request(frontOnly, "/api/orders", false);
        request(frontOnly, "/api/users", false);
        request(frontOnly, "/login", true);
        for (String url : List.of("https://www.example.org/a", "https://www.example.org/b")) {
            frontOnly.declare(Node.builder(Ids.httpRequest("GET", url, "src/App.jsx", 30), NodeKind.HTTP_REQUEST).name("GET " + url)
                    .attr("external", true).attr("path", url).build());
        }
        int server = io.codeatlas.export.report.SummaryReport.requestStats(frontOnly).server();
        assertThat(server).isEqualTo(2);
        assertThat(Hints.of(frontOnly)).singleElement().asString().contains("서버 요청 " + server + "건").doesNotContain("4개").doesNotContain("4건")
                .contains("외부 URL").contains("report.md");
        assertThat(io.codeatlas.export.report.SummaryReport.render(frontOnly)).contains("서버 요청 " + server + "건 중");

        // 서버 요청이 하나도 없으면(외부 URL뿐) 연결할 것이 없다: 힌트가 없다
        GraphModel externalOnly = graph(AnalysisOptions.defaults());
        externalOnly.declare(Node.builder(Ids.httpRequest("GET", "https://www.example.org/a", "src/App.jsx", 30), NodeKind.HTTP_REQUEST)
                .name("GET https://www.example.org/a").attr("external", true).build());
        assertThat(Hints.of(externalOnly)).isEmpty();
    }

    @Test
    @DisplayName("F-001-AC67 DDL 방언을 골랐거나(DIALECT_VARIANT_DDL) 요청한 방언의 파일이 없으면(DIALECT_NOT_FOUND) 선택된 방언과 --dialect를 알리고, 매퍼와 DDL의 방언이 다르면 경고한다")
    void ddlDialectHints() {
        GraphModel graph = graph(AnalysisOptions.defaults());
        graph.report(Diagnostic.info("DIALECT_VARIANT_DDL",
                "방언별 DDL 3벌 감지(oracle, mysql, hsql): oracle 파일을 사용(테이블 40개), 다른 방언에서 보충 0개, 제외한 파일 8개.", null));
        graph.report(Diagnostic.warn("DIALECT_NOT_FOUND",
                "요청한 방언 db2(`--dialect`)의 DDL·데이터 스크립트 파일이 없음: 감지한 방언(oracle, mysql, hsql) 가운데 oracle 파일을 사용", null));
        graph.report(Diagnostic.info("DIALECT_VARIANT_MAPPERS", "방언별 매퍼 2벌 감지(mysql, oracle): mysql 파일을 사용, 제외한 파일 3개.", null));
        // 방언이 어긋났는지는 그래프에서 읽는다(F-001-AC72): 매퍼 구문의 dialectVariant, 테이블 정의 파일의 경로
        mapperStatement(graph, "findList", "src/main/resources/mapper/A_SQL_mysql.xml", "mysql");
        table(graph, "TB_A", "db/oracle/ddl_oracle.sql");

        List<String> hints = Hints.of(graph);

        assertThat(hints).hasSize(4).allSatisfy(hint -> assertThat(hint).doesNotContain("\n"));
        // WARN(요청한 방언의 파일이 없음)은 맨 위에 "주의:"로 올린다
        assertThat(hints.get(0)).startsWith("주의: ").contains("요청한 방언 db2").contains("oracle 파일을 사용").contains("--dialect");
        assertThat(hints.get(1)).contains("방언별 매퍼").contains("mysql 파일을 사용");
        assertThat(hints.get(2)).contains("방언별 DDL").contains("oracle 파일을 사용").contains("--dialect");
        assertThat(hints.get(3)).startsWith("경고").contains("매퍼는 mysql").contains("DDL은 oracle").contains("--dialect")
                .contains("--opt mybatis.dialectVariant=");

        // 대상 방언(Oracle, DB2, PostgreSQL)의 파일이 없어 대상 외 방언으로 분석됐으면(WARN) 그 진단도 맨 위로 올린다.
        // 방언별 DDL에서 한 벌을 고른 것이면 파일 쌍마다 나온 중복 정의 힌트는 접는다
        GraphModel foreign = graph(AnalysisOptions.defaults());
        file(foreign, "db/mysql/schema.sql", "SQL");
        foreign.report(Diagnostic.info("DUPLICATE_OBJECT_NAME", "같은 테이블 3개의 정의가 중복됨", SourceLocation.ofFile("db/mysql/schema.sql")));
        foreign.report(Diagnostic.warn("DIALECT_VARIANT_DDL", FOREIGN_DDL, null));
        foreign.report(Diagnostic.warn("DIALECT_VARIANT_MAPPERS", FOREIGN_MAPPERS, null));
        assertThat(Hints.of(foreign)).hasSize(2).allMatch(hint -> hint.startsWith("주의: "))
                .noneMatch(hint -> hint.contains("DUPLICATE_OBJECT_NAME"));

        // 같은 방언이면 경고가 없다. 매퍼 방언을 알 수 없어도(all로 합쳐 dialectVariant가 없다) 없다
        GraphModel same = graph(AnalysisOptions.defaults());
        same.report(Diagnostic.info("DIALECT_VARIANT_DDL", "방언별 DDL 2벌 감지(oracle, mysql): oracle 파일을 사용(테이블 4개), 제외한 파일 1개.", null));
        same.report(Diagnostic.info("DIALECT_VARIANT_MAPPERS", "방언별 매퍼 2벌 감지(oracle, mysql): oracle 파일을 사용, 제외한 파일 3개.", null));
        mapperStatement(same, "findList", "src/main/resources/mapper/A_SQL_oracle.xml", "oracle");
        table(same, "TB_A", "db/oracle/ddl_oracle.sql");
        assertThat(Hints.of(same)).hasSize(2).noneMatch(hint -> hint.startsWith("경고"));
        GraphModel merged = graph(AnalysisOptions.defaults());
        merged.report(Diagnostic.info("DIALECT_VARIANT_DDL", "방언별 DDL 2벌 감지(oracle, mysql): oracle 파일을 사용(테이블 4개), 제외한 파일 1개.", null));
        merged.report(Diagnostic.info("DIALECT_VARIANT_MAPPERS",
                "방언별 매퍼 2벌 감지(oracle, mysql): mybatis.dialectVariant=all이라 모든 방언 파일의 정의를 한 구문에 합쳤다", null));
        mapperStatement(merged, "findList", "src/main/resources/mapper/A_SQL_oracle.xml", null);
        table(merged, "TB_A", "db/mysql/ddl_mysql.sql");
        assertThat(Hints.of(merged)).hasSize(2).noneMatch(hint -> hint.startsWith("경고"));
    }

    private static void mapperStatement(GraphModel graph, String id, String file, String dialectVariant) {
        graph.declare(Node.builder(Ids.mybatisStatement("aDAO", id), NodeKind.SQL_STATEMENT).name(id)
                .location(SourceLocation.of(file, 4)).attr("statementType", "SELECT").attr("dialectVariant", dialectVariant).build());
    }

    private static void table(GraphModel graph, String name, String file) {
        graph.declare(Node.builder(Ids.table(name), NodeKind.DB_TABLE).name(name).location(SourceLocation.of(file, 1)).build());
    }

    @Test
    @DisplayName("F-001-AC72 매퍼·DDL 방언 불일치 경고는 진단 문구가 아니라 그래프에서 판정한다: 매퍼 구문의 dialectVariant와 테이블 정의 파일 경로의 방언 토큰")
    void dialectMismatchComesFromGraphNotFromMessages() {
        // 문구가 바뀌어도(": <방언> 파일을 사용"이 없다) 그래프가 어긋나면 경고한다. 방언별 DDL 진단이 없어도(한 벌뿐) 마찬가지다
        GraphModel mismatch = graph(AnalysisOptions.defaults());
        mismatch.report(Diagnostic.info("DIALECT_VARIANT_MAPPERS",
                "방언별 매퍼 2벌(mysql, oracle) 가운데 대상 방언 oracle을 골랐다(프로젝트 설정은 mysql). `--opt mybatis.dialectVariant=<방언|all>`로 변경", null));
        mapperStatement(mismatch, "findList", "src/main/resources/mapper/A_SQL_oracle.xml", "oracle");
        mapperStatement(mismatch, "findOne", "src/main/resources/mapper/A_SQL_oracle.xml", "oracle");
        mapperStatement(mismatch, "onlyInMysql", "src/main/resources/mapper/A_SQL_mysql.xml", "mysql");
        table(mismatch, "TB_A", "db/mysql/ddl_mysql.sql");
        table(mismatch, "TB_B", "script/ddl/mysql/b_create.sql");
        table(mismatch, "ACT_X", "db/act.oracle.create.sql");
        table(mismatch, "TB_PLAIN", "db/schema.sql");
        List<String> hints = Hints.of(mismatch);
        assertThat(hints).hasSize(2).allSatisfy(hint -> assertThat(hint).doesNotContain("\n"));
        assertThat(hints.get(0)).contains("방언별 매퍼");
        assertThat(hints.get(1)).startsWith("경고").contains("매퍼는 oracle").contains("DDL은 mysql").contains("--dialect")
                .contains("--opt mybatis.dialectVariant=");

        // 문구가 서로 다른 방언을 말해도 그래프가 같은 방언이면 경고하지 않는다
        GraphModel same = graph(AnalysisOptions.defaults());
        same.report(Diagnostic.info("DIALECT_VARIANT_DDL", "방언별 DDL 2벌 감지(oracle, mysql): mysql 파일을 사용(테이블 4개), 제외한 파일 1개.", null));
        same.report(Diagnostic.info("DIALECT_VARIANT_MAPPERS", "방언별 매퍼 2벌 감지(oracle, mysql): oracle 파일을 사용, 제외한 파일 3개.", null));
        mapperStatement(same, "findList", "src/main/resources/mapper/A_SQL_oracle.xml", "oracle");
        table(same, "TB_A", "db/oracle/ddl_oracle.sql");
        table(same, "TB_B", "db/ora/b.sql");
        table(same, "TB_C", "db/mysql/c_mysql.sql");
        assertThat(Hints.of(same)).hasSize(2).noneMatch(hint -> hint.startsWith("경고"));

        // 어느 한쪽을 알 수 없으면 견주지 않는다: dialectVariant가 없는 구문(방언 변형이 아니거나 all로 합침), 경로에 방언 토큰이 없는 DDL
        GraphModel unknownMapper = graph(AnalysisOptions.defaults());
        mapperStatement(unknownMapper, "findList", "src/main/resources/mapper/A_SQL_mysql.xml", null);
        table(unknownMapper, "TB_A", "db/oracle/ddl_oracle.sql");
        assertThat(Hints.of(unknownMapper)).isEmpty();
        GraphModel unknownDdl = graph(AnalysisOptions.defaults());
        mapperStatement(unknownDdl, "findList", "src/main/resources/mapper/A_SQL_mysql.xml", "mysql");
        table(unknownDdl, "TB_A", "db/schema.sql");
        table(unknownDdl, "TB_B", "db/OracleLike/schema.sql");
        assertThat(Hints.of(unknownDdl)).isEmpty();

        // 구문에 값이 없으면 MYBATIS_MAPPER의 dialectVariant를 쓴다. 같은 수면 매퍼 방언이 DDL 쪽에 있는 한 경고하지 않는다
        GraphModel byMapperNode = graph(AnalysisOptions.defaults());
        byMapperNode.declare(Node.builder(Ids.mybatisMapper("aDAO"), NodeKind.MYBATIS_MAPPER).name("aDAO")
                .location(SourceLocation.of("mapper/tibero/A.xml", 1)).attr("dialectVariant", "tibero").build());
        table(byMapperNode, "TB_A", "db/postgresql/a.sql");
        assertThat(Hints.of(byMapperNode)).singleElement().asString().startsWith("경고").contains("매퍼는 tibero").contains("DDL은 postgres");
        table(byMapperNode, "TB_B", "db/tibero6/b.sql");
        assertThat(Hints.of(byMapperNode)).isEmpty();
    }

    @Test
    @DisplayName("F-001-AC72 경로의 방언 토큰은 sql·mybatis와 같은 표로 읽는다: 파일 이름의 토큰(뒤에서부터), 그다음 폴더 이름(가까운 것부터, 이름 전체), 별칭과 끝의 버전 숫자")
    void dialectOfPath() {
        // sql 모듈 DialectTokensTest의 표에서 옮긴 것
        java.util.Map<String, String> table = new java.util.LinkedHashMap<>();
        table.put("mapper/EgovLoginUsr_SQL_oracle.xml", "oracle");
        table.put("mapper/Order.postgresql.xml", "postgres");
        table.put("db/act/activiti.db2.create.engine.sql", "db2");
        table.put("db/act/activiti.mysql55.create.history.sql", "mysql");
        table.put("db/sys/jeesite_mssql.sql", "mssql");
        table.put("DATABASE/altibase/all_ebt_ddl_altibase.sql", "altibase");
        table.put("script/ddl/tibero/uss.umt_create_tibero.sql", "tibero");
        table.put("mapper/db2/sub/Stock.xml", "db2");
        table.put("db/ora/schema.sql", "oracle");
        table.put("db/oracle/mysql_compat.sql", "mysql");
        table.put("db/mysql/postgres/x.sql", "postgres");
        table.put("mapper\\maria\\Stock.xml", "mariadb");
        table.put("db/sqlserver2012/x.sql", "mssql");
        table.put("db/cubrid/x.sql", "cubrid");
        table.put("db/hsqldb/x.sql", "hsql");
        table.put("h2/schema.sql", "h2");
        table.forEach((path, dialect) -> assertThat(Hints.dialectOfPath(path)).as(path).isEqualTo(dialect));
        for (String path : List.of("mapper/OracleUserMapper.xml", "db/my-oracle-scripts/schema.sql", "db/schema.sql", "schema.sql")) {
            assertThat(Hints.dialectOfPath(path)).as(path).isNull();
        }
        assertThat(Hints.dialectOfPath(null)).isNull();
    }

    // sql·mybatis가 내는 문구의 꼴. 대상 방언 파일이 없을 때만 WARN이고 옵션 안내가 없다. PostgreSQL이 대상 방언이 되면서(F-019-R4)
    // 대상 외 방언의 예시를 postgres에서 cubrid로 바꿨다(postgres 파일이 있으면 이 WARN이 나오지 않는다)
    private static final String FOREIGN_MAPPERS = "방언별 매퍼 2벌 감지(cubrid, mysql): cubrid 파일을 사용, 제외한 파일 1개."
            + " 이 도구의 대상 방언(Oracle, DB2, PostgreSQL)의 파일이 없어 cubrid 파일을 씀: SQL 해석이 부정확할 수 있음";
    private static final String FOREIGN_DDL = "방언별 DDL 2벌 감지(mysql, cubrid): mysql 파일을 사용(테이블 2개), 다른 방언에서 보충 0개, 제외한 파일 1개."
            + " 이 도구의 대상 방언(Oracle, DB2, PostgreSQL)의 파일이 없어 mysql 파일을 씀: SQL 해석이 부정확할 수 있음";
    private static final String PREFERRED_MAPPERS = "방언별 매퍼 2벌 감지(oracle, mysql): oracle 파일을 사용, 제외한 파일 1개."
            + " 프로젝트 설정(src/main/resources/globals.properties:Globals.DbType=mysql)과 다르게 대상 방언 oracle 파일을 사용."
            + " 설정대로 분석하려면 `--opt mybatis.dialectVariant=mysql`. `--opt mybatis.dialectVariant=<방언|all>`로 변경";

    @Test
    @DisplayName("F-001-AC73 F-019-AC32 방언 힌트는 지금의 방언 정책을 따른다: DIALECT_NOT_TARGET 한 줄, 대상 외 방언으로 바꾸는 안내는 --dialect가 아니라 --opt mybatis.dialectVariant, 설정과 다르게 대상 방언을 고른 INFO는 주의로 올리지 않는다")
    void dialectPolicyHints() {
        // 방언별 여러 벌은 아니지만 프로젝트의 SQL이 대상 외 방언으로 보임
        GraphModel notTarget = graph(AnalysisOptions.defaults());
        notTarget.report(Diagnostic.info("DIALECT_NOT_TARGET",
                "이 프로젝트의 SQL은 mysql로 보임. 대상 방언이 아니어서 일부 구문을 해석하지 못할 수 있음(이 도구의 대상 방언은 Oracle, DB2, PostgreSQL)", null));
        assertThat(Hints.of(notTarget)).singleElement().asString().contains("mysql로 보임").contains("대상 방언").doesNotContain("\n");

        // 대상 방언 파일이 없어 대상 외 방언을 쓴 경우(WARN): 메시지에 옵션 안내가 없으면 덧붙이되,
        // --dialect는 oracle·db2·postgres만 받으므로 그 밖의 방언은 --opt mybatis.dialectVariant로 안내한다(매퍼와 DDL 선택 둘 다 이 값을 읽는다)
        GraphModel foreignOnly = graph(AnalysisOptions.defaults());
        foreignOnly.report(Diagnostic.warn("DIALECT_VARIANT_MAPPERS", FOREIGN_MAPPERS, null));
        foreignOnly.report(Diagnostic.warn("DIALECT_VARIANT_DDL", FOREIGN_DDL, null));
        foreignOnly.report(Diagnostic.warn("DIALECT_NOT_FOUND",
                "요청한 방언 db2(`--dialect`)의 매퍼 파일이 없음: 감지한 방언(cubrid, mysql) 가운데 cubrid 파일을 사용", null));
        mapperStatement(foreignOnly, "findList", "src/main/resources/mapper/A_SQL_cubrid.xml", "cubrid");
        table(foreignOnly, "TB_A", "db/mysql/ddl_mysql.sql");
        List<String> hints = Hints.of(foreignOnly);
        assertThat(hints).hasSize(4);
        assertThat(hints.subList(0, 3)).allMatch(hint -> hint.startsWith("주의: "))
                .allMatch(hint -> hint.contains("--opt mybatis.dialectVariant=<방언>"));
        // 메시지가 마침표 없이 끝나도 덧붙이는 안내와 붙어 버리지 않는다
        assertThat(hints).noneMatch(hint -> hint.contains("있음 다른"));
        assertThat(hints.get(3)).startsWith("경고").contains("매퍼는 cubrid").contains("DDL은 mysql")
                .contains("--opt mybatis.dialectVariant=<방언>").contains("둘 다");
        // 대상 외 방언을 --dialect로 주라는 안내는 어디에도 없다. --dialect 안내는 대상 방언 셋뿐이다
        assertThat(hints).noneMatch(hint -> hint.matches(".*--dialect (<방언|mysql|cubrid|<oracle\\|db2\\|postgres>를 감지한).*"));
        assertThat(hints).noneMatch(hint -> hint.contains("<oracle|db2>"));
        assertThat(hints.subList(1, 3)).allMatch(hint -> hint.contains("--dialect <oracle|db2|postgres>"));

        // 프로젝트 설정(mysql)과 다르게 대상 방언 oracle을 고른 것은 INFO다: 주의로 올리지 않고 메시지를 그대로 옮긴다
        GraphModel preferred = graph(AnalysisOptions.defaults());
        preferred.report(Diagnostic.info("DIALECT_VARIANT_MAPPERS", PREFERRED_MAPPERS, null));
        assertThat(Hints.of(preferred)).containsExactly(PREFERRED_MAPPERS);

        // DDL에 없는 컬럼 안내도 대상 외 방언은 --opt로 가리킨다
        GraphModel unknown = graph(AnalysisOptions.defaults());
        unknown.report(Diagnostic.info("UNKNOWN_COLUMN", "DDL에 없는 컬럼이라 소속 테이블을 정하지 못함: C", SourceLocation.of("m.xml", 1)));
        assertThat(Hints.of(unknown)).singleElement().asString().contains("--dialect").contains("--opt mybatis.dialectVariant=<방언>");
    }

    @Test
    @DisplayName("F-001-AC68 UNKNOWN_COLUMN 진단이 있으면 건수와 SQL이 DDL에 없는 컬럼을 참조한다는 안내를 낸다")
    void unknownColumnHint() {
        GraphModel graph = graph(AnalysisOptions.defaults());
        for (int i = 0; i < 3; i++) {
            graph.report(Diagnostic.info("UNKNOWN_COLUMN", "DDL에 없는 컬럼이라 소속 테이블을 정하지 못함: C" + i, SourceLocation.of("m.xml", i + 1)));
        }
        assertThat(Hints.of(graph)).singleElement().asString().contains("UNKNOWN_COLUMN 3건").contains("DDL에 없는 컬럼").contains("--ddl");
    }

    @Test
    @DisplayName("F-001-AC86 F-019-AC32 방언별 DDL 요약(DIALECT_VARIANT_DDL)에 '다른 방언 파일의 적재 묶음' 문장이 붙어도 힌트는 그 문장을 그대로 옮기고 옵션 안내(--dialect <oracle|db2|postgres>)를 한 번만 붙인다")
    void ddlVariantHintCarriesBorrowedDataLoadSentence() {
        String borrowed = "oracle 파일에 적재가 없는 테이블의 묶음 18개는 다른 방언 파일의 것을 씀(mysql 12개, hsql 6개).";
        // 만들지 않은 묶음이 있을 때: "…만들지 않음." 바로 뒤에 붙는다
        GraphModel both = graph(AnalysisOptions.defaults());
        both.report(Diagnostic.info("DIALECT_VARIANT_DDL", "방언별 DDL 3벌 감지(oracle, mysql, hsql): oracle 파일을 사용(테이블 20개), 제외한 파일 40개. "
                + "데이터 적재 스크립트도 같은 규칙으로 골라 다른 방언 파일의 묶음 95개를 만들지 않음. " + borrowed, null));
        assertThat(Hints.of(both)).singleElement().asString().contains("만들지 않음. " + borrowed + " 다른 방언의 DDL을 쓰려면 --dialect <oracle|db2|postgres>")
                .containsOnlyOnce("mybatis.dialectVariant").doesNotContain("\n").doesNotContain("..");

        // 만들지 않은 묶음이 0이면 그 문장만 나온다
        GraphModel only = graph(AnalysisOptions.defaults());
        only.report(Diagnostic.info("DIALECT_VARIANT_DDL", "방언별 DDL 2벌 감지(oracle, mysql): oracle 파일을 사용(테이블 20개), 제외한 파일 4개. " + borrowed, null));
        assertThat(Hints.of(only)).singleElement().asString().contains("제외한 파일 4개. " + borrowed + " 다른 방언의 DDL을 쓰려면").doesNotContain("만들지 않음");

        // 메시지에 옵션 안내가 이미 들어 있으면(끝에 `--opt mybatis.dialectVariant=…`) 덧붙이지 않는다
        GraphModel withOption = graph(AnalysisOptions.defaults());
        String message = "방언별 DDL 2벌 감지(oracle, mysql): oracle 파일을 사용(테이블 20개), 제외한 파일 4개. " + borrowed
                + " `--dialect <oracle|db2|postgres>`(그 밖의 방언은 `--opt mybatis.dialectVariant=<방언>`)로 변경";
        withOption.report(Diagnostic.info("DIALECT_VARIANT_DDL", message, null));
        assertThat(Hints.of(withOption)).containsExactly(message);
    }
}
