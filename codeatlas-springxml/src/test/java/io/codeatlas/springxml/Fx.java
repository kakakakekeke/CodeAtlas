package io.codeatlas.springxml;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.testing.TestProject;
import java.util.List;

/** 테스트 픽스처 도우미. */
final class Fx {

    /** {@link #beans(String)}이 붙이는 머리말. 흔히 쓰는 네임스페이스를 모두 선언한다. */
    static final String HEADER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xmlns:p="http://www.springframework.org/schema/p"
                   xmlns:c="http://www.springframework.org/schema/c"
                   xmlns:context="http://www.springframework.org/schema/context"
                   xmlns:util="http://www.springframework.org/schema/util"
                   xmlns:mvc="http://www.springframework.org/schema/mvc"
                   xmlns:aop="http://www.springframework.org/schema/aop"
                   xmlns:tx="http://www.springframework.org/schema/tx"
                   xmlns:batch="http://www.springframework.org/schema/batch"
                   xmlns:mybatis="http://mybatis.org/schema/mybatis-spring"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd">
            """;

    private Fx() {
    }

    /** 본문을 {@code <beans>}로 감싼 Spring XML. */
    static String beans(String body) {
        return HEADER + body + "</beans>\n";
    }

    /** (경로, 본문) 쌍들로 프로젝트를 만들어 분석기를 돌린다. */
    static AnalysisContext run(String... pathsAndContents) {
        try (TestProject project = TestProject.create()) {
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                project.file(pathsAndContents[i], pathsAndContents[i + 1]);
            }
            return project.run(new SpringXmlAnalyzer());
        }
    }

    /** needle이 처음 나오는 줄(1부터). */
    static int lineOf(String content, String needle) {
        int index = content.indexOf(needle);
        assertThat(index).as("픽스처에 '%s'가 있어야 한다", needle).isGreaterThanOrEqualTo(0);
        return (int) content.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    static Node node(GraphModel graph, String id) {
        assertThat(graph.node(id)).as("노드 %s", id).isPresent();
        return graph.node(id).orElseThrow();
    }

    /** from에서 to로 가는 kind 엣지가 정확히 하나 있어야 한다. */
    static Edge edge(GraphModel graph, String from, EdgeKind kind, String to) {
        List<Edge> found = graph.edges(from, kind, to);
        assertThat(found).as("%s -%s-> %s (나가는 엣지: %s)", from, kind, to, graph.outgoing(from, kind)).hasSize(1);
        return found.get(0);
    }

    static boolean hasEdge(GraphModel graph, String from, EdgeKind kind, String to) {
        return !graph.edges(from, kind, to).isEmpty();
    }

    static List<String> targets(GraphModel graph, String from, EdgeKind kind) {
        return graph.outgoing(from, kind).stream().map(Edge::to).toList();
    }

    static List<Diagnostic> diagnostics(GraphModel graph, String code) {
        return graph.diagnostics().stream().filter(d -> d.code().equals(code)).toList();
    }
}
