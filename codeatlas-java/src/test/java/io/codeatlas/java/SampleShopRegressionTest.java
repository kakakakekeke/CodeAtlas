package io.codeatlas.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 샘플 프로젝트({@code samples/sample-shop})를 Java 분석기만으로 돌려 정답 목록의 Java 항목과 대조한다.
 * 전체 정답 대조(다른 분석기·링커 포함)는 cli 모듈의 검증이 한다. 여기서는 출발·도착이 모두 {@code java:}인
 * CALLS/OVERRIDES/EXTENDS/IMPLEMENTS/ASSOCIATES/INJECTS 항목만 본다.
 */
class SampleShopRegressionTest {

    private static final Set<String> KINDS = Set.of("CALLS", "OVERRIDES", "EXTENDS", "IMPLEMENTS", "ASSOCIATES", "INJECTS");

    private static Path sample;
    private static GraphModel graph;

    @BeforeAll
    static void analyze() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("samples/sample-shop"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("samples/sample-shop을 찾지 못함").isNotNull();
        sample = dir.resolve("samples/sample-shop");
        try (TestProject project = TestProject.at(sample)) {
            graph = project.run(new JavaAnalyzer()).graph();
        }
    }

    private static List<JsonNode> expected(String... files) {
        List<JsonNode> items = new ArrayList<>();
        for (String file : files) {
            try {
                new ObjectMapper().readTree(Files.readString(sample.resolve("expected").resolve(file))).forEach(items::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return items;
    }

    private static boolean isJava(JsonNode item, String field) {
        return item.path(field).asText("").startsWith("java:");
    }

    private static List<String> ends(JsonNode item, String field, String altField) {
        List<String> ids = new ArrayList<>();
        ids.add(item.path(field).asText());
        JsonNode alt = item.path(altField);
        if (alt.isArray()) {
            alt.forEach(a -> ids.add(a.asText()));
        } else if (alt.isTextual()) {
            ids.add(alt.asText());
        }
        return ids;
    }

    private static boolean present(JsonNode item) {
        EdgeKind kind = EdgeKind.valueOf(item.path("kind").asText());
        for (String from : ends(item, "from", "altFrom")) {
            for (String to : ends(item, "to", "altTo")) {
                if (!graph.edges(from, kind, to).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Java 분석기 혼자서는 만들지 않는 항목: XML 주입(springxml이 만든다). */
    private static boolean otherModules(JsonNode item) {
        return item.path("attrs").path("via").asText("").equals("xml");
    }

    @Test
    @DisplayName("샘플 회귀: 정답 목록의 Java 엣지(CALLS/OVERRIDES/EXTENDS/IMPLEMENTS/ASSOCIATES/INJECTS)가 모두 있다")
    void mustHaveJavaEdges() {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (JsonNode item : expected("must-have-edges.json", "must-have-edges-extra.json")) {
            if (!KINDS.contains(item.path("kind").asText()) || !isJava(item, "from") || !isJava(item, "to") || otherModules(item)) {
                continue;
            }
            checked++;
            if (!present(item)) {
                missing.add(item.path("kind").asText() + " " + item.path("from").asText() + " -> " + item.path("to").asText()
                        + " " + item.path("attrs") + " // " + item.path("note").asText());
            }
        }
        assertThat(checked).isGreaterThan(90);
        assertThat(missing).as("정답에 있는데 없는 Java 엣지").isEmpty();
    }

    @Test
    @DisplayName("샘플 회귀: 함정 목록의 Java 엣지가 없다")
    void mustNotHaveJavaEdges() {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (JsonNode item : expected("must-not-have-edges.json", "must-not-have-edges-extra.json")) {
            String from = item.path("from").asText("");
            String to = item.path("to").asText("");
            String kind = item.path("kind").asText("");
            if (!(from.startsWith("java:") || to.startsWith("java:")) || from.isEmpty() || to.isEmpty()) {
                continue;
            }
            checked++;
            for (Edge e : graph.edges()) {
                if ((from.equals("*") || e.from().equals(from)) && (to.equals("*") || e.to().equals(to))
                        && (kind.equals("*") || e.kind().name().equals(kind))) {
                    violations.add(e.kind() + " " + e.from() + " -> " + e.to() + " // " + item.path("note").asText());
                }
            }
        }
        assertThat(checked).isGreaterThan(10);
        assertThat(violations).as("있으면 안 되는 Java 엣지").isEmpty();
    }

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    @Test
    @DisplayName("F-003-AC23 샘플: 미해석 타입의 인자를 받는 외부 타입 생성(SqlSessionTemplate, JobBuilder, StepBuilder)에 <init>(?) CALLS가 있다")
    void externalConstructorCalls() {
        Edge template = edge(graph, m("com.example.shop.config.AppConfig", "batchSqlSession", "org.apache.ibatis.session.SqlSessionFactory"),
                EdgeKind.CALLS, m("org.mybatis.spring.SqlSessionTemplate", "<init>", "?"));
        assertThat(template.attrString("callType")).isEqualTo("constructor");
        assertThat(template.location().startLine()).isEqualTo(23);

        String config = "com.example.shop.batch.config.DailySalesJobConfig";
        String step = "org.springframework.batch.core.Step";
        assertThat(edge(graph, m(config, "dailySalesJob", step, step), EdgeKind.CALLS,
                m("org.springframework.batch.core.job.builder.JobBuilder", "<init>", "?")).location().startLine()).isEqualTo(51);
        String stepBuilder = m("org.springframework.batch.core.step.builder.StepBuilder", "<init>", "?");
        assertThat(edge(graph, m(config, "salesSumStep", "com.example.shop.batch.SalesProcessor", "com.example.shop.batch.SalesWriter"),
                EdgeKind.CALLS, stepBuilder).location().startLine()).isEqualTo(59);
        assertThat(edge(graph, m(config, "closeOrderStep", "com.example.shop.batch.CloseOrderTasklet"), EdgeKind.CALLS, stepBuilder)
                .location().startLine()).isEqualTo(69);
        // 대조군
        assertThat(graph.incoming(m("org.springframework.web.servlet.view.InternalResourceViewResolver", "<init>", "?"), EdgeKind.CALLS)).hasSize(1);
        assertThat(graph.incoming(m("org.mybatis.spring.batch.MyBatisPagingItemReader", "<init>", "?"), EdgeKind.CALLS)).hasSize(1);
    }

    @Test
    @DisplayName("F-003-AC27 샘플: 미해결 호출 원문에 인자 수가 괄호로 들어가지 않는다")
    void unresolvedCallTexts() {
        List<String> texts = graph.edges(EdgeKind.CALLS).stream().map(Edge::to).filter(t -> t.startsWith("unresolved:java-call:")).toList();
        assertThat(texts).isNotEmpty().noneMatch(t -> t.matches(".*\\(\\d+\\)$"));
        assertThat(texts).contains(Ids.unresolved("java-call", "chunkContext.getStepContext().getJobParameters(…)"));
    }

    @Test
    @DisplayName("F-004-AC14 샘플: LoginCheckInterceptor의 sendRedirect(request.getContextPath() + \"/main.do\")가 attrs.redirects에 남는다")
    void interceptorRedirect() {
        String preHandle = m("com.example.shop.common.LoginCheckInterceptor", "preHandle",
                "jakarta.servlet.http.HttpServletRequest", "jakarta.servlet.http.HttpServletResponse", "java.lang.Object");
        assertThat(node(graph, preHandle).attrList("redirects")).containsExactly("/main.do");
        assertThat(node(graph, preHandle).attrs()).doesNotContainKey("forwards");
    }
}
