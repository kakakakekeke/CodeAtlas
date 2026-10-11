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
import java.util.function.Function;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.hasEdge;
import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-004 Spring 해석 (애너테이션). */
class SpringAnalysisTest {

    private static String type(String name) {
        return Ids.javaType(name);
    }

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    private static final String[] STEREOTYPES = {
            "src/com/a/OrderServiceImpl.java", "package com.a; import org.springframework.stereotype.Service; @Service public class OrderServiceImpl { }",
            "src/com/a/OrderDao.java", "package com.a; import org.springframework.stereotype.*; @Repository(\"orderDao\") public class OrderDao { }",
            "src/com/a/OrderController.java", "package com.a; import org.springframework.stereotype.Controller; @Controller public class OrderController { }",
            "src/com/a/ApiController.java", "package com.a; import org.springframework.web.bind.annotation.RestController; @RestController public class ApiController { }",
            "src/com/a/Helper.java", "package com.a; @org.springframework.stereotype.Component(value = \"helperBean\") public class Helper { }",
            "src/com/a/AppConfig.java", "package com.a; import org.springframework.context.annotation.Configuration; @Configuration public class AppConfig { }",
            "src/com/a/Advice.java", "package com.a; import org.springframework.web.bind.annotation.*; @ControllerAdvice public class Advice { }",
            "src/com/a/URLMapper.java", "package com.a; import org.springframework.stereotype.Component; @Component public class URLMapper { }",
            "src/com/a/AbstractBase.java", "package com.a; import org.springframework.stereotype.Service; @Service public abstract class AbstractBase { }",
            "src/com/own/Service.java", "package com.own; public @interface Service { }",
            "src/com/own/NotABean.java", "package com.own; @Service public class NotABean { }",
            "src/com/a/Plain.java", "package com.a; public class Plain { }",
    };

    private static void assertStereotypes(GraphModel g) {
        assertThat(node(g, type("com.a.OrderServiceImpl")).attrString("stereotype")).isEqualTo("service");
        assertThat(node(g, type("com.a.OrderDao")).attrString("stereotype")).isEqualTo("repository");
        assertThat(node(g, type("com.a.OrderController")).attrString("stereotype")).isEqualTo("controller");
        Node api = node(g, type("com.a.ApiController"));
        assertThat(api.attrString("stereotype")).isEqualTo("controller");
        assertThat(api.attrBoolean("restController")).isTrue();
        assertThat(node(g, type("com.a.Helper")).attrString("stereotype")).isEqualTo("component");
        assertThat(node(g, type("com.a.AppConfig")).attrString("stereotype")).isEqualTo("configuration");
        assertThat(node(g, type("com.a.Advice")).attrString("stereotype")).isEqualTo("component");
        assertThat(node(g, type("com.own.NotABean")).attrString("stereotype")).isNull();
        assertThat(node(g, type("com.a.Plain")).attrString("stereotype")).isNull();

        Node bean = node(g, Ids.springBean("orderServiceImpl"));
        assertThat(bean.kind()).isEqualTo(NodeKind.SPRING_BEAN);
        assertThat(bean.name()).isEqualTo("orderServiceImpl");
        assertThat(bean.attrString("class")).isEqualTo("com.a.OrderServiceImpl");
        assertThat(bean.declared()).isTrue();
        assertThat(hasEdge(g, type("com.a.OrderServiceImpl"), EdgeKind.DEFINES_BEAN, bean.id())).isTrue();
        assertThat(hasEdge(g, type("com.a.OrderDao"), EdgeKind.DEFINES_BEAN, Ids.springBean("orderDao"))).isTrue();
        assertThat(hasEdge(g, type("com.a.Helper"), EdgeKind.DEFINES_BEAN, Ids.springBean("helperBean"))).isTrue();
        assertThat(hasEdge(g, type("com.a.OrderController"), EdgeKind.DEFINES_BEAN, Ids.springBean("orderController"))).isTrue();
        // 앞 두 글자가 대문자면 그대로 (Introspector.decapitalize)
        assertThat(hasEdge(g, type("com.a.URLMapper"), EdgeKind.DEFINES_BEAN, Ids.springBean("URLMapper"))).isTrue();
        // 추상 클래스는 빈이 아니다
        assertThat(targets(g, type("com.a.AbstractBase"), EdgeKind.DEFINES_BEAN)).isEmpty();
        assertThat(g.nodes(NodeKind.SPRING_BEAN)).hasSize(8);
    }

    @Test
    @DisplayName("F-004-AC1 jar가 없어도 import와 단순 이름으로 스테레오타입을 인식해 빈을 만들고 프로젝트 자체 애너테이션은 구분한다")
    void stereotypesWithoutJar() {
        assertStereotypes(Fx.analyze(STEREOTYPES));
    }

