package io.codeatlas.java;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.spi.AnalysisOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.hasEdge;
import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-002-R13, F-003-R10: JDK 타입과 라이브러리 타입(javax.servlet 등)의 구분. */
class JdkClassificationTest {

    private static final Map<String, String> SOURCE = Fx.files(
            "src/com/a/C.java", """
                    package com.a;
                    import javax.servlet.http.HttpServletRequest;
                    import javax.sql.DataSource;
                    import jakarta.validation.Validator;
                    public class C {
                        private HttpServletRequest request;
                        private DataSource dataSource;
                        private Validator validator;
                        String run(HttpServletRequest req, javax.naming.Context ctx, javax.persistence.EntityManager em) throws Exception {
                            dataSource.getConnection();
                            javax.servlet.http.HttpSession session = new javax.servlet.http.HttpSessionWrapper();
                            javax.naming.Context other = new javax.naming.InitialContext();
                            return req.getParameter("a");
                        }
                    }
                    """);

    private static String type(String name) {
        return Ids.javaType(name);
    }

    private static Path servletStub() {
        return Fx.compile(Fx.files(
                "javax/servlet/http/HttpServletRequest.java",
                "package javax.servlet.http; public interface HttpServletRequest { String getParameter(String name); }",
                "javax/servlet/http/HttpSession.java", "package javax.servlet.http; public interface HttpSession { }",
                "javax/servlet/http/HttpSessionWrapper.java",
                "package javax.servlet.http; public class HttpSessionWrapper implements HttpSession { public HttpSessionWrapper() { } }",
                "javax/persistence/EntityManager.java", "package javax.persistence; public interface EntityManager { }",
                "jakarta/validation/Validator.java", "package jakarta.validation; public interface Validator { }"));
    }

    @Test
    @DisplayName("F-002-AC12 classpath에 있는 javax.servlet·javax.persistence·jakarta 타입은 라이브러리로, javax.sql·javax.naming은 JDK로 판정한다 (출처 기준)")
    void originDecidesJdk() {
        GraphModel g = Fx.analyze(AnalysisOptions.builder().classpath(servletStub()).build(), SOURCE);
        String c = type("com.a.C");

        Edge request = edge(g, c, EdgeKind.ASSOCIATES, type("javax.servlet.http.HttpServletRequest"));
        assertThat(request.attrString("field")).isEqualTo("request");
        assertThat(node(g, type("javax.servlet.http.HttpServletRequest")).attrBoolean("external")).isTrue();
        assertThat(hasEdge(g, c, EdgeKind.ASSOCIATES, type("jakarta.validation.Validator"))).isTrue();
        assertThat(targets(g, c, EdgeKind.DEPENDS_ON)).contains(type("javax.persistence.EntityManager"),
                type("javax.servlet.http.HttpSession"), type("javax.servlet.http.HttpSessionWrapper"));

        // JDK에 들어 있는 javax 패키지는 기본 옵션에서 기록하지 않는다
        assertThat(hasEdge(g, c, EdgeKind.ASSOCIATES, type("javax.sql.DataSource"))).isFalse();
        assertThat(targets(g, c, EdgeKind.DEPENDS_ON)).doesNotContain(type("javax.naming.Context"), type("javax.naming.InitialContext"),
                type("javax.sql.DataSource"));

        GraphModel all = Fx.analyze(AnalysisOptions.builder().classpath(servletStub()).extra("java.includeJdkTypes", "true").build(), SOURCE);
        assertThat(hasEdge(all, c, EdgeKind.ASSOCIATES, type("javax.sql.DataSource"))).isTrue();
        assertThat(targets(all, c, EdgeKind.DEPENDS_ON)).contains(type("javax.naming.Context"));
    }

    @Test
    @DisplayName("F-002-AC12 classpath에 없어 import로 복원한 javax 타입은 이름 규칙으로 판정한다")
    void nameRuleWhenUnresolved() {
        GraphModel g = Fx.analyze(AnalysisOptions.defaults(), SOURCE);
        String c = type("com.a.C");

        assertThat(hasEdge(g, c, EdgeKind.ASSOCIATES, type("javax.servlet.http.HttpServletRequest"))).isTrue();
        assertThat(hasEdge(g, c, EdgeKind.ASSOCIATES, type("jakarta.validation.Validator"))).isTrue();
        assertThat(targets(g, c, EdgeKind.DEPENDS_ON)).contains(type("javax.persistence.EntityManager"), type("javax.servlet.http.HttpSession"));
        assertThat(hasEdge(g, c, EdgeKind.ASSOCIATES, type("javax.sql.DataSource"))).isFalse();
        assertThat(targets(g, c, EdgeKind.DEPENDS_ON)).doesNotContain(type("javax.naming.Context"));
    }

