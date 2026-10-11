package io.codeatlas.springxml;

import static io.codeatlas.core.model.EdgeKind.CONTAINS;
import static io.codeatlas.core.model.EdgeKind.DEFINES_BEAN;
import static io.codeatlas.core.model.EdgeKind.INJECTS;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-017-R14: {@code id}가 있는 확장 네임스페이스 요소({@code jdbc:embedded-database} 등)를 빈으로 등록한다. */
class NamespaceElementBeanTest {

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:db="http://www.springframework.org/schema/jdbc"
                   xmlns:jee="http://www.springframework.org/schema/jee"
                   xmlns:task="http://www.springframework.org/schema/task"
                   xmlns:dubbo="http://code.alibabatech.com/schema/dubbo"
                   xmlns:x="http://example.com/schema/x">
              <db:embedded-database id="dataSource" type="HSQL">
                <db:script location="classpath:schema.sql"/>
              </db:embedded-database>
              <jee:jndi-lookup id="jndiDs" jndi-name="java:comp/env/jdbc/shop"/>
              <jee:jndi-lookup id="typedDs" jndi-name="java:comp/env/jdbc/shop" expected-type="javax.sql.DataSource"/>
              <dubbo:reference id="apiService" interface="com.z.api.ApiService" mock="true"/>
              <task:executor id="executor" pool-size="10"/>
              <x:custom id="custom"/>
              <x:custom/>
              <x:custom id="${custom.id}"/>
              <bean id="dao" class="com.a.Dao">
                <property name="dataSource" ref="dataSource"/>
                <property name="api" ref="apiService"/>
                <property name="jndi" ref="jndiDs"/>
              </bean>
            </beans>
            """;

    @Test
    @DisplayName("F-017-AC14 id가 있는 네임스페이스 요소는 SPRING_BEAN(attrs.element)이 되고 알려진 요소는 대표 타입을 class로 가져 참조가 그 타입으로 풀린다")
    void namespaceElementsWithIdAreBeans() {
        GraphModel graph = Fx.run("conf/context-datasource.xml", XML).graph();

        Node dataSource = Fx.node(graph, Ids.springBean("dataSource"));
        // 접두사는 파일마다 다를 수 있어 네임스페이스 URI의 마지막 조각으로 적는다
        assertThat(dataSource.attrString("element")).isEqualTo("jdbc:embedded-database");
        assertThat(dataSource.attrString("class")).isEqualTo("javax.sql.DataSource");
        assertThat(dataSource.location().startLine()).isEqualTo(Fx.lineOf(XML, "<db:embedded-database"));
        Fx.edge(graph, Ids.javaType("javax.sql.DataSource"), DEFINES_BEAN, dataSource.id());
        Fx.edge(graph, Ids.file("conf/context-datasource.xml"), CONTAINS, dataSource.id());
        Edge injected = Fx.edge(graph, Ids.javaType("com.a.Dao"), INJECTS, Ids.javaType("javax.sql.DataSource"));
        assertThat(injected.attrString("qualifier")).isEqualTo("dataSource");

        // 타입을 모르는 요소: class를 비우고 참조는 빈 노드로 둔다
        Node jndi = Fx.node(graph, Ids.springBean("jndiDs"));
        assertThat(jndi.attrString("element")).isEqualTo("jee:jndi-lookup");
        assertThat(jndi.attrs()).doesNotContainKey("class");
        assertThat(graph.incoming(jndi.id(), DEFINES_BEAN)).isEmpty();
        assertThat(Fx.edge(graph, Ids.javaType("com.a.Dao"), INJECTS, jndi.id()).attrBoolean("unresolvedBeanRef")).isTrue();
        assertThat(Fx.node(graph, Ids.springBean("typedDs")).attrString("class")).isEqualTo("javax.sql.DataSource");

        Node api = Fx.node(graph, Ids.springBean("apiService"));
        assertThat(api.attrString("element")).isEqualTo("dubbo:reference");
        assertThat(api.attrString("class")).isEqualTo("com.z.api.ApiService");
        Fx.edge(graph, Ids.javaType("com.a.Dao"), INJECTS, Ids.javaType("com.z.api.ApiService"));

        assertThat(Fx.node(graph, Ids.springBean("executor")).attrString("class"))
                .isEqualTo("org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor");
        Node custom = Fx.node(graph, Ids.springBean("custom"));
        assertThat(custom.attrString("element")).isEqualTo("x:custom");
        assertThat(custom.attrs()).doesNotContainKey("class");
        // id가 없거나 플레이스홀더면 빈으로 만들지 않는다
        assertThat(graph.nodes(io.codeatlas.core.model.NodeKind.SPRING_BEAN)).extracting(Node::name)
                .containsExactlyInAnyOrder("dataSource", "jndiDs", "typedDs", "apiService", "executor", "custom", "dao");
    }
}
