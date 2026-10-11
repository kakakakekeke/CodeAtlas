package io.codeatlas.springxml;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WebXmlTest {

    private static final String PATH = "src/main/webapp/WEB-INF/web.xml";

    @Test
    @DisplayName("F-017-AC11 web.xml의 DispatcherServlet 매핑과 contextConfigLocation이 FILE 노드 attrs로 남고 엣지는 없다")
    void dispatcherServletMappings() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee" version="3.1">
                  <context-param>
                    <param-name>contextConfigLocation</param-name>
                    <param-value>
                      classpath*:egovframework/spring/context-*.xml,
                      /WEB-INF/config/context-security.xml
                    </param-value>
                  </context-param>
                  <listener>
                    <listener-class>org.springframework.web.context.ContextLoaderListener</listener-class>
                  </listener>
                  <servlet>
                    <servlet-name>action</servlet-name>
                    <servlet-class>org.springframework.web.servlet.DispatcherServlet</servlet-class>
                    <init-param>
                      <param-name>contextConfigLocation</param-name>
                      <param-value>/WEB-INF/config/egovframework/springmvc/dispatcher-servlet.xml</param-value>
                    </init-param>
                    <load-on-startup>1</load-on-startup>
                  </servlet>
                  <servlet>
                    <servlet-name>report</servlet-name>
                    <servlet-class>com.a.report.ReportServlet</servlet-class>
                  </servlet>
                  <servlet-mapping>
                    <servlet-name>action</servlet-name>
                    <url-pattern>*.do</url-pattern>
                    <url-pattern>/api/*</url-pattern>
                  </servlet-mapping>
                  <servlet-mapping>
                    <servlet-name>report</servlet-name>
                    <url-pattern>/report/*</url-pattern>
                  </servlet-mapping>
                </web-app>
                """;
        GraphModel graph = Fx.run(PATH, xml, "pom.xml", "<project><artifactId>x</artifactId></project>").graph();

        Node file = Fx.node(graph, Ids.file(PATH));
        assertThat(file.attrList("servletMappings")).containsExactly("*.do", "/api/*");
        assertThat(file.attrList("dispatcherServlets")).containsExactly("action");
        assertThat(file.attrList("servletContextConfigLocations"))
                .containsExactly("/WEB-INF/config/egovframework/springmvc/dispatcher-servlet.xml");
        assertThat(file.attrList("contextConfigLocations"))
                .containsExactly("classpath*:egovframework/spring/context-*.xml", "/WEB-INF/config/context-security.xml");
        assertThat(graph.edges()).isEmpty();
        assertThat(graph.diagnostics()).isEmpty();
        // web-app이 아닌 일반 XML은 건드리지 않는다
        assertThat(Fx.node(graph, Ids.file("pom.xml")).attrs()).doesNotContainKey("servletMappings");
    }

    @Test
    @DisplayName("F-017-AC11 DTD 방식(2.3) web.xml도 읽는다")
    void legacyDtdWebXml() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE web-app PUBLIC "-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN" "http://java.sun.com/dtd/web-app_2_3.dtd">
                <web-app>
                  <servlet>
                    <servlet-name>dispatcher</servlet-name>
                    <servlet-class>org.springframework.web.servlet.DispatcherServlet</servlet-class>
                  </servlet>
                  <servlet-mapping>
                    <servlet-name>dispatcher</servlet-name>
                    <url-pattern>/</url-pattern>
                  </servlet-mapping>
                </web-app>
                """;
        GraphModel graph = Fx.run(PATH, xml).graph();

        assertThat(Fx.node(graph, Ids.file(PATH)).attrList("servletMappings")).containsExactly("/");
        assertThat(graph.diagnostics()).isEmpty();
    }
}
