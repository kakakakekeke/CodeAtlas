package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-004-R14~R16: 다른 메서드를 부르는 반환식의 뷰 이름, {@code @ModelAttribute} 메서드, 요청 경로의 프로퍼티 자리표시자. */
class SpringControllerFlowTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    private static final String[] VIEWS = {
            "src/com/a/BaseController.java", """
                    package com.a;
                    public abstract class BaseController {
                        protected String error() { return "error/500"; }
                        protected String renderString(Object response, String body) { return null; }
                        public static String thymeleaf(String path) { return "/".concat(path).concat(".html"); }
                    }
                    """,
            "src/com/a/ViewService.java", "package com.a; public interface ViewService { String pick(); }",
            "src/com/a/ArticleController.java", """
                    package com.a;
                    import org.springframework.stereotype.Controller;
                    import org.springframework.web.bind.annotation.RequestMapping;
                    @Controller
                    @RequestMapping("/cms/article")
                    public class ArticleController extends BaseController {
                        ViewService viewService;
                        @RequestMapping("form")
                        public String form(Object article, Object model) { return "modules/cms/articleForm"; }
                        @RequestMapping("save")
                        public String save(Object article, Object model) {
                            if (article == null) {
                                return form(article, model);
                            }
                            return "redirect:/cms/article/list";
                        }
                        @RequestMapping("chain")
                        public String chain(Object article, Object model) { return save(article, model); }
                        @RequestMapping("a")
                        public String a() { return b(); }
                        @RequestMapping("b")
                        public String b() { return a(); }
                        @RequestMapping("viaHelper")
                        public String viaHelper(String type) { return view(type); }
                        private String view(String name) { return "modules/cms/" + name; }
                        @RequestMapping("viaSuper")
                        public String viaSuper() { return this.error(); }
                        @RequestMapping("nullHelper")
                        public String nullHelper(Object response) { return renderString(response, "ok"); }
                        @RequestMapping("dynHelper")
                        public String dynHelper(String v) { String link = echo(v); if (v == null) { return link; } return link; }
                        private String echo(String v) { return v; }
                        @RequestMapping("iface")
                        public String iface() { return viewService.pick(); }
                        @RequestMapping("dyn")
                        public String dyn(String v) { return v; }
                    }
                    """,
            // 매개변수 타입(Model)이 classpath에 없으면 컴파일러가 thymeleaf("/index")를 자기 자신의 호출로 고른다
            "src/com/a/IndexController.java", """
                    package com.a;
                    import org.springframework.stereotype.Controller;
                    import org.springframework.ui.Model;
                    import org.springframework.web.bind.annotation.RequestMapping;
                    @Controller
                    public class IndexController extends BaseController {
                        @RequestMapping("/thymeleaf")
                        public String thymeleaf(Model model) { return thymeleaf("/index"); }
                    }
                    """,
    };

    private static List<String> unresolvedViews(GraphModel g, String methodId) {
        return targets(g, methodId, EdgeKind.RENDERS);
    }

    @Test
    @DisplayName("F-004-AC16 반환식이 같은 클래스의 핸들러 호출이면 뷰 미해결을 남기지 않고 그 핸들러의 viewNames를 합친다(전이적으로, 순환은 끊는다)")
    void returnedHandlerCallMergesViewNames() {
        GraphModel g = Fx.analyze(VIEWS);

        String save = m("com.a.ArticleController", "save", "java.lang.Object", "java.lang.Object");
        assertThat(node(g, save).attrList("viewNames")).containsExactlyInAnyOrder("redirect:/cms/article/list", "modules/cms/articleForm");
        assertThat(unresolvedViews(g, save)).isEmpty();
        // 그 호출은 Java CALLS로 이미 있다
        assertThat(edge(g, save, EdgeKind.CALLS, m("com.a.ArticleController", "form", "java.lang.Object", "java.lang.Object")).confidence())
                .isEqualTo(Confidence.RESOLVED);
        // 전이적으로
        String chain = m("com.a.ArticleController", "chain", "java.lang.Object", "java.lang.Object");
        assertThat(node(g, chain).attrList("viewNames")).containsExactlyInAnyOrder("redirect:/cms/article/list", "modules/cms/articleForm");
        assertThat(unresolvedViews(g, chain)).isEmpty();
        // 순환: 뷰 이름도 미해결도 없이 끝난다
        assertThat(node(g, m("com.a.ArticleController", "a")).attrs()).doesNotContainKey("viewNames");
        assertThat(unresolvedViews(g, m("com.a.ArticleController", "a"))).isEmpty();
        assertThat(unresolvedViews(g, m("com.a.ArticleController", "b"))).isEmpty();
        assertThat(g.nodes(NodeKind.UNRESOLVED)).filteredOn(n -> "view".equals(n.attrString("category")))
                // 2026-10-11 고쳐 씀(F-004-R25): thymeleaf("/index")는 도우미에 리터럴 인자를 대입해 풀리므로 미해결 목록에서 빠졌다
                .extracting(Node::name).containsExactlyInAnyOrder("echo(v)", "viewService.pick()", "v");
    }

    @Test
    @DisplayName("F-004-AC16 호출 대상이 뷰 이름을 조립하는 헬퍼면 그 반환식을 같은 규칙으로 평가하고(매개변수는 {*}), 헬퍼가 값을 정하지 못하는 진짜 동적 식은 미해결로 남긴다")
    void returnedHelperCallIsEvaluated() {
        GraphModel g = Fx.analyze(VIEWS);

        String viaHelper = m("com.a.ArticleController", "viaHelper", "java.lang.String");
        assertThat(node(g, viaHelper).attrList("viewNames")).containsExactly("modules/cms/{*}");
        assertThat(unresolvedViews(g, viaHelper)).isEmpty();
        // 다른 파일의 상위 클래스에 있는 헬퍼
        assertThat(node(g, m("com.a.ArticleController", "viaSuper")).attrList("viewNames")).containsExactly("error/500");
        // null만 돌려주는 헬퍼: 뷰도 미해결도 없다
        String nullHelper = m("com.a.ArticleController", "nullHelper", "java.lang.Object");
        assertThat(node(g, nullHelper).attrs()).doesNotContainKey("viewNames");
        assertThat(unresolvedViews(g, nullHelper)).isEmpty();
        // 헬퍼가 매개변수를 그대로 돌려준다, 본문이 없는 인터페이스 메서드다, 매개변수를 그대로 반환한다: 지금처럼 미해결
        String dynHelper = m("com.a.ArticleController", "dynHelper", "java.lang.String");
        Edge dynamic = edge(g, dynHelper, EdgeKind.RENDERS, Ids.unresolved("view", "echo(v)"));
        assertThat(dynamic.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(dynamic.location().startLine()).isEqualTo(31);
        // 같은 식에 return이 둘 닿아도 미해결 엣지와 진단은 하나다
        assertThat(g.diagnostics()).filteredOn(d -> d.message().contains("(view): echo(v)")).hasSize(1);
        assertThat(unresolvedViews(g, m("com.a.ArticleController", "iface"))).containsExactly(Ids.unresolved("view", "viewService.pick()"));
        assertThat(unresolvedViews(g, m("com.a.ArticleController", "dyn", "java.lang.String"))).containsExactly(Ids.unresolved("view", "v"));
        // 2026-10-11 고쳐 씀(F-004-R25): 컴파일러가 자기 자신의 호출로 잘못 고른 반환식도 인자 타입이 맞는 도우미로 풀고,
        // 도우미의 반환식("/".concat(path).concat(".html"))에 호출 지점의 리터럴을 대입한다. 전에는 미해결(thymeleaf("/index"))이었다
        String index = m("com.a.IndexController", "thymeleaf", "org.springframework.ui.Model");
        assertThat(unresolvedViews(g, index)).isEmpty();
        assertThat(node(g, index).attrList("viewNames")).containsExactly("//index.html");
        // 헬퍼 자신은 핸들러가 아니다: viewNames를 붙이지 않는다
        assertThat(node(g, m("com.a.ArticleController", "view", "java.lang.String")).attrs()).doesNotContainKey("viewNames");
        assertThat(node(g, m("com.a.BaseController", "error")).attrs()).doesNotContainKey("viewNames");
    }

    @Test
    @DisplayName("F-004-AC17 같은 컨트롤러와 상위 클래스의 @ModelAttribute 메서드마다 각 핸들러에서 CALLS(INFERRED, callType=modelAttribute)를 만들고 @InitBinder와 매개변수의 @ModelAttribute로는 만들지 않는다")
    void modelAttributeMethods() {
        GraphModel g = Fx.analyze(
                "src/com/a/BaseController.java", """
                        package com.a;
                        import org.springframework.web.bind.annotation.InitBinder;
                        import org.springframework.web.bind.annotation.ModelAttribute;
                        public abstract class BaseController {
                            @ModelAttribute
                            public void common(Object model) { }
                            @ModelAttribute("shadowed")
                            public Object shadowed() { return null; }
                            @InitBinder
                            protected void initBinder(Object binder) { }
                        }
                        """,
                "src/com/a/UserController.java", """
                        package com.a;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.*;
                        @Controller
                        @RequestMapping("/sys/user")
                        public class UserController extends BaseController {
                            @ModelAttribute
                            public Object get(@RequestParam(required = false) String id) {
                                return id;
                            }
                            @Override
                            @ModelAttribute("shadowed")
                            public Object shadowed() { return "x"; }
                            @RequestMapping("list")
                            public String list(@ModelAttribute("user") Object user) { return "list"; }
                            @GetMapping("form")
                            public String form(Object user) { return "form"; }
                            @ModelAttribute("types")
                            @RequestMapping("types")
                            public Object types() { return null; }
                            public void helper() { }
                        }
                        """,
                "src/com/a/PlainController.java", """
                        package com.a;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.RequestMapping;
                        @Controller
                        public class PlainController {
                            @RequestMapping("plain")
                            public String plain() { return "plain"; }
                        }
                        """);

        String get = m("com.a.UserController", "get", "java.lang.String");
        String common = m("com.a.BaseController", "common", "java.lang.Object");
        String shadowed = m("com.a.UserController", "shadowed");
        String list = m("com.a.UserController", "list", "java.lang.Object");
        String form = m("com.a.UserController", "form", "java.lang.Object");
        String types = m("com.a.UserController", "types");

        Edge e = edge(g, list, EdgeKind.CALLS, get);
        assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(e.attrString("callType")).isEqualTo("modelAttribute");
        assertThat(e.location().file()).isEqualTo("src/com/a/UserController.java");
        assertThat(e.location().startLine()).isEqualTo(8);
        Edge inherited = edge(g, list, EdgeKind.CALLS, common);
        assertThat(inherited.attrString("callType")).isEqualTo("modelAttribute");
        assertThat(inherited.location().file()).isEqualTo("src/com/a/BaseController.java");
        assertThat(inherited.location().startLine()).isEqualTo(6);
        // 핸들러마다, 재정의한 상위 메서드는 하위의 것만
        for (String handler : List.of(list, form, types)) {
            assertThat(targets(g, handler, EdgeKind.CALLS)).containsExactlyInAnyOrder(get, common, shadowed);
        }
        // 핸들러가 아닌 메서드와 @ModelAttribute 메서드 자신에서는 나가지 않는다
        assertThat(targets(g, m("com.a.UserController", "helper"), EdgeKind.CALLS)).isEmpty();
        assertThat(targets(g, get, EdgeKind.CALLS)).isEmpty();
        // 매핑이 함께 붙은 메서드는 핸들러다(대상이 아니다). @InitBinder도 대상이 아니다
        assertThat(g.incoming(types, EdgeKind.CALLS)).isEmpty();
        assertThat(g.incoming(m("com.a.BaseController", "initBinder", "java.lang.Object"), EdgeKind.CALLS)).isEmpty();
        assertThat(targets(g, m("com.a.PlainController", "plain"), EdgeKind.CALLS)).isEmpty();
    }

    @Test
    @DisplayName("F-004-AC18 요청 경로의 ${…} 자리표시자는 그대로 두고(슬래시 정규화가 깨뜨리지 않는다) 엔드포인트 attrs.placeholders에 이름을 남긴다")
    void endpointPlaceholders() {
        GraphModel g = Fx.analyze(
                "src/com/a/UserController.java", """
                        package com.a;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.*;
                        @Controller
                        @RequestMapping("${adminPath}/sys/user")
                        public class UserController {
                            @RequestMapping("list")
                            public String list() { return "list"; }
                            @RequestMapping(value = {"${sub}/form", ""})
                            public String form() { return "form"; }
                            @GetMapping("/{id}")
                            public String one() { return "one"; }
                        }
                        """,
                "src/com/a/FrontController.java", """
                        package com.a;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.*;
                        @Controller
                        public class FrontController {
                            static final String FRONT = "${frontPath:/f}";
                            @RequestMapping("${a}${b}/x")
                            public String joined() { return "x"; }
                            @RequestMapping(FRONT + "/index")
                            public String index() { return "index"; }
                            @RequestMapping("/plain/{id}")
                            public String plain() { return "plain"; }
                        }
                        """);

        Node list = node(g, "http:endpoint:ANY /${adminPath}/sys/user/list");
        assertThat(list.attrString("path")).isEqualTo("/${adminPath}/sys/user/list");
        assertThat(list.attrList("placeholders")).containsExactly("adminPath");
        assertThat(Fx.sources(g, m("com.a.UserController", "list"), EdgeKind.ROUTES_TO)).containsExactly(list.id());
        // 클래스 수준과 메서드 수준 양쪽
        assertThat(node(g, "http:endpoint:ANY /${adminPath}/sys/user/${sub}/form").attrList("placeholders")).containsExactly("adminPath", "sub");
        assertThat(node(g, "http:endpoint:ANY /${adminPath}/sys/user").attrList("placeholders")).containsExactly("adminPath");
        // 경로 변수 {id}는 자리표시자가 아니다
        assertThat(node(g, "http:endpoint:GET /${adminPath}/sys/user/{id}").attrList("placeholders")).containsExactly("adminPath");
        assertThat(node(g, "http:endpoint:ANY /plain/{id}").attrs()).doesNotContainKey("placeholders");
        // 연달아 온 자리표시자 사이에는 슬래시를 넣지 않는다. 기본값이 있으면 이름만 남긴다
        assertThat(node(g, "http:endpoint:ANY /${a}${b}/x").attrList("placeholders")).containsExactly("a", "b");
        Node index = node(g, "http:endpoint:ANY /${frontPath:/f}/index");
        assertThat(index.attrString("path")).isEqualTo("/${frontPath:/f}/index");
        assertThat(index.attrList("placeholders")).containsExactly("frontPath");

        assertThat(Ids.normalizePath("${adminPath}/sys/user")).isEqualTo("/${adminPath}/sys/user");
        assertThat(Ids.normalizePath("${adminPath}//sys/")).isEqualTo("/${adminPath}/sys/");
        assertThat(Ids.normalizePath("${a}${b}/x")).isEqualTo("/${a}${b}/x");
        assertThat(Ids.httpEndpoint("get", "${adminPath}/sys")).isEqualTo("http:endpoint:GET /${adminPath}/sys");
    }
}
