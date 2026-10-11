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
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** URL로 시작 노드 찾기({@link EndpointLookup})의 일치 판정. 표시 이름에는 기대지 않는다(노드 ID만 본다). */
class EndpointLookupTest {

    @TempDir
    Path work;

    static final String EDIT = "src/pages/admin/schedule/EgovAdminScheduleEdit.jsx";
    /** 메서드를 하나로 정하지 못한 요청: {@code httpMethod=ANY} + {@code httpMethods=[POST, PUT]}(web이 남긴다). */
    static final String REQUEST_CANDIDATES = Ids.httpRequest("ANY", "/api/schedule", EDIT, 146);
    /** 메서드 후보가 없는 {@code ANY} 요청. */
    static final String REQUEST_ANY = Ids.httpRequest("ANY", "/api/schedule", "src/legacy/schedule.js", 10);
    static final String REQUEST_GET = Ids.httpRequest("GET", "/api/schedule", "src/pages/admin/schedule/EgovAdminScheduleList.jsx", 20);
    static final String ENDPOINT_POST = Ids.httpEndpoint("POST", "/api/schedule");
    static final String ENDPOINT_GET = Ids.httpEndpoint("GET", "/api/schedule");

    private static GraphModel empty() {
        GraphModel graph = new GraphModel();
        graph.project(new ProjectInfo("front", "/work/front", Instant.parse("2026-10-11T00:00:00Z")));
        return graph;
    }

    private static void request(GraphModel graph, String id, String method, String path, List<String> methods) {
        String file = id.substring(id.indexOf('@') + 1, id.lastIndexOf(':'));
        int line = Integer.parseInt(id.substring(id.lastIndexOf(':') + 1));
        graph.declare(Node.builder(id, NodeKind.HTTP_REQUEST).name(method + " " + path).location(SourceLocation.of(file, line))
                .attr("api", "fetch").attr("httpMethod", method).attr("httpMethods", methods).attr("path", path).build());
    }

    private static void endpoint(GraphModel graph, String id, String method, String path) {
        graph.declare(Node.builder(id, NodeKind.HTTP_ENDPOINT).name(method + " " + path)
                .attr("httpMethod", method).attr("path", path).location(SourceLocation.of("src/main/java/a/ScheduleController.java", 30)).build());
    }

    /** 화면 요청만 있는 그래프(프런트만 분석한 것): 메서드 후보 요청, 후보 없는 ANY 요청, GET 요청. */
    static GraphModel frontOnly() {
        GraphModel graph = empty();
        request(graph, REQUEST_CANDIDATES, "ANY", "/api/schedule", List.of("POST", "PUT"));
        request(graph, REQUEST_ANY, "ANY", "/api/schedule", null);
        request(graph, REQUEST_GET, "GET", "/api/schedule", null);
        return graph;
    }

    /** {@link #frontOnly()}에 {@code POST}·{@code GET /api/schedule} 엔드포인트를 더한 그래프. */
    static GraphModel frontAndBack() {
        GraphModel graph = frontOnly();
        endpoint(graph, ENDPOINT_POST, "POST", "/api/schedule");
        endpoint(graph, ENDPOINT_GET, "GET", "/api/schedule");
        return graph;
    }

    /** 명령이 읽는 것과 같은 그래프(저장했다가 복원한 것: 목록 속성이 JSON을 거친다). */
    private GraphModel stored(GraphModel graph, String name) throws IOException {
        return GraphJson.read(ShopGraphFixture.save(graph, work.resolve(name)));
    }

    private static List<String> ids(List<Node> nodes) {
        return nodes.stream().map(Node::id).toList();
    }

    @Test
    @DisplayName("F-001-AC75 URL 검색의 메서드는 화면 요청의 메서드 후보(httpMethods)에 든 것에만 맞는다. 후보가 없는 ANY 요청은 전처럼 전부 맞는다")
    void methodMatchesRequestCandidates() throws IOException {
        GraphModel graph = stored(frontAndBack(), "both");

        EndpointLookup.Match post = EndpointLookup.find(graph, "POST /api/schedule");
        assertThat(ids(post.endpoints())).containsExactly(ENDPOINT_POST);
        assertThat(ids(post.requests())).containsExactly(REQUEST_ANY, REQUEST_CANDIDATES);

        // httpMethods가 있는데 거기 없는 메서드면 맞지 않는다
        EndpointLookup.Match get = EndpointLookup.find(graph, "GET /api/schedule?page=2");
        assertThat(ids(get.endpoints())).containsExactly(ENDPOINT_GET);
        assertThat(ids(get.requests())).containsExactly(REQUEST_ANY, REQUEST_GET);

        // 메서드를 주지 않으면 그 경로의 전부다
        EndpointLookup.Match all = EndpointLookup.find(graph, "/api/schedule");
        assertThat(ids(all.endpoints())).containsExactly(ENDPOINT_GET, ENDPOINT_POST);
        assertThat(ids(all.requests())).containsExactly(REQUEST_ANY, REQUEST_CANDIDATES, REQUEST_GET);

        // 검색어의 ANY는 메서드를 정하지 않은 것(ANY)에만 맞는다(전과 같다)
        assertThat(EndpointLookup.find(graph, "ANY /api/schedule").requests()).extracting(Node::id)
                .containsExactly(REQUEST_ANY, REQUEST_CANDIDATES);
    }

