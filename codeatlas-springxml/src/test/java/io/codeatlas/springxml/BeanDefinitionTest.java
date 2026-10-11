package io.codeatlas.springxml;

import static io.codeatlas.core.model.EdgeKind.DEFINES_BEAN;
import static io.codeatlas.core.model.EdgeKind.INJECTS;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BeanDefinitionTest {

    private static final String PATH = "src/main/resources/egovframework/spring/context-common.xml";

    @Test
    @DisplayName("F-017-AC1 bean 정의가 SPRING_BEAN 노드, DEFINES_BEAN, class·scope·property attrs로 남는다")
    void beanDefinitions() {
        String xml = Fx.beans("""
                  <bean id="viewResolver" class="org.springframework.web.servlet.view.InternalResourceViewResolver"
                        p:order="1">
                    <property name="prefix" value="/WEB-INF/jsp/"/>
                    <property name="suffix"><value>.jsp</value></property>
                  </bean>
                  <bean class="com.a.Foo"/>
                  <bean class="com.a.Foo" scope="prototype" init-method="start"/>
                  <bean name="fooService,fooAlias" class="com.a.FooService"/>
                """);
        GraphModel graph = Fx.run(PATH, xml).graph();

        Node resolver = Fx.node(graph, Ids.springBean("viewResolver"));
        assertThat(resolver.kind()).isEqualTo(NodeKind.SPRING_BEAN);
        assertThat(resolver.name()).isEqualTo("viewResolver");
        assertThat(resolver.declared()).isTrue();
        assertThat(resolver.attrString("class")).isEqualTo("org.springframework.web.servlet.view.InternalResourceViewResolver");
        assertThat(resolver.attrString("scope")).isEqualTo("singleton");
        assertThat(resolver.attrString("property.prefix")).isEqualTo("/WEB-INF/jsp/");
        assertThat(resolver.attrString("property.suffix")).isEqualTo(".jsp");
        assertThat(resolver.attrString("property.order")).isEqualTo("1");
        assertThat(resolver.location().file()).isEqualTo(PATH);
        assertThat(resolver.location().startLine()).isEqualTo(Fx.lineOf(xml, "<bean id=\"viewResolver\""));
        assertThat(resolver.location().endLine()).isEqualTo(Fx.lineOf(xml, "</bean>"));

        String resolverType = Ids.javaType("org.springframework.web.servlet.view.InternalResourceViewResolver");
        Edge defines = Fx.edge(graph, resolverType, DEFINES_BEAN, resolver.id());
        assertThat(defines.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(defines.location().startLine()).isEqualTo(resolver.location().startLine());
        // Java 타입은 참조만 한다(정의는 codeatlas-java가 한다).
        assertThat(Fx.node(graph, resolverType).kind()).isEqualTo(NodeKind.JAVA_TYPE);
        assertThat(Fx.node(graph, resolverType).declared()).isFalse();

        // id 없는 빈은 Spring 규칙대로 <FQN>#<순번>
        assertThat(Fx.node(graph, Ids.springBean("com.a.Foo#0")).attrString("scope")).isEqualTo("singleton");
        Node second = Fx.node(graph, Ids.springBean("com.a.Foo#1"));
        assertThat(second.attrString("scope")).isEqualTo("prototype");
        assertThat(second.attrString("initMethod")).isEqualTo("start");
        Fx.edge(graph, Ids.javaType("com.a.Foo"), DEFINES_BEAN, Ids.springBean("com.a.Foo#0"));
        Fx.edge(graph, Ids.javaType("com.a.Foo"), DEFINES_BEAN, Ids.springBean("com.a.Foo#1"));

        // name 속성의 첫 이름이 빈 이름, 나머지는 별칭
        assertThat(Fx.node(graph, Ids.springBean("fooService")).attrList("aliases")).containsExactly("fooAlias");
        assertThat(graph.hasNode(Ids.springBean("fooAlias"))).isFalse();

        // FILE -> 최상위 빈
        Fx.edge(graph, Ids.file(PATH), EdgeKind.CONTAINS, resolver.id());
        assertThat(graph.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-017-AC2 XML 주입 방식마다 INJECTS(주입받는 클래스 -> 참조된 클래스)가 생기고 depends-on은 제외한다")
    void injections() {
        String xml = Fx.beans("""
                  <bean id="orderService" class="com.a.OrderServiceImpl" depends-on="initBean"
                        p:auditDao-ref="auditDao" c:policy-ref="policy">
                    <constructor-arg ref="clock"/>
                    <constructor-arg index="2" value="10"/>
                    <property name="orderDao" ref="orderDao"/>
                    <property name="memberDao"><ref bean="memberDao"/></property>
                    <property name="notifier">
                      <bean class="com.a.MailNotifier">
                        <property name="sender" ref="mailSender"/>
                      </bean>
                    </property>
                    <property name="validators">
                      <list>
                        <ref bean="amountValidator"/>
                        <bean class="com.a.InlineValidator"/>
                      </list>
                    </property>
                    <property name="handlers">
                      <map>
                        <entry key="card" value-ref="cardHandler"/>
                        <entry key="bank"><ref bean="bankHandler"/></entry>
                      </map>
                    </property>
                    <property name="targetName"><idref bean="targetBean"/></property>
                    <lookup-method name="createCommand" bean="command"/>
                  </bean>
                  <bean id="initBean" class="com.a.InitBean"/>
                  <bean id="auditDao" class="com.a.AuditDao"/>
                  <bean id="policy" class="com.a.DefaultPolicy"/>
                  <bean id="clock" class="com.a.SystemClock"/>
                  <bean id="orderDao" class="com.a.OrderDao"/>
                  <bean id="memberDao" class="com.a.MemberDao"/>
                  <bean id="mailSender" class="com.a.MailSender"/>
                  <bean id="amountValidator" class="com.a.AmountValidator"/>
                  <bean id="cardHandler" class="com.a.CardHandler"/>
                  <bean id="bankHandler" class="com.a.BankHandler"/>
                  <bean id="targetBean" class="com.a.TargetBean"/>
                  <bean id="command" class="com.a.Command" scope="prototype"/>
                """);
        GraphModel graph = Fx.run(PATH, xml).graph();
        String service = Ids.javaType("com.a.OrderServiceImpl");

        Edge orderDao = Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.OrderDao"));
        assertThat(orderDao.attrString("via")).isEqualTo("xml");
        assertThat(orderDao.attrString("property")).isEqualTo("orderDao");
        assertThat(orderDao.attrString("qualifier")).isEqualTo("orderDao");
        assertThat(orderDao.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(orderDao.location().startLine()).isEqualTo(Fx.lineOf(xml, "<property name=\"orderDao\""));
        assertThat(orderDao.attrs()).doesNotContainKey("unresolvedBeanRef");

        assertThat(Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.MemberDao")).attrString("property")).isEqualTo("memberDao");
        assertThat(Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.AuditDao")).attrString("property")).isEqualTo("auditDao");
        assertThat(Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.DefaultPolicy")).attrString("property"))
                .isEqualTo("constructor-arg[policy]");
        Edge clock = Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.SystemClock"));
        assertThat(clock.attrString("property")).isEqualTo("constructor-arg[0]");
        assertThat(clock.attrString("qualifier")).isEqualTo("clock");

        // 내부 빈: 바깥 클래스 -> 내부 빈 클래스, 내부 빈의 주입도 잡는다
        Edge notifier = Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.MailNotifier"));
        assertThat(notifier.attrString("property")).isEqualTo("notifier");
        assertThat(notifier.attrBoolean("innerBean")).isTrue();
        assertThat(Fx.edge(graph, Ids.javaType("com.a.MailNotifier"), INJECTS, Ids.javaType("com.a.MailSender")).attrString("property"))
                .isEqualTo("sender");
        List<Node> innerBeans = graph.nodes(NodeKind.SPRING_BEAN).stream().filter(n -> n.attrBoolean("inner")).toList();
        assertThat(innerBeans).extracting(n -> n.attrString("class"))
                .containsExactlyInAnyOrder("com.a.MailNotifier", "com.a.InlineValidator");

        // 컬렉션 안의 ref와 내부 빈
        assertThat(Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.AmountValidator")).attrString("property")).isEqualTo("validators");
        assertThat(Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.InlineValidator")).attrString("property")).isEqualTo("validators");
        assertThat(Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.CardHandler")).attrString("property")).isEqualTo("handlers");
        assertThat(Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.BankHandler")).attrString("property")).isEqualTo("handlers");

        // idref, lookup-method
        Edge idref = Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.TargetBean"));
        assertThat(idref.attrString("property")).isEqualTo("targetName");
        assertThat(idref.attrBoolean("idref")).isTrue();
        Edge lookup = Fx.edge(graph, service, INJECTS, Ids.javaType("com.a.Command"));
        assertThat(lookup.attrString("property")).isEqualTo("createCommand");
        assertThat(lookup.attrString("qualifier")).isEqualTo("command");

        // depends-on은 주입이 아니다
        assertThat(Fx.hasEdge(graph, service, INJECTS, Ids.javaType("com.a.InitBean"))).isFalse();
        assertThat(graph.outgoing(service, INJECTS)).hasSize(12);
        assertThat(graph.edges(INJECTS)).allSatisfy(e -> assertThat(e.attrString("via")).isEqualTo("xml"));
    }

    @Test
    @DisplayName("F-017-AC3 XML에 없는 빈을 참조하면 INJECTS 대상이 spring:bean:<이름>이고 unresolvedBeanRef=true다")
    void referenceToBeanOutsideXml() {
        String xml = Fx.beans("""
                  <bean id="paymentService" class="com.example.shop.payment.PaymentServiceImpl">
                    <constructor-arg ref="defaultDiscountPolicy"/>
                    <property name="orderDao" ref="orderDao"/>
                    <property name="gateway" ref="${payment.gateway.bean}"/>
                  </bean>
                """);
        GraphModel graph = Fx.run(PATH, xml).graph();
        String service = Ids.javaType("com.example.shop.payment.PaymentServiceImpl");

        Edge edge = Fx.edge(graph, service, INJECTS, Ids.springBean("orderDao"));
        assertThat(edge.attrBoolean("unresolvedBeanRef")).isTrue();
        assertThat(edge.attrString("qualifier")).isEqualTo("orderDao");
        assertThat(edge.attrString("property")).isEqualTo("orderDao");
        assertThat(edge.attrString("via")).isEqualTo("xml");
        Node bean = Fx.node(graph, Ids.springBean("orderDao"));
        assertThat(bean.kind()).isEqualTo(NodeKind.SPRING_BEAN);
        assertThat(bean.declared()).isFalse();

        assertThat(Fx.edge(graph, service, INJECTS, Ids.springBean("defaultDiscountPolicy")).attrBoolean("unresolvedBeanRef")).isTrue();

        // 플레이스홀더 ref는 이름을 알 수 없으므로 미해결로 남긴다
        Edge placeholder = Fx.edge(graph, service, INJECTS, Ids.unresolved("spring-bean-ref", "${payment.gateway.bean}"));
        assertThat(placeholder.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(Fx.diagnostics(graph, DiagnosticCodes.UNRESOLVED_REFERENCE)).hasSize(1);
    }
}
