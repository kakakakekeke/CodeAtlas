package io.codeatlas.springxml;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.testing.TestProject;
import java.nio.charset.Charset;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RobustnessTest {

    @Test
    @DisplayName("F-017-AC12 깨진 XML은 PARSE_FAILED 진단을 남기고 나머지 파일은 계속 분석한다")
    void brokenFileDoesNotStopAnalysis() {
        String broken = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                  <bean id="broken" class="com.a.Broken">
                </beans>
                """;
        String ok = Fx.beans("""
                  <bean id="fine" class="com.a.Fine"/>
                """);
        GraphModel graph = Fx.run("conf/a-broken.xml", broken, "conf/b-ok.xml", ok).graph();

        List<Diagnostic> failed = Fx.diagnostics(graph, DiagnosticCodes.PARSE_FAILED);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).location().file()).isEqualTo("conf/a-broken.xml");
        assertThat(failed.get(0).location().startLine()).isGreaterThan(0);
        assertThat(graph.hasNode(Ids.springBean("broken"))).isFalse();
        Fx.node(graph, Ids.springBean("fine"));
    }

    @Test
    @DisplayName("F-017-AC12 외부 DTD와 외부 엔티티를 읽지 않고 DOCTYPE 방식 설정 파일도 해석한다")
    void noExternalAccess() {
        try (TestProject project = TestProject.create()) {
            project.file("secret.txt", "TOP-SECRET-CONTENT");
            String secretUri = project.root().resolve("secret.txt").toUri().toString();
            // 존재하지 않는 호스트의 DTD: 받으려 하면 실패하거나 오래 걸린다
            project.file("conf/legacy.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE beans PUBLIC "-//SPRING//DTD BEAN 2.0//EN" "http://unreachable.invalid/dtd/spring-beans-2.0.dtd">
                    <beans>
                      <bean id="legacyDao" class="com.a.LegacyDao">
                        <property name="dataSource"><ref local="dataSource"/></property>
                      </bean>
                      <bean id="dataSource" class="com.a.LegacyDataSource"/>
                    </beans>
                    """);
            project.file("conf/xxe.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE beans [ <!ENTITY secret SYSTEM "%s"> ]>
                    <beans xmlns="http://www.springframework.org/schema/beans">
                      <bean id="leaky" class="com.a.Leaky">
                        <property name="value"><value>&secret;</value></property>
                      </bean>
                    </beans>
                    """.formatted(secretUri));
            GraphModel graph = project.run(new SpringXmlAnalyzer()).graph();

            // DTD 방식(네임스페이스 없음) 파일
            assertThat(Fx.node(graph, Ids.springBean("legacyDao")).location().startLine()).isEqualTo(4);
            Fx.edge(graph, Ids.javaType("com.a.LegacyDao"), EdgeKind.INJECTS, Ids.javaType("com.a.LegacyDataSource"));
            assertThat(graph.diagnostics()).noneMatch(d -> d.location() != null && "conf/legacy.xml".equals(d.location().file()));

            // 외부 엔티티의 내용은 어디에도 들어오지 않는다
            assertThat(graph.nodes()).noneMatch(n -> n.attrs().values().stream().anyMatch(v -> String.valueOf(v).contains("TOP-SECRET")));
            assertThat(graph.diagnostics()).noneMatch(d -> d.message().contains("TOP-SECRET"));
        }
    }

    @Test
    @DisplayName("F-017-AC12 MS949로 저장된 설정 파일도 읽는다")
    void ms949File() {
        String xml = """
                <?xml version="1.0" encoding="EUC-KR"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                  <!-- 주문 서비스 -->
                  <bean id="orderService" class="com.a.OrderService">
                    <property name="title" value="주문관리"/>
                  </bean>
                </beans>
                """;
        try (TestProject project = TestProject.create()) {
            project.file("conf/context-kr.xml", xml, Charset.forName("MS949"));
            GraphModel graph = project.run(new SpringXmlAnalyzer()).graph();

            assertThat(Fx.node(graph, Ids.springBean("orderService")).attrString("property.title")).isEqualTo("주문관리");
            assertThat(graph.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-017-AC1 분석기가 ServiceLoader에 등록되어 있고 order가 100이다")
    void registeredAsService() {
        List<Analyzer> analyzers = ServiceLoader.load(Analyzer.class).stream().map(ServiceLoader.Provider::get).toList();
        assertThat(analyzers).anySatisfy(a -> {
            assertThat(a).isInstanceOf(SpringXmlAnalyzer.class);
            assertThat(a.order()).isEqualTo(100);
            assertThat(a.name()).isEqualTo("springxml");
        });
    }
}
