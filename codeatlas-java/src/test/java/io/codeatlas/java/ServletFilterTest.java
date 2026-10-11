package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.spi.AnalysisOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.hasEdge;
import static io.codeatlas.java.Fx.node;
import static org.assertj.core.api.Assertions.assertThat;

/** F-004-R17, R18: 서블릿 필터·인터셉터·인증 핸들러의 redirect·forward와 진입점 표시(stereotype). */
class ServletFilterTest {

    private static final String REQ = "javax.servlet.http.HttpServletRequest";
    private static final String RES = "javax.servlet.http.HttpServletResponse";

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    private static String type(String name) {
        return Ids.javaType(name);
    }

    private static final Map<String, String> SOURCES = Fx.files(
            "src/com/a/LoginFilter.java", """
                    package com.a;
                    import javax.servlet.Filter;
                    import javax.servlet.FilterChain;
                    import javax.servlet.ServletRequest;
                    import javax.servlet.ServletResponse;
                    import javax.servlet.http.HttpServletRequest;
                    import javax.servlet.http.HttpServletResponse;
                    public class LoginFilter implements Filter {
                        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws Exception {
                            HttpServletRequest request = (HttpServletRequest) req;
                            HttpServletResponse response = (HttpServletResponse) res;
                            if (request.getParameter("a") != null) {
                                response.sendRedirect(request.getContextPath() + "/login.do");
                                return;
                            }
                            if (request.getParameter("b") != null) {
                                ((HttpServletResponse) res).sendRedirect(request.getContextPath() + "/denied.do?reason=1");
                                return;
                            }
                            if (request.getParameter("c") != null) {
                                req.getRequestDispatcher("/error/403.do").forward(req, res);
                                return;
                            }
                            chain.doFilter(req, res);
                        }
                    }
                    """,
            "src/com/a/TokenFilter.java", """
                    package com.a;
                    import javax.servlet.FilterChain;
                    import javax.servlet.http.HttpServletRequest;
                    import javax.servlet.http.HttpServletResponse;
                    import org.springframework.web.filter.OncePerRequestFilter;
                    public class TokenFilter extends OncePerRequestFilter {
                        static final String LOGIN_PATH = "/uat/uia/egovLoginUsr.do";
                        @Override
                        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws Exception {
                            if (request.getHeader("X") == null) {
                                response.sendRedirect(response.encodeRedirectURL(request.getContextPath() + "/expired.do"));
                                return;
                            }
                            request.getRequestDispatcher(LOGIN_PATH).forward(request, response);
                        }
                    }
                    """,
            "src/com/a/BaseFilter.java", "package com.a; public abstract class BaseFilter implements jakarta.servlet.Filter { }",
            "src/com/a/SubFilter.java", "package com.a; public class SubFilter extends BaseFilter { }",
            "src/com/a/WildFilter.java", "package com.a; import javax.servlet.*; public class WildFilter implements Filter { }",
            "src/com/a/ShiroFilter.java", """
                    package com.a;
                    import javax.servlet.ServletRequest;
                    import javax.servlet.ServletResponse;
                    import org.apache.shiro.web.filter.authc.AuthenticationFilter;
                    import org.apache.shiro.web.util.WebUtils;
                    public class ShiroFilter extends AuthenticationFilter {
                        protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
                            if (request.getParameter("sso") != null) {
                                WebUtils.toHttp(response).sendRedirect("/sso/login");
                                return false;
                            }
                            WebUtils.issueRedirect(request, response, "/login?forceLogout=1");
                            return false;
                        }
                        protected boolean dynamic(ServletRequest request, ServletResponse response, String url) throws Exception {
                            WebUtils.issueRedirect(request, response, url, null, true);
                            return false;
                        }
                        protected boolean queryOnly(ServletRequest request, ServletResponse response) throws Exception {
                            String loginUrl = getLoginUrl() + (getLoginUrl().contains("?") ? "&" : "?") + "forceLogout=1";
                            WebUtils.issueRedirect(request, response, loginUrl);
                            return false;
                        }
                    }
                    """,
            "src/com/a/AuthInterceptor.java", """
                    package com.a;
                    import javax.servlet.http.HttpServletRequest;
                    import javax.servlet.http.HttpServletResponse;
                    import org.springframework.web.servlet.HandlerInterceptor;
                    public class AuthInterceptor implements HandlerInterceptor {
                        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                            response.sendRedirect(request.getContextPath() + "/main.do");
                            return false;
                        }
                    }
                    """,
            "src/com/a/OldInterceptor.java", """
                    package com.a;
                    import org.springframework.stereotype.Component;
                    import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;
                    @Component
                    public class OldInterceptor extends HandlerInterceptorAdapter { }
                    """,
            "src/com/a/LoginSuccessHandler.java", """
                    package com.a;
                    import javax.servlet.http.HttpServletRequest;
                    import javax.servlet.http.HttpServletResponse;
                    import org.springframework.security.core.Authentication;
                    import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
                    public class LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
                        @Override
                        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth) throws Exception {
                            getRedirectStrategy().sendRedirect(request, response, "/home.do");
                        }
                    }
                    """,
            "src/com/a/LoginFailureHandler.java", """
                    package com.a;
                    import javax.servlet.http.HttpServletRequest;
                    import javax.servlet.http.HttpServletResponse;
                    import org.springframework.security.web.DefaultRedirectStrategy;
                    import org.springframework.security.web.RedirectStrategy;
                    import org.springframework.security.web.authentication.AuthenticationFailureHandler;
                    public class LoginFailureHandler implements AuthenticationFailureHandler {
                        private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
                        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, Exception e) throws Exception {
                            if (e == null) {
                                response.sendRedirect("/login.do?error=1");
                                return;
                            }
                            redirectStrategy.sendRedirect(request, response, request.getParameter("next"));
                        }
                    }
                    """,
            "src/com/a/FormFilter.java", """
                    package com.a;
                    import org.springframework.stereotype.Service;
                    @Service
                    public class FormFilter extends org.apache.shiro.web.filter.authc.FormAuthenticationFilter { }
                    """,
            "src/com/a/CacheFilter.java", "package com.a; import net.sf.ehcache.constructs.web.filter.SimplePageCachingFilter; public class CacheFilter extends SimplePageCachingFilter { }",
            "src/com/a/ScanFilter.java", "package com.a; import org.springframework.core.type.filter.TypeFilter; public class ScanFilter implements TypeFilter { }",
            "src/com/a/FilesFilter.java", "package com.a; public class FilesFilter implements java.io.FileFilter { public boolean accept(java.io.File f) { return true; } }",
            "src/com/a/WebController.java", """
                    package com.a;
                    import org.springframework.stereotype.Controller;
                    import org.springframework.web.servlet.HandlerInterceptor;
                    @Controller
                    public class WebController implements HandlerInterceptor { }
                    """,
            "src/com/own/Filter.java", "package com.own; public interface Filter { }",
            "src/com/a/NameFilter.java", "package com.a; import com.own.Filter; public class NameFilter implements Filter { }",
            "src/com/a/Other.java", """
                    package com.a;
                    public class Other {
                        void sendRedirect(Object a, Object b, String url) { }
                        void run(Other strategy) { strategy.sendRedirect(null, null, "/not-a-redirect.do"); }
                    }
                    """);