    @Test
    @DisplayName("F-004-AC2 jar가 있을 때도 같은 스테레오타입과 빈이 나온다")
    void stereotypesWithJar() {
        assertStereotypes(Fx.analyzeWithStubs(STEREOTYPES));
    }

    @Test
    @DisplayName("F-004-AC1 프로젝트가 만든 합성 애너테이션은 메타 애너테이션의 스테레오타입을 따른다")
    void composedStereotype() {
        GraphModel g = Fx.analyze(
                "src/com/a/BizService.java", "package com.a; import org.springframework.stereotype.Service; @Service public @interface BizService { }",
                "src/com/a/OrderBiz.java", "package com.a; @BizService public class OrderBiz { }");

        assertThat(node(g, type("com.a.OrderBiz")).attrString("stereotype")).isEqualTo("service");
        assertThat(hasEdge(g, type("com.a.OrderBiz"), EdgeKind.DEFINES_BEAN, Ids.springBean("orderBiz"))).isTrue();
    }

    @Test
    @DisplayName("F-004-AC5 주입 대상이 @Mapper 인터페이스 자신뿐이면 candidates를 남기지 않는다")
    void mapperInjectionHasNoSelfCandidate() {
        GraphModel g = Fx.analyze(
                "src/com/a/OrderMapper.java", "package com.a; import org.apache.ibatis.annotations.Mapper; @Mapper public interface OrderMapper { }",
                "src/com/a/Svc.java", "package com.a; import org.springframework.beans.factory.annotation.Autowired; public class Svc { @Autowired OrderMapper orderMapper; }");

        assertThat(edge(g, type("com.a.Svc"), EdgeKind.INJECTS, type("com.a.OrderMapper")).attrs()).doesNotContainKey("candidates");
    }

    @Test
    @DisplayName("F-004-AC3 @Bean 메서드는 이름(name/value 또는 메서드명)마다 빈과 DEFINES_BEAN(METHOD→BEAN), beanType을 만든다")
    void beanMethods() {
        GraphModel g = Fx.analyze(
                "src/com/a/PageRequest.java", "package com.a; public class PageRequest { }",
                "src/com/a/AppConfig.java", """
                        package com.a;
                        import org.mybatis.spring.SqlSessionTemplate;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;
                        @Configuration
                        public class AppConfig {
                            @Bean
                            public SqlSessionTemplate batchSqlSession(Object factory) { return new SqlSessionTemplate(factory); }
                            @Bean(name = "defaultPage")
                            public PageRequest defaultPageRequest() { return new PageRequest(); }
                            @Bean({"a", "b"})
                            public PageRequest aliases() { return null; }
                            public PageRequest notABean() { return null; }
                        }
                        """);

        String method = m("com.a.AppConfig", "batchSqlSession", "java.lang.Object");
        Node bean = node(g, Ids.springBean("batchSqlSession"));
        assertThat(bean.attrString("beanType")).isEqualTo("org.mybatis.spring.SqlSessionTemplate");
        assertThat(bean.attrString("class")).isEqualTo("org.mybatis.spring.SqlSessionTemplate");
        assertThat(bean.location().file()).isEqualTo("src/com/a/AppConfig.java");
        Edge defines = edge(g, method, EdgeKind.DEFINES_BEAN, bean.id());
        assertThat(defines.attrString("beanType")).isEqualTo("org.mybatis.spring.SqlSessionTemplate");
        assertThat(node(g, method).attrString("beanType")).isEqualTo("org.mybatis.spring.SqlSessionTemplate");

        assertThat(hasEdge(g, m("com.a.AppConfig", "defaultPageRequest"), EdgeKind.DEFINES_BEAN, Ids.springBean("defaultPage"))).isTrue();
        assertThat(node(g, Ids.springBean("defaultPage")).attrString("beanType")).isEqualTo("com.a.PageRequest");
        assertThat(g.node(Ids.springBean("defaultPageRequest"))).isEmpty();
        assertThat(targets(g, m("com.a.AppConfig", "aliases"), EdgeKind.DEFINES_BEAN))
                .containsExactlyInAnyOrder(Ids.springBean("a"), Ids.springBean("b"));
        assertThat(g.node(Ids.springBean("notABean"))).isEmpty();
    }