    @Test
    @DisplayName("F-001-AC75 URL에 맞는 엔드포인트가 없으면 같은 경로·메서드의 화면 요청을 찾는다(엔드포인트 목록은 비어 있다). 요청도 없으면 null이다")
    void findsRequestsWhenNoEndpointMatches() throws IOException {
        GraphModel front = stored(frontOnly(), "front");

        EndpointLookup.Match put = EndpointLookup.find(front, "PUT /api/schedule");
        assertThat(put).isNotNull();
        assertThat(put.endpoints()).isEmpty();
        assertThat(ids(put.requests())).containsExactly(REQUEST_ANY, REQUEST_CANDIDATES);
        assertThat(put.path()).isEqualTo("/api/schedule");
        assertThat(put.contextPath()).isNull();
        assertThat(put.byPattern()).isFalse();

        assertThat(ids(EndpointLookup.find(front, "put http://localhost:3000/api/schedule?id=3#top").requests()))
                .containsExactly(REQUEST_ANY, REQUEST_CANDIDATES);
        assertThat(ids(EndpointLookup.find(front, "GET /api/schedule").requests())).containsExactly(REQUEST_ANY, REQUEST_GET);
        assertThat(ids(EndpointLookup.find(front, "DELETE /api/schedule").requests())).containsExactly(REQUEST_ANY);
        assertThat(ids(EndpointLookup.find(front, "/api/schedule/").requests())).containsExactly(REQUEST_ANY, REQUEST_CANDIDATES, REQUEST_GET);
        // 경로가 다르면 없다. 요청만 찾을 때도 엔드포인트처럼 맨 앞 1~2 세그먼트(컨텍스트 경로)를 떼고 다시 찾는다(F-001-R59)
        assertThat(EndpointLookup.find(front, "PUT /api/schedules")).isNull();
        EndpointLookup.Match stripped = EndpointLookup.find(front, "PUT /ctx/api/schedule");
        assertThat(ids(stripped.requests())).containsExactly(REQUEST_ANY, REQUEST_CANDIDATES);
        assertThat(stripped.contextPath()).isEqualTo("/ctx");
        assertThat(stripped.path()).isEqualTo("/api/schedule");
        assertThat(EndpointLookup.find(front, "PUT /a/b/c/api/schedule")).isNull();

        // 엔드포인트가 있는 그래프에서도 그 메서드의 엔드포인트가 없으면 요청을 찾는다
        GraphModel both = stored(frontAndBack(), "both");
        EndpointLookup.Match putBoth = EndpointLookup.find(both, "PUT /api/schedule");
        assertThat(putBoth.endpoints()).isEmpty();
        assertThat(ids(putBoth.requests())).containsExactly(REQUEST_ANY, REQUEST_CANDIDATES);

        // 메서드 후보가 [POST, PUT]뿐인 요청 하나만 있으면 GET으로는 아무것도 없다
        GraphModel single = empty();
        request(single, REQUEST_CANDIDATES, "ANY", "/api/schedule", List.of("POST", "PUT"));
        GraphModel stored = stored(single, "single");
        assertThat(EndpointLookup.find(stored, "GET /api/schedule")).isNull();
        assertThat(ids(EndpointLookup.find(stored, "post /api/schedule").requests())).containsExactly(REQUEST_CANDIDATES);
        assertThat(ids(EndpointLookup.find(stored, "/api/schedule").requests())).containsExactly(REQUEST_CANDIDATES);
    }

