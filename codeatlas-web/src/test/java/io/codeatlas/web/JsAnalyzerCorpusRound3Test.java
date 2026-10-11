package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static io.codeatlas.web.G.mod;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 수정본을 공개 프로젝트에 다시 돌려 찾은 것(3차): 메서드×URL 곱, showModalDialog, CR뿐인 줄 끝, 클라이언트 라우트,
 * JS 파일 안의 JSP 태그, 플러그인 기본 메서드, 레이아웃(SiteMesh·Tiles)이 싣는 스크립트의 전역.
 */
class JsAnalyzerCorpusRound3Test {

    private static Analyzer producer(Consumer<AnalysisContext> body) {
        return new Analyzer() {
            @Override
            public String name() {
                return "fake-jsp";
            }

            @Override
            public void analyze(AnalysisContext ctx) {
                body.accept(ctx);
            }
        };
    }

    private static String page(AnalysisContext ctx, String jsp) {
        String pageId = Ids.jspPage(jsp);
        if (!ctx.graph().hasNode(pageId)) {
            ctx.graph().declare(Node.of(pageId, NodeKind.JSP_PAGE, jsp));
        }
        return pageId;
    }

    private static void inline(AnalysisContext ctx, String jsp, int line, String text) {
        String pageId = page(ctx, jsp);
        if (!ctx.graph().hasNode(Ids.jsModule(jsp))) {
            ctx.graph().declare(Node.builder(Ids.jsModule(jsp), NodeKind.JS_MODULE).attr("inline", true).build());
            ctx.graph().addEdge(Edge.of(pageId, Ids.jsModule(jsp), EdgeKind.CONTAINS));
        }
        ctx.addEmbedded(EmbeddedSource.js(Ids.jsModule(jsp), text, SourceLocation.of(jsp, line, 1, line, 1), true));
    }

    private static void includes(AnalysisContext ctx, String from, String to, String via, boolean inferred) {
        Edge.Builder edge = Edge.builder(from, to, EdgeKind.INCLUDES).attr("via", via);
        ctx.graph().addEdge(inferred ? edge.inferred().build() : edge.build());
    }

    private static Confidence confidence(G g, String from, String method, String path) {
        return g.edge(from, EdgeKind.REQUESTS, g.request(method, path).id()).confidence();
    }