    @Test
    @DisplayName("F-004-AC4 필드·생성자·setter·@Resource·단일 생성자·Lombok 생성자 주입마다 INJECTS를 만든다")
    void injections() {
        GraphModel g = Fx.analyze(
                "src/com/a/A.java", "package com.a; public class A { }",
                "src/com/a/B.java", "package com.a; public class B { }",
                "src/com/a/C.java", "package com.a; public class C { }",
                "src/com/a/FieldInject.java", """
                        package com.a;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import javax.annotation.Resource;
                        import java.util.List;
                        public class FieldInject {
                            @Autowired private A a;
                            @Resource(name = "specialB") private B b;
                            @Autowired private List<C> cs;
                            private C notInjected;
                        }
                        """,
                "src/com/a/CtorInject.java", """
                        package com.a;
                        import org.springframework.beans.factory.annotation.Autowired;
                        public class CtorInject {
                            private final A first;
                            @Autowired public CtorInject(A a, B b) { this.first = a; }
                            public CtorInject() { this.first = null; }
                        }
                        """,
                "src/com/a/SetterInject.java", """
                        package com.a;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.beans.factory.annotation.Qualifier;
                        public class SetterInject {
                            private A session;
                            @Autowired public void setSession(@Qualifier("batchA") A a) { this.session = a; }
                            public void setOther(B b) { }
                        }
                        """,
                "src/com/a/ImplicitInject.java", """
                        package com.a;
                        import org.springframework.stereotype.Service;
                        import org.springframework.beans.factory.annotation.Value;
                        @Service
                        public class ImplicitInject {
                            private final A a;
                            public ImplicitInject(A a, String name, @Value("${x}") int size) { this.a = a; }
                        }
                        """,
                "src/com/a/NotBean.java", "package com.a; public class NotBean { public NotBean(A a) { } }",
                "src/com/a/LombokInject.java", """
                        package com.a;
                        import lombok.RequiredArgsConstructor;
                        import org.springframework.stereotype.Service;
                        @Service
                        @RequiredArgsConstructor
                        public class LombokInject {
                            private final A a;
                            private final B withInit = new B();
                            private C notFinal;
                            private static final C CONST = null;
                        }
                        """);

        Function<Edge, String> desc = e -> e.attrString("via") + ":" + e.attrString("field") + ":" + e.attrString("qualifier");
        String a = type("com.a.A");
        String b = type("com.a.B");
        String c = type("com.a.C");
        assertThat(Fx.edges(g, type("com.a.FieldInject"), EdgeKind.INJECTS, a)).extracting(desc).containsExactly("field:a:null");
        assertThat(Fx.edges(g, type("com.a.FieldInject"), EdgeKind.INJECTS, b)).extracting(desc).containsExactly("field:b:specialB");
        assertThat(Fx.edges(g, type("com.a.FieldInject"), EdgeKind.INJECTS, c)).extracting(desc).containsExactly("field:cs:null");
        assertThat(Fx.edges(g, type("com.a.CtorInject"), EdgeKind.INJECTS, a)).extracting(desc).containsExactly("constructor:first:null");
        assertThat(Fx.edges(g, type("com.a.CtorInject"), EdgeKind.INJECTS, b)).extracting(desc).containsExactly("constructor:b:null");
        assertThat(Fx.edges(g, type("com.a.SetterInject"), EdgeKind.INJECTS, a)).extracting(desc).containsExactly("setter:session:batchA");
        assertThat(targets(g, type("com.a.SetterInject"), EdgeKind.INJECTS)).containsExactly(a);
        assertThat(Fx.edges(g, type("com.a.ImplicitInject"), EdgeKind.INJECTS, a)).extracting(desc).containsExactly("constructor:a:null");
        assertThat(targets(g, type("com.a.ImplicitInject"), EdgeKind.INJECTS)).containsExactly(a);
        assertThat(targets(g, type("com.a.NotBean"), EdgeKind.INJECTS)).isEmpty();
        assertThat(Fx.edges(g, type("com.a.LombokInject"), EdgeKind.INJECTS, a)).extracting(desc).containsExactly("constructor:a:null");
        assertThat(targets(g, type("com.a.LombokInject"), EdgeKind.INJECTS)).containsExactly(a);
        assertThat(edge(g, type("com.a.FieldInject"), EdgeKind.INJECTS, a).location().startLine()).isEqualTo(6);
    }