    @Test
    @DisplayName("F-001-AC76 action 없는 폼의 요청(selfSubmit, 경로 자리가 {self})은 URL 검색의 후보가 아니다: {self}는 경로도 경로 변수도 아니다")
    void selfSubmitRequestIsNotUrlCandidate() throws IOException {
        String page = "src/main/webapp/WEB-INF/jsp/user/form.jsp";
        String self = Ids.httpRequest("POST", "{self}", page, 95);
        String endpoint = Ids.httpEndpoint("POST", "/user/form");
        GraphModel graph = empty();
        graph.declare(Node.builder(self, NodeKind.HTTP_REQUEST).name("POST {self}").location(SourceLocation.of(page, 95))
                .attr("api", "form").attr("httpMethod", "POST").attr("path", "{self}").attr("selfSubmit", true).attr("url", "").build());
        endpoint(graph, endpoint, "POST", "/user/form");
        graph.addEdge(Edge.builder(self, endpoint, EdgeKind.MATCHES).inferred().attr("selfSubmit", true).build());
        GraphModel stored = stored(graph, "self");

        // 자기 페이지로 제출하는 폼이 그 엔드포인트로 이어져 있어도 "같은 경로의 화면 요청"으로 치지 않는다(요청에는 경로가 없다)
        EndpointLookup.Match match = EndpointLookup.find(stored, "POST /user/form");
        assertThat(ids(match.endpoints())).containsExactly(endpoint);
        assertThat(match.requests()).isEmpty();
        // {self}는 URL이 아니다: URL 검색이 아니라 이름 검색으로 넘어간다
        assertThat(EndpointLookup.find(stored, "POST {self}")).isNull();
        assertThat(EndpointLookup.find(stored, "{self}")).isNull();
        // 한 세그먼트짜리 경로를 줘도 {self}를 경로 변수 패턴으로 보고 맞추지 않는다
        assertThat(EndpointLookup.find(stored, "POST /anything")).isNull();
        assertThat(EndpointLookup.find(stored, "/{self}")).isNull();

        // 엔드포인트가 없는 그래프(요청만 찾는 경우)에서도 같다
        GraphModel only = empty();
        only.declare(stored.node(self).orElseThrow());
        GraphModel onlyStored = stored(only, "self-only");
        for (String query : List.of("POST /user/form", "/{self}", "POST /anything", "POST {self}")) {
            assertThat(EndpointLookup.find(onlyStored, query)).as(query).isNull();
        }
    }

    static final String REQUEST_DETAIL = Ids.httpRequest("ANY", "/api/schedule/{*}", EDIT, 150);
    static final String REQUEST_DETAIL_GET = Ids.httpRequest("GET", "/api/schedule/{*}", "src/pages/admin/schedule/EgovAdminScheduleDetail.jsx", 40);
    static final String REQUEST_NAMED = Ids.httpRequest("DELETE", "/api/board/{bbsId}/{nttId}", "src/pages/board/Detail.jsx", 70);
    static final String ENDPOINT_DETAIL_PUT = Ids.httpEndpoint("PUT", "/schedule/{schdulId}");

    /** {@link #frontOnly()}에 경로 변수가 든 요청 셋을 더한 그래프: {@code POST|PUT /api/schedule/{*}}, {@code GET /api/schedule/{*}}, {@code DELETE /api/board/{bbsId}/{nttId}}. */
    static GraphModel frontWithPatterns() {
        GraphModel graph = frontOnly();
        request(graph, REQUEST_DETAIL, "ANY", "/api/schedule/{*}", List.of("POST", "PUT"));
        request(graph, REQUEST_DETAIL_GET, "GET", "/api/schedule/{*}", null);
        request(graph, REQUEST_NAMED, "DELETE", "/api/board/{bbsId}/{nttId}", null);
        return graph;
    }

