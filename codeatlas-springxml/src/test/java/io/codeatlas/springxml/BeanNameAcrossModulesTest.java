package io.codeatlas.springxml;

import static io.codeatlas.core.model.EdgeKind.DEFINES_BEAN;
import static io.codeatlas.core.model.EdgeKind.INJECTS;
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

/** F-017-R13: 이름이 같은 빈 정의가 여러 모듈·테스트 설정에 있을 때의 노드 attrs, 진단, 참조 해석. */
class BeanNameAcrossModulesTest {

    private static final String POM = "<project><modelVersion>4.0.0</modelVersion></project>\n";

    private static final String A_MAIN = Fx.beans("""
              <bean id="dataSource" class="com.a.DsA">
                <property name="url" value="jdbc:a"/>
              </bean>
            """);
    private static final String A_MAIN_OTHER = Fx.beans("""
              <bean id="aDao" class="com.a.Dao">
                <property name="dataSource" ref="dataSource"/>
              </bean>
            """);
    private static final String A_TEST = Fx.beans("""
              <bean id="dataSource" class="com.t.TestDs">
                <property name="url" value="jdbc:test"/>
              </bean>
              <bean id="cache" class="com.t.TestCache"/>
              <bean id="tDao" class="com.t.Dao">
                <property name="dataSource" ref="dataSource"/>
              </bean>
            """);
    private static final String B_MAIN = Fx.beans("""
              <bean id="dataSource" class="com.b.DsB"/>
              <bean id="cache" class="com.b.RealCache"/>
            """);
    private static final String B_MAIN_OTHER = Fx.beans("""
              <bean id="bDao" class="com.b.Dao">
                <property name="dataSource" ref="dataSource"/>
              </bean>
            """);

    private static GraphModel run() {
        return Fx.run(
                "web-a/pom.xml", POM,
                "web-b/pom.xml", POM,
                "web-a/src/main/resources/spring/ctx.xml", A_MAIN,
                "web-a/src/main/resources/spring/dao.xml", A_MAIN_OTHER,
                "web-a/src/test/resources/test-ctx.xml", A_TEST,
                "web-b/src/main/resources/spring/ctx.xml", B_MAIN,
                "web-b/src/main/resources/spring/dao.xml", B_MAIN_OTHER).graph();
    }

    @Test
    @DisplayName("F-017-AC13 다른 모듈·테스트 설정의 같은 이름 빈은 중복 진단 없이 main 쪽 경로순 첫 정의의 attrs를 유지하고 나머지는 otherDefinitions에 남는다")
    void sameNameInOtherModuleOrTestIsNotADuplicate() {
        GraphModel graph = run();

        assertThat(Fx.diagnostics(graph, DiagnosticCodes.DUPLICATE_OBJECT_NAME)).isEmpty();

        Node dataSource = Fx.node(graph, Ids.springBean("dataSource"));
        assertThat(dataSource.attrString("class")).isEqualTo("com.a.DsA");
        assertThat(dataSource.attrString("property.url")).isEqualTo("jdbc:a");
        assertThat(dataSource.location().file()).isEqualTo("web-a/src/main/resources/spring/ctx.xml");
        assertThat(dataSource.attrList("otherDefinitions")).containsExactly(
                "web-b/src/main/resources/spring/ctx.xml:" + Fx.lineOf(B_MAIN, "id=\"dataSource\"") + " com.b.DsB",
                "web-a/src/test/resources/test-ctx.xml:" + Fx.lineOf(A_TEST, "id=\"dataSource\"") + " com.t.TestDs");
        // 정의마다의 엣지는 그대로 남는다(링커가 참조하는 쪽의 모듈로 고른다)
        assertThat(graph.incoming(dataSource.id(), DEFINES_BEAN)).extracting(Edge::from).containsExactlyInAnyOrder(
                Ids.javaType("com.a.DsA"), Ids.javaType("com.b.DsB"), Ids.javaType("com.t.TestDs"));

        // 테스트 설정의 경로가 앞서도 main 쪽 정의가 노드의 attrs와 위치를 정한다
        Node cache = Fx.node(graph, Ids.springBean("cache"));
        assertThat(cache.attrString("class")).isEqualTo("com.b.RealCache");
        assertThat(cache.location().file()).isEqualTo("web-b/src/main/resources/spring/ctx.xml");
        assertThat(cache.attrList("otherDefinitions")).containsExactly(
                "web-a/src/test/resources/test-ctx.xml:" + Fx.lineOf(A_TEST, "id=\"cache\"") + " com.t.TestCache");
        // 정의가 하나뿐인 빈에는 otherDefinitions가 없다
        assertThat(Fx.node(graph, Ids.springBean("aDao")).attrs()).doesNotContainKey("otherDefinitions");
    }

    @Test
    @DisplayName("F-017-AC13 이름이 같은 정의가 여럿이면 참조는 같은 파일, 그다음 같은 모듈의 정의로 풀고 테스트 설정의 정의는 테스트 설정에서만 쓴다")
    void referenceResolvesWithinModule() {
        GraphModel graph = run();

        Fx.edge(graph, Ids.javaType("com.a.Dao"), INJECTS, Ids.javaType("com.a.DsA"));
        Fx.edge(graph, Ids.javaType("com.b.Dao"), INJECTS, Ids.javaType("com.b.DsB"));
        Fx.edge(graph, Ids.javaType("com.t.Dao"), INJECTS, Ids.javaType("com.t.TestDs"));
        assertThat(graph.edges(INJECTS)).noneMatch(e -> e.attrBoolean("unresolvedBeanRef"));
    }

    @Test
    @DisplayName("F-017-AC13 같은 모듈의 main 설정끼리 겹친 이름은 지금처럼 DUPLICATE_OBJECT_NAME(WARN)이고 그 정의들만 적는다")
    void duplicateWithinModuleStillReported() {
        GraphModel graph = Fx.run(
                "web-a/pom.xml", POM,
                "web-b/pom.xml", POM,
                "web-a/src/main/resources/one.xml", Fx.beans("  <bean id=\"notifier\" class=\"com.a.Mail\"/>\n"),
                "web-a/src/main/resources/two.xml", Fx.beans("  <bean id=\"notifier\" class=\"com.a.Sms\"/>\n"),
                "web-b/src/main/resources/one.xml", Fx.beans("  <bean id=\"notifier\" class=\"com.b.Push\"/>\n")).graph();

        List<Diagnostic> duplicates = Fx.diagnostics(graph, DiagnosticCodes.DUPLICATE_OBJECT_NAME);
        assertThat(duplicates).hasSize(1);
        assertThat(duplicates.get(0).severity()).isEqualTo(Severity.WARN);
        assertThat(duplicates.get(0).message()).contains("notifier").contains("web-a/src/main/resources/one.xml")
                .contains("web-a/src/main/resources/two.xml").doesNotContain("web-b/");
        assertThat(Fx.node(graph, Ids.springBean("notifier")).attrString("class")).isEqualTo("com.a.Mail");
        assertThat(Fx.node(graph, Ids.springBean("notifier")).attrList("otherDefinitions")).hasSize(2);
    }
}