    @Test
    @DisplayName("F-004-AC5 인터페이스 주입의 구현 빈 후보를 candidates에 남기고 qualifier·필드명이 맞으면 하나만 남긴다")
    void injectionCandidates() {
        GraphModel g = Fx.analyze(
                "src/com/a/DiscountPolicy.java", "package com.a; public interface DiscountPolicy { }",
                "src/com/a/DefaultDiscountPolicy.java", "package com.a; import org.springframework.stereotype.Component; @Component public class DefaultDiscountPolicy implements DiscountPolicy { }",
                "src/com/a/VipDiscountPolicy.java", "package com.a; import org.springframework.stereotype.Component; @Component public class VipDiscountPolicy extends DefaultDiscountPolicy { }",
                "src/com/a/ManualPolicy.java", "package com.a; public class ManualPolicy implements DiscountPolicy { }",
                "src/com/a/PayService.java", "package com.a; public interface PayService { }",
                "src/com/a/PayServiceImpl.java", "package com.a; import org.springframework.stereotype.Service; @Service public class PayServiceImpl implements PayService { }",
                "src/com/a/Client.java", """
                        package com.a;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.beans.factory.annotation.Qualifier;
                        public class Client {
                            @Autowired private DiscountPolicy policy;
                            @Autowired @Qualifier("vipDiscountPolicy") private DiscountPolicy vip;
                            @Autowired private DiscountPolicy defaultDiscountPolicy;
                            @Autowired @Qualifier("xmlOnlyBean") private DiscountPolicy unknown;
                            @Autowired private PayService payService;
                            @Autowired private PayServiceImpl concrete;
                        }
                        """);

        String policy = type("com.a.DiscountPolicy");
        List<Edge> edges = Fx.edges(g, type("com.a.Client"), EdgeKind.INJECTS, policy);
        Function<String, List<String>> candidates = field -> edges.stream().filter(e -> field.equals(e.attrString("field")))
                .findFirst().orElseThrow().attrList("candidates");
        assertThat(candidates.apply("policy")).containsExactlyInAnyOrder(type("com.a.DefaultDiscountPolicy"), type("com.a.VipDiscountPolicy"));
        assertThat(candidates.apply("vip")).containsExactly(type("com.a.VipDiscountPolicy"));
        assertThat(candidates.apply("defaultDiscountPolicy")).containsExactly(type("com.a.DefaultDiscountPolicy"));
        assertThat(candidates.apply("unknown")).isEmpty();
        assertThat(edge(g, type("com.a.Client"), EdgeKind.INJECTS, type("com.a.PayService")).attrList("candidates"))
                .containsExactly(type("com.a.PayServiceImpl"));
        assertThat(edge(g, type("com.a.Client"), EdgeKind.INJECTS, type("com.a.PayServiceImpl")).attrs()).doesNotContainKey("candidates");
    }

    private static final String[] ENDPOINTS = {
            "src/com/a/Paths.java", "package com.a; public class Paths { public static final String API = \"/api\"; }",
            "src/com/a/OrderController.java", """
                    package com.a;
                    import org.springframework.stereotype.Controller;
                    import org.springframework.web.bind.annotation.*;
                    @Controller
                    @RequestMapping("/order")
                    public class OrderController {
                        @GetMapping("/list")
                        public String list() { return "order/list"; }
                        @RequestMapping(value = {"/a", "/b"}, method = {RequestMethod.GET, RequestMethod.POST})
                        public String multi() { return "order/multi"; }
                        @RequestMapping("/x.do")
                        public String any() { return "order/x"; }
                        @PostMapping(Paths.API + "/save")
                        public String constant() { return "redirect:/order/list"; }
                        @DeleteMapping
                        public void root() { }
                    }
                    """,
            "src/com/a/PlainController.java", """
                    package com.a;
                    import org.springframework.web.bind.annotation.PostMapping;
                    import org.springframework.web.bind.annotation.PutMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class PlainController {
                        @PostMapping("save")
                        public String save() { return "ok"; }
                        @PutMapping(path = "/items/{id}")
                        public void update(String id) { }
                    }
                    """,
    };

    private static void assertEndpoints(GraphModel g) {
        String list = m("com.a.OrderController", "list");
        Node get = node(g, Ids.httpEndpoint("GET", "/order/list"));
        assertThat(get.id()).isEqualTo("http:endpoint:GET /order/list");
        assertThat(get.kind()).isEqualTo(NodeKind.HTTP_ENDPOINT);
        assertThat(get.attrString("httpMethod")).isEqualTo("GET");
        assertThat(get.attrString("path")).isEqualTo("/order/list");
        assertThat(get.location().startLine()).isEqualTo(7);
        assertThat(edge(g, get.id(), EdgeKind.ROUTES_TO, list).confidence()).isEqualTo(Confidence.RESOLVED);

        String multi = m("com.a.OrderController", "multi");
        assertThat(Fx.sources(g, multi, EdgeKind.ROUTES_TO)).containsExactlyInAnyOrder(
                "http:endpoint:GET /order/a", "http:endpoint:POST /order/a", "http:endpoint:GET /order/b", "http:endpoint:POST /order/b");
        assertThat(Fx.sources(g, m("com.a.OrderController", "any"), EdgeKind.ROUTES_TO)).containsExactly("http:endpoint:ANY /order/x.do");
        assertThat(node(g, "http:endpoint:ANY /order/x.do").attrString("httpMethod")).isEqualTo("ANY");
        assertThat(Fx.sources(g, m("com.a.OrderController", "constant"), EdgeKind.ROUTES_TO)).containsExactly("http:endpoint:POST /order/api/save");
        assertThat(Fx.sources(g, m("com.a.OrderController", "root"), EdgeKind.ROUTES_TO)).containsExactly("http:endpoint:DELETE /order");
        assertThat(Fx.sources(g, m("com.a.PlainController", "save"), EdgeKind.ROUTES_TO)).containsExactly("http:endpoint:POST /save");
        assertThat(Fx.sources(g, m("com.a.PlainController", "update", "java.lang.String"), EdgeKind.ROUTES_TO)).containsExactly("http:endpoint:PUT /items/{id}");
        assertThat(g.nodes(NodeKind.HTTP_ENDPOINT)).hasSize(10);
    }

