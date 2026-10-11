package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BeanReferenceLinkerTest extends LinkTestBase {

    @Test
    @DisplayName("F-012-AC21 빈을 가리키는 INJECTS에 정의 타입으로 가는 INJECTS를 더하고 기존 엣지는 남긴다")
    void injectsToBeanDefinedByType() {
        String service = type("com.a.OrderService", "class");
        String dao = type("com.a.OrderDao", "class");
        String beanId = bean("orderDao", "com.a.OrderDao");
        g.addEdge(Edge.of(dao, beanId, EdgeKind.DEFINES_BEAN));
        Edge original = g.addEdge(Edge.builder(service, beanId, EdgeKind.INJECTS).location(loc())
                .attr("via", "xml").attr("unresolvedBeanRef", true).build());

        run(new BeanReferenceLinker());

        List<Edge> edges = out(service, EdgeKind.INJECTS);
        assertThat(edges).hasSize(2).contains(original);
        Edge added = edges.stream().filter(e -> e.to().equals(dao)).findFirst().orElseThrow();
        assertThat(added.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(added.attrString("via")).isEqualTo("xml");
        assertThat(added.attrString("resolvedFrom")).isEqualTo(beanId);
        assertThat(added.attrs()).doesNotContainKey("unresolvedBeanRef");
        assertThat(added.location()).isEqualTo(original.location());
    }

    @Test
    @DisplayName("F-012-AC21 @Bean 메서드가 정의한 빈은 beanType의 타입으로 잇고, 그래프에 없는 타입으로는 잇지 않는다")
    void injectsToBeanDefinedByMethod() {
        String service = type("com.a.OrderService", "class");
        String policy = type("com.a.VipPolicy", "class");
        type("com.a.AppConfig", "class");
        String factory = method("com.a.AppConfig", "discountPolicy");
        g.putAttrs(factory, Map.of("beanType", "com.a.VipPolicy"));
        String beanId = g.declare(Node.of(Ids.springBean("discountPolicy"), NodeKind.SPRING_BEAN, "discountPolicy")).id();
        g.addEdge(Edge.of(factory, beanId, EdgeKind.DEFINES_BEAN));
        edge(service, EdgeKind.INJECTS, beanId);
        // 라이브러리 타입의 빈
        String ds = bean("dataSource", "org.apache.commons.dbcp2.BasicDataSource");
        edge(service, EdgeKind.INJECTS, ds);
        // 정의를 모르는 빈
        edge(service, EdgeKind.INJECTS, Ids.springBean("unknown"));

        run(new BeanReferenceLinker());

        assertThat(out(service, EdgeKind.INJECTS)).extracting(Edge::to)
                .containsExactlyInAnyOrder(beanId, policy, ds, Ids.springBean("unknown"));
    }

    @Test
    @DisplayName("F-012-AC21 출발점이 빈인 INJECTS(springxml의 unresolvedBeanSource)도 정의 타입으로 푼다")
    void injectsFromBean() {
        String service = type("com.a.OrderService", "class");
        String dao = type("com.a.OrderDao", "class");
        String serviceBean = g.declare(Node.of(Ids.springBean("orderService"), NodeKind.SPRING_BEAN, "orderService")).id();
        g.addEdge(Edge.of(service, serviceBean, EdgeKind.DEFINES_BEAN));
        String daoBean = bean("orderDao", "com.a.OrderDao");
        g.addEdge(Edge.builder(serviceBean, daoBean, EdgeKind.INJECTS).attr("via", "xml")
                .attr("unresolvedBeanRef", true).attr("unresolvedBeanSource", true).build());
        g.addEdge(Edge.builder(serviceBean, dao, EdgeKind.INJECTS).location(loc()).attr("unresolvedBeanSource", true).build());

        run(new BeanReferenceLinker());

        Edge added = only(service, EdgeKind.INJECTS);
        assertThat(added.to()).isEqualTo(dao);
        assertThat(added.attrs()).doesNotContainKeys("unresolvedBeanRef", "unresolvedBeanSource");
        assertThat(added.attrString("resolvedFrom")).isEqualTo(daoBean);
        assertThat(out(serviceBean, EdgeKind.INJECTS)).hasSize(2);
    }

    @Test
    @DisplayName("F-012-AC21 DEFINES_BEAN이 없어도 빈의 class가 그래프의 타입이면 그 타입으로 잇는다. USES(스텝→빈)도 같다")
    void usesToBean() {
        String step = g.declare(Node.of(Ids.batchStep("settleJob", "step1"), NodeKind.BATCH_STEP, "step1")).id();
        String reader = type("com.a.SettleReader", "class");
        String beanId = bean("settleReader", "com.a.SettleReader");
        Edge original = g.addEdge(Edge.builder(step, beanId, EdgeKind.USES).location(loc()).attr("role", "reader").build());

        run(new BeanReferenceLinker());

        Edge added = out(step, EdgeKind.USES).stream().filter(e -> e.to().equals(reader)).findFirst().orElseThrow();
        assertThat(added.attrString("role")).isEqualTo("reader");
        assertThat(added.attrString("resolvedFrom")).isEqualTo(beanId);
        assertThat(out(step, EdgeKind.USES)).contains(original);
    }

    @Test
    @DisplayName("F-012-AC22 스텝이 쓰는 타입의 역할 메서드로 USES(스텝→메서드)를 더한다")
    void usesRoleMethods() {
        String step = g.declare(Node.of(Ids.batchStep("job", "s1"), NodeKind.BATCH_STEP, "s1")).id();
        type("com.a.CloseTasklet", "class");
        String execute = method("com.a.CloseTasklet", "execute", "org.x.StepContribution", "org.x.ChunkContext");
        method("com.a.CloseTasklet", "helper");
        type("com.a.BaseWriter", "class");
        String writer = type("com.a.SalesWriter", "class");
        g.addEdge(Edge.of(writer, Ids.javaType("com.a.BaseWriter"), EdgeKind.EXTENDS));
        String write = method("com.a.BaseWriter", "write", "java.util.List");
        type("com.a.NoRead", "class");
        type("com.a.MyListener", "class");
        method("com.a.MyListener", "execute");
        // 빈을 거쳐 풀리는 processor
        String processor = type("com.a.SalesProcessor", "class");
        String process = method("com.a.SalesProcessor", "process", "java.lang.Object");
        String beanId = bean("salesProcessor", "com.a.SalesProcessor");
        g.addEdge(Edge.of(processor, beanId, EdgeKind.DEFINES_BEAN));

        g.addEdge(Edge.builder(step, Ids.javaType("com.a.CloseTasklet"), EdgeKind.USES).attr("role", "tasklet").build());
        g.addEdge(Edge.builder(step, writer, EdgeKind.USES).attr("role", "writer").build());
        g.addEdge(Edge.builder(step, Ids.javaType("com.a.NoRead"), EdgeKind.USES).attr("role", "reader").build());
        g.addEdge(Edge.builder(step, Ids.javaType("com.a.MyListener"), EdgeKind.USES).attr("role", "listener").build());
        g.addEdge(Edge.builder(step, beanId, EdgeKind.USES).attr("role", "processor").build());

        run(new BeanReferenceLinker());

        List<Edge> toMethods = out(step, EdgeKind.USES).stream().filter(e -> e.to().startsWith("java:method:")).toList();
        assertThat(toMethods).extracting(Edge::to).containsExactlyInAnyOrder(execute, write, process);
        assertThat(toMethods).extracting(e -> e.attrString("role")).containsExactlyInAnyOrder("tasklet", "writer", "processor");

        run(new BeanReferenceLinker());   // 다시 돌려도 늘지 않는다
        assertThat(out(step, EdgeKind.USES).stream().filter(e -> e.to().startsWith("java:method:"))).hasSize(3);
    }

    private Edge injectAt(String from, String to, String file) {
        return g.addEdge(Edge.builder(from, to, EdgeKind.INJECTS).location(io.codeatlas.core.model.SourceLocation.of(file, 7))
                .attr("via", "xml").attr("unresolvedBeanRef", true).build());
    }

    @Test
    @DisplayName("F-012-AC38 자리표시자가 든 빈 이름은 프로젝트 properties 값으로 치환해 그 빈으로 풀고, 못 풀면 보강 엣지 없이 INFO 진단 1건만 남긴다")
    void placeholderBeanName() {
        project.file("src/main/resources/globals.properties", "Globals.DbType = mysql\n");
        rescan();
        String idGen = type("com.a.IdGenService", "class");
        String other = type("com.a.OtherService", "class");
        String dataSource = type("com.a.MysqlDataSource", "class");
        String mysql = bean("dataSource-mysql", "com.a.MysqlDataSource");
        bean("dataSource-oracle", "com.a.OracleDataSource");
        String byType = g.reference(Ids.springBean("dataSource-${Globals.DbType}"), NodeKind.SPRING_BEAN, "dataSource-${Globals.DbType}").id();
        String unknown = g.reference(Ids.springBean("cache-${Globals.Cache}"), NodeKind.SPRING_BEAN, "cache-${Globals.Cache}").id();
        String missing = g.reference(Ids.springBean("mq-${Globals.DbType}"), NodeKind.SPRING_BEAN, "mq-${Globals.DbType}").id();
        Edge original = injectAt(idGen, byType, "src/main/resources/spring/context-idgen.xml");
        injectAt(idGen, unknown, "src/main/resources/spring/context-idgen.xml");
        injectAt(other, unknown, "src/main/resources/spring/context-other.xml");
        injectAt(other, missing, "src/main/resources/spring/context-other.xml");

        run(new BeanReferenceLinker());

        List<Edge> edges = out(idGen, EdgeKind.INJECTS);
        assertThat(edges).contains(original);
        Edge toBean = edges.stream().filter(e -> e.to().equals(mysql)).findFirst().orElseThrow();
        assertThat(toBean.attrString("resolvedFrom")).isEqualTo(byType);
        assertThat(toBean.confidence()).isEqualTo(Confidence.RESOLVED);
        Edge toType = edges.stream().filter(e -> e.to().equals(dataSource)).findFirst().orElseThrow();
        assertThat(toType.attrString("resolvedFrom")).isEqualTo(mysql);
        assertThat(edges).extracting(Edge::to).containsExactlyInAnyOrder(byType, mysql, dataSource, unknown);
        assertThat(out(other, EdgeKind.INJECTS)).extracting(Edge::to).as("못 푼 이름, 풀었지만 정의가 없는 이름: 보강 엣지 없음")
                .containsExactlyInAnyOrder(unknown, missing);
        assertThat(diagnostics("UNRESOLVED_BEAN_PLACEHOLDER")).hasSize(1)
                .allMatch(d -> d.severity() == io.codeatlas.core.model.Severity.INFO
                        && d.message().contains("cache-${Globals.Cache}") && d.message().contains("mq-${Globals.DbType}"));
    }

    @Test
    @DisplayName("F-012-AC41 테스트 소스의 빈 정의는 참조하는 쪽도 테스트 소스일 때만 후보다")
    void testBeansOnlyForTestReferrers() {
        String service = typeAt("src/main/java/com/a/OrderService.java", "com.a.OrderService");
        String testService = typeAt("src/test/java/com/a/OrderServiceTest.java", "com.a.OrderServiceTest");
        String testDs = typeAt("src/test/java/com/a/TestDataSource.java", "com.a.TestDataSource");
        String factory = methodAt("src/test/java/com/a/TestConfig.java", "com.a.TestConfig", "dataSource");
        g.putAttrs(factory, Map.of("beanType", "com.a.TestDataSource"));
        String beanId = g.declare(Node.of(Ids.springBean("dataSource"), NodeKind.SPRING_BEAN, "dataSource")).id();
        g.addEdge(Edge.of(factory, beanId, EdgeKind.DEFINES_BEAN));
        injectAt(service, beanId, "src/main/resources/spring/context-datasource.xml");
        injectAt(testService, beanId, "src/test/resources/test-context.xml");
        // DEFINES_BEAN 없이 class만 있는 빈도 같은 규칙: 빈의 위치가 테스트 소스다
        String testOnly = g.declare(Node.builder(Ids.springBean("clock"), NodeKind.SPRING_BEAN).name("clock")
                .location(io.codeatlas.core.model.SourceLocation.of("src/test/resources/test-context.xml", 3))
                .attr("class", "com.a.TestDataSource").build()).id();
        injectAt(service, testOnly, "src/main/resources/spring/context-datasource.xml");

        run(new BeanReferenceLinker());

        assertThat(out(service, EdgeKind.INJECTS)).extracting(Edge::to).as("main의 참조는 테스트 @Bean에 묶이지 않는다")
                .containsExactlyInAnyOrder(beanId, testOnly);
        assertThat(out(testService, EdgeKind.INJECTS)).extracting(Edge::to).containsExactlyInAnyOrder(beanId, testDs);
    }

    @Test
    @DisplayName("F-012-AC41 같은 이름의 빈 정의가 여러 모듈에 있으면 참조하는 쪽과 같은 모듈의 정의로만 잇고, 고르지 못하면 잇지 않는다")
    void beanDefinitionsScopedByModule() {
        project.file("web-a/pom.xml", "<project/>");
        project.file("web-b/pom.xml", "<project/>");
        rescan();
        String a = typeAt("web-a/src/main/java/com/a/UserServiceImpl.java", "com.a.UserServiceImpl");
        String b = typeAt("web-b/src/main/java/com/b/UserServiceImpl.java", "com.b.UserServiceImpl");
        String beanId = g.declare(Node.of(Ids.springBean("userService"), NodeKind.SPRING_BEAN, "userService")).id();
        g.addEdge(Edge.of(a, beanId, EdgeKind.DEFINES_BEAN));
        g.addEdge(Edge.of(b, beanId, EdgeKind.DEFINES_BEAN));
        String inA = typeAt("web-a/src/main/java/com/a/UserController.java", "com.a.UserController");
        String inB = typeAt("web-b/src/main/java/com/b/UserController.java", "com.b.UserController");
        String outside = typeAt("tools/src/Util.java", "com.t.Util");
        injectAt(inA, beanId, "web-a/src/main/resources/spring/context.xml");
        injectAt(inB, beanId, "web-b/src/main/resources/spring/context.xml");
        injectAt(outside, beanId, "tools/context.xml");

        run(new BeanReferenceLinker());

        assertThat(out(inA, EdgeKind.INJECTS)).extracting(Edge::to).containsExactlyInAnyOrder(beanId, a);
        assertThat(out(inB, EdgeKind.INJECTS)).extracting(Edge::to).containsExactlyInAnyOrder(beanId, b);
        assertThat(out(outside, EdgeKind.INJECTS)).extracting(Edge::to).as("후보가 둘이고 모듈로 고를 수 없다").containsExactly(beanId);
    }
}
