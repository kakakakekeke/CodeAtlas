package io.codeatlas.springxml;

import static io.codeatlas.core.model.EdgeKind.DEFINES_BEAN;
import static io.codeatlas.core.model.EdgeKind.INJECTS;
import static io.codeatlas.core.model.EdgeKind.USES;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.Severity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** spec 5장(가정)에 적은 기본값과 경계 상황. */
class BeanEdgeCasesTest {

    @Test
    @DisplayName("F-017-AC3 같은 이름의 빈이 여러 파일에 다른 클래스로 있으면 같은 파일의 정의를 쓰고, 없으면 고르지 않는다")
    void duplicateBeanNames() {
        String a = Fx.beans("""
                  <bean id="notifier" class="com.a.MailNotifier"/>
                  <bean id="orderService" class="com.a.OrderService">
                    <property name="notifier" ref="notifier"/>
                  </bean>
                """);
        String b = Fx.beans("""
                  <bean id="notifier" class="com.b.SmsNotifier"/>
                """);
        String c = Fx.beans("""
                  <bean id="memberService" class="com.c.MemberService">
                    <property name="notifier" ref="notifier"/>
                  </bean>
                """);
        GraphModel graph = Fx.run("conf/a.xml", a, "conf/b.xml", b, "conf/c.xml", c).graph();

        // 같은 파일의 정의가 먼저다
        Fx.edge(graph, Ids.javaType("com.a.OrderService"), INJECTS, Ids.javaType("com.a.MailNotifier"));
        // 다른 파일에서는 어느 쪽인지 알 수 없다. 추측하지 않고 빈 노드로 둔다
        Edge ambiguous = Fx.edge(graph, Ids.javaType("com.c.MemberService"), INJECTS, Ids.springBean("notifier"));
        assertThat(ambiguous.attrBoolean("unresolvedBeanRef")).isTrue();
        assertThat(graph.incoming(Ids.springBean("notifier"), DEFINES_BEAN)).extracting(Edge::from)
                .containsExactlyInAnyOrder(Ids.javaType("com.a.MailNotifier"), Ids.javaType("com.b.SmsNotifier"));

        List<Diagnostic> duplicates = Fx.diagnostics(graph, DiagnosticCodes.DUPLICATE_OBJECT_NAME);
        assertThat(duplicates).hasSize(1);
        assertThat(duplicates.get(0).severity()).isEqualTo(Severity.WARN);
        assertThat(duplicates.get(0).message()).contains("notifier").contains("conf/a.xml").contains("conf/b.xml");
    }

    @Test
    @DisplayName("F-017-AC3 factory-bean 빈과 플레이스홀더 class는 클래스를 알 수 없어 빈 노드로 주고받는다")
    void beansWithoutKnownClass() {
        String xml = Fx.beans("""
                  <bean id="clientFactory" class="com.a.ClientFactory"/>
                  <bean id="client" factory-bean="clientFactory" factory-method="create">
                    <property name="timeoutPolicy" ref="timeoutPolicy"/>
                  </bean>
                  <bean id="timeoutPolicy" class="com.a.TimeoutPolicy"/>
                  <bean id="gateway" class="${gateway.class}">
                    <property name="client" ref="client"/>
                  </bean>
                  <bean id="instance" class="com.a.Registry" factory-method="getInstance"/>
                """);
        GraphModel graph = Fx.run("conf/context-client.xml", xml).graph();

        Node client = Fx.node(graph, Ids.springBean("client"));
        assertThat(client.attrString("factoryBean")).isEqualTo("clientFactory");
        assertThat(client.attrString("factoryMethod")).isEqualTo("create");
        assertThat(client.attrs()).doesNotContainKey("class");
        assertThat(graph.incoming(client.id(), DEFINES_BEAN)).isEmpty();
        // 주입받는 쪽의 클래스를 모른다: 출발이 빈 노드
        Edge fromBean = Fx.edge(graph, client.id(), INJECTS, Ids.javaType("com.a.TimeoutPolicy"));
        assertThat(fromBean.attrBoolean("unresolvedBeanSource")).isTrue();
        assertThat(fromBean.attrs()).doesNotContainKey("unresolvedBeanRef");

        // 플레이스홀더 class: 원문은 attrs에, 타입 노드는 만들지 않는다
        Node gateway = Fx.node(graph, Ids.springBean("gateway"));
        assertThat(gateway.attrString("class")).isEqualTo("${gateway.class}");
        assertThat(graph.hasNode(Ids.javaType("${gateway.class}"))).isFalse();
        Edge both = Fx.edge(graph, gateway.id(), INJECTS, client.id());
        assertThat(both.attrBoolean("unresolvedBeanSource")).isTrue();
        assertThat(both.attrBoolean("unresolvedBeanRef")).isTrue();

        // 정적 팩터리: class 속성 값을 빈 클래스로 본다
        Fx.edge(graph, Ids.javaType("com.a.Registry"), DEFINES_BEAN, Ids.springBean("instance"));
    }

