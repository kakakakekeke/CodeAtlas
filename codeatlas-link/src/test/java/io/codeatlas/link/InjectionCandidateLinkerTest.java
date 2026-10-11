package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InjectionCandidateLinkerTest extends LinkTestBase {

    private String iface;
    private String client;

    private String impl(String name, String stereotype) {
        String id = type(name, "class");
        if (stereotype != null) {
            // java 분석기가 스테레오타입 빈에 남기는 것: stereotype과 빈 이름(기본은 단순 이름의 첫 글자 소문자)
            String simple = name.substring(name.lastIndexOf('.') + 1);
            g.putAttrs(id, Map.of("stereotype", stereotype, "beanName", Character.toLowerCase(simple.charAt(0)) + simple.substring(1)));
        }
        g.addEdge(Edge.of(id, iface, EdgeKind.IMPLEMENTS));
        return id;
    }

    private void setUpTypes() {
        iface = type("com.a.PaymentService", "interface");
        client = type("com.a.OrderServiceImpl", "class");
    }

    private List<Edge> added() {
        return out(client, EdgeKind.INJECTS).stream().filter(e -> !e.to().equals(iface)).toList();
    }

    @Test
    @DisplayName("F-012-AC23 빈으로 정의된 구현이 하나면 INFERRED INJECTS를 더한다")
    void singleImplementation() {
        setUpTypes();
        String impl = impl("com.a.PaymentServiceImpl", "service");
        impl("com.a.NotABean", null);
        Edge original = g.addEdge(Edge.builder(client, iface, EdgeKind.INJECTS).location(loc()).attr("via", "field").build());

        run(new InjectionCandidateLinker());

        assertThat(added()).hasSize(1);
        Edge e = added().get(0);
        assertThat(e.to()).isEqualTo(impl);
        assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(e.attrString("resolvedFrom")).isEqualTo(iface);
        assertThat(e.attrString("via")).isEqualTo("field");
        assertThat(e.location()).isEqualTo(original.location());
        assertThat(out(client, EdgeKind.INJECTS)).contains(original);
    }

    @Test
    @DisplayName("F-012-AC23 구현이 인터페이스를 간접으로 구현해도(추상 클래스 경유) 찾고, 추상 클래스와 인터페이스는 후보가 아니다")
    void transitiveImplementation() {
        setUpTypes();
        String abstractBase = impl("com.a.AbstractPayment", "component");
        g.putAttrs(abstractBase, Map.of("modifiers", List.of("public", "abstract")));
        String sub = type("com.a.sub.SubPayment", "interface");
        g.putAttrs(sub, Map.of("stereotype", "component"));
        g.addEdge(Edge.of(sub, iface, EdgeKind.EXTENDS));
        String concrete = type("com.a.CardPayment", "class");
        g.addEdge(Edge.of(concrete, abstractBase, EdgeKind.EXTENDS));
        g.addEdge(Edge.of(concrete, bean("cardPayment", "com.a.CardPayment"), EdgeKind.DEFINES_BEAN));
        edge(client, EdgeKind.INJECTS, iface);

        run(new InjectionCandidateLinker());

        assertThat(added()).extracting(Edge::to).containsExactly(concrete);
    }

    @Test
    @DisplayName("F-012-AC23 구현이 둘이면 qualifier나 필드명이 빈 이름과 맞는 하나만 더한다")
    void multipleImplementations() {
        iface = type("com.a.DiscountPolicy", "interface");
        String vip = impl("com.a.VipDiscountPolicy", "component");
        String basic = impl("com.a.DefaultDiscountPolicy", null);
        g.addEdge(Edge.of(basic, bean("basicPolicy", "com.a.DefaultDiscountPolicy"), EdgeKind.DEFINES_BEAN));
        String byQualifier = type("com.a.A", "class");
        String byField = type("com.a.B", "class");
        String undecided = type("com.a.C", "class");
        String wrongQualifier = type("com.a.D", "class");
        g.addEdge(Edge.builder(byQualifier, iface, EdgeKind.INJECTS).attr("qualifier", "basicPolicy").attr("field", "policy").build());
        g.addEdge(Edge.builder(byField, iface, EdgeKind.INJECTS).attr("field", "vipDiscountPolicy").build());
        g.addEdge(Edge.builder(undecided, iface, EdgeKind.INJECTS).attr("field", "policy").build());
        g.addEdge(Edge.builder(wrongQualifier, iface, EdgeKind.INJECTS).attr("qualifier", "nope").attr("field", "basicPolicy").build());

        run(new InjectionCandidateLinker());

        Edge q = out(byQualifier, EdgeKind.INJECTS).stream().filter(e -> !e.to().equals(iface)).findFirst().orElseThrow();
        assertThat(q.to()).isEqualTo(basic);
        assertThat(inferred(q)).isTrue();
        assertThat(q.attrString("matchedBy")).isEqualTo("qualifier");
        Edge f = out(byField, EdgeKind.INJECTS).stream().filter(e -> !e.to().equals(iface)).findFirst().orElseThrow();
        assertThat(f.to()).isEqualTo(vip);
        assertThat(f.attrString("matchedBy")).isEqualTo("name");
        assertThat(out(undecided, EdgeKind.INJECTS)).hasSize(1);
        assertThat(out(wrongQualifier, EdgeKind.INJECTS)).hasSize(1);
    }

    @Test
    @DisplayName("F-012-AC42 stereotype만 있고(filter·interceptor) 빈 정의가 없는 타입은 후보가 아니고, 빈으로 정의된 필터는 stereotype이 filter여도 후보다")
    void stereotypeAloneIsNotABean() {
        iface = type("com.a.RequestGuard", "interface");
        client = type("com.a.GuardRegistry", "class");
        // web.xml에 등록한 필터: java가 stereotype=filter만 붙인다(빈이 아니다)
        String plainFilter = type("com.a.PlainGuardFilter", "class");
        g.putAttrs(plainFilter, Map.of("stereotype", "filter"));
        g.addEdge(Edge.of(plainFilter, iface, EdgeKind.IMPLEMENTS));
        String plainInterceptor = type("com.a.PlainGuardInterceptor", "class");
        g.putAttrs(plainInterceptor, Map.of("stereotype", "interceptor"));
        g.addEdge(Edge.of(plainInterceptor, iface, EdgeKind.IMPLEMENTS));
        // @Component 필터: stereotype은 filter지만 beanName과 DEFINES_BEAN이 있다
        String beanFilter = type("com.a.ComponentGuardFilter", "class");
        g.putAttrs(beanFilter, Map.of("stereotype", "filter", "beanName", "componentGuardFilter"));
        g.addEdge(Edge.of(beanFilter, iface, EdgeKind.IMPLEMENTS));
        g.addEdge(Edge.of(beanFilter, bean("componentGuardFilter", "com.a.ComponentGuardFilter"), EdgeKind.DEFINES_BEAN));
        edge(client, EdgeKind.INJECTS, iface);

        run(new InjectionCandidateLinker());

        assertThat(added()).extracting(Edge::to).containsExactly(beanFilter);
    }

    @Test
    @DisplayName("F-012-AC42 DEFINES_BEAN이 없어도 타입의 beanName이 있으면 그 이름의 빈으로 본다(명시적 이름 포함)")
    void beanNameAttributeNamesTheBean() {
        iface = type("com.a.DiscountPolicy", "interface");
        String named = type("com.a.VipDiscountPolicy", "class");
        g.putAttrs(named, Map.of("stereotype", "interceptor", "beanName", "vip"));
        g.addEdge(Edge.of(named, iface, EdgeKind.IMPLEMENTS));
        String other = impl("com.a.DefaultDiscountPolicy", "component");
        String byExplicitName = type("com.a.A", "class");
        String byDefaultName = type("com.a.B", "class");
        g.addEdge(Edge.builder(byExplicitName, iface, EdgeKind.INJECTS).attr("qualifier", "vip").build());
        // 기본 이름(vipDiscountPolicy)이 아니라 java가 적은 빈 이름으로만 맞춘다
        g.addEdge(Edge.builder(byDefaultName, iface, EdgeKind.INJECTS).attr("qualifier", "vipDiscountPolicy").build());

        run(new InjectionCandidateLinker());

        assertThat(out(byExplicitName, EdgeKind.INJECTS)).extracting(Edge::to).containsExactlyInAnyOrder(iface, named);
        assertThat(out(byDefaultName, EdgeKind.INJECTS)).extracting(Edge::to).containsExactly(iface);
        assertThat(other).isNotNull();
    }

    @Test
    @DisplayName("F-012-AC23 클래스 주입, 이미 구현으로 가는 엣지가 있는 경우는 건너뛴다")
    void skips() {
        setUpTypes();
        String impl = impl("com.a.PaymentServiceImpl", "service");
        String other = type("com.a.Other", "class");
        edge(other, EdgeKind.INJECTS, impl);    // 클래스 주입
        String already = type("com.a.Already", "class");
        edge(already, EdgeKind.INJECTS, iface);
        edge(already, EdgeKind.INJECTS, impl);
        edge(impl, EdgeKind.INJECTS, iface);    // 자기 자신으로는 잇지 않는다

        run(new InjectionCandidateLinker());

        assertThat(added()).isEmpty();
        assertThat(out(other, EdgeKind.INJECTS)).hasSize(1);
        assertThat(out(already, EdgeKind.INJECTS)).hasSize(2);
        assertThat(out(impl, EdgeKind.INJECTS)).hasSize(1);
    }

    @Test
    @DisplayName("F-012-AC23 빈 참조 링커가 푼 인터페이스 타입 주입에도 이어서 후보를 찾는다")
    void afterBeanReference() {
        setUpTypes();
        String impl = impl("com.a.PaymentServiceImpl", "service");
        String beanId = bean("paymentService", "com.a.PaymentService");   // 인터페이스 타입으로 선언된 빈
        g.addEdge(Edge.builder(client, beanId, EdgeKind.INJECTS).attr("unresolvedBeanRef", true).build());

        run(new InjectionCandidateLinker(), new BeanReferenceLinker());

        assertThat(out(client, EdgeKind.INJECTS)).extracting(Edge::to).containsExactlyInAnyOrder(beanId, iface, impl);
        assertThat(Ids.kindOf(impl)).isPresent();
    }

    // ------------------------------------------------------------------ AC24: java가 후보를 이미 정한 주입

    @Test
    @DisplayName("F-012-AC24 candidates가 정확히 하나면 그 타입으로 INFERRED INJECTS를 더한다(resolvedFrom, via·field·qualifier 복사)")
    void singleCandidateFromAnalyzer() {
        iface = type("com.a.DiscountPolicy", "interface");
        client = type("com.a.OrderServiceImpl", "class");
        String basic = impl("com.a.DefaultDiscountPolicy", "component");
        String vip = impl("com.a.VipDiscountPolicy", "component");
        // java가 qualifier로 후보를 하나로 좁혀 둔 주입: 구현 빈이 둘이어도 후보를 따른다
        Edge original = g.addEdge(Edge.builder(client, iface, EdgeKind.INJECTS).location(loc())
                .attr("via", "constructor").attr("field", "discountPolicy").attr("qualifier", "vipDiscountPolicy")
                .attr("candidates", List.of(vip)).build());

        run(new InjectionCandidateLinker());

        assertThat(added()).hasSize(1);
        Edge e = added().get(0);
        assertThat(e.to()).isEqualTo(vip);
        assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(e.attrString("resolvedFrom")).isEqualTo(iface);
        assertThat(e.attrString("via")).isEqualTo("constructor");
        assertThat(e.attrString("field")).isEqualTo("discountPolicy");
        assertThat(e.attrString("qualifier")).isEqualTo("vipDiscountPolicy");
        assertThat(e.attrs()).doesNotContainKeys("candidates", "matchedBy");
        assertThat(e.location()).isEqualTo(original.location());
        assertThat(out(client, EdgeKind.INJECTS)).contains(original);
        assertThat(g.edges(client, EdgeKind.INJECTS, basic)).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC24 candidates가 여럿이거나, 후보가 그래프에 없는 타입·주입받는 타입 자신이거나, 이미 그 타입으로 가는 엣지가 있으면 더하지 않는다")
    void candidatesThatAddNothing() {
        iface = type("com.a.DiscountPolicy", "interface");
        String basic = impl("com.a.DefaultDiscountPolicy", "component");
        String vip = impl("com.a.VipDiscountPolicy", "component");
        String many = type("com.a.Many", "class");
        String library = type("com.a.Library", "class");
        String already = type("com.a.Already", "class");
        g.addEdge(Edge.builder(many, iface, EdgeKind.INJECTS).attr("field", "vipDiscountPolicy").attr("candidates", List.of(basic, vip)).build());
        g.addEdge(Edge.builder(library, iface, EdgeKind.INJECTS).attr("candidates", List.of("java:type:org.lib.NotInGraph")).build());
        g.addEdge(Edge.builder(vip, iface, EdgeKind.INJECTS).attr("candidates", List.of(vip)).build());   // 자기 자신
        g.addEdge(Edge.builder(already, iface, EdgeKind.INJECTS).attr("candidates", List.of(basic)).build());
        edge(already, EdgeKind.INJECTS, basic);

        run(new InjectionCandidateLinker());

        // 후보가 여럿이면 필드명이 빈 이름과 맞아도 링커가 다시 고르지 않는다(후보 판단은 java 한 곳에서 한다)
        assertThat(out(many, EdgeKind.INJECTS)).hasSize(1);
        assertThat(out(library, EdgeKind.INJECTS)).hasSize(1);
        assertThat(out(vip, EdgeKind.INJECTS)).hasSize(1);
        assertThat(out(already, EdgeKind.INJECTS)).hasSize(2);
        assertThat(g.node("java:type:org.lib.NotInGraph")).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC24 후보가 하나면 주입 대상이 인터페이스가 아니어도(추상 클래스 등) 같은 방식으로 더한다")
    void singleCandidateForClassTarget() {
        String base = type("com.a.AbstractGateway", "class");
        String impl = type("com.a.CardGateway", "class");
        String user = type("com.a.PayService", "class");
        g.addEdge(Edge.builder(user, base, EdgeKind.INJECTS).attr("via", "field").attr("candidates", List.of(impl)).build());

        run(new InjectionCandidateLinker());

        Edge e = g.edges(user, EdgeKind.INJECTS, impl).get(0);
        assertThat(inferred(e)).isTrue();
        assertThat(e.attrString("resolvedFrom")).isEqualTo(base);
    }
}
