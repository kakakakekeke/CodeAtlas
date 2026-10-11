package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMatchLinkerTest extends LinkTestBase {

    private Edge match(String request) {
        return only(request, EdgeKind.MATCHES);
    }

    private boolean unresolved(String request) {
        List<Edge> edges = out(request, EdgeKind.MATCHES);
        return edges.size() == 1 && edges.get(0).confidence() == Confidence.UNRESOLVED
                && edges.get(0).to().startsWith("unresolved:http-endpoint:");
    }

    @Test
    @DisplayName("F-012-AC12 메서드가 호환되고 경로가 맞으면 RESOLVED MATCHES")
    void exactMatch() {
        String getOne = endpoint("GET", "/orders/{id}");
        String post = endpoint("POST", "/orders");
        String any = endpoint(null, "/member/list.do");
        String r1 = request("GET", "/orders/{*}");
        String r2 = request("POST", "/orders");
        String r3 = request("GET", "/member/list.do?page=1#top");
        String r4 = request("ANY", "/orders");
        String r5 = request("GET", "/orders");

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(getOne);
        assertThat(match(r1).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r1).location()).isEqualTo(g.node(r1).orElseThrow().location());
        assertThat(match(r2).to()).isEqualTo(post);
        assertThat(match(r2).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r3).to()).isEqualTo(any);
        assertThat(match(r3).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r4).to()).isEqualTo(post);
        assertThat(unresolved(r5)).as("GET /orders 는 POST /orders 와 맞지 않는다").isTrue();
    }

    @Test
    @DisplayName("F-012-AC12 속성이 없는 노드는 ID에서 메서드와 경로를 얻는다")
    void attrsFallBackToId() {
        String ep = Ids.httpEndpoint("GET", "/a/b");
        g.declare(Node.of(ep, NodeKind.HTTP_ENDPOINT, "/a/b"));
        String rq = Ids.httpRequest("GET", "/a/b", "web/x.js", 3);
        g.declare(Node.of(rq, NodeKind.HTTP_REQUEST, "/a/b"));

        run(new HttpMatchLinker());

        assertThat(match(rq).to()).isEqualTo(ep);
    }

    @Test
    @DisplayName("F-012-AC12 attrs.path(정규화된 패턴)가 있으면 url(원문)보다 먼저 쓰고, external=true인 요청은 맞추지 않는다")
    void pathAttrPreferredAndExternalExcluded() {
        String ep = endpoint("GET", "/order/detail");
        String r1 = g.declare(requestBuilder("GET", "${ctx}/order/detail?orderId=${o.orderId}")
                .attr("path", "/order/detail").attr("query", "orderId=${o.orderId}").build()).id();
        String r2 = g.declare(requestBuilder("GET", "http://other.example.com/order/detail")
                .attr("path", "/order/detail").attr("external", true).build()).id();

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(ep);
        assertThat(match(r1).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(out(r2, EdgeKind.MATCHES)).isEmpty();
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC13 와일드카드 세그먼트(**, {var:regex}, *.do). 끝의 / 만 다른 경로는 맞는다(F-012-AC34)")
    void wildcardSegments() {
        String deep = endpoint("GET", "/files/**");
        String item = endpoint("GET", "/item/{id:\\d+}");
        String glob = endpoint("GET", "/a/*.do");
        String orders = endpoint("GET", "/orders");
        String r1 = request("GET", "/files/a/b");
        String r2 = request("GET", "/item/12");
        String r3 = request("GET", "/item/abc");
        String r4 = request("GET", "/a/x.do");
        String r5 = request("GET", "/orders/");
        String r6 = request("GET", "/a/x.json");

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(deep);
        assertThat(match(r2).to()).isEqualTo(item);
        assertThat(unresolved(r3)).isTrue();
        assertThat(match(r4).to()).isEqualTo(glob);
        assertThat(match(r5).to()).as("끝 슬래시만 다른 /orders/ 는 /orders 와 맞는다").isEqualTo(orders);
        assertThat(match(r5).attrBoolean("trailingSlash")).isTrue();
        assertThat(unresolved(r6)).isTrue();
    }

    @Test
    @DisplayName("F-012-AC14 접두 세그먼트(서블릿 매핑, 컨텍스트 경로)를 떼어 맞으면 INFERRED + strippedPrefix")
    void strippedPrefix() {
        project.file("src/main/webapp/WEB-INF/web.xml", "<web-app/>");
        rescan();
        g.putAttrs(Ids.file("src/main/webapp/WEB-INF/web.xml"), java.util.Map.of("servletMappings", List.of("/api/*", "*.do", "/")));
        String ep = endpoint("GET", "/orders");
        String r1 = request("GET", "/api/orders");
        String r2 = request("GET", "/shop/app/orders");
        String r3 = request("GET", "{*}/orders");
        String r4 = request("GET", "/a/b/c/orders");

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(ep);
        assertThat(inferred(match(r1))).isTrue();
        assertThat(match(r1).attrString("strippedPrefix")).isEqualTo("/api");
        assertThat(inferred(match(r2))).isTrue();
        assertThat(match(r2).attrString("strippedPrefix")).isEqualTo("/shop/app");
        assertThat(inferred(match(r3))).isTrue();
        assertThat(match(r3).attrString("strippedPrefix")).isEqualTo("{*}");
        assertThat(unresolved(r4)).as("세 세그먼트 접두는 떼지 않는다").isTrue();
    }

    @Test
    @DisplayName("F-012-AC14 정확히 맞는 엔드포인트가 있으면 접두를 떼지 않는다")
    void exactBeatsStripped() {
        String full = endpoint("GET", "/api/orders");
        endpoint("GET", "/orders");
        String r = request("GET", "/api/orders");

        run(new HttpMatchLinker());

        assertThat(match(r).to()).isEqualTo(full);
        assertThat(match(r).confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    @DisplayName("F-012-AC15 상대 URL은 끝이 맞는 엔드포인트가 하나일 때만 INFERRED")
    void relativeUrl() {
        String save = endpoint("POST", "/order/save.do");
        endpoint("GET", "/order/list.do");
        endpoint("GET", "/member/list.do");
        String detail = endpoint("GET", "/order/detail.do");
        String r1 = g.declare(requestBuilder("POST", "save.do").attr("relative", true).build()).id();
        String r2 = g.declare(requestBuilder("GET", "list.do").attr("relative", true).build()).id();
        String r3 = request("GET", "../order/detail.do");   // relative 속성이 없어도 상대 URL로 본다

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(save);
        assertThat(inferred(match(r1))).isTrue();
        assertThat(match(r1).attrBoolean("relative")).isTrue();
        assertThat(unresolved(r2)).isTrue();
        assertThat(match(r3).to()).isEqualTo(detail);
        assertThat(inferred(match(r3))).isTrue();
    }

    @Test
    @DisplayName("F-012-AC16 후보가 여럿이면 가장 구체적인 것 하나, 동률이면 전부 INFERRED + ambiguous")
    void mostSpecificOrAmbiguous() {
        String byId = endpoint("GET", "/orders/{id}");
        String fresh = endpoint("GET", "/orders/new");
        String ax = endpoint("GET", "/a/{x}");
        String ay = endpoint("GET", "/a/{y}");
        String r1 = request("GET", "/orders/new");
        String r2 = request("GET", "/orders/{*}");
        String r3 = request("GET", "/a/1");

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(fresh);
        assertThat(match(r1).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r2).to()).isEqualTo(byId);
        assertThat(match(r2).confidence()).isEqualTo(Confidence.RESOLVED);
        List<Edge> ambiguous = out(r3, EdgeKind.MATCHES);
        assertThat(ambiguous).extracting(Edge::to).containsExactlyInAnyOrder(ax, ay);
        assertThat(ambiguous).allMatch(e -> inferred(e) && e.attrBoolean("ambiguous"));
    }

    @Test
    @DisplayName("F-012-AC16 메서드가 정확히 같은 엔드포인트가 ANY 엔드포인트보다 우선하고, 동적 세그먼트가 고정 세그먼트와 맞으면 INFERRED")
    void methodTieBreakAndDynamicSegment() {
        String get = endpoint("GET", "/x");
        endpoint(null, "/x");
        String list = endpoint("GET", "/order/list");
        String r1 = request("GET", "/x");
        String r2 = request("GET", "/{*}/list");

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(get);
        assertThat(match(r1).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r2).to()).isEqualTo(list);
        assertThat(inferred(match(r2))).isTrue();
        assertThat(match(r2).attrBoolean("dynamicSegment")).isTrue();
    }

    @Test
    @DisplayName("F-012-AC17 맞는 엔드포인트가 없으면 미해결 엣지와 진단, 외부 URL·정적 자원은 아무것도 만들지 않는다")
    void noMatch() {
        endpoint("GET", "/orders");
        String r1 = request("GET", "/nothing/here");
        String r2 = g.declare(requestBuilder("GET", "https://api.partner.com/v1/pay").attr("external", true).build()).id();
        String r3 = request("GET", "/resources/js/a.js");
        String r4 = g.declare(requestBuilder("POST", "{*}").attr("dynamic", true).build()).id();
        String r5 = request("GET", "javascript:void(0)");
        String r6 = request("GET", "/images/logo.PNG?v=3");

        run(new HttpMatchLinker());

        assertThat(unresolved(r1)).isTrue();
        assertThat(match(r1).to()).isEqualTo("unresolved:http-endpoint:GET /nothing/here");
        assertThat(g.node(match(r1).to()).orElseThrow().kind()).isEqualTo(NodeKind.UNRESOLVED);
        assertThat(unresolved(r4)).isTrue();
        assertThat(out(r2, EdgeKind.MATCHES)).isEmpty();
        assertThat(out(r3, EdgeKind.MATCHES)).isEmpty();
        assertThat(out(r5, EdgeKind.MATCHES)).isEmpty();
        assertThat(out(r6, EdgeKind.MATCHES)).isEmpty();
        assertThat(diagnostics("UNRESOLVED_REFERENCE")).hasSize(2);
    }

    @Test
    @DisplayName("F-012-AC17 호스트가 붙은 URL은 호스트를 뗀 경로가 맞으면 INFERRED")
    void hostQualifiedUrl() {
        String ep = endpoint("GET", "/orders");
        String r = request("GET", "http://localhost:8080/orders");

        run(new HttpMatchLinker());

        assertThat(match(r).to()).isEqualTo(ep);
        assertThat(inferred(match(r))).isTrue();
        assertThat(match(r).attrString("strippedPrefix")).isEqualTo("http://localhost:8080");
    }

    @Test
    @DisplayName("F-012-AC12 엔드포인트가 수천 개여도 빠르게 맞춘다")
    void manyEndpoints() {
        for (int i = 0; i < 3000; i++) {
            endpoint("GET", "/m" + i + "/list/{id}");
        }
        for (int i = 0; i < 3000; i++) {
            request("GET", "/m" + i + "/list/{*}");
        }
        long start = System.nanoTime();

        run(new HttpMatchLinker());

        assertThat(g.edges(EdgeKind.MATCHES)).hasSize(3000).allMatch(e -> e.confidence() == Confidence.RESOLVED);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5000);
    }

    @Test
    @DisplayName("F-012-AC44 clientRoute=true인 요청(클라이언트 라우팅)은 맞는 엔드포인트가 있어도 잇지 않고 미해결도 남기지 않는다")
    void clientRouteRequestsAreSkipped() {
        String login = endpoint("GET", "/login");
        String route = g.declare(requestBuilder("GET", "/login").attr("path", "/login").attr("api", "route").attr("clientRoute", true).build()).id();
        String missing = g.declare(requestBuilder("GET", "/admin/board/{*}").attr("path", "/admin/board/{*}")
                .attr("api", "route").attr("clientRoute", true).build()).id();
        String real = request("GET", "/login");

        run(new HttpMatchLinker());

        assertThat(out(route, EdgeKind.MATCHES)).isEmpty();
        assertThat(out(missing, EdgeKind.MATCHES)).isEmpty();
        assertThat(g.diagnostics()).isEmpty();
        assertThat(g.nodes(NodeKind.UNRESOLVED)).isEmpty();
        // 같은 경로의 보통 요청은 전처럼 잇는다
        assertThat(match(real).to()).isEqualTo(login);
    }
}
