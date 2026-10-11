package io.codeatlas.link;

import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.Linker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalReferenceMarkerTest extends LinkTestBase {

    @Test
    @DisplayName("F-012-AC26 어느 분석기도 선언하지 않은 Java 타입·메서드·필드 참조(declared=false)에 external=true를 붙인다")
    void marksUndeclaredJavaReferences() {
        // springxml·mybatis가 reference로만 만든 타입: java 분석기가 끝내 선언하지 않았다
        String factory = g.reference(Ids.javaType("org.mybatis.spring.SqlSessionFactoryBean"), NodeKind.JAVA_TYPE, "SqlSessionFactoryBean").id();
        String method = g.reference(Ids.javaMethod("org.lib.Util", "run"), NodeKind.JAVA_METHOD, "run").id();
        String field = g.reference(Ids.javaField("org.lib.Util", "INSTANCE"), NodeKind.JAVA_FIELD, "INSTANCE").id();

        run(new ExternalReferenceMarker());

        assertThat(g.node(factory).orElseThrow().attrBoolean("external")).isTrue();
        assertThat(g.node(method).orElseThrow().attrBoolean("external")).isTrue();
        assertThat(g.node(field).orElseThrow().attrBoolean("external")).isTrue();
        assertThat(g.node(factory).orElseThrow().declared()).isFalse();
        assertThat(g.node(factory).orElseThrow().kind()).isEqualTo(NodeKind.JAVA_TYPE);
    }

    @Test
    @DisplayName("F-012-AC26 선언된 노드, external이 이미 있는 노드, UNRESOLVED 노드, Java가 아닌 참조는 건드리지 않는다")
    void leavesOthersAlone() {
        String declared = type("com.a.OrderService", "interface");
        String already = g.reference(Ids.javaType("org.lib.Known"), NodeKind.JAVA_TYPE, "Known").id();
        g.putAttrs(already, Map.of("external", false));
        String unresolved = g.declare(Node.builder(Ids.unresolved("java-type", "Foo"), NodeKind.UNRESOLVED).name("Foo")
                .attr("category", "java-type").build()).id();
        String danglingUnresolved = g.reference(Ids.unresolved("java-call", "x.y(0)"), NodeKind.UNRESOLVED, "x.y(0)").id();
        String table = stubTable("TB_GHOST");
        String bean = g.reference(Ids.springBean("ghost"), NodeKind.SPRING_BEAN, "ghost").id();

        run(new ExternalReferenceMarker());

        assertThat(g.node(declared).orElseThrow().attrs()).doesNotContainKey("external");
        assertThat(g.node(already).orElseThrow().attr("external")).isEqualTo(false);
        assertThat(g.node(unresolved).orElseThrow().attrs()).doesNotContainKey("external");
        assertThat(g.node(danglingUnresolved).orElseThrow().attrs()).doesNotContainKey("external");
        assertThat(g.node(table).orElseThrow().attrs()).doesNotContainKey("external");
        assertThat(g.node(bean).orElseThrow().attrs()).doesNotContainKey("external");
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC26 link 모듈의 링커 가운데 가장 먼저(order 90) 돌고 ServiceLoader로 등록된다")
    void runsFirst() {
        assertThat(new ExternalReferenceMarker().order()).isEqualTo(90).isLessThan(new DbObjectNormalizer().order());
        assertThat(ServiceLoader.load(Linker.class).stream().map(ServiceLoader.Provider::get))
                .anyMatch(l -> l instanceof ExternalReferenceMarker);
    }
}