    private static void assertDispatch(GraphModel g) {
        Node doFilter = node(g, m("com.a.LoginFilter", "doFilter", "javax.servlet.ServletRequest", "javax.servlet.ServletResponse", "javax.servlet.FilterChain"));
        // 형 변환한 지역 변수, 형 변환한 식, 쿼리 문자열 제거
        assertThat(doFilter.attrList("redirects")).containsExactly("/login.do", "/denied.do");
        assertThat(doFilter.attrList("forwards")).containsExactly("/error/403.do");
        Node internal = node(g, m("com.a.TokenFilter", "doFilterInternal", REQ, RES, "javax.servlet.FilterChain"));
        // encodeRedirectURL로 감싼 경로, 상수로 준 forward 경로
        assertThat(internal.attrList("redirects")).containsExactly("/expired.do");
        assertThat(internal.attrList("forwards")).containsExactly("/uat/uia/egovLoginUsr.do");
        assertThat(node(g, m("com.a.AuthInterceptor", "preHandle", REQ, RES, "java.lang.Object")).attrList("redirects")).containsExactly("/main.do");
        // Spring Security의 RedirectStrategy.sendRedirect(request, response, url)
        assertThat(node(g, m("com.a.LoginSuccessHandler", "onAuthenticationSuccess", REQ, RES, "org.springframework.security.core.Authentication"))
                .attrList("redirects")).containsExactly("/home.do");
        String failure = m("com.a.LoginFailureHandler", "onAuthenticationFailure", REQ, RES, "java.lang.Exception");
        assertThat(node(g, failure).attrList("redirects")).containsExactly("/login.do");
        Edge dynamic = edge(g, failure, EdgeKind.CALLS, Ids.unresolved("http-endpoint", "request.getParameter(\"next\")"));
        assertThat(dynamic.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(dynamic.attrString("callType")).isEqualTo("redirect");
        // 이름만 같은 다른 타입의 sendRedirect는 보지 않는다
        assertThat(node(g, m("com.a.Other", "run", "com.a.Other")).attrs()).doesNotContainKeys("redirects", "forwards");
    }

    @Test
    @DisplayName("F-004-AC19 필터(doFilter, doFilterInternal)·인터셉터·인증 핸들러의 sendRedirect·forward 경로가 redirects·forwards에 남는다 (jar 없음, 서블릿 jar 있음)")
    void filterRedirectsAndForwards() {
        assertDispatch(Fx.analyze(AnalysisOptions.defaults(), SOURCES));

        Path servlet = Fx.compile(Fx.files(
                "javax/servlet/ServletRequest.java", "package javax.servlet; public interface ServletRequest { String getParameter(String n); RequestDispatcher getRequestDispatcher(String path); }",
                "javax/servlet/ServletResponse.java", "package javax.servlet; public interface ServletResponse { }",
                "javax/servlet/FilterChain.java", "package javax.servlet; public interface FilterChain { void doFilter(ServletRequest req, ServletResponse res) throws Exception; }",
                "javax/servlet/Filter.java", "package javax.servlet; public interface Filter { void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws Exception; }",
                "javax/servlet/RequestDispatcher.java", "package javax.servlet; public interface RequestDispatcher { void forward(ServletRequest req, ServletResponse res); void include(ServletRequest req, ServletResponse res); }",
                "javax/servlet/http/HttpServletRequest.java", "package javax.servlet.http; public interface HttpServletRequest extends javax.servlet.ServletRequest { String getContextPath(); String getHeader(String n); }",
                "javax/servlet/http/HttpServletResponse.java", "package javax.servlet.http; public interface HttpServletResponse extends javax.servlet.ServletResponse { void sendRedirect(String location) throws java.io.IOException; String encodeRedirectURL(String url); }"));
        GraphModel g = Fx.analyze(AnalysisOptions.builder().classpath(servlet).build(), SOURCES);
        assertDispatch(g);
        assertThat(node(g, type("com.a.LoginFilter")).attrString("stereotype")).isEqualTo("filter");
    }

    @Test
    @DisplayName("F-004-AC19 Shiro의 WebUtils.toHttp(response).sendRedirect(..)와 WebUtils.issueRedirect(request, response, url, ..)도 redirect로 본다")
    void shiroRedirects() {
        GraphModel g = Fx.analyze(AnalysisOptions.defaults(), SOURCES);

        Node denied = node(g, m("com.a.ShiroFilter", "onAccessDenied", "javax.servlet.ServletRequest", "javax.servlet.ServletResponse"));
        assertThat(denied.attrList("redirects")).containsExactly("/sso/login", "/login");
        String dynamic = m("com.a.ShiroFilter", "dynamic", "javax.servlet.ServletRequest", "javax.servlet.ServletResponse", "java.lang.String");
        assertThat(node(g, dynamic).attrs()).doesNotContainKey("redirects");
        assertThat(edge(g, dynamic, EdgeKind.CALLS, Ids.unresolved("http-endpoint", "url")).attrString("callType")).isEqualTo("redirect");
        // 앞이 동적이고 상수가 쿼리 문자열 조각뿐이면 경로를 아는 것이 아니다: redirects에 넣지 않고 미해결로 남긴다
        String queryOnly = m("com.a.ShiroFilter", "queryOnly", "javax.servlet.ServletRequest", "javax.servlet.ServletResponse");
        assertThat(node(g, queryOnly).attrs()).doesNotContainKey("redirects");
        assertThat(Fx.targets(g, queryOnly, EdgeKind.CALLS)).filteredOn(t -> t.startsWith("unresolved:http-endpoint:")).hasSize(1);
    }

    @Test
    @DisplayName("F-004-AC20 서블릿 필터 클래스는 stereotype=filter, HandlerInterceptor 구현은 stereotype=interceptor다(이름만 Filter인 프로젝트 타입은 아니다)")
    void filterAndInterceptorStereotype() {
        GraphModel g = Fx.analyze(AnalysisOptions.defaults(), SOURCES);

        assertThat(node(g, type("com.a.LoginFilter")).attrString("stereotype")).isEqualTo("filter");
        assertThat(node(g, type("com.a.TokenFilter")).attrString("stereotype")).isEqualTo("filter");
        // 프로젝트의 상위 클래스를 거쳐 구현한 필터, jakarta.servlet.Filter
        assertThat(node(g, type("com.a.BaseFilter")).attrString("stereotype")).isEqualTo("filter");
        assertThat(node(g, type("com.a.SubFilter")).attrString("stereotype")).isEqualTo("filter");
        // 와일드카드 import의 Filter도 알려진 타입으로 복원한다
        assertThat(node(g, type("com.a.WildFilter")).attrString("stereotype")).isEqualTo("filter");
        assertThat(hasEdge(g, type("com.a.WildFilter"), EdgeKind.IMPLEMENTS, type("javax.servlet.Filter"))).isTrue();
        assertThat(node(g, type("com.a.ShiroFilter")).attrString("stereotype")).isEqualTo("filter");
        assertThat(node(g, type("com.a.AuthInterceptor")).attrString("stereotype")).isEqualTo("interceptor");
        // @Component가 함께 붙으면 타입은 interceptor, 빈은 그대로 만든다
        Node old = node(g, type("com.a.OldInterceptor"));
        assertThat(old.attrString("stereotype")).isEqualTo("interceptor");
        assertThat(old.attrString("beanName")).isEqualTo("oldInterceptor");
        assertThat(hasEdge(g, old.id(), EdgeKind.DEFINES_BEAN, Ids.springBean("oldInterceptor"))).isTrue();
        assertThat(node(g, Ids.springBean("oldInterceptor")).attrString("stereotype")).isEqualTo("component");
        // 빈 등록용 스테레오타입(@Service)이 붙은 필터도 타입은 filter다. 빈의 stereotype은 그대로다
        Node form = node(g, type("com.a.FormFilter"));
        assertThat(form.attrString("stereotype")).isEqualTo("filter");
        assertThat(node(g, Ids.springBean("formFilter")).attrString("stereotype")).isEqualTo("service");
        // 링커는 stereotype 값이 아니라 이 둘(beanName, DEFINES_BEAN)로 빈인지 판단한다(F-012-R27)
        assertThat(form.attrString("beanName")).isEqualTo("formFilter");
        assertThat(hasEdge(g, form.id(), EdgeKind.DEFINES_BEAN, Ids.springBean("formFilter"))).isTrue();
        // 그 밖의 라이브러리의 웹 필터 기반 클래스(패키지에 web·servlet 조각)
        assertThat(node(g, type("com.a.CacheFilter")).attrString("stereotype")).isEqualTo("filter");
        // 이름만 Filter로 끝나는 다른 것(컴포넌트 스캔 필터, java.io.FileFilter)은 아니다. @Controller는 그대로 둔다
        assertThat(node(g, type("com.a.ScanFilter")).attrString("stereotype")).isNull();
        assertThat(node(g, type("com.a.FilesFilter")).attrString("stereotype")).isNull();
        assertThat(node(g, type("com.a.WebController")).attrString("stereotype")).isEqualTo("controller");
        // 필터가 아닌 것: 프로젝트가 만든 Filter 인터페이스의 구현, 인증 핸들러
        assertThat(node(g, type("com.a.NameFilter")).attrString("stereotype")).isNull();
        assertThat(node(g, type("com.own.Filter")).attrString("stereotype")).isNull();
        assertThat(node(g, type("com.a.LoginSuccessHandler")).attrString("stereotype")).isNull();
        // 빈이 아닌 필터에는 빈 이름을 붙이지 않는다
        assertThat(node(g, type("com.a.LoginFilter")).attrs()).doesNotContainKey("beanName");
    }
}
