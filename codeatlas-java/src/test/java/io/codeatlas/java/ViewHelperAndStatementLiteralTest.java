package io.codeatlas.java;

import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3차 재검증 후속(M13): 구문 ID처럼 보이는 문자열 상수 인자를 메서드 attrs에 남기기(F-004-R24),
 * 뷰 이름을 만드는 도우미 메서드에 호출 지점의 리터럴 인자를 대입하기(F-004-R25).
 */
class ViewHelperAndStatementLiteralTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    // ---- 구문 ID 문자열 인자 (R24) ----

    @Test
    @DisplayName("F-004-AC26 라이브러리 메서드 호출에 넘긴 '점이 하나 이상이고 공백이 없는 문자열 상수'를 메서드 attrs.statementIdLiterals와 병렬 목록 statementIdLiteralLines(줄:열)에 남긴다. SqlSession 호출의 구문 ID, 프로젝트·JDK 메서드의 인자, 단언(assert*)·구문 조회(getMappedStatement, hasStatement)의 인자는 넣지 않는다")
    void statementIdLikeLiteralArguments() {
        GraphModel g = Fx.analyze(
                "src/com/a/ZipService.java", """
                        package com.a;
                        import egovframework.rte.fdl.excel.EgovExcelService;
                        import org.apache.ibatis.session.Configuration;
                        import org.apache.ibatis.session.SqlSession;
                        import org.springframework.context.MessageSource;
                        import static org.junit.jupiter.api.Assertions.assertEquals;
                        public class ZipService {
                            private static final String QUERY = "ZipManageDAO.insertRdnmadrZip";
                            EgovExcelService excelZipService;
                            SqlSession session;
                            MessageSource messages;
                            Configuration configuration;
                            Helper helper;
                            public void upload(java.io.InputStream in) throws Exception {
                                excelZipService.uploadExcel("ZipManageDAO.insertExcelZip", in, 2, 5000);
                                excelZipService.uploadExcel(QUERY, in);
                                messages.getMessage("fail.common.msg", "no dot", "two words.here", "trailing.", ".leading", "a.b");
                                session.insert("ZipManageDAO.direct", in);
                                helper.message("Project.method");
                                local("Own.method");
                                System.getProperty("user.dir");
                                "x.y".equals(in.toString());
                                assertEquals("ZipManageDAO.asserted", in.toString());
                                configuration.getMappedStatement("ZipManageDAO.lookedUp");
                                configuration.hasStatement("ZipManageDAO.checked");
                            }
                            void local(String id) { }
                            public void none() { messages.getMessage("plain"); }
                        }
                        """,
                "src/com/a/Helper.java", """
                        package com.a;
                        public class Helper {
                            public String message(String... codes) { return ""; }
                        }
                        """);

        Node upload = node(g, m("com.a.ZipService", "upload", "java.io.InputStream"));
        assertThat(upload.attrList("statementIdLiterals"))
                .containsExactly("ZipManageDAO.insertExcelZip", "ZipManageDAO.insertRdnmadrZip", "fail.common.msg", "a.b");
        assertThat(upload.attrList("statementIdLiteralLines")).hasSize(4);
        assertThat(upload.attrList("statementIdLiteralLines").get(0)).startsWith("15:");
        assertThat(upload.attrList("statementIdLiteralLines").get(1)).startsWith("16:");
        assertThat(node(g, m("com.a.ZipService", "none")).attrs()).doesNotContainKey("statementIdLiterals");
        // SqlSession 호출의 구문 ID는 전처럼 EXECUTES다
        assertThat(targets(g, upload.id(), EdgeKind.EXECUTES)).containsExactly(Ids.mybatisStatement("ZipManageDAO", "direct"));
    }

    // ---- 뷰 이름을 만드는 도우미 (R25) ----

    private static final String[] HELPERS = {
            "src/com/a/BaseController.java", """
                    package com.a;
                    public abstract class BaseController {
                        private static final String SUFFIX = ".html";
                        public static String jsp(String path) { return path.concat(".jsp"); }
                        public static String thymeleaf(String path) {
                            String folder = Props.get("app.name");
                            return "/".concat(folder).concat(path).concat(SUFFIX);
                        }
                        protected String page(String module, String name) { return "modules/" + module + "/" + name; }
                        protected String same(String name) { return name; }
                        protected String pick(String name, boolean flag) { if (flag) { return "fixed/view"; } return "views/" + name; }
                    }
                    """,
            "src/com/a/Props.java", "package com.a; public class Props { public static String get(String key) { return null; } }",
            "src/com/a/BlogController.java", """
                    package com.a;
                    import org.springframework.stereotype.Controller;
                    import org.springframework.ui.Model;
                    import org.springframework.web.bind.annotation.RequestMapping;
                    @Controller
                    public class BlogController extends BaseController {
                        @RequestMapping("/blog")
                        public String blog() { return thymeleaf("/blog/index"); }
                        @RequestMapping("/index")
                        public String index() { return jsp("/index"); }
                        @RequestMapping("/page")
                        public String page() { return page("cms", "list"); }
                        @RequestMapping("/same")
                        public String same() { return same("plain/view"); }
                        @RequestMapping("/pick")
                        public String pick() { return pick("a", true); }
                        @RequestMapping("/partly")
                        public String partly(String type) { return page("cms", type); }
                        @RequestMapping("/unknown")
                        public String unknown(String v) { return same(v); }
                        @RequestMapping("/thymeleaf")
                        public String thymeleaf(Model model) { return thymeleaf("/index"); }
                    }
                    """,
    };

    @Test
    @DisplayName("F-004-AC27 뷰 이름을 만드는 도우미(접두 + 인자 + 접미, concat 사슬, 인자를 그대로 반환)는 호출 지점의 리터럴 인자를 대입해 뷰 이름을 만든다. 도우미 안의 알 수 없는 조각은 {*}다")
    void helperWithLiteralArgument() {
        GraphModel g = Fx.analyze(HELPERS);

        // 도우미 안의 Props.get("app.name")은 프로퍼티를 읽는 꼴의 호출이라 ${app.name}으로 남는다(F-004-R26. 전에는 {*}였다)
        assertThat(node(g, m("com.a.BlogController", "blog")).attrList("viewNames")).containsExactly("/${app.name}/blog/index.html");
        assertThat(node(g, m("com.a.BlogController", "index")).attrList("viewNames")).containsExactly("/index.jsp");
        assertThat(node(g, m("com.a.BlogController", "page")).attrList("viewNames")).containsExactly("modules/cms/list");
        assertThat(node(g, m("com.a.BlogController", "same")).attrList("viewNames")).containsExactly("plain/view");
        assertThat(node(g, m("com.a.BlogController", "pick")).attrList("viewNames")).containsExactlyInAnyOrder("fixed/view", "views/a");
        for (String handler : List.of("blog", "index", "page", "same", "pick")) {
            assertThat(targets(g, m("com.a.BlogController", handler), EdgeKind.RENDERS)).as(handler).isEmpty();
        }
        // 뷰 이름의 자리는 그 도우미를 부른 호출 지점이다
        assertThat(node(g, m("com.a.BlogController", "blog")).attrList("viewNameLines").get(0)).startsWith("8:");
    }

    @Test
    @DisplayName("F-004-AC27 호출 지점의 인자가 상수가 아니면 그 자리는 {*}이고, 상수 조각이 하나도 남지 않으면(인자를 그대로 돌려주는 도우미에 변수를 넘김) 전처럼 미해결이다")
    void helperWithNonLiteralArgument() {
        GraphModel g = Fx.analyze(HELPERS);

        assertThat(node(g, m("com.a.BlogController", "partly", "java.lang.String")).attrList("viewNames")).containsExactly("modules/cms/{*}");
        String unknown = m("com.a.BlogController", "unknown", "java.lang.String");
        assertThat(node(g, unknown).attrs()).doesNotContainKey("viewNames");
        assertThat(targets(g, unknown, EdgeKind.RENDERS)).containsExactly(Ids.unresolved("view", "same(v)"));
    }

    @Test
    @DisplayName("F-004-AC27 매개변수 타입이 classpath에 없어 컴파일러가 도우미 호출을 자기 자신의 호출로 고른 핸들러(thymeleaf(Model)의 return thymeleaf(\"/index\"))도 인자 타입이 맞는 같은 이름의 도우미로 푼다")
    void helperCallMistakenForSelfCall() {
        GraphModel g = Fx.analyze(HELPERS);

        String handler = m("com.a.BlogController", "thymeleaf", "org.springframework.ui.Model");
        assertThat(node(g, handler).attrList("viewNames")).containsExactly("/${app.name}/index.html");
        assertThat(g.nodes(NodeKind.UNRESOLVED)).filteredOn(n -> "view".equals(n.attrString("category")))
                .extracting(Node::name).containsExactly("same(v)");
    }

    // ---- 프로퍼티를 읽는 호출 (R26) ----

    @Test
    @DisplayName("F-004-AC28 뷰 이름 연결식의 '프로퍼티를 읽는 꼴의 호출'(<수신자>.get|getProperty|getString|getValue|getConfig(\"키\"))은 그 자리에 ${키}를 남긴다(도우미의 지역 변수 경유 포함). 수신자 없는 호출, 다른 이름의 호출, 키 꼴이 아닌 인자, Map·서블릿 API의 호출은 전처럼 {*}다")
    void propertyReadInViewName() {
        GraphModel g = Fx.analyze(
                "src/com/a/BaseController.java", """
                        package com.a;
                        public abstract class BaseController {
                            public static String thymeleaf(String path) {
                                String folder = Props.getInstance().get("app.name");
                                return "/".concat(folder).concat(path).concat(".html");
                            }
                        }
                        """,
                "src/com/a/Props.java", """
                        package com.a;
                        public class Props {
                            public static Props getInstance() { return new Props(); }
                            public String get(String key) { return null; }
                            public String getConfig(String key) { return null; }
                            public String lookup(String key) { return null; }
                            public int getValue(String key) { return 0; }
                        }
                        """,
                "src/com/a/SignController.java", """
                        package com.a;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.RequestMapping;
                        import java.util.Map;
                        import java.util.Properties;
                        @Controller
                        public class SignController extends BaseController {
                            Props props;
                            Properties jdk;
                            Map<String, String> map;
                            @RequestMapping("/signin")
                            public String signin() { return thymeleaf("/login"); }
                            @RequestMapping("/direct")
                            public String direct() { return "/" + props.getConfig("skin-dir") + "/main"; }
                            @RequestMapping("/jdk")
                            public String jdk() { return "themes/" + jdk.getProperty("theme") + "/index"; }
                            @RequestMapping("/go")
                            public String go() { return "redirect:" + props.get("adminPath") + "/login"; }
                            @RequestMapping("/map")
                            public String map() { return "/" + map.get("app.name") + "/main"; }
                            @RequestMapping("/other")
                            public String other() { return "/" + props.lookup("app.name") + "/main"; }
                            @RequestMapping("/words")
                            public String words() { return "/" + props.get("two words") + "/main"; }
                            @RequestMapping("/number")
                            public String number() { return "/" + props.getValue("app.count") + "/main"; }
                            @RequestMapping("/own")
                            public String own() { return "/" + get("app.name") + "/main"; }
                            String get(String key) { return null; }
                        }
                        """);

        assertThat(node(g, m("com.a.SignController", "signin")).attrList("viewNames")).containsExactly("/${app.name}/login.html");
        assertThat(node(g, m("com.a.SignController", "direct")).attrList("viewNames")).containsExactly("/${skin-dir}/main");
        assertThat(node(g, m("com.a.SignController", "jdk")).attrList("viewNames")).containsExactly("themes/${theme}/index");
        assertThat(node(g, m("com.a.SignController", "go")).attrList("viewNames")).containsExactly("redirect:${adminPath}/login");
        for (String handler : List.of("map", "other", "words", "number", "own")) {
            assertThat(node(g, m("com.a.SignController", handler)).attrList("viewNames")).as(handler).containsExactly("/{*}/main");
        }
    }
}