    @Test
    @DisplayName("F-004-AC6 클래스와 메서드의 매핑을 조합해 경로×HTTP 메서드마다 엔드포인트와 ROUTES_TO를 만든다 (jar 없음)")
    void endpointsWithoutJar() {
        assertEndpoints(Fx.analyze(ENDPOINTS));
    }

    @Test
    @DisplayName("F-004-AC6 jar가 있을 때도 같은 엔드포인트가 나온다")
    void endpointsWithJar() {
        assertEndpoints(Fx.analyzeWithStubs(ENDPOINTS));
    }

    @Test
    @DisplayName("F-004-AC6 경로가 상수가 아니면 미해결 ROUTES_TO를 남긴다")
    void dynamicEndpointPath() {
        GraphModel g = Fx.analyze("src/com/a/C.java", """
                package com.a;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.GetMapping;
                @Controller
                public class C {
                    static String base() { return "/x"; }
                    static final String DYNAMIC = base();
                    @GetMapping(DYNAMIC)
                    public String h() { return "v"; }
                }
                """);

        Edge e = edge(g, Ids.unresolved("http-endpoint", "DYNAMIC"), EdgeKind.ROUTES_TO, m("com.a.C", "h"));
        assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(g.nodes(NodeKind.HTTP_ENDPOINT)).isEmpty();
    }