    @Test
    @DisplayName("F-003-AC13 javax.servlet 타입의 메서드 호출·생성은 기본 옵션(nonJdk)에서 external 호출로 기록하고 javax.sql·javax.naming은 건너뛴다")
    void callsToJavaxLibraries() {
        String run = Ids.javaMethod("com.a.C", "run", "javax.servlet.http.HttpServletRequest", "javax.naming.Context",
                "javax.persistence.EntityManager");
        // jar가 있을 때: 정확한 시그니처의 external 메서드
        GraphModel g = Fx.analyze(AnalysisOptions.builder().classpath(servletStub()).build(), SOURCE);
        String getParameter = Ids.javaMethod("javax.servlet.http.HttpServletRequest", "getParameter", "java.lang.String");
        assertThat(edge(g, run, EdgeKind.CALLS, getParameter).attrString("callType")).isEqualTo("interface");
        assertThat(node(g, getParameter).attrBoolean("external")).isTrue();
        assertThat(hasEdge(g, run, EdgeKind.CALLS, Ids.javaMethod("javax.servlet.http.HttpSessionWrapper", "<init>"))).isTrue();
        assertThat(hasEdge(g, run, EdgeKind.INSTANTIATES, type("javax.servlet.http.HttpSessionWrapper"))).isTrue();
        assertThat(targets(g, run, EdgeKind.CALLS)).noneMatch(t -> t.contains("javax.sql.") || t.contains("javax.naming."));
        assertThat(targets(g, run, EdgeKind.INSTANTIATES)).doesNotContain(type("javax.naming.InitialContext"));
        assertThat(g.diagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("EXTERNAL_CALLS_SKIPPED");
            assertThat(d.message()).contains("JDK 2건");
        });

        // jar가 없을 때: <FQN>#<이름>(?) external 메서드
        GraphModel bare = Fx.analyze(AnalysisOptions.defaults(), SOURCE);
        assertThat(hasEdge(bare, run, EdgeKind.CALLS, Ids.javaMethod("javax.servlet.http.HttpServletRequest", "getParameter", "?"))).isTrue();
        assertThat(hasEdge(bare, run, EdgeKind.INSTANTIATES, type("javax.servlet.http.HttpSessionWrapper"))).isTrue();
        assertThat(targets(bare, run, EdgeKind.CALLS)).noneMatch(t -> t.contains("javax.sql.") || t.contains("javax.naming."));
    }

    @Test
    @DisplayName("F-002-AC13 이름 규칙의 JDK 패키지 목록은 실행 중인 JDK의 시스템 모듈이 내보내는 패키지를 모두 덮고 Java EE·Jakarta 패키지는 덮지 않는다")
    void nameRuleMatchesRunningJdk() {
        Set<String> systemPackages = new TreeSet<>();
        Set<String> exported = new TreeSet<>();
        for (ModuleReference reference : ModuleFinder.ofSystem().findAll()) {
            ModuleDescriptor descriptor = reference.descriptor();
            if (!descriptor.name().startsWith("java.") && !descriptor.name().startsWith("jdk.")) {
                continue;
            }
            systemPackages.addAll(descriptor.packages());
            for (ModuleDescriptor.Exports e : descriptor.exports()) {
                if (!e.isQualified()) {
                    exported.add(e.source());
                }
            }
        }
        assertThat(exported).contains("java.lang", "javax.sql", "org.w3c.dom");
        assertThat(exported).allSatisfy(pkg -> assertThat(JdkTypes.isJdkName(pkg + ".Some")).as(pkg).isTrue());

        // 하드코딩한 목록의 항목마다 실제 JDK 패키지가 있어야 한다(오타 방지)
        assertThat(JdkTypes.NON_JAVA_PREFIX_PACKAGES).allSatisfy(entry ->
                assertThat(systemPackages).as(entry).anyMatch(pkg -> pkg.equals(entry) || pkg.startsWith(entry + ".")));

        assertThat(JdkTypes.isJdkName("java.util.List")).isTrue();
        assertThat(JdkTypes.isJdkName("javax.sql.DataSource")).isTrue();
        assertThat(JdkTypes.isJdkName("javax.xml.XMLConstants")).isTrue();
        assertThat(JdkTypes.isJdkName("javax.xml.parsers.DocumentBuilder")).isTrue();
        assertThat(JdkTypes.isJdkName("javax.transaction.xa.XAResource")).isTrue();
        assertThat(JdkTypes.isJdkName("javax.annotation.processing.Processor")).isTrue();
        assertThat(JdkTypes.isJdkName("javax.security.auth.Subject")).isTrue();
        assertThat(JdkTypes.isJdkName("org.w3c.dom.Document")).isTrue();
        assertThat(JdkTypes.isJdkName("org.xml.sax.InputSource")).isTrue();
        assertThat(JdkTypes.isJdkName("com.sun.net.httpserver.HttpServer")).isTrue();
        assertThat(JdkTypes.isJdkName("sun.misc.Unsafe")).isTrue();
        assertThat(JdkTypes.isJdkName("java.util.Map$Entry")).isTrue();

        for (String library : new String[]{
                "javax.servlet.http.HttpServletRequest", "javax.servlet.Filter", "javax.persistence.Entity", "javax.inject.Inject",
                "javax.validation.Valid", "javax.annotation.Resource", "javax.annotation.PostConstruct", "javax.ws.rs.GET",
                "javax.ejb.Stateless", "javax.jms.Queue", "javax.mail.Session", "javax.transaction.Transactional",
                "javax.transaction.UserTransaction", "javax.el.ELContext", "javax.websocket.Session", "javax.json.JsonObject",
                "javax.batch.api.Batchlet", "javax.enterprise.context.ApplicationScoped", "javax.xml.bind.JAXBContext",
                "javax.xml.ws.Service", "javax.xml.soap.SOAPMessage", "javax.jws.WebService", "javax.activation.DataHandler",
                "javax.security.enterprise.SecurityContext", "javax.cache.Cache",
                "jakarta.servlet.http.HttpServletRequest", "jakarta.persistence.Entity", "jakarta.annotation.Resource",
                "org.w3c.css.sac.Parser", "org.springframework.stereotype.Service", "com.example.Order", "javaxx.Foo", "javafx.scene.Node"}) {
            assertThat(JdkTypes.isJdkName(library)).as(library).isFalse();
        }
    }
}