    @Test
    @DisplayName("F-010-AC39 메서드와 URL이 같은 객체(상태·여러 번 대입한 변수)의 속성이면 대입한 객체 리터럴마다 짝지어 요청을 만들고, 서로 다른 변수의 여러 후보면 곱하지 않고 URL마다 ANY로 둔다")
    void methodAndUrlArePairedPerObjectLiteral() {
        try (TestProject project = TestProject.create()
                .file("src/api/net.js", """
                        const SERVER_URL = '/api';
                        export function requestFetch(url, requestOptions, handler) {
                          return fetch(SERVER_URL + url, requestOptions).then(handler);
                        }
                        """)
                .file("src/pages/Edit.jsx", """
                        import { useState } from 'react';
                        import * as EgovNet from '../api/net';
                        export function Edit(props) {
                          const [modeInfo, setModeInfo] = useState({ mode: props.mode });
                          const [memberInfo, setMemberInfo] = useState({ mode: props.mode });
                          const initMode = () => {
                            switch (props.mode) {
                              case 'C':
                                setModeInfo({ ...modeInfo, modeTitle: "등록", method: "POST", editURL: "/board" });
                                setMemberInfo({ ...memberInfo, editURL: "/members/insert" });
                                break;
                              case 'M':
                                setModeInfo({ ...modeInfo, modeTitle: "수정", method: "PUT", editURL: `/board/${props.id}` });
                                setMemberInfo({ ...memberInfo, editURL: "/members/update" });
                                break;
                              case 'R':
                                setModeInfo({ ...modeInfo, modeTitle: "답글쓰기", method: "POST", editURL: "/boardReply" });
                                break;
                              default:
                                setModeInfo({ ...modeInfo, editURL: "/boardDraft" });
                            }
                          };
                          const updateBoard = () => {
                            const requestOptions = { method: modeInfo.method, body: props.fd };
                            EgovNet.requestFetch(modeInfo.editURL, requestOptions, (resp) => {});
                          };
                          const direct = () => { fetch(modeInfo.editURL, { method: modeInfo.method }); };
                          const updateMember = () => {
                            let modeStr = memberInfo.mode === 'C' ? "POST" : "PUT";
                            const requestOptions = { method: modeStr, body: props.fd };
                            EgovNet.requestFetch(memberInfo.editURL, requestOptions, (resp) => {});
                          };
                          const oneMethod = () => { EgovNet.requestFetch(memberInfo.editURL, { method: 'DELETE' }); };
                          const oneUrl = () => { fetch('/members/one', { method: memberInfo.mode === 'C' ? 'POST' : 'PUT' }); };
                          return <div onClick={updateBoard}>{modeInfo.modeTitle}</div>;
                        }
                        """)
                .file("src/legacy.js", """
                        function save(a) {
                          var cfg = {};
                          if (a) { cfg = { url: '/a/save', type: 'post' }; } else { cfg = { url: '/b/remove', type: 'delete' }; }
                          $.ajax({ url: cfg.url, type: cfg.type });
                        }
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));
            String file = "src/pages/Edit.jsx";

            // 후보 리터럴 하나 = 요청 하나. POST /board/{*}, PUT /board, PUT /boardReply 같은 곱은 만들지 않는다
            assertThat(g.requestsFrom(fn(file, "Edit.updateBoard"))).containsExactlyInAnyOrder("POST /api/board",
                    "PUT /api/board/{*}", "POST /api/boardReply", "ANY /api/boardDraft");
            assertThat(confidence(g, fn(file, "Edit.updateBoard"), "POST", "/api/board")).isEqualTo(Confidence.INFERRED);
            assertThat(confidence(g, fn(file, "Edit.updateBoard"), "PUT", "/api/board/{*}")).isEqualTo(Confidence.INFERRED);
            assertThat(g.request("PUT", "/api/board/{*}").attrString("url")).isEqualTo("modeInfo.editURL");
            assertThat(g.requestsFrom(fn(file, "Edit.direct"))).containsExactlyInAnyOrder("POST /board", "PUT /board/{*}",
                    "POST /boardReply", "ANY /boardDraft");
            // 메서드와 URL이 서로 다른 변수에서 온 여러 후보: 짝을 모르므로 URL마다 ANY 하나
            assertThat(g.requestsFrom(fn(file, "Edit.updateMember"))).containsExactlyInAnyOrder("ANY /api/members/insert",
                    "ANY /api/members/update");
            assertThat(confidence(g, fn(file, "Edit.updateMember"), "ANY", "/api/members/insert")).isEqualTo(Confidence.INFERRED);
            // 메서드 후보가 하나뿐이면 그 메서드, URL 후보가 하나뿐이면 메서드마다(F-010-R20 그대로)
            assertThat(g.requestsFrom(fn(file, "Edit.oneMethod"))).containsExactlyInAnyOrder("DELETE /api/members/insert",
                    "DELETE /api/members/update");
            assertThat(g.requestsFrom(fn(file, "Edit.oneUrl"))).containsExactlyInAnyOrder("POST /members/one", "PUT /members/one");
            // 여러 번 대입한 객체 변수도 같다
            assertThat(g.requestsFrom(fn("src/legacy.js", "save"))).containsExactlyInAnyOrder("POST /a/save", "DELETE /b/remove");
        }
    }

    @Test
    @DisplayName("F-010-AC40 window.showModalDialog(url, …)·showModelessDialog의 첫 인자는 화면 이동 요청(api=navigation, GET)이고, 페이지가 같은 이름의 프로젝트 함수를 싣고 있으면 그 함수 호출로 해석한다")
    void showModalDialogIsNavigation() {
        String plain = "web/plain.jsp";
        String poly = "web/poly.jsp";
        Analyzer fake = producer(ctx -> {
            inline(ctx, plain, 10, """
                    function fn_pop() {
                      var ret = window.showModalDialog("/pop/list.do", self, "dialogWidth=800px;dialogHeight=600px");
                    }
                    function fn_detail(id) { showModelessDialog('__JSP_EXPR__/pop/detail.do?id=' + id, window, 'resizable:yes'); }
                    function fn_zip() { var url = '/pop/zip.do'; var r = showModalDialog(url, null, 'x'); }
                    function fn_dyn(target) { window.showModalDialog(target.value, self); }
                    """);
            inline(ctx, poly, 5, "function fn_open() { window.showModalDialog('/pop/b.do', self, 'x', 'cb'); }\n");
            includes(ctx, page(ctx, poly), Ids.jsModule("js/showModalDialog.js"), "script", false);
        });
        try (TestProject project = TestProject.create().file(plain, "<html></html>").file(poly, "<html></html>")
                .file("js/showModalDialog.js", """
                        if (!window.showModalDialog) {
                          window.showModalDialog = function(arg1, arg2, arg3, callback) {
                            var w = window.open(arg1, 'modal', arg3);
                            return w;
                          };
                        }
                        """)) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            assertThat(g.requestsFrom(fn(plain, "fn_pop"))).containsExactly("GET /pop/list.do");
            Node list = g.request("GET", "/pop/list.do");
            assertThat(list.attrString("api")).isEqualTo("navigation");
            assertThat(list.id()).isEqualTo("http:request:GET /pop/list.do@" + plain + ":11");
            assertThat(confidence(g, fn(plain, "fn_pop"), "GET", "/pop/list.do")).isEqualTo(Confidence.RESOLVED);
            assertThat(g.requestsFrom(fn(plain, "fn_detail"))).containsExactly("GET /pop/detail.do");
            assertThat(g.request("GET", "/pop/detail.do").attrString("query")).isEqualTo("id={*}");
            assertThat(g.requestsFrom(fn(plain, "fn_zip"))).containsExactly("GET /pop/zip.do");
            assertThat(g.targets(fn(plain, "fn_dyn"), EdgeKind.REQUESTS)).containsExactly("unresolved:http-url:target.value");
            // 이 페이지는 대체 구현을 싣지 않는다: 그 함수로 가는 호출은 없다
            assertThat(g.targets(fn(plain, "fn_pop"), EdgeKind.CALLS)).isEmpty();
            // 대체 구현(같은 이름의 프로젝트 함수)을 싣는 페이지: 그 함수 호출이고, 요청은 래퍼 전파로 생긴다
            String polyfill = fn("js/showModalDialog.js", "showModalDialog");
            assertThat(g.targets(fn(poly, "fn_open"), EdgeKind.CALLS)).containsExactly(polyfill);
            assertThat(g.requestsFrom(fn(poly, "fn_open"))).containsExactly("GET /pop/b.do");
            assertThat(g.request("GET", "/pop/b.do").attrString("via")).isEqualTo(polyfill);
        }
    }

    @Test
    @DisplayName("F-010-AC41 줄 끝이 CR뿐인 스크립트도 줄 단위로 읽는다: 구문 오류가 없고 위치의 줄 번호가 CR마다 늘며 압축 파일로 보지 않는다")
    void carriageReturnOnlyLineEnds() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            big.append("function f").append(i).append("(a) { return a + ").append(i).append("; }\r");
        }
        try (TestProject project = TestProject.create()
                .file("js/cr.js", "function a(form) {\r\tvar x = g(form);\r\tlocation.href = '/cr/list.do';\r}\r"
                        + "function g(f) {\r\treturn f;\r}\r")
                .file("js/asi.js", "a = g(form) \r\tb = h(a);")
                .file("js/mixed.js", "function m1() {}\r\nfunction m2() {}\rfunction m3() {}\nfunction m4() { m1(); }\n")
                .file("js/big.js", big.toString())) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.diagnostics("PARSE_FAILED")).isEmpty();
            assertThat(g.node(fn("js/cr.js", "a")).location().startLine()).isEqualTo(1);
            assertThat(g.node(fn("js/cr.js", "g")).location().startLine()).isEqualTo(5);
            assertThat(g.node(fn("js/cr.js", "g")).location().startColumn()).isEqualTo(1);
            assertThat(g.request("GET", "/cr/list.do").id()).isEqualTo("http:request:GET /cr/list.do@js/cr.js:3");
            Edge call = g.edge(fn("js/cr.js", "a"), EdgeKind.CALLS, fn("js/cr.js", "g"));
            assertThat(call.location().startLine()).isEqualTo(2);
            assertThat(call.location().startColumn()).isEqualTo(10);
            // CRLF는 줄 하나, CR 단독과 LF 단독도 줄 하나
            assertThat(g.node(fn("js/mixed.js", "m2")).location().startLine()).isEqualTo(2);
            assertThat(g.node(fn("js/mixed.js", "m3")).location().startLine()).isEqualTo(3);
            assertThat(g.node(fn("js/mixed.js", "m4")).location().startLine()).isEqualTo(4);
            // 20KB를 넘는 CR 파일을 "한 줄이 매우 긴 압축 파일"로 보지 않는다
            assertThat(g.node(mod("js/big.js")).attr("skipped")).isNull();
            assertThat(g.node(fn("js/big.js", "f1199")).location().startLine()).isEqualTo(1200);
        }
    }

    @Test
    @DisplayName("F-010-AC42 라우터를 import한 모듈의 navigate·history.push·router.push와 Link·NavLink·Navigate·Redirect의 to는 api=route, clientRoute=true인 요청이고, 선언된 클라이언트 라우트로 가는 location 이동도 clientRoute=true다")
    void clientRoutes() {
        try (TestProject project = TestProject.create()
                .file("src/constants/url.js", """
                        const URL = { MAIN: '/', LOGIN: '/login', ERROR: '/error', NOTICE: '/inform/notice' };
                        export default URL;
                        """)
                .file("src/routes/index.jsx", """
                        import { Routes, Route } from 'react-router-dom';
                        import URL from '../constants/url';
                        import { Page } from '../pages/Page';
                        export default function AppRoutes() {
                          return (
                            <Routes>
                              <Route path={URL.LOGIN} element={<Page />} />
                              <Route path="/inform/notice/:id" element={<Page />} />
                            </Routes>
                          );
                        }
                        """)
                .file("src/pages/Page.jsx", """
                        import { Link, NavLink, Navigate, useNavigate } from 'react-router-dom';
                        import URL from '../constants/url';
                        export function Page(props) {
                          const navigate = useNavigate();
                          const go = () => { navigate(URL.LOGIN); };
                          const err = () => { navigate({ pathname: URL.ERROR }, { state: { msg: '' } }); };
                          const back = () => { navigate(-1); };
                          const detail = (id) => { navigate(`/inform/notice/${id}`, { replace: true }); };
                          const hard = () => { window.location.href = URL.LOGIN; };
                          const hardDetail = (id) => { location.assign('/inform/notice/' + id); };
                          const server = () => { window.location.href = '/server/logout.do'; };
                          const api = () => { fetch('/api/notice'); };
                          const list = () => { const items = []; items.push('/not/a/route'); };
                          return (
                            <div>
                              <Link to={URL.MAIN}>home</Link>
                              <NavLink to="/about" className="x">about</NavLink>
                              <Link to={{ pathname: URL.NOTICE }} state={{ a: 1 }}>notice</Link>
                              {props.guest && <Navigate to={URL.LOGIN} replace />}
                              <a href="/plain/anchor">a</a>
                            </div>
                          );
                        }
                        """)
                .file("src/legacy/Old.jsx", """
                        import React from 'react';
                        import { Redirect, useHistory } from 'react-router-dom';
                        export class Old extends React.Component {
                          go() { this.props.history.push('/old/list'); }
                          render() { return <Redirect to="/old/login" />; }
                        }
                        export function Hook() {
                          const history = useHistory();
                          const home = () => history.replace('/old/home');
                          return <b onClick={home}>x</b>;
                        }
                        """)
                .file("src/next/N.jsx", """
                        import { useRouter } from 'next/router';
                        import Link from 'next/link';
                        export function N() {
                          const router = useRouter();
                          const f = () => router.push('/next/page');
                          return <Link href="/next/about">a</Link>;
                        }
                        """)
                .file("src/vue/guard.js", """
                        import { useRouter } from 'vue-router';
                        export function useGuard() {
                          const router = useRouter();
                          const toHome = () => router.push({ path: '/vue/home' });
                          const named = () => router.push({ name: 'user' });
                          return { toHome, named };
                        }
                        """)
                .file("src/plain/noRouter.js", """
                        export function run(navigate, history) {
                          navigate('/no/router');
                          history.push('/no/router/push');
                        }
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));
            String page = "src/pages/Page.jsx";

            assertThat(g.requestsFrom(fn(page, "Page.go"))).containsExactly("GET /login");
            Node login = g.graph().node("http:request:GET /login@" + page + ":5").orElseThrow();
            assertThat(login.attrString("api")).isEqualTo("route");
            assertThat(login.attr("clientRoute")).isEqualTo(true);
            assertThat(login.attrString("url")).isEqualTo("URL.LOGIN");
            assertThat(g.edge(fn(page, "Page.go"), EdgeKind.REQUESTS, login.id()).confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(g.requestsFrom(fn(page, "Page.err"))).containsExactly("GET /error");
            assertThat(g.edges(fn(page, "Page.back"), EdgeKind.REQUESTS)).isEmpty();
            assertThat(g.requestsFrom(fn(page, "Page.detail"))).containsExactly("GET /inform/notice/{*}");
            assertThat(g.graph().node("http:request:GET /inform/notice/{*}@" + page + ":8").orElseThrow().attr("clientRoute"))
                    .isEqualTo(true);
            // location 이동: 요청은 종전대로 api=navigation이고, 경로가 선언된 라우트(<Route path>)면 clientRoute=true
            Node hard = g.graph().node("http:request:GET /login@" + page + ":9").orElseThrow();
            assertThat(hard.attrString("api")).isEqualTo("navigation");
            assertThat(hard.attr("clientRoute")).isEqualTo(true);
            assertThat(g.graph().node("http:request:GET /inform/notice/{*}@" + page + ":10").orElseThrow().attr("clientRoute"))
                    .isEqualTo(true);
            Node server = g.request("GET", "/server/logout.do");
            assertThat(server.attrString("api")).isEqualTo("navigation");
            assertThat(server.attr("clientRoute")).isNull();
            assertThat(g.request("GET", "/api/notice").attr("clientRoute")).isNull();
            assertThat(g.edges(fn(page, "Page.list"), EdgeKind.REQUESTS)).isEmpty();
            // JSX: Link·NavLink·Navigate의 to (컴포넌트 함수에서 나간다).
            // 개정(4차, F-010-R42): HTML 요소 <a href>도 서버 경로 꼴이면 api=link 요청이다(선언된 라우트가 아니면 clientRoute가 없다)
            assertThat(g.requestsFrom(fn(page, "Page"))).containsExactlyInAnyOrder("GET /", "GET /about", "GET /inform/notice",
                    "GET /login", "GET /plain/anchor");
            assertThat(g.request("GET", "/plain/anchor").attrString("api")).isEqualTo("link");
            assertThat(g.request("GET", "/plain/anchor").attr("clientRoute")).isNull();
            for (Edge e : g.edges(fn(page, "Page"), EdgeKind.REQUESTS)) {
                Node n = g.node(e.to());
                if (n.attrString("api").equals("link")) {
                    continue;
                }
                assertThat(n.attrString("api")).as(n.id()).isEqualTo("route");
                assertThat(n.attr("clientRoute")).as(n.id()).isEqualTo(true);
            }
            assertThat(g.request("GET", "/about").id()).isEqualTo("http:request:GET /about@" + page + ":17");
            // react-router v5, next, vue-router
            assertThat(g.requestsFrom(fn("src/legacy/Old.jsx", "Old.go"))).containsExactly("GET /old/list");
            assertThat(g.requestsFrom(fn("src/legacy/Old.jsx", "Old.render"))).containsExactly("GET /old/login");
            assertThat(g.requestsFrom(fn("src/legacy/Old.jsx", "Hook.home"))).containsExactly("GET /old/home");
            assertThat(g.requestsFrom(fn("src/next/N.jsx", "N.f"))).containsExactly("GET /next/page");
            assertThat(g.requestsFrom(fn("src/next/N.jsx", "N"))).containsExactly("GET /next/about");
            assertThat(g.requestsFrom(fn("src/vue/guard.js", "useGuard.toHome"))).containsExactly("GET /vue/home");
            assertThat(g.edges(fn("src/vue/guard.js", "useGuard.named"), EdgeKind.REQUESTS)).isEmpty();
            for (String path : List.of("/old/list", "/old/login", "/old/home", "/next/page", "/next/about", "/vue/home")) {
                assertThat(g.request("GET", path).attrString("api")).as(path).isEqualTo("route");
                assertThat(g.request("GET", path).attr("clientRoute")).as(path).isEqualTo(true);
            }
            // 라우터를 import하지 않은 모듈의 navigate(…)·history.push(…)는 종전대로 요청이 아니다
            assertThat(g.edges(fn("src/plain/noRouter.js", "run"), EdgeKind.REQUESTS)).isEmpty();
            // <Route path> 선언 자체는 요청이 아니다
            assertThat(g.edges(fn("src/routes/index.jsx", "AppRoutes"), EdgeKind.REQUESTS)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC43 JS 파일의 URL 문자열에 JSP 태그·식이 그대로 적혀 있으면(<c:url value='X'/>, ${ctx}X, <%=…%>X) JSP 인라인과 같은 규칙으로 정규화한다(path=X, url=원문)")
    void jspMarkupInScriptFile() {
        try (TestProject project = TestProject.create().file("js/treemenu.js", """
                function fn_save() {
                  document.menuListForm.action = "<c:url value='/sym/mnu/mpm/EgovMenuListUpdt.do'/>";
                  document.menuListForm.submit();
                }
                function fn_list(id) { location.href = "${pageContext.request.contextPath}/menu/list.do?id=" + id; }
                function fn_expr() { $.post('<%=request.getContextPath()%>/menu/save.do'); }
                function fn_el() { $.get('${ctx}/menu/${menuId}/detail.do'); }
                function fn_spring() { $.get("<spring:url value=\\"/menu/tree.do\\" />"); }
                function fn_tpl(id) { fetch(`/tpl/${id}/view`); }
                """)) {
            G g = new G(project.run(new JsAnalyzer()));
            String file = "js/treemenu.js";

            assertThat(g.requestsFrom(fn(file, "fn_save"))).containsExactly("ANY /sym/mnu/mpm/EgovMenuListUpdt.do");
            Node save = g.request("ANY", "/sym/mnu/mpm/EgovMenuListUpdt.do");
            assertThat(save.attrString("url")).isEqualTo("\"<c:url value='/sym/mnu/mpm/EgovMenuListUpdt.do'/>\"");
            assertThat(save.id()).isEqualTo("http:request:ANY /sym/mnu/mpm/EgovMenuListUpdt.do@" + file + ":2");
            assertThat(save.attr("relative")).isNull();
            assertThat(g.requestsFrom(fn(file, "fn_list"))).containsExactly("GET /menu/list.do");
            assertThat(g.request("GET", "/menu/list.do").attrString("query")).isEqualTo("id={*}");
            assertThat(g.requestsFrom(fn(file, "fn_expr"))).containsExactly("POST /menu/save.do");
            assertThat(g.requestsFrom(fn(file, "fn_el"))).containsExactly("GET /menu/{*}/detail.do");
            assertThat(g.requestsFrom(fn(file, "fn_spring"))).containsExactly("GET /menu/tree.do");
            assertThat(g.requestsFrom(fn(file, "fn_tpl"))).containsExactly("GET /tpl/{*}/view");
            for (Node n : g.graph().nodes(NodeKind.HTTP_REQUEST)) {
                assertThat(n.id()).doesNotContain("<c:url").doesNotContain("<%=").doesNotContain("${");
            }
        }
    }

    @Test
    @DisplayName("F-010-AC44 옵션에 메서드가 없는 api=option 요청은 알려진 플러그인의 기본 메서드를 쓴다(bootstrapTable·DataTables·jqGrid GET, zTree async·easyui datagrid류 POST). 모르는 플러그인은 ANY다")
    void pluginDefaultMethods() {
        String jsp = "web/plugins.jsp";
        Analyzer fake = producer(ctx -> inline(ctx, jsp, 1, """
                function bt() { $('#t').bootstrapTable({ url: '/bt/list', striped: true }); }
                function bt2() { $('#t').bootstrapTable({ url: '/bt/save', method: 'post' }); }
                function dt() { $('#d').DataTable({ ajax: '/dt/rows' }); $('#d2').dataTable({ ajax: { url: '/dt/rows2' } }); }
                function zt() { $.fn.zTree.init($('#z'), { async: { enable: true, url: '/zt/nodes' } }); }
                function zt2() { var setting = { async: { enable: true, url: '/zt/get', type: 'get' } }; $.fn.zTree.init($('#z'), setting); }
                function eg() { $('#g').datagrid({ url: '/eg/data' }); $('#tg').treegrid({ url: '/eg/tree' }); }
                function jq() { $('#j').jqGrid({ url: '/jq/data', datatype: 'json' }); }
                function un() { $('#u').somePlugin({ url: '/un/data' }); init({ async: { url: '/un/async' } }); }
                """));
        try (TestProject project = TestProject.create().file(jsp, "<html></html>")) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            assertThat(g.requestsFrom(fn(jsp, "bt"))).containsExactly("GET /bt/list");
            assertThat(g.requestsFrom(fn(jsp, "bt2"))).containsExactly("POST /bt/save");
            assertThat(g.requestsFrom(fn(jsp, "dt"))).containsExactlyInAnyOrder("GET /dt/rows", "GET /dt/rows2");
            assertThat(g.requestsFrom(fn(jsp, "zt"))).containsExactly("POST /zt/nodes");
            assertThat(g.requestsFrom(fn(jsp, "zt2"))).containsExactly("GET /zt/get");
            assertThat(g.requestsFrom(fn(jsp, "eg"))).containsExactlyInAnyOrder("POST /eg/data", "POST /eg/tree");
            assertThat(g.requestsFrom(fn(jsp, "jq"))).containsExactly("GET /jq/data");
            assertThat(g.requestsFrom(fn(jsp, "un"))).containsExactlyInAnyOrder("ANY /un/data", "ANY /un/async");
            for (Node n : g.graph().nodes(NodeKind.HTTP_REQUEST)) {
                assertThat(n.attrString("api")).isEqualTo("option");
                for (Edge e : g.graph().incoming(n.id(), EdgeKind.REQUESTS)) {
                    assertThat(e.confidence()).as(n.id()).isEqualTo(Confidence.INFERRED);
                }
            }
        }
    }

    @Test
    @DisplayName("F-010-AC45 SiteMesh 데코레이터(페이지 → 데코레이터 INCLUDES, via=sitemesh)가 싣는 스크립트의 전역을 내용 페이지의 스크립트가 부르면 INFERRED + resolution=global로 잇는다")
    void decoratorScriptsAreGlobalForContentPages() {
        String list = "views/sys/userList.jsp";
        String form = "views/sys/userForm.jsp";
        String bare = "views/sys/bare.jsp";
        String decorator = "views/layouts/default.jsp";
        Analyzer fake = producer(ctx -> {
            String layout = page(ctx, decorator);
            includes(ctx, layout, page(ctx, "views/include/head.jsp"), "directive", false);
            includes(ctx, Ids.jspPage("views/include/head.jsp"), Ids.jsModule("static/common/jeesite.js"), "script", false);
            inline(ctx, decorator, 12, "function layoutInit() { loading('x'); }\n");
            includes(ctx, page(ctx, list), layout, "sitemesh", true);
            includes(ctx, page(ctx, form), layout, "sitemesh", true);
            includes(ctx, Ids.jspPage(form), Ids.jsModule("static/sys/form.js"), "script", false);
            inline(ctx, list, 8, "function page(n) { loading('조회 중'); return confirmx('삭제?', '/sys/user/delete'); }\n");
            inline(ctx, form, 8, "function save() { loading('저장 중'); validate(); }\n");
            inline(ctx, bare, 8, "function alone() { loading('x'); }\n");
        });
        try (TestProject project = TestProject.create()
                .file(list, "<html></html>").file(form, "<html></html>").file(bare, "<html></html>")
                .file(decorator, "<html></html>").file("views/include/head.jsp", "<html></html>")
                .file("static/common/jeesite.js", """
                        function loading(mess) { top.$.jBox.tip(mess, 'loading'); }
                        function confirmx(mess, href) { location = href; return false; }
                        """)
                .file("static/sys/form.js", "function validate() { loading('검증'); pageOnly(); }\n")) {
            G g = new G(project.run(fake, new JsAnalyzer()));
            String loading = fn("static/common/jeesite.js", "loading");

            assertThat(g.targets(fn(list, "page"), EdgeKind.CALLS)).containsExactlyInAnyOrder(loading,
                    fn("static/common/jeesite.js", "confirmx"));
            for (Edge e : g.edges(fn(list, "page"), EdgeKind.CALLS)) {
                assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
                assertThat(e.attrString("resolution")).isEqualTo("global");
                assertThat(e.attrString("viaPage")).isEqualTo(Ids.jspPage(list));
            }
            // 페이지가 직접 싣는 스크립트는 종전대로 RESOLVED, 데코레이터가 싣는 것은 추론
            assertThat(g.edge(fn(form, "save"), EdgeKind.CALLS, fn("static/sys/form.js", "validate")).confidence())
                    .isEqualTo(Confidence.RESOLVED);
            assertThat(g.edge(fn(form, "save"), EdgeKind.CALLS, loading).confidence()).isEqualTo(Confidence.INFERRED);
            // 페이지가 싣는 JS 파일 → 데코레이터가 싣는 JS 파일
            Edge fromFile = g.edge(fn("static/sys/form.js", "validate"), EdgeKind.CALLS, loading);
            assertThat(fromFile.attrString("viaPage")).isEqualTo(Ids.jspPage(form));
            // 데코레이터 자신의 스크립트는 자기가 싣는 것이라 종전 규칙대로다(include한 조각이 싣는다)
            assertThat(g.edge(fn(decorator, "layoutInit"), EdgeKind.CALLS, loading).confidence()).isEqualTo(Confidence.RESOLVED);
            // 데코레이터를 쓰지 않는 페이지: 함께 싣는 근거가 없다(정의가 하나뿐이라 loadUnverified로만 이어진다, F-010-R34)
            Edge bareCall = g.edge(fn(bare, "alone"), EdgeKind.CALLS, loading);
            assertThat(bareCall.attr("loadUnverified")).isEqualTo(true);
            assertThat(bareCall.attr("resolution")).isNull();
            assertThat(g.edge(fn(list, "page"), EdgeKind.CALLS, loading).attr("loadUnverified")).isNull();
            // 서로 다른 내용 페이지끼리는 잇지 않는다
            assertThat(g.targets(fn(list, "page"), EdgeKind.CALLS)).doesNotContain(fn(form, "save"));
        }
    }

    @Test
    @DisplayName("F-010-AC46 Tiles 정의(FILE attrs tilesDefinitions·tilesAttributes)의 template이 싣는 스크립트의 전역을 그 정의로 그려지는 본문 JSP가 부르면 INFERRED + viaPage=template으로 잇고, 서로 다른 정의의 본문끼리는 잇지 않는다")
    void tilesTemplateScriptsAreGlobalForBodies() {
        String root = "src/main/webapp/";
        String template = root + "WEB-INF/layout/main.jsp";
        String header = root + "WEB-INF/layout/header.jsp";
        String orderList = root + "WEB-INF/views/order/list.jsp";
        String memberEdit = root + "WEB-INF/views/member/edit.jsp";
        String outside = root + "WEB-INF/other/standalone.jsp";
        String config = root + "WEB-INF/tiles/tiles.xml";
        Analyzer fake = producer(ctx -> {
            String layout = page(ctx, template);
            includes(ctx, layout, Ids.jsModule(root + "js/common.js"), "script", false);
            includes(ctx, layout, page(ctx, header), "tiles", true);
            includes(ctx, layout, page(ctx, orderList), "tiles", true);
            inline(ctx, header, 3, "function openMenu() { commonFn(); }\n");
            inline(ctx, orderList, 5, "function search() { commonFn(); memberOnly(); openMenu(); }\n");
            inline(ctx, memberEdit, 5, "function memberOnly() { commonFn(); }\n");
            inline(ctx, outside, 5, "function standalone() { commonFn(); }\n");
            ctx.graph().putAttrs(Ids.file(config), Map.of(
                    "tilesDefinitions", List.of("base=/WEB-INF/layout/main.jsp", "order.list=/WEB-INF/layout/main.jsp",
                            "*/*=/WEB-INF/layout/main.jsp", "broken="),
                    "tilesAttributes", List.of("base=/WEB-INF/layout/header.jsp", "order.list=/WEB-INF/layout/header.jsp",
                            "order.list=/WEB-INF/views/order/list.jsp", "*/*=/WEB-INF/layout/header.jsp",
                            "*/*=/WEB-INF/views/{1}/{2}.jsp", "broken=/WEB-INF/views/none.jsp")));
        });
        try (TestProject project = TestProject.create()
                .file(config, "<tiles-definitions></tiles-definitions>")
                .file(template, "<html></html>").file(header, "<html></html>").file(orderList, "<html></html>")
                .file(memberEdit, "<html></html>").file(outside, "<html></html>")
                .file(root + "js/common.js", "function commonFn() {}\n")) {
            G g = new G(project.run(fake, new JsAnalyzer()));
            String commonFn = fn(root + "js/common.js", "commonFn");

            // 정적 정의의 본문: template이 싣는 스크립트와 같은 정의의 다른 조각(header)의 전역
            assertThat(g.targets(fn(orderList, "search"), EdgeKind.CALLS)).containsExactlyInAnyOrder(commonFn, fn(header, "openMenu"));
            Edge call = g.edge(fn(orderList, "search"), EdgeKind.CALLS, commonFn);
            assertThat(call.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(call.attrString("resolution")).isEqualTo("global");
            assertThat(call.attrString("viaPage")).isEqualTo(Ids.jspPage(template));
            // 와일드카드 정의(*/* → /WEB-INF/views/{1}/{2}.jsp)에 맞는 JSP도 같다
            Edge wildcard = g.edge(fn(memberEdit, "memberOnly"), EdgeKind.CALLS, commonFn);
            assertThat(wildcard.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(wildcard.attrString("viaPage")).isEqualTo(Ids.jspPage(template));
            assertThat(g.edge(fn(header, "openMenu"), EdgeKind.CALLS, commonFn).confidence()).isEqualTo(Confidence.INFERRED);
            // 서로 다른 정의의 본문끼리는 함께 실리지 않는다(order/list.jsp → member/edit.jsp의 memberOnly 없음)
            assertThat(g.targets(fn(orderList, "search"), EdgeKind.CALLS)).doesNotContain(fn(memberEdit, "memberOnly"));
            // 어느 정의의 패턴에도 맞지 않는 JSP: 함께 싣는 근거가 없다(정의가 하나뿐이라 loadUnverified로만 이어진다)
            Edge unverified = g.edge(fn(outside, "standalone"), EdgeKind.CALLS, commonFn);
            assertThat(unverified.attr("loadUnverified")).isEqualTo(true);
            assertThat(unverified.attr("viaPage")).isNull();
            assertThat(wildcard.attr("loadUnverified")).isNull();
        }
    }

    @Test
    @DisplayName("F-010-AC47 분기마다 호출 지점이 따로 있으면 그 가지에서 도달할 수 있는 대입만 후보다: 다른 if/else 가지의 대입은 빼고, 조건이 x === '리터럴'이면 x의 후보를 그 값으로 좁힌다")
    void candidatesAreNarrowedByBranch() {
        try (TestProject project = TestProject.create()
                .file("src/api/net.js", """
                        const SERVER_URL = '/api';
                        export function requestFetch(url, requestOptions, handler) {
                          return fetch(SERVER_URL + url, requestOptions).then(handler);
                        }
                        """)
                .file("src/pages/Admin.jsx", """
                        import { useState } from 'react';
                        import * as EgovNet from '../api/net';
                        export function Admin(props) {
                          const [modeInfo, setModeInfo] = useState({ mode: props.mode });
                          const init = () => {
                            if (props.mode === 'C') { setModeInfo({ ...modeInfo, editURL: "/bbsMaster" }); }
                            else { setModeInfo({ ...modeInfo, editURL: `/bbsMaster/${props.id}` }); }
                          };
                          const updateBoard = () => {
                            let modeStr = modeInfo.mode === 'C' ? "POST" : "PUT";
                            let requestOptions = {};
                            if (modeStr === "POST") {
                              props.validate().then((ok) => {
                                if (ok) {
                                  requestOptions = { method: modeStr, headers: {}, body: props.fd };
                                  EgovNet.requestFetch(modeInfo.editURL, requestOptions, (resp) => {});
                                }
                              });
                            } else {
                              requestOptions = { method: modeStr, headers: { "Content-type": "application/json" } };
                              EgovNet.requestFetch(modeInfo.editURL, requestOptions, (resp) => {});
                            }
                          };
                          return <div onClick={updateBoard}>x</div>;
                        }
                        """)
                .file("src/legacy.js", """
                        function save2(kind) {
                          var url = "";
                          var method = "";
                          if (kind === 'a') {
                            url = '/a/create'; method = 'POST';
                            fetch(url, { method: method });
                          } else {
                            url = '/a/update'; method = 'PUT';
                            fetch(url, { method: method });
                          }
                        }
                        function save3(m) {
                          var method = m ? 'POST' : 'PUT';
                          var url = m ? '/c/a' : '/c/b';
                          fetch(url, { method: method });
                        }
                        function save4(type) {
                          var verb = type === 'x' ? 'DELETE' : 'PATCH';
                          if (verb !== 'DELETE') { fetch('/d/patch', { method: verb }); }
                          if ('DELETE' == verb && type) { fetch('/d/delete', { method: verb }); }
                        }
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));
            String file = "src/pages/Admin.jsx";

            // POST 가지(16행)에는 POST만, PUT 가지(21행)에는 PUT만. URL 후보는 좁힐 근거가 없어 둘 다 남는다
            assertThat(g.ids(NodeKind.HTTP_REQUEST).stream().filter(id -> id.contains("@" + file + ":")))
                    .containsExactlyInAnyOrder(
                            "http:request:POST /api/bbsMaster@" + file + ":16", "http:request:POST /api/bbsMaster/{*}@" + file + ":16",
                            "http:request:PUT /api/bbsMaster@" + file + ":21", "http:request:PUT /api/bbsMaster/{*}@" + file + ":21");
            assertThat(g.ids(NodeKind.HTTP_REQUEST).stream().filter(id -> id.contains("@src/legacy.js:")))
                    .containsExactlyInAnyOrder(
                            "http:request:POST /a/create@src/legacy.js:6", "http:request:PUT /a/update@src/legacy.js:9",
                            // 가지로 가를 수 없는 독립 후보: 곱하지 않고 URL마다 ANY
                            "http:request:ANY /c/a@src/legacy.js:15", "http:request:ANY /c/b@src/legacy.js:15",
                            "http:request:PATCH /d/patch@src/legacy.js:19", "http:request:DELETE /d/delete@src/legacy.js:20");
        }
    }

    @Test
    @DisplayName("F-010-AC48 함께 싣는 근거를 못 찾은 전역 호출은 그 이름의 최상위 함수 정의가 프로젝트(벤더 제외)에 정확히 하나이고 JS 파일에 있을 때만 INFERRED + loadUnverified=true로 잇고, 정의가 여럿이면 잇지 않고 CALLS_SKIPPED에 센다")
    void uniqueGlobalWithoutLoadEvidence() {
        String jsp = "web/alone.jsp";
        Analyzer fake = producer(ctx -> {
            inline(ctx, jsp, 3, """
                    function run() {
                      onlyHere('a');
                      twice();
                      inlineElsewhere();
                      alert('x'); nowhere();
                      App.start();
                    }
                    """);
            inline(ctx, "web/other.jsp", 3, "function inlineElsewhere() {}\n");
        });
        try (TestProject project = TestProject.create().file(jsp, "<html></html>").file("web/other.jsp", "<html></html>")
                .file("js/util.js", "function onlyHere(x) { return x; }\nfunction twice() {}\nvar App = { start: function () {} };\n")
                .file("js/dup.js", "function twice() {}\nfunction caller() { onlyHere(1); }\n")
                .file("js/lib/jquery.plugin.js", "function onlyVendor() {}\n")
                .file("src/esm.js", "export function esm() { onlyHere(2); }\n")) {
            G g = new G(project.run(fake, new JsAnalyzer()));
            String onlyHere = fn("js/util.js", "onlyHere");

            assertThat(g.targets(fn(jsp, "run"), EdgeKind.CALLS)).containsExactlyInAnyOrder(onlyHere, fn("js/util.js", "App.start"));
            Edge edge = g.edge(fn(jsp, "run"), EdgeKind.CALLS, onlyHere);
            assertThat(edge.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(edge.attr("loadUnverified")).isEqualTo(true);
            assertThat(edge.attr("viaPage")).isNull();
            assertThat(edge.attrString("callType")).isEqualTo("call");
            // JS 파일끼리도 같다. ES 모듈의 전역 호출은 잇지 않는다
            assertThat(g.edge(fn("js/dup.js", "caller"), EdgeKind.CALLS, onlyHere).attr("loadUnverified")).isEqualTo(true);
            assertThat(g.targets(fn("src/esm.js", "esm"), EdgeKind.CALLS)).isEmpty();
            // 정의가 여럿인 이름은 건수만 남긴다
            assertThat(g.diagnostics("CALLS_SKIPPED")).anySatisfy(d -> {
                assertThat(d.location().file()).isEqualTo(jsp);
                assertThat(d.message()).contains("수신자를 알 수 없는 호출 1건").contains("정의가 여럿인 전역 이름 1건");
            });
        }
    }

    @Test
    @DisplayName("F-010-AC49 페이지가 압축본(x.min.js)을 싣고 같은 폴더에 분석한 x.js가 있으면 x.js를 그 페이지가 싣는 것으로 본다(INCLUDES INFERRED, minOf)")
    void minifiedScriptSibling() {
        String jsp = "web/app.jsp";
        String min = "function appInit(){return 1}" + ";var a=function(b){return b+1}".repeat(60) + ";";
        Analyzer fake = producer(ctx -> {
            String pageId = page(ctx, jsp);
            includes(ctx, pageId, Ids.jsModule("js/app.min.js"), "script", false);
            includes(ctx, pageId, Ids.jsModule("js/solo.min.js"), "script", false);
            inline(ctx, jsp, 3, "function start() { appInit(); soloInit(); }\n");
        });
        try (TestProject project = TestProject.create().file(jsp, "<html></html>")
                .file("js/app.min.js", min).file("js/app.js", "function appInit() {\n  return 1;\n}\n")
                .file("js/solo.min.js", min.replace("appInit", "soloInit"))) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            assertThat(g.node(mod("js/app.min.js")).attrString("skipped")).isEqualTo("minified");
            assertThat(g.targets(fn(jsp, "start"), EdgeKind.CALLS)).containsExactly(fn("js/app.js", "appInit"));
            Edge include = g.edge(Ids.jspPage(jsp), EdgeKind.INCLUDES, mod("js/app.js"));
            assertThat(include.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(include.attrString("minOf")).isEqualTo("js/app.min.js");
            assertThat(include.attrString("via")).isEqualTo("script");
        }
    }

    @Test
    @DisplayName("F-010-AC50 location 자체에 대입하는 화면 이동(location = url, top.location = url 등)과 iframe:·url:·get:·post: 접두 문자열 인자, layer의 type 2 content를 요청으로 읽는다")
    void locationAssignmentAndPrefixedUrls() {
        String jsp = "web/views/list.jsp";
        Analyzer fake = producer(ctx -> inline(ctx, jsp, 2, """
                function go() { location = '__JSP_EXPR__/x/list'; }
                function go2() {
                  top.location = '/x/top'; parent.location = '/x/parent'; document.location = '/x/doc';
                  window.location = '/x/win'; self.location = '/x/self';
                }
                function pop() { top.$.jBox.open("iframe:__JSP_EXPR__/x/pop?id=1", "t", 800, 500); }
                function pop2(id) { $.jBox("iframe:/x/pop2/" + id); $.jBox('get:/x/get'); $.jBox('post:/x/post'); }
                function lay() { layer.open({ type: 2, title: 't', content: '/x/layer' }); layer.open({ type: 1, content: '/x/html' }); }
                function none(x) { alert("iframe: 쓰는 법"); foo("url:" + x); bar('get:ok'); $.jBox('html:<b>/x/b</b>'); }
                function reload() { location = location.href; window.location.href = window.location.href; top.location = location; }
                function session(sid) { top.$.jBox('get:/x/menu;JSESSIONID=' + sid); }
                """));
        try (TestProject project = TestProject.create().file(jsp, "<html></html>")) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            assertThat(g.requestsFrom(fn(jsp, "go"))).containsExactly("GET /x/list");
            assertThat(g.request("GET", "/x/list").attrString("api")).isEqualTo("navigation");
            assertThat(g.requestsFrom(fn(jsp, "go2"))).containsExactlyInAnyOrder("GET /x/top", "GET /x/parent", "GET /x/doc",
                    "GET /x/win", "GET /x/self");
            assertThat(g.requestsFrom(fn(jsp, "pop"))).containsExactly("GET /x/pop");
            Node pop = g.request("GET", "/x/pop");
            assertThat(pop.attrString("api")).isEqualTo("option");
            assertThat(pop.attrString("optionKey")).isEqualTo("arg");
            assertThat(pop.attrString("callee")).isEqualTo("top.$.jBox.open");
            assertThat(pop.attrString("query")).isEqualTo("id=1");
            assertThat(confidence(g, fn(jsp, "pop"), "GET", "/x/pop")).isEqualTo(Confidence.INFERRED);
            assertThat(g.requestsFrom(fn(jsp, "pop2"))).containsExactlyInAnyOrder("GET /x/pop2/{*}", "GET /x/get", "POST /x/post");
            assertThat(g.requestsFrom(fn(jsp, "lay"))).containsExactly("GET /x/layer");
            assertThat(g.request("GET", "/x/layer").attrString("optionKey")).isEqualTo("content");
            assertThat(g.edges(fn(jsp, "none"), EdgeKind.REQUESTS)).isEmpty();
            // 지금 주소를 다시 여는 새로 고침은 요청도 미해결도 아니고, 경로에 붙인 세션 ID는 경로가 아니다
            assertThat(g.edges(fn(jsp, "reload"), EdgeKind.REQUESTS)).isEmpty();
            assertThat(g.requestsFrom(fn(jsp, "session"))).containsExactly("GET /x/menu");
        }
    }

    @Test
    @DisplayName("F-010-AC51 쿼리 조각만 있는 문자열로는 요청을 만들지 않고, 컨텍스트 경로 '/' 뒤의 '/x'는 슬래시를 하나로 합치며, 컨텍스트 경로 뒤가 통째로 동적이면 미해결로 둔다")
    void queryOnlyAndDoubleSlash() {
        String jsp = "web/views/pop.jsp";
        Analyzer fake = producer(ctx -> inline(ctx, jsp, 2, """
                function open1(popupId, fileUrl) {
                  var url = "/pwm/openPopup.do?";
                  url = url + "fileUrl=" + fileUrl;
                  url = url + "&popupId=" + popupId;
                  window.open(url, popupId);
                }
                var ctx = "/";
                function login() { window.open(ctx + "/twitter/login.do"); fetch(ctx + "/twitter/me.do"); }
                function cdn() { fetch('//cdn.example.com/lib/x'); }
                function tree(id) { $.get("__JSP_EXPR____JSP_EXPR____JSP_EXPR__&extId=" + id + "&isAll=1"); }
                function amp(id) { location.href = '/x/view.do&id=' + id; }
                """));
        try (TestProject project = TestProject.create().file(jsp, "<html></html>")) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            assertThat(g.requestsFrom(fn(jsp, "open1"))).containsExactly("GET /pwm/openPopup.do");
            assertThat(g.requestsFrom(fn(jsp, "login"))).containsExactlyInAnyOrder("GET /twitter/login.do", "GET /twitter/me.do");
            assertThat(g.request("GET", "/twitter/login.do").attr("external")).isNull();
            assertThat(g.request("GET", "//cdn.example.com/lib/x").attr("external")).isEqualTo(true);
            // 경로가 통째로 동적: 요청 노드 없이 미해결
            assertThat(g.requestsFrom(fn(jsp, "tree"))).isEmpty();
            assertThat(g.targets(fn(jsp, "tree"), EdgeKind.REQUESTS)).hasSize(1).allMatch(id -> id.startsWith("unresolved:http-url:"));
            // '?' 없이 '&'로 이은 것도 쿼리로 가른다
            assertThat(g.requestsFrom(fn(jsp, "amp"))).containsExactly("GET /x/view.do");
            assertThat(g.request("GET", "/x/view.do").attrString("query")).isEqualTo("id={*}");
            for (String id : g.ids(NodeKind.HTTP_REQUEST)) {
                assertThat(id).doesNotContain("&").doesNotContain("=");
            }
        }
    }

    @Test
    @DisplayName("F-010-AC52 벤더 판정 보강: .debug.·.pack.·.bundle. 이름, plugins/<이름>/ 아래, 라이선스 문구와 Author·Version 표기가 있는 머리 주석, 매우 큰 생성물은 건너뛰고, 압축 여부는 이름이 아니라 내용으로 본다")
    void vendorAndMinifiedByContent() {
        String oneLine = "(function(t,e){\"use strict\";t.x=e()})(this,function(){" + "var a=1;function b(c){return c+a}".repeat(40)
                + "});";
        StringBuilder generated = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            generated.append("ORYX.f").append(i).append(" = function (a, b) {\n    return a + b + ").append(i).append(";\n};\n");
        }
        try (TestProject project = TestProject.create()
                .file("static/act/editor/oryx.debug.js", "var ORYX = {};\nfunction oryxInit() {}\n")
                .file("static/x/app.bundle.js", "function bundled() {}\n")
                .file("static/x/lib.pack.js", "function packed() {}\n")
                .file("static/x/foo-debug.js", "function fooDebug() {}\n")
                .file("admin/src/plugins/checkbix/js/checkbix.js", "function checkbix() {}\n")
                .file("admin/src/plugins/own.js", "function ownPlugin() {}\n")
                .file("static/pngfix/DD_belatedPNG.js", """
                        /**
                        * DD_belatedPNG: Adds IE6 support: PNG images for CSS background-image and HTML <IMG/>.
                        * Author: Drew Diller
                        * Version: 0.0.8a
                        * Licensed under the MIT License: http://example.com/#license
                        **/
                        var DD_belatedPNG = { fix: function () {} };
                        """)
                .file("static/one/oneline.js", oneLine)
                .file("static/own/progress.min.js", """
                        /**
                         * 우리 팀이 쓴 진행 막대. 압축하지 않았다.
                         */
                        function progressStart(el) {
                          el.style.width = '0%';
                        }
                        function progressEnd(el) {
                          el.style.width = '100%';
                        }
                        function progressSet(el, n) {
                          el.style.width = n + '%';
                        }
                        """)
                .file("static/gen/generated.js", generated.toString())
                .file("static/own/app.js", "function appMain() {}\n")) {
            G g = new G(project.run(new JsAnalyzer()));

            for (String vendor : List.of("static/act/editor/oryx.debug.js", "static/x/app.bundle.js", "static/x/lib.pack.js",
                    "static/x/foo-debug.js", "admin/src/plugins/checkbix/js/checkbix.js", "static/pngfix/DD_belatedPNG.js",
                    "static/gen/generated.js")) {
                assertThat(g.node(mod(vendor)).attrString("skipped")).as(vendor).isEqualTo("vendor");
                assertThat(g.functions(vendor)).as(vendor).isEmpty();
            }
            // 이름에 .min이 없어도 내용이 압축본이면 건너뛴다
            assertThat(g.node(mod("static/one/oneline.js")).attrString("skipped")).isEqualTo("minified");
            // .min.js라도 내용이 압축돼 있지 않으면 분석한다
            assertThat(g.node(mod("static/own/progress.min.js")).attr("skipped")).isNull();
            assertThat(g.functions("static/own/progress.min.js")).containsExactly("progressEnd", "progressSet", "progressStart");
            // plugins 바로 아래의 파일과 보통 파일은 그대로 분석한다
            assertThat(g.functions("admin/src/plugins/own.js")).containsExactly("ownPlugin");
            assertThat(g.functions("static/own/app.js")).containsExactly("appMain");
        }
    }
}