    @Test
    @DisplayName("F-001-AC83 엔드포인트가 없는 그래프에서 구체 경로를 주면 화면 요청의 경로 패턴({*}, {이름})에 맞춘다. 메서드 후보와 컨텍스트 경로 규칙은 그대로 적용한다")
    void concretePathMatchesRequestPatterns() throws IOException {
        GraphModel front = stored(frontWithPatterns(), "patterns");

        EndpointLookup.Match put = EndpointLookup.find(front, "PUT /api/schedule/1");
        assertThat(put).isNotNull();
        assertThat(put.endpoints()).isEmpty();
        assertThat(ids(put.requests())).containsExactly(REQUEST_DETAIL);
        assertThat(put.byPattern()).isTrue();
        assertThat(put.path()).isEqualTo("/api/schedule/1");
        // 메서드 후보([POST, PUT])에 없는 메서드는 그 요청에 맞지 않는다
        assertThat(ids(EndpointLookup.find(front, "GET /api/schedule/1?x=1").requests())).containsExactly(REQUEST_DETAIL_GET);
        assertThat(EndpointLookup.find(front, "DELETE /api/schedule/1")).isNull();
        assertThat(ids(EndpointLookup.find(front, "/api/schedule/1").requests())).containsExactly(REQUEST_DETAIL, REQUEST_DETAIL_GET);
        // 이름 있는 경로 변수도 같다. 세그먼트 수가 다르면 맞지 않는다
        assertThat(ids(EndpointLookup.find(front, "DELETE /api/board/BBS_1/15").requests())).containsExactly(REQUEST_NAMED);
        assertThat(EndpointLookup.find(front, "DELETE /api/board/BBS_1")).isNull();
        // 글자 그대로 같은 요청이 있으면 그것이 먼저다(패턴 꼴 검색어는 전처럼 글자 그대로 찾는다)
        EndpointLookup.Match literal = EndpointLookup.find(front, "PUT /api/schedule/{*}");
        assertThat(ids(literal.requests())).containsExactly(REQUEST_DETAIL);
        assertThat(literal.byPattern()).isFalse();
        assertThat(ids(EndpointLookup.find(front, "PUT /api/schedule").requests())).containsExactly(REQUEST_ANY, REQUEST_CANDIDATES);
        // 컨텍스트 경로를 뗀 뒤에도 패턴에 맞춘다
        EndpointLookup.Match stripped = EndpointLookup.find(front, "PUT /ctx/api/schedule/1");
        assertThat(ids(stripped.requests())).containsExactly(REQUEST_DETAIL);
        assertThat(stripped.contextPath()).isEqualTo("/ctx");

        // 세그먼트 전체인 {*}(한 세그먼트)가 세그먼트 일부인 {*}(/api{*}: 뒤가 통째로 동적)보다 좁다: 좁은 것만 남긴다
        GraphModel withBase = frontWithPatterns();
        String base = Ids.httpRequest("ANY", "/api{*}", "src/api/egovFetch.js", 18);
        request(withBase, base, "ANY", "/api{*}", null);
        GraphModel loose = stored(withBase, "patterns-base");
        assertThat(ids(EndpointLookup.find(loose, "PUT /api/schedule/1").requests())).containsExactly(REQUEST_DETAIL);
        EndpointLookup.Match onlyLoose = EndpointLookup.find(loose, "PUT /api/unknown/1/2");
        assertThat(ids(onlyLoose.requests())).containsExactly(base);
        assertThat(onlyLoose.byPattern()).isTrue();
        // 세그먼트 전체인 {*}는 한 세그먼트에만 맞는다
        assertThat(EndpointLookup.find(front, "PUT /api/schedule/1/files")).isNull();
    }

    @Test
    @DisplayName("F-001-AC83 엔드포인트를 찾았을 때의 '같은 경로의 화면 요청'에도 요청의 경로 패턴을 맞춘다(컨텍스트 경로를 떼기 전이나 뗀 뒤의 구체 경로)")
    void sameUrlRequestsMatchByPattern() throws IOException {
        GraphModel graph = frontWithPatterns();
        endpoint(graph, ENDPOINT_DETAIL_PUT, "PUT", "/schedule/{schdulId}");
        GraphModel both = stored(graph, "patterns-both");

        EndpointLookup.Match put = EndpointLookup.find(both, "PUT /api/schedule/5");
        assertThat(ids(put.endpoints())).containsExactly(ENDPOINT_DETAIL_PUT);
        assertThat(put.contextPath()).isEqualTo("/api");
        assertThat(ids(put.requests())).containsExactly(REQUEST_DETAIL);
        // 패턴 꼴 검색어(엔드포인트의 이름 그대로)는 요청의 패턴에 맞추지 않는다: 전과 같다
        assertThat(EndpointLookup.find(both, "PUT /schedule/{schdulId}").requests()).isEmpty();
    }

    @Test
    @DisplayName("F-001-AC84 메서드가 달라 찾지 못한 URL: 같은 경로(패턴·컨텍스트 경로 포함)에 다른 메서드로 맞는 엔드포인트(없으면 화면 요청)를 알려 준다")
    void otherMethodsOfTheSamePath() throws IOException {
        GraphModel both = stored(frontAndBack(), "both");
        assertThat(EndpointLookup.find(both, "DELETE /api/schedule").endpoints()).isEmpty();
        assertThat(ids(EndpointLookup.otherMethods(both, "DELETE /ctx/api/schedule", true, true))).containsExactly(ENDPOINT_GET, ENDPOINT_POST);
        // 메서드를 주지 않았거나 URL 꼴이 아니면 없다
        assertThat(EndpointLookup.otherMethods(both, "/api/schedule", true, true)).isEmpty();
        assertThat(EndpointLookup.otherMethods(both, "ScheduleController", true, true)).isEmpty();
        assertThat(EndpointLookup.otherMethods(both, "DELETE /api/none", true, true)).isEmpty();

        // 엔드포인트가 없는 그래프: 다른 메서드의 화면 요청
        GraphModel single = empty();
        request(single, REQUEST_GET, "GET", "/api/schedule", null);
        GraphModel front = stored(single, "single-get");
        assertThat(EndpointLookup.find(front, "POST /api/schedule")).isNull();
        assertThat(ids(EndpointLookup.otherMethods(front, "POST /api/schedule", true, true))).containsExactly(REQUEST_GET);
        assertThat(EndpointLookup.otherMethods(front, "POST /api/schedule", true, false)).isEmpty();
    }
}
