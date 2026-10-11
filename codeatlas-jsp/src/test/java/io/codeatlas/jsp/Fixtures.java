package io.codeatlas.jsp;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import java.util.List;

/** 테스트 공용 경로와 조회 도우미. 픽스처는 한국 SI 현장의 JSP 구성을 본뜬다. */
final class Fixtures {

    static final String WEB = "src/main/webapp";
    static final String VIEWS = WEB + "/WEB-INF/views";

    static final String TAGLIBS = """
            <%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
            <%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
            <%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
            <%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
            <%@ taglib prefix="spring" uri="http://www.springframework.org/tags" %>
            <c:set var="ctx" value="${pageContext.request.contextPath}"/>
            """;

    private Fixtures() {
    }

    /** HTTP_REQUEST 노드를 "METHOD URL" 문자열로(ID에서 @파일:줄을 뗀 것). */
    static List<String> requestKeys(AnalysisContext ctx) {
        return ctx.graph().nodes(NodeKind.HTTP_REQUEST).stream().map(Fixtures::requestKey).toList();
    }

    static String requestKey(Node node) {
        String rest = Ids.remainder(node.id());
        return rest.substring(0, rest.lastIndexOf('@'));
    }

    static Node request(AnalysisContext ctx, String key) {
        return ctx.graph().nodes(NodeKind.HTTP_REQUEST).stream().filter(n -> requestKey(n).equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("요청 없음: " + key + " / 있는 것: " + requestKeys(ctx)));
    }

    static List<Edge> includes(AnalysisContext ctx, String pagePath) {
        return ctx.graph().outgoing(Ids.jspPage(pagePath), EdgeKind.INCLUDES);
    }

    static Edge include(AnalysisContext ctx, String pagePath, String targetId) {
        return includes(ctx, pagePath).stream().filter(e -> e.to().equals(targetId)).findFirst()
                .orElseThrow(() -> new AssertionError("INCLUDES 없음: " + targetId + " / 있는 것: "
                        + includes(ctx, pagePath).stream().map(Edge::to).toList()));
    }
}
