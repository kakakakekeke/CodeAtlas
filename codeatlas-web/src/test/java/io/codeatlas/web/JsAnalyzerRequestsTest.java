package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static io.codeatlas.web.G.mod;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsAnalyzerRequestsTest {

    private static final String REQ = """
            import axios from 'axios';
            export function f1() { fetch('/a/fetch'); }
            export function f2() { fetch('/a/fetch-post', { method: 'post' }); window.fetch('/a/fetch-win'); }
            export function f3(m, opts) { fetch('/a/fetch-any', { method: m.name }); fetch('/a/fetch-opts', opts); }
            export function a1() { axios.get('/a/axios-get'); axios.delete('/a/axios-del'); axios.post('/a/axios-post', {}); }
            export function a2() {
              axios('/a/axios-fn');
              axios({ url: '/a/axios-cfg', method: 'put' });
              axios.request({ url: '/a/axios-req', method: 'PATCH' });
              axios('/a/axios-fn2', { method: 'post' });
            }
            export function j1() {
              $.ajax({ url: '/a/jq-ajax', type: 'POST', success: function () {} });
              $.ajax('/a/jq-ajax2', { method: 'DELETE' });
              $.ajax({ url: '/a/jq-default' });
            }
            export function j2() {
              $.get('/a/jq-get');
              $.post('/a/jq-post', {});
              $.getJSON('/a/jq-json');
              jQuery.getScript('/a/jq-script');
              $('#d').load('/a/jq-load');
              $('#d').find('.x').load('/a/jq-load-post', { a: 1 });
            }
            export function x1() { var xhr = new XMLHttpRequest(); xhr.open('PUT', '/a/xhr'); xhr.send(); }
            export function x2(req) { req.open('post', '/a/xhr2', true); }
            export function b1() { navigator.sendBeacon('/a/beacon', d); new EventSource('/a/sse'); }
            export function n1() {
              location.href = '/a/nav1';
              window.location = '/a/nav2';
              location.assign('/a/nav3');
              window.location.replace('/a/nav4');
              window.open('/a/nav5', '_blank');
              document.location.href = '/a/nav6';
              location.href = '#top';
              location.href = 'javascript:void(0)';
            }
            export function fm(action) {
              document.forms[0].action = '/a/form1';
              $('#f').attr('action', '/a/form2');
              action.type = 'x';
              state.action = action;
            }
            $(function () { $.get('/a/module-level'); });
            """;

    private static void assertRequest(G g, String owner, String method, String path, String api) {
        Node n = g.request(method, path);
        assertThat(n.attrString("api")).as(path).isEqualTo(api);
        assertThat(n.attrBoolean("dynamic")).as(path).isFalse();
        assertThat(g.hasEdge(owner, EdgeKind.REQUESTS, n.id())).as("REQUESTS " + owner + " → " + n.id()).isTrue();
    }

    @Test
    @DisplayName("F-010-AC9 HTTP API 형태마다 HTTP_REQUEST 노드와 REQUESTS 엣지를 만들고 메서드를 정한다")
    void requestApis() {
        try (TestProject project = TestProject.create().file("src/req.js", REQ)) {
            G g = new G(project.run(new JsAnalyzer()));
            String p = "src/req.js";

            assertRequest(g, fn(p, "f1"), "GET", "/a/fetch", "fetch");
            assertRequest(g, fn(p, "f2"), "POST", "/a/fetch-post", "fetch");
            assertRequest(g, fn(p, "f2"), "GET", "/a/fetch-win", "fetch");
            assertRequest(g, fn(p, "f3"), "ANY", "/a/fetch-any", "fetch");
            assertRequest(g, fn(p, "f3"), "ANY", "/a/fetch-opts", "fetch");
            assertRequest(g, fn(p, "a1"), "GET", "/a/axios-get", "axios");
            assertRequest(g, fn(p, "a1"), "DELETE", "/a/axios-del", "axios");
            assertRequest(g, fn(p, "a1"), "POST", "/a/axios-post", "axios");
            assertRequest(g, fn(p, "a2"), "GET", "/a/axios-fn", "axios");
            assertRequest(g, fn(p, "a2"), "PUT", "/a/axios-cfg", "axios");
            assertRequest(g, fn(p, "a2"), "PATCH", "/a/axios-req", "axios");
            assertRequest(g, fn(p, "a2"), "POST", "/a/axios-fn2", "axios");
            assertRequest(g, fn(p, "j1"), "POST", "/a/jq-ajax", "jquery");
            assertRequest(g, fn(p, "j1"), "DELETE", "/a/jq-ajax2", "jquery");
            assertRequest(g, fn(p, "j1"), "GET", "/a/jq-default", "jquery");
            assertRequest(g, fn(p, "j2"), "GET", "/a/jq-get", "jquery");
            assertRequest(g, fn(p, "j2"), "POST", "/a/jq-post", "jquery");
            assertRequest(g, fn(p, "j2"), "GET", "/a/jq-json", "jquery");
            assertRequest(g, fn(p, "j2"), "GET", "/a/jq-script", "jquery");
            assertRequest(g, fn(p, "j2"), "GET", "/a/jq-load", "jquery");
            assertRequest(g, fn(p, "j2"), "POST", "/a/jq-load-post", "jquery");
            assertRequest(g, fn(p, "x1"), "PUT", "/a/xhr", "xhr");
            assertRequest(g, fn(p, "x2"), "POST", "/a/xhr2", "xhr");
            assertRequest(g, fn(p, "b1"), "POST", "/a/beacon", "beacon");
            assertRequest(g, fn(p, "b1"), "GET", "/a/sse", "eventsource");
            for (int i = 1; i <= 6; i++) {
                assertRequest(g, fn(p, "n1"), "GET", "/a/nav" + i, "navigation");
            }
            assertRequest(g, fn(p, "fm"), "ANY", "/a/form1", "form");
            assertRequest(g, fn(p, "fm"), "ANY", "/a/form2", "form");
            assertRequest(g, mod(p), "GET", "/a/module-level", "jquery");

            assertThat(g.ids(NodeKind.HTTP_REQUEST)).hasSize(34);
            assertThat(g.ids(NodeKind.UNRESOLVED)).isEmpty();

            Node fetch = g.request("GET", "/a/fetch");
            assertThat(fetch.id()).isEqualTo("http:request:GET /a/fetch@src/req.js:2");
            assertThat(fetch.name()).isEqualTo("GET /a/fetch");
            assertThat(fetch.attrString("url")).isEqualTo("'/a/fetch'");
            assertThat(fetch.location().startLine()).isEqualTo(2);
            // 요청 지점은 CALLS로 기록하지 않는다
            assertThat(g.edges(fn(p, "f1"), EdgeKind.CALLS)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC10 URL 식을 평가한다: 템플릿, 연결, 상수 전파, 객체 상수, 쿼리, 외부 URL, 삼항")
    void urlEvaluation() {
        String url = """
                import { API_BASE, API } from './consts';
                import paths from './consts';
                const LOCAL = '/local';
                export function u1(id) { fetch(`/orders/${id}`); }
                export function u2(id) { fetch(`${API_BASE}/products/${id}/stock`); }
                export function u3(id) { fetch(LOCAL + '/x/' + id + '/y'); }
                export function u4() { fetch(API.ORDERS); fetch(API.nested.ITEM + '/1'); fetch(paths.ORDERS + '/default'); }
                export function u5(p) { fetch('/search?q=' + p + '&page=1'); }
                export function u6() { fetch('https://ext.example.com/v1/ping'); fetch('//cdn.example.com/x.json'); }
                export function u7(isNew) {
                  var url = isNew ? '/order/insert.do' : '/order/update.do';
                  $.ajax({ url: url, type: 'POST' });
                }
                export function u8() { var url = '/list.do'; url += '?page=' + 1; location.href = url; }
                export function u9(ctx, a, b) { fetch(ctx + '/c/' + a + b + '/d'); }
                export function u10(base) { fetch(base + '/tail'); }
                export function u11(mode) {
                  var url;
                  if (mode) { url = '/m/one'; } else { url = '/m/two'; }
                  fetch(url);
                }
                export function u12() { fetch(getContextPath() + '/main.do'); fetch('rel/path.do'); }
                export function u13(id) { const base = `/orders/${id}`; return fetch(`${base}/items`); }
                """;
        try (TestProject project = TestProject.create()
                .file("src/consts.js", "export const API_BASE = '/api';\nexport const API = { ORDERS: '/api/orders', nested: { ITEM: '/api/items' } };\nexport default API;\n")
                .file("src/url.js", url)) {
            G g = new G(project.run(new JsAnalyzer()));
            String p = "src/url.js";

            assertThat(g.requestsFrom(fn(p, "u1"))).containsExactly("GET /orders/{*}");
            assertThat(g.request("GET", "/orders/{*}").attrBoolean("dynamic")).isTrue();
            assertThat(g.request("GET", "/orders/{*}").id()).isEqualTo("http:request:GET /orders/{*}@src/url.js:4");
            assertThat(g.requestsFrom(fn(p, "u2"))).containsExactly("GET /api/products/{*}/stock");
            assertThat(g.requestsFrom(fn(p, "u3"))).containsExactly("GET /local/x/{*}/y");
            assertThat(g.requestsFrom(fn(p, "u4"))).containsExactlyInAnyOrder("GET /api/orders", "GET /api/items/1", "GET /api/orders/default");
            assertThat(g.request("GET", "/api/orders").attrBoolean("dynamic")).isFalse();

            Node search = g.request("GET", "/search");
            assertThat(search.attrString("query")).isEqualTo("q={*}&page=1");
            assertThat(search.attrBoolean("dynamic")).isTrue();
            assertThat(search.attrString("url")).isEqualTo("'/search?q=' + p + '&page=1'");

            assertThat(g.request("GET", "https://ext.example.com/v1/ping").attrBoolean("external")).isTrue();
            assertThat(g.request("GET", "//cdn.example.com/x.json").attrBoolean("external")).isTrue();
            assertThat(g.request("GET", "/api/orders").attr("external")).isNull();

            assertThat(g.requestsFrom(fn(p, "u7"))).containsExactlyInAnyOrder("POST /order/insert.do", "POST /order/update.do");
            assertThat(g.edge(fn(p, "u7"), EdgeKind.REQUESTS, g.request("POST", "/order/insert.do").id()).confidence())
                    .isEqualTo(Confidence.RESOLVED);
            assertThat(g.requestsFrom(fn(p, "u8"))).containsExactly("GET /list.do");
            assertThat(g.request("GET", "/list.do").attrString("query")).isEqualTo("page=1");
            assertThat(g.requestsFrom(fn(p, "u9"))).containsExactly("GET /c/{*}/d");
            assertThat(g.requestsFrom(fn(p, "u10"))).containsExactly("GET {*}/tail");
            assertThat(g.requestsFrom(fn(p, "u11"))).containsExactlyInAnyOrder("GET /m/one", "GET /m/two");
            assertThat(g.edge(fn(p, "u11"), EdgeKind.REQUESTS, g.request("GET", "/m/one").id()).confidence())
                    .isEqualTo(Confidence.INFERRED);
            assertThat(g.requestsFrom(fn(p, "u12"))).containsExactlyInAnyOrder("GET /main.do", "GET rel/path.do");
            assertThat(g.request("GET", "rel/path.do").attrBoolean("relative")).isTrue();
            assertThat(g.request("GET", "/main.do").attr("relative")).isNull();
            assertThat(g.requestsFrom(fn(p, "u13"))).containsExactly("GET /orders/{*}/items");
            assertThat(g.ids(NodeKind.UNRESOLVED)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC11 정적인 경로 조각이 없는 URL은 HTTP_REQUEST를 만들지 않고 미해결 REQUESTS(http-url)를 남긴다")
    void unresolvedUrls() {
        String src = """
                function buildUrl(...parts) { return '/' + parts.join('/'); }
                export function e() { const url = buildUrl('orders', 'export'); return fetch(url); }
                export function h(settings) { $.ajax(settings); location.href = settings.next; }
                export function k(a, b) { axios.get(`${a}/${b}`); }
                """;
        try (TestProject project = TestProject.create().file("src/u.js", src)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.ids(NodeKind.HTTP_REQUEST)).isEmpty();
            assertThat(g.targets(fn("src/u.js", "e"), EdgeKind.REQUESTS)).containsExactly("unresolved:http-url:url");
            assertThat(g.edge(fn("src/u.js", "e"), EdgeKind.REQUESTS, "unresolved:http-url:url").confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(g.edge(fn("src/u.js", "e"), EdgeKind.REQUESTS, "unresolved:http-url:url").location().startLine()).isEqualTo(2);
            assertThat(g.targets(fn("src/u.js", "h"), EdgeKind.REQUESTS))
                    .containsExactlyInAnyOrder("unresolved:http-url:settings", "unresolved:http-url:settings.next");
            assertThat(g.targets(fn("src/u.js", "k"), EdgeKind.REQUESTS)).containsExactly("unresolved:http-url:`${a}/${b}`");
            assertThat(g.diagnostics(DiagnosticCodes.UNRESOLVED_REFERENCE)).hasSize(4);
            // 요청은 미해결이어도 buildUrl 호출은 CALLS로 남는다
            assertThat(g.targets(fn("src/u.js", "e"), EdgeKind.CALLS)).containsExactly(fn("src/u.js", "buildUrl"));
        }
    }

    @Test
    @DisplayName("F-010-AC12 axios.create의 baseURL을 인스턴스 요청 URL 앞에 붙인다(다른 모듈에서 import해도)")
    void axiosInstanceBaseUrl() {
        try (TestProject project = TestProject.create()
                .file("src/api/client.js", """
                        import axios from 'axios';
                        const client = axios.create({ baseURL: '/api', timeout: 5000 });
                        client.interceptors.response.use((r) => r);
                        export default client;
                        export const admin = axios.create({ baseURL: '/admin/' });
                        """)
                .file("src/api/orderApi.js", """
                        import client, { admin } from './client';
                        import axios from 'axios';
                        const BASE = '/v2';
                        const local = axios.create({ baseURL: BASE });
                        export const fetchOrders = (params) => client.get('/orders', { params });
                        export const changeStatus = (id, status) => client.put(`/orders/${id}/status`, { status });
                        export function remove(id) { return client.delete('orders/' + id); }
                        export function users() { return admin.get('/users'); }
                        export function cfg() { return client({ url: '/cfg', method: 'post' }); }
                        export function v2() { return local.post('/things'); }
                        export function abs() { return client.get('https://other.example.com/x'); }
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));
            String p = "src/api/orderApi.js";

            assertThat(g.requestsFrom(fn(p, "fetchOrders"))).containsExactly("GET /api/orders");
            assertThat(g.requestsFrom(fn(p, "changeStatus"))).containsExactly("PUT /api/orders/{*}/status");
            assertThat(g.requestsFrom(fn(p, "remove"))).containsExactly("DELETE /api/orders/{*}");
            assertThat(g.requestsFrom(fn(p, "users"))).containsExactly("GET /admin/users");
            assertThat(g.requestsFrom(fn(p, "cfg"))).containsExactly("POST /api/cfg");
            assertThat(g.requestsFrom(fn(p, "v2"))).containsExactly("POST /v2/things");
            assertThat(g.requestsFrom(fn(p, "abs"))).containsExactly("GET https://other.example.com/x");
            assertThat(g.request("GET", "/api/orders").attrString("api")).isEqualTo("axios");
            assertThat(g.request("GET", "/api/orders").attrString("url")).isEqualTo("'/orders'");
            assertThat(g.ids(NodeKind.UNRESOLVED)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC16 URL을 매개변수로 받는 요청 래퍼의 호출 지점에 요청을 만든다")
    void requestWrappers() {
        try (TestProject project = TestProject.create()
                .file("js/common.js", """
                        function ajaxPost(url, data, callback) {
                          $.ajax({ url: url, type: 'POST', data: data, success: callback });
                        }
                        function send(method, path) {
                          var xhr = new XMLHttpRequest();
                          xhr.open(method, CTX + '/api' + path);
                        }
                        function postJson(path, body) { send('POST', path); }
                        """)
                .file("js/order.js", """
                        var OrderModule = (function ($) {
                          var BASE = '/order';
                          function close(orderId) {
                            ajaxPost(BASE + '/close.do', { orderId: orderId }, function () {});
                          }
                          function load(id) { send('get', '/orders/' + id); }
                          function save(o) { postJson('/orders', o); }
                          function pass(u) { ajaxPost(u); }
                          return { close: close };
                        })(jQuery);
                        """)) {
            // 두 파일을 함께 싣는 페이지가 있어야 order.js의 호출이 common.js의 래퍼로 이어진다(F-010-R18)
            G g = new G(project.run(G.page("web/order.jsp", "js/common.js", "js/order.js"), new JsAnalyzer()));
            String wrapper = fn("js/common.js", "ajaxPost");

            // 래퍼 안의 요청 지점은 URL을 알 수 없다
            assertThat(g.targets(wrapper, EdgeKind.REQUESTS)).containsExactly("unresolved:http-url:url");
            // 호출 지점에 요청이 생긴다
            assertThat(g.requestsFrom(fn("js/order.js", "OrderModule.close"))).containsExactly("POST /order/close.do");
            Node close = g.request("POST", "/order/close.do");
            assertThat(close.id()).isEqualTo("http:request:POST /order/close.do@js/order.js:4");
            assertThat(close.attrString("api")).isEqualTo("jquery");
            assertThat(close.attrString("via")).isEqualTo(wrapper);
            assertThat(close.attrString("url")).isEqualTo("BASE + '/close.do'");
            assertThat(g.hasEdge(fn("js/order.js", "OrderModule.close"), EdgeKind.CALLS, wrapper)).isTrue();
            // 메서드도 인자에서 온다. 래퍼의 고정 접두사가 붙는다
            assertThat(g.requestsFrom(fn("js/order.js", "OrderModule.load"))).containsExactly("GET /api/orders/{*}");
            assertThat(g.request("GET", "/api/orders/{*}").attrString("api")).isEqualTo("xhr");
            // 래퍼를 감싼 래퍼
            assertThat(g.requestsFrom(fn("js/order.js", "OrderModule.save"))).containsExactly("POST /api/orders");
            assertThat(g.request("POST", "/api/orders").attrString("via")).isEqualTo(fn("js/common.js", "send"));
            // 인자도 동적이면 호출 지점에는 아무것도 만들지 않는다
            assertThat(g.edges(fn("js/order.js", "OrderModule.pass"), EdgeKind.REQUESTS)).isEmpty();
            // 래퍼 자신: 정적 접두사가 있으면 그 패턴으로 남는다
            assertThat(g.requestsFrom(fn("js/common.js", "send"))).containsExactly("ANY /api{*}");
        }
    }

    @Test
    @DisplayName("F-010-AC11 URL 식 자체에 정적 조각이 없으면 baseURL이 있어도 미해결이다")
    void baseUrlAloneIsNotStatic() {
        try (TestProject project = TestProject.create()
                .file("src/client.js", "import axios from 'axios';\nexport default axios.create({ baseURL: '/api' });\n")
                .file("src/api.js", """
                        import client from './client';
                        function buildUrl(...parts) { return '/' + parts.join('/'); }
                        export function exportOrders(type) {
                          const url = buildUrl('orders', 'export');
                          return client.get(url, { params: { type } });
                        }
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.ids(NodeKind.HTTP_REQUEST)).isEmpty();
            assertThat(g.targets(fn("src/api.js", "exportOrders"), EdgeKind.REQUESTS)).containsExactly("unresolved:http-url:url");
        }
    }

    @Test
    @DisplayName("F-010-AC16 경로 변수만 매개변수로 받는 API 함수는 요청 래퍼로 보지 않는다(호출 지점에 요청을 만들지 않는다)")
    void idParametersAreNotWrappers() {
        try (TestProject project = TestProject.create()
                .file("src/api.js", """
                        export function fetchOrder(id) { return fetch(`/api/orders/${id}`); }
                        export const remove = (id) => fetch('/api/orders/' + id, { method: 'DELETE' });
                        """)
                .file("src/page.js", """
                        import { fetchOrder, remove } from './api';
                        export function load(orderId) { fetchOrder(orderId); fetchOrder(7); }
                        export function handleDelete(orderId) { remove(orderId); }
                        export function all(rows) { rows.forEach((r) => handleDelete(r.id)); }
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.requests()).containsExactlyInAnyOrder("GET /api/orders/{*}", "DELETE /api/orders/{*}");
            assertThat(g.ids(NodeKind.HTTP_REQUEST)).hasSize(2);
            assertThat(g.edges(fn("src/page.js", "load"), EdgeKind.REQUESTS)).isEmpty();
            assertThat(g.edges(fn("src/page.js", "all"), EdgeKind.REQUESTS)).isEmpty();
            assertThat(g.targets(fn("src/page.js", "load"), EdgeKind.CALLS)).containsExactly(fn("src/api.js", "fetchOrder"));
        }
    }
}