    @Test
    @DisplayName("F-017-AC4 parent가 서로를 가리켜도 멈추지 않고, 프로파일이 attrs에 남는다")
    void cyclicParentsAndProfiles() {
        String xml = Fx.beans("""
                  <bean id="a" parent="b"><property name="x" value="1"/></bean>
                  <bean id="b" parent="a"><property name="y" value="2"/></bean>
                  <bean id="selfish" parent="selfish" class="com.a.Selfish"/>
                  <bean id="m1" parent="m2" class="org.mybatis.spring.mapper.MapperFactoryBean"/>
                  <bean id="m2" parent="m1"/>
                  <bean id="usesMapper" class="com.a.UsesMapper"><property name="mapper" ref="m2"/></bean>
                  <beans profile="dev">
                    <bean id="mailSender" class="com.a.FakeMailSender"/>
                  </beans>
                  <beans profile="prod">
                    <bean id="mailSender" class="com.a.SmtpMailSender"/>
                  </beans>
                """);
        GraphModel graph = Fx.run("conf/context-cycle.xml", xml).graph();

        assertThat(Fx.node(graph, Ids.springBean("a")).attrString("property.y")).isEqualTo("2");
        assertThat(Fx.node(graph, Ids.springBean("b")).attrString("property.x")).isEqualTo("1");
        Fx.node(graph, Ids.springBean("selfish"));
        Fx.edge(graph, Ids.javaType("com.a.UsesMapper"), INJECTS, Ids.javaType("org.mybatis.spring.mapper.MapperFactoryBean"));
        assertThat(graph.incoming(Ids.springBean("mailSender"), DEFINES_BEAN)).hasSize(2);
        // 프로파일로 나뉜 중복은 흔한 일이라 INFO
        assertThat(Fx.diagnostics(graph, DiagnosticCodes.DUPLICATE_OBJECT_NAME))
                .singleElement().satisfies(d -> assertThat(d.severity()).isEqualTo(Severity.INFO));
    }

    @Test
    @DisplayName("F-017-AC2 c:_0-ref, util:map의 value-ref, 프로퍼티 안의 인라인 util:list를 주입으로 잡는다")
    void constructorIndexAndUtilMap() {
        String xml = Fx.beans("""
                  <bean id="router" class="com.a.Router" c:_0-ref="primaryHandler" c:_1="5">
                    <property name="fallbacks">
                      <util:list>
                        <ref bean="backupHandler"/>
                      </util:list>
                    </property>
                  </bean>
                  <bean id="primaryHandler" class="com.a.PrimaryHandler"/>
                  <bean id="backupHandler" class="com.a.BackupHandler"/>
                  <util:map id="handlerMap">
                    <entry key="primary" value-ref="primaryHandler"/>
                    <entry key="mode" value="strict"/>
                  </util:map>
                """);
        GraphModel graph = Fx.run("conf/context-router.xml", xml).graph();

        assertThat(Fx.edge(graph, Ids.javaType("com.a.Router"), INJECTS, Ids.javaType("com.a.PrimaryHandler")).attrString("property"))
                .isEqualTo("constructor-arg[0]");
        assertThat(Fx.edge(graph, Ids.javaType("com.a.Router"), INJECTS, Ids.javaType("com.a.BackupHandler")).attrString("property"))
                .isEqualTo("fallbacks");
        Node map = Fx.node(graph, Ids.springBean("handlerMap"));
        assertThat(map.attrString("element")).isEqualTo("util:map");
        assertThat(map.attrList("values")).containsExactly("mode=strict");
        assertThat(Fx.edge(graph, map.id(), INJECTS, Ids.javaType("com.a.PrimaryHandler")).attrBoolean("unresolvedBeanSource")).isTrue();
    }

    @Test
    @DisplayName("F-017-AC8 플레이스홀더로 준 tasklet ref는 미해결로 남기고, 한 빈을 reader와 writer로 함께 써도 둘 다 남는다")
    void batchComponentEdgeCases() {
        String xml = Fx.beans("""
                  <batch:job id="edgeJob">
                    <batch:step id="dynamicStep" next="sameBeanStep">
                      <batch:tasklet ref="${job.tasklet}"/>
                    </batch:step>
                    <batch:step id="sameBeanStep">
                      <batch:tasklet>
                        <batch:chunk reader="passThrough" writer="passThrough" commit-interval="1"/>
                      </batch:tasklet>
                    </batch:step>
                  </batch:job>
                  <bean id="passThrough" class="com.a.batch.PassThrough"/>
                """);
        GraphModel graph = Fx.run("conf/edge-job.xml", xml).graph();

        Fx.edge(graph, Ids.batchStep("edgeJob", "dynamicStep"), USES, Ids.unresolved("spring-bean-ref", "${job.tasklet}"));
        List<Edge> uses = graph.edges(Ids.batchStep("edgeJob", "sameBeanStep"), USES, Ids.javaType("com.a.batch.PassThrough"));
        assertThat(uses).extracting(e -> e.attrString("role")).containsExactlyInAnyOrder("reader", "writer");
    }
}
