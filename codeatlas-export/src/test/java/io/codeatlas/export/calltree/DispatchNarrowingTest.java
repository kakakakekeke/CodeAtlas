package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.fixture.GraphFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 구현 전개를 좁히고 표시하는 규칙 가운데 뷰어 대조에서 찾은 것(F-015-R34). */
class DispatchNarrowingTest {

    private static final String POLICY = "com.d.DiscountPolicy";
    private static final String DEFAULT = "com.d.DefaultDiscountPolicy";
    private static final String VIP = "com.d.VipDiscountPolicy";

    private static String callees(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.callees()), false);
    }

    private static String callers(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.callers()), false);
    }

    /** DiscountPolicy#discount ⇐ DefaultDiscountPolicy#discount, VipDiscountPolicy#discount (서로 무관한 두 구현). */
    private static GraphFixture policies() {
        GraphFixture f = GraphFixture.create();
        f.type(POLICY, "interface", "public");
        f.type(DEFAULT, "class", "public");
        f.type(VIP, "class", "public");
        f.typeEdge(DEFAULT, POLICY, EdgeKind.IMPLEMENTS);
        f.typeEdge(VIP, POLICY, EdgeKind.IMPLEMENTS);
        String discount = f.method(POLICY, "discount", List.of(), "int", "public", "abstract");
        f.edge(f.method(DEFAULT, "discount"), discount, EdgeKind.OVERRIDES);
        f.edge(f.method(VIP, "discount"), discount, EdgeKind.OVERRIDES);
        return f;
    }

    private static String client(GraphFixture f, String type) {
        f.type(type, "class", "public");
        String pay = f.method(type, "pay");
        f.edge(Edge.builder(pay, Ids.javaMethod(POLICY, "discount"), EdgeKind.CALLS).attr("callType", "interface")
                .location(SourceLocation.of("src/main/java/" + type.replace('.', '/') + ".java", 12)).build());
        return pay;
    }

    @Test
    @DisplayName("F-015-AC44 구현 타입으로 바로 가는 주입(XML 주입: RESOLVED, resolvedFrom=spring:bean:…, via=xml / 구현 클래스 필드 주입)도 구현 전개를 좁힌다")
    void directInjectionOfImplementationNarrows() {
        GraphFixture f = policies();
        // XML 주입: 호출 타입 → 구현 타입(RESOLVED, resolvedFrom은 빈 ID)과 빈으로 가는 원래 엣지
        String xml = client(f, "com.d.XmlPaymentService");
        f.edge(Ids.javaType("com.d.XmlPaymentService"), Ids.javaType(DEFAULT), EdgeKind.INJECTS,
                Map.of("property", "constructor-arg[0]", "resolvedFrom", "spring:bean:defaultDiscountPolicy", "via", "xml"));
        f.graph().reference(Ids.springBean("defaultDiscountPolicy"), NodeKind.SPRING_BEAN, "defaultDiscountPolicy");
        f.edge(Ids.javaType("com.d.XmlPaymentService"), Ids.springBean("defaultDiscountPolicy"), EdgeKind.INJECTS,
                Map.of("unresolvedBeanRef", true, "via", "xml"));
        // 필드 타입이 구현 클래스인 직접 주입
        String field = client(f, "com.d.VipPaymentService");
        f.edge(Ids.javaType("com.d.VipPaymentService"), Ids.javaType(VIP), EdgeKind.INJECTS, Map.of("field", "vip", "via", "field"));
        // 주입 정보가 없는 호출 타입
        String plain = client(f, "com.d.PlainService");
        // 인터페이스로 가는 주입이 후보 없이 따로 있으면(어느 구현이 들어오는지 모른다) 구현 타입 주입만으로 좁히지 않는다
        String mixed = client(f, "com.d.MixedService");
        f.typeEdge("com.d.MixedService", POLICY, EdgeKind.INJECTS);
        f.typeEdge("com.d.MixedService", VIP, EdgeKind.INJECTS);
        GraphModel g = f.graph();

        assertThat(callees(g, xml)).isEqualTo("""
                XmlPaymentService#pay()
                └─ DiscountPolicy#discount()
                   └─ [impl] DefaultDiscountPolicy#discount()
                """);
        assertThat(callees(g, field)).isEqualTo("""
                VipPaymentService#pay()
                └─ DiscountPolicy#discount()
                   └─ [impl] VipDiscountPolicy#discount()
                """);
        for (String unknown : List.of(plain, mixed)) {
            assertThat(callees(g, unknown)).contains("[impl] DefaultDiscountPolicy#discount()").contains("[impl] VipDiscountPolicy#discount()");
        }
        // 좁히기를 끄면 전부
        assertThat(CallTrees.toText(CallTrees.build(g, xml, CallTreeOptions.builder().narrowByInjection(false).build()), false))
                .contains("[impl] VipDiscountPolicy#discount()");
        // callers도 같다: Vip 구현의 호출자에 Default를 주입받는 XmlPaymentService는 없다
        assertThat(callers(g, Ids.javaMethod(VIP, "discount"))).isEqualTo("""
                VipDiscountPolicy#discount()
                ├─ [via interface] MixedService#pay()
                ├─ [via interface] PlainService#pay()
                └─ [via interface] VipPaymentService#pay()
                """);
    }

    @Test
    @DisplayName("F-015-AC45 근거 OVERRIDES가 추론이면 [impl] 줄에 ~가 붙고, super 호출로 도착한 메서드에서는 구현으로 펼치지 않는다")
    void inferredOverridesAndSuperCalls() {
        GraphFixture f = policies();
        String discount = Ids.javaMethod(POLICY, "discount");
        // 소스 없는 상위 타입의 메서드를 이름·인자 수로 맞춘 재정의(INFERRED)
        f.type("com.d.GuessedPolicy", "class", "public");
        String guessed = f.method("com.d.GuessedPolicy", "discount");
        f.edge(Edge.builder(guessed, discount, EdgeKind.OVERRIDES).inferred().build());
        // 추론 재정의를 거친 간접 구현도 추론이다
        f.type("com.d.GuessedChild", "class", "public");
        String child = f.method("com.d.GuessedChild", "discount");
        f.edge(child, guessed, EdgeKind.OVERRIDES);
        String plain = client(f, "com.d.PlainService");
        GraphModel g = f.graph();

        assertThat(callees(g, plain)).isEqualTo("""
                PlainService#pay()
                └─ DiscountPolicy#discount()
                   ├─ [impl] DefaultDiscountPolicy#discount()
                   ├─ ~ [impl] GuessedChild#discount()
                   ├─ ~ [impl] GuessedPolicy#discount()
                   └─ [impl] VipDiscountPolicy#discount()
                """);
        assertThat(CallTrees.toMermaid(CallTrees.build(g, plain, CallTreeOptions.callees()))).contains("-.->|\"impl\"|");

        // 인터페이스의 default 메서드를 재정의한 쪽이 super로 부른다: 도착한 default 메서드를 다시 구현으로 펼치지 않는다
        f.type("com.d.Auditable", "interface", "public");
        f.type("com.d.OrderEntity", "class", "public");
        f.type("com.d.OtherEntity", "class", "public");
        String touch = f.method("com.d.Auditable", "touch", List.of(), "void", "public", "default");
        String orderTouch = f.method("com.d.OrderEntity", "touch");
        String otherTouch = f.method("com.d.OtherEntity", "touch");
        f.edge(orderTouch, touch, EdgeKind.OVERRIDES);
        f.edge(otherTouch, touch, EdgeKind.OVERRIDES);
        String stamp = f.method("com.d.Clock", "stamp");
        f.calls(touch, stamp);
        f.edge(Edge.builder(orderTouch, touch, EdgeKind.CALLS).attr("callType", "super")
                .location(SourceLocation.of("src/main/java/com/d/OrderEntity.java", 9)).build());
        String user = f.method("com.d.Saver", "save");
        f.edge(Edge.builder(user, touch, EdgeKind.CALLS).attr("callType", "interface")
                .location(SourceLocation.of("src/main/java/com/d/Saver.java", 5)).build());

        assertThat(callees(f.graph(), orderTouch)).isEqualTo("""
                OrderEntity#touch()
                └─ Auditable#touch() (super)
                   └─ Clock#stamp()
                """);
        // 보통의 호출로 도착하면 전처럼 구현으로 펼친다
        assertThat(callees(f.graph(), user)).isEqualTo("""
                Saver#save()
                └─ Auditable#touch()
                   ├─ Clock#stamp()
                   ├─ [impl] OrderEntity#touch()
                   │  └─ Auditable#touch() (super) [cycle]
                   └─ [impl] OtherEntity#touch()
                """);
    }
}