    @Test
    @DisplayName("F-004-AC7 핸들러의 뷰 이름을 viewNames에 남기고 @ResponseBody·@RestController 메서드는 뺀다")
    void viewNames() {
        GraphModel g = Fx.analyze(
                "src/com/a/ViewController.java", """
                        package com.a;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.*;
                        import org.springframework.web.servlet.ModelAndView;
                        @Controller
                        public class ViewController {
                            static final String PREFIX = "order/";
                            @GetMapping("/list") public String list() { return PREFIX + "list"; }
                            @PostMapping("/save") public String save() { return "redirect:/order/list"; }
                            @GetMapping("/pick") public String pick(boolean ok) { return ok ? "order/ok" : "order/fail"; }
                            @GetMapping("/detail") public ModelAndView detail() { return new ModelAndView("order/detail"); }
                            @GetMapping("/form") public ModelAndView form() {
                                ModelAndView mav = new ModelAndView();
                                mav.setViewName("order/form");
                                return mav;
                            }
                            @GetMapping("/popup") public String popup(String type) { return "order/popup_" + type; }
                            @GetMapping("/dyn") public String dynamic(String view) { return view; }
                            @GetMapping("/var") public String variable(boolean b) {
                                String view = "order/a";
                                if (b) { view = "order/b"; }
                                return view;
                            }
                            @GetMapping("/json") @ResponseBody public String json() { return "not-a-view"; }
                            @GetMapping("/lambda") public String lambda() {
                                java.util.function.Supplier<String> s = () -> { return "inner"; };
                                return "order/lambda";
                            }
                            public String helper() { return "not-handler"; }
                        }
                        """,
                "src/com/a/Api.java", """
                        package com.a;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class Api { @GetMapping("/api") public String api() { return "text"; } }
                        """);

        Function<String, List<String>> views = name -> node(g, m("com.a.ViewController", name)).attrList("viewNames");
        assertThat(views.apply("list")).containsExactly("order/list");
        assertThat(views.apply("save")).containsExactly("redirect:/order/list");
        assertThat(node(g, m("com.a.ViewController", "pick", "boolean")).attrList("viewNames")).containsExactly("order/ok", "order/fail");
        assertThat(views.apply("detail")).containsExactly("order/detail");
        assertThat(views.apply("form")).containsExactly("order/form");
        assertThat(node(g, m("com.a.ViewController", "variable", "boolean")).attrList("viewNames")).containsExactly("order/a", "order/b");
        assertThat(views.apply("lambda")).containsExactly("order/lambda");
        // 정적으로 알 수 없는 부분은 {*}
        assertThat(node(g, m("com.a.ViewController", "popup", "java.lang.String")).attrList("viewNames")).containsExactly("order/popup_{*}");
        // 전부 동적이면 viewNames에 넣지 않고 미해결 RENDERS를 남긴다
        String dynamic = m("com.a.ViewController", "dynamic", "java.lang.String");
        assertThat(node(g, dynamic).attrs()).doesNotContainKey("viewNames");
        assertThat(edge(g, dynamic, EdgeKind.RENDERS, Ids.unresolved("view", "view")).confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(node(g, m("com.a.ViewController", "json")).attrs()).doesNotContainKey("viewNames");
        assertThat(node(g, m("com.a.ViewController", "helper")).attrs()).doesNotContainKey("viewNames");
        assertThat(node(g, m("com.a.Api", "api")).attrs()).doesNotContainKey("viewNames");
    }

    private static final String[] VIEW_RESOLVER = {
            "src/com/a/WebConfig.java", """
                    package com.a;
                    import org.springframework.context.annotation.Bean;
                    import org.springframework.context.annotation.Configuration;
                    import org.springframework.web.servlet.ViewResolver;
                    import org.springframework.web.servlet.view.InternalResourceViewResolver;
                    @Configuration
                    public class WebConfig {
                        @Bean
                        public ViewResolver viewResolver() {
                            InternalResourceViewResolver resolver = new InternalResourceViewResolver();
                            resolver.setPrefix("/WEB-INF/views/");
                            resolver.setSuffix(".jsp");
                            resolver.setOrder(1);
                            return resolver;
                        }
                    }
                    """,
    };

    private static void assertViewResolver(GraphModel g) {
        Node bean = node(g, Ids.springBean("viewResolver"));
        assertThat(bean.attrString("class")).isEqualTo("org.springframework.web.servlet.view.InternalResourceViewResolver");
        assertThat(bean.attrString("beanType")).isEqualTo("org.springframework.web.servlet.ViewResolver");
        assertThat(bean.attrString("property.prefix")).isEqualTo("/WEB-INF/views/");
        assertThat(bean.attrString("property.suffix")).isEqualTo(".jsp");
        assertThat(bean.attrString("property.order")).isEqualTo("1");
    }

    @Test
    @DisplayName("F-004-AC8 뷰 리졸버 @Bean의 setPrefix/setSuffix 상수를 property.prefix/suffix로 남긴다 (jar 없음)")
    void viewResolverWithoutJar() {
        assertViewResolver(Fx.analyze(VIEW_RESOLVER));
    }

    @Test
    @DisplayName("F-004-AC8 jar가 있을 때도 같은 뷰 리졸버 프로퍼티가 나온다")
    void viewResolverWithJar() {
        assertViewResolver(Fx.analyzeWithStubs(VIEW_RESOLVER));
    }

    @Test
    @DisplayName("F-004-AC13 Lombok 생성자 주입은 합성 생성자와 같은 필드를 쓴다: @AllArgsConstructor의 초기화된 비final 필드, 생성자가 둘이면 주입 없음, @FieldDefaults(makeFinal)")
    void lombokConstructorInjection() {
        GraphModel g = Fx.analyze(
                "src/com/a/A.java", "package com.a; public class A { }",
                "src/com/a/B.java", "package com.a; public class B { }",
                "src/com/a/C.java", "package com.a; public class C { }",
                "src/com/a/D.java", "package com.a; public class D { }",
                "src/com/a/AllArgs.java", """
                        package com.a;
                        import lombok.AllArgsConstructor;
                        import org.springframework.stereotype.Service;
                        @Service
                        @AllArgsConstructor
                        public class AllArgs {
                            private A a;
                            private B b = new B();
                            private static C c;
                            private final D d = new D();
                        }
                        """,
                "src/com/a/TwoCtors.java", """
                        package com.a;
                        import lombok.AllArgsConstructor;
                        import lombok.NoArgsConstructor;
                        import org.springframework.stereotype.Service;
                        @Service
                        @NoArgsConstructor
                        @AllArgsConstructor
                        public class TwoCtors {
                            private A a;
                        }
                        """,
                "src/com/a/MadeFinal.java", """
                        package com.a;
                        import lombok.AccessLevel;
                        import lombok.RequiredArgsConstructor;
                        import lombok.experimental.FieldDefaults;
                        import org.springframework.stereotype.Service;
                        @Service
                        @RequiredArgsConstructor
                        @FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
                        public class MadeFinal {
                            A a;
                        }
                        """);

        assertThat(targets(g, type("com.a.AllArgs"), EdgeKind.INJECTS)).containsExactly(type("com.a.A"), type("com.a.B"));
        assertThat(edge(g, type("com.a.AllArgs"), EdgeKind.INJECTS, type("com.a.B")).attrString("via")).isEqualTo("constructor");
        // 주입에 쓰는 생성자는 합성 생성자 노드와 같다
        assertThat(node(g, Ids.javaMethod("com.a.AllArgs", "<init>", "com.a.A", "com.a.B")).attrString("lombok")).isEqualTo("AllArgsConstructor");
        assertThat(targets(g, type("com.a.TwoCtors"), EdgeKind.INJECTS)).isEmpty();
        assertThat(targets(g, type("com.a.MadeFinal"), EdgeKind.INJECTS)).containsExactly(type("com.a.A"));
        assertThat(node(g, Ids.javaMethod("com.a.MadeFinal", "<init>", "com.a.A")).attrString("lombok")).isEqualTo("RequiredArgsConstructor");
    }

    private static final java.util.Map<String, String> DISPATCH = Fx.files(
            "src/com/a/Other.java", "package com.a; public class Other { void sendRedirect(String url) { } void forward(Object a, Object b) { } }",
            "src/com/a/Flow.java", """
                    package com.a;
                    import javax.servlet.RequestDispatcher;
                    import javax.servlet.ServletContext;
                    import javax.servlet.http.HttpServletRequest;
                    import javax.servlet.http.HttpServletResponse;
                    public class Flow {
                        static final String LOGIN = "/login.do";
                        void constant(HttpServletResponse response) throws Exception { response.sendRedirect("/main.do"); }
                        void context(HttpServletRequest request, HttpServletResponse response) throws Exception {
                            response.sendRedirect(request.getContextPath() + "/main.do");
                        }
                        void contextVariable(HttpServletRequest req, HttpServletResponse res) throws Exception {
                            String ctx = req.getContextPath();
                            res.sendRedirect(ctx + LOGIN + "?from=" + req.getRequestURI());
                        }
                        void partial(HttpServletResponse response, long id) throws Exception {
                            response.sendRedirect("/order/" + id + "/detail.do");
                        }
                        void branches(HttpServletResponse response, boolean ok) throws Exception {
                            response.sendRedirect(ok ? "/ok.do" : "/fail.do");
                            String target = "/a.do";
                            if (ok) { target = "/b.do"; }
                            response.sendRedirect(target);
                        }
                        void root(HttpServletRequest request, HttpServletResponse response) throws Exception {
                            response.sendRedirect(request.getContextPath());
                        }
                        void forward(HttpServletRequest request, HttpServletResponse response) throws Exception {
                            request.getRequestDispatcher("/order/list.do").forward(request, response);
                            request.getRequestDispatcher("/common/header.do").include(request, response);
                        }
                        void dispatcherVariable(ServletContext context, HttpServletRequest request, HttpServletResponse response) throws Exception {
                            RequestDispatcher rd = context.getRequestDispatcher("/WEB-INF/views/main.jsp");
                            rd.forward(request, response);
                        }
                        void dynamic(HttpServletRequest request, HttpServletResponse response, String url, RequestDispatcher given) throws Exception {
                            response.sendRedirect(url);
                            request.getRequestDispatcher(request.getParameter("next")).forward(request, response);
                            given.include(request, response);
                        }
                        void notServlet(Other other) { other.sendRedirect("/x.do"); other.forward("/y.do", null); }
                    }
                    """);

    private static void assertDispatch(GraphModel g) {
        String request = "javax.servlet.http.HttpServletRequest";
        String response = "javax.servlet.http.HttpServletResponse";
        Function<String, Node> method = id -> node(g, id);
        // 문자열 상수, 컨텍스트 경로 접두(직접 호출·지역 변수), 쿼리 문자열 제거
        assertThat(method.apply(m("com.a.Flow", "constant", response)).attrList("redirects")).containsExactly("/main.do");
        assertThat(method.apply(m("com.a.Flow", "context", request, response)).attrList("redirects")).containsExactly("/main.do");
        assertThat(method.apply(m("com.a.Flow", "contextVariable", request, response)).attrList("redirects")).containsExactly("/login.do");
        // 알 수 없는 부분은 {*}, 분기와 지역 변수의 값들
        assertThat(method.apply(m("com.a.Flow", "partial", response, "long")).attrList("redirects")).containsExactly("/order/{*}/detail.do");
        assertThat(method.apply(m("com.a.Flow", "branches", response, "boolean")).attrList("redirects"))
                .containsExactly("/ok.do", "/fail.do", "/a.do", "/b.do");
        assertThat(method.apply(m("com.a.Flow", "root", request, response)).attrList("redirects")).containsExactly("/");
        // forward와 include는 forwards에
        Node forward = method.apply(m("com.a.Flow", "forward", request, response));
        assertThat(forward.attrList("forwards")).containsExactly("/order/list.do", "/common/header.do");
        assertThat(forward.attrs()).doesNotContainKey("redirects");
        assertThat(method.apply(m("com.a.Flow", "dispatcherVariable", "javax.servlet.ServletContext", request, response)).attrList("forwards"))
                .containsExactly("/WEB-INF/views/main.jsp");
        // 서블릿 타입이 아니면 보지 않는다
        Node other = method.apply(m("com.a.Flow", "notServlet", "com.a.Other"));
        assertThat(other.attrs()).doesNotContainKeys("redirects", "forwards");
        assertThat(targets(g, other.id(), EdgeKind.CALLS)).noneMatch(t -> t.startsWith("unresolved:"));
    }

    @Test
    @DisplayName("F-004-AC14 sendRedirect와 getRequestDispatcher(..).forward/include의 경로를 메서드 attrs.redirects·forwards에 남긴다 (jar 없음, jar 있음)")
    void javaRedirectsAndForwards() {
        assertDispatch(Fx.analyze(io.codeatlas.core.spi.AnalysisOptions.defaults(), DISPATCH));

        java.nio.file.Path servlet = Fx.compile(Fx.files(
                "javax/servlet/RequestDispatcher.java", "package javax.servlet; public interface RequestDispatcher { void forward(Object req, Object res); void include(Object req, Object res); }",
                "javax/servlet/ServletContext.java", "package javax.servlet; public interface ServletContext { RequestDispatcher getRequestDispatcher(String path); }",
                "javax/servlet/http/HttpServletRequest.java", "package javax.servlet.http; public interface HttpServletRequest { String getContextPath(); String getRequestURI(); String getParameter(String n); javax.servlet.RequestDispatcher getRequestDispatcher(String path); }",
                "javax/servlet/http/HttpServletResponse.java", "package javax.servlet.http; public interface HttpServletResponse { void sendRedirect(String location) throws java.io.IOException; }",
                "javax/servlet/http/HttpServletResponseWrapper.java", "package javax.servlet.http; public class HttpServletResponseWrapper implements HttpServletResponse { public void sendRedirect(String location) { } }"));
        java.util.Map<String, String> files = new java.util.LinkedHashMap<>(DISPATCH);
        // 상위 타입이 HttpServletResponse인 타입도 응답 객체다(상위 타입은 jar가 있을 때만 알 수 있다)
        files.put("src/com/a/Wrapped.java", """
                package com.a;
                import javax.servlet.http.HttpServletResponseWrapper;
                public class Wrapped extends HttpServletResponseWrapper {
                    void go(Wrapped self) { self.sendRedirect("/wrapped.do"); }
                }
                """);
        GraphModel g = Fx.analyze(io.codeatlas.core.spi.AnalysisOptions.builder().classpath(servlet).build(), files);
        assertDispatch(g);
        assertThat(node(g, m("com.a.Wrapped", "go", "com.a.Wrapped")).attrList("redirects")).containsExactly("/wrapped.do");
    }

    @Test
    @DisplayName("F-004-AC15 redirect·forward 경로를 전혀 알 수 없으면 attrs 없이 미해결 엣지(CALLS, 분류 http-endpoint)를 남긴다")
    void dynamicRedirectIsUnresolved() {
        GraphModel g = Fx.analyze(io.codeatlas.core.spi.AnalysisOptions.defaults(), DISPATCH);

        String dynamic = m("com.a.Flow", "dynamic", "javax.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletResponse",
                "java.lang.String", "javax.servlet.RequestDispatcher");
        assertThat(node(g, dynamic).attrs()).doesNotContainKeys("redirects", "forwards");
        Edge redirect = edge(g, dynamic, EdgeKind.CALLS, Ids.unresolved("http-endpoint", "url"));
        assertThat(redirect.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(redirect.attrString("callType")).isEqualTo("redirect");
        assertThat(redirect.location().startLine()).isEqualTo(37);
        assertThat(node(g, redirect.to()).attrString("category")).isEqualTo("http-endpoint");
        Edge forward = edge(g, dynamic, EdgeKind.CALLS, Ids.unresolved("http-endpoint", "request.getParameter(\"next\")"));
        assertThat(forward.attrString("callType")).isEqualTo("forward");
        // 어디서 온 것인지 모르는 RequestDispatcher
        assertThat(edge(g, dynamic, EdgeKind.CALLS, Ids.unresolved("http-endpoint", "given")).attrString("callType")).isEqualTo("forward");
        assertThat(g.diagnostics()).filteredOn(d -> d.code().equals("UNRESOLVED_REFERENCE") && d.message().contains("http-endpoint")).hasSize(3);
    }
}
