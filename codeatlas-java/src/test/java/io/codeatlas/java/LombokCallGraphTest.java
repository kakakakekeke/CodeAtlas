package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.hasEdge;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-003-R11: Lombok 합성 멤버로의 호출 해석. */
class LombokCallGraphTest {

    private static final Map<String, String> MODEL = Fx.files(
            "src/com/a/Address.java", """
                    package com.a;
                    import lombok.Getter;
                    @Getter
                    public class Address {
                        private String city;
                        private java.util.List<String> lines;
                    }
                    """,
            "src/com/a/Customer.java", """
                    package com.a;
                    import lombok.Data;
                    @Data
                    public class Customer {
                        private Long id;
                        private Address address;
                        private boolean vip;
                        public void validate() { }
                        public void rename(String name) { }
                        public void rename(Integer code) { }
                    }
                    """,
            "src/com/a/VipCustomer.java", "package com.a; public class VipCustomer extends Customer { }",
            "src/com/a/Item.java", "package com.a; public class Item { }",
            "src/com/a/Order.java", """
                    package com.a;
                    import lombok.AllArgsConstructor;
                    import lombok.Builder;
                    import lombok.Getter;
                    import lombok.NoArgsConstructor;
                    import lombok.Setter;
                    import lombok.Singular;
                    import java.util.List;
                    @Getter
                    @Setter
                    @Builder(toBuilder = true)
                    @NoArgsConstructor
                    @AllArgsConstructor
                    public class Order {
                        private Long id;
                        private String name;
                        private Customer customer;
                        @Singular("item") private List<Item> items;
                        private Object any;
                        String label() { return getName() + this.getId(); }
                    }
                    """,
            "src/com/a/Base.java", """
                    package com.a;
                    import lombok.experimental.SuperBuilder;
                    @SuperBuilder
                    public class Base { private Long id; }
                    """,
            "src/com/a/Child.java", """
                    package com.a;
                    import lombok.Getter;
                    import lombok.experimental.SuperBuilder;
                    @Getter
                    @SuperBuilder
                    public class Child extends Base { private String name; }
                    """);

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    private static String type(String name) {
        return Ids.javaType(name);
    }

    private static Map<String, String> with(String path, String content) {
        Map<String, String> files = new java.util.LinkedHashMap<>(MODEL);
        files.put(path, content);
        return files;
    }

    private static List<String> unresolved(GraphModel g, String from) {
        return targets(g, from, EdgeKind.CALLS).stream().filter(t -> t.startsWith("unresolved:")).toList();
    }

    @Test
    @DisplayName("F-003-AC14 합성 getter·setter·생성자·정적 팩터리 호출을 그 노드로 RESOLVED(attrs.lombok)로 잇고 상속·암묵적 this도 해석한다")
    void directCalls() {
        LombokFx.eachClasspath(with("src/com/a/Caller.java", """
                package com.a;
                public class Caller {
                    void run(Customer c, VipCustomer vip) {
                        c.setId(1L);
                        Long id = c.getId();
                        boolean v = c.isVip();
                        c.validate();
                        vip.getAddress();
                        Order o = new Order(1L, "n", c, null, null);
                        Order empty = new Order();
                        Customer made = new Customer();
                        c.setNothing("x");
                        new Order(1L);
                    }
                }
                """), (cp, g) -> {
            String run = m("com.a.Caller", "run", "com.a.Customer", "com.a.VipCustomer");
            Edge set = edge(g, run, EdgeKind.CALLS, m("com.a.Customer", "setId", "java.lang.Long"));
            assertThat(set.confidence()).as(cp).isEqualTo(Confidence.RESOLVED);
            assertThat(set.attrBoolean("lombok")).isTrue();
            assertThat(set.attrString("callType")).isEqualTo("virtual");
            assertThat(set.location().file()).isEqualTo("src/com/a/Caller.java");
            assertThat(set.location().startLine()).isEqualTo(4);
            assertThat(edge(g, run, EdgeKind.CALLS, m("com.a.Customer", "getId")).confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(hasEdge(g, run, EdgeKind.CALLS, m("com.a.Customer", "isVip"))).isTrue();
            // 소스에 선언한 메서드는 JDT가 해석한 그대로(lombok 표시 없음)
            assertThat(edge(g, run, EdgeKind.CALLS, m("com.a.Customer", "validate")).attrBoolean("lombok")).isFalse();
            // 상위 타입의 합성 멤버
            Edge inherited = edge(g, run, EdgeKind.CALLS, m("com.a.Customer", "getAddress"));
            assertThat(inherited.attrString("receiverType")).isEqualTo("com.a.VipCustomer");

            String allArgs = m("com.a.Order", "<init>", "java.lang.Long", "java.lang.String", "com.a.Customer", "java.util.List", "java.lang.Object");
            Edge ctor = edge(g, run, EdgeKind.CALLS, allArgs);
            assertThat(ctor.attrString("callType")).isEqualTo("constructor");
            assertThat(ctor.attrBoolean("lombok")).isTrue();
            assertThat(hasEdge(g, run, EdgeKind.CALLS, m("com.a.Order", "<init>"))).isTrue();
            assertThat(hasEdge(g, run, EdgeKind.CALLS, m("com.a.Customer", "<init>"))).isTrue();
            assertThat(Fx.edges(g, run, EdgeKind.INSTANTIATES, type("com.a.Order"))).hasSize(2);

            // 합성 멤버에도 없으면 기존대로 미해결
            assertThat(unresolved(g, run)).as(cp).containsExactlyInAnyOrder(
                    Ids.unresolved("java-call", "c.setNothing(…)"), Ids.unresolved("java-call", "new Order(…)"));

            // 수신자 없는 호출과 this.호출
            String label = m("com.a.Order", "label");
            assertThat(edge(g, label, EdgeKind.CALLS, m("com.a.Order", "getName")).attrBoolean("lombok")).isTrue();
            assertThat(hasEdge(g, label, EdgeKind.CALLS, m("com.a.Order", "getId"))).isTrue();
            assertThat(unresolved(g, label)).isEmpty();
        });
    }

    @Test
    @DisplayName("F-003-AC15 체인: 합성 메서드의 반환 타입을 따라 빌더 체인, getter 체인, 선언 메서드, var 변수, @SuperBuilder 상속 메서드를 잇는다")
    void chains() {
        LombokFx.eachClasspath(with("src/com/a/Caller.java", """
                package com.a;
                public class Caller {
                    Order build() {
                        return Order.builder().id(1L).name("n").item(new Item()).build();
                    }
                    void getters(Order o) {
                        String city = o.getCustomer().getAddress().getCity();
                        o.getCustomer().validate();
                        var c = o.getCustomer();
                        c.getAddress();
                    }
                    void rebuild(Order o) {
                        o.toBuilder().name("m").build().getName();
                    }
                    void child() {
                        Child.builder().id(1L).name("x").build().getName();
                    }
                    void broken(Order o) {
                        o.getCustomer().getNope().foo();
                    }
                    void jdk(Order o) {
                        int n = o.getName().trim().length();
                        o.getItems().size();
                    }
                }
                """), (cp, g) -> {
            String build = m("com.a.Caller", "build");
            String builderType = "com.a.Order$OrderBuilder";
            Edge builder = edge(g, build, EdgeKind.CALLS, m("com.a.Order", "builder"));
            assertThat(builder.attrString("callType")).as(cp).isEqualTo("static");
            assertThat(builder.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(targets(g, build, EdgeKind.CALLS)).as(cp).containsExactlyInAnyOrder(
                    m("com.a.Order", "builder"), m(builderType, "id", "java.lang.Long"), m(builderType, "name", "java.lang.String"),
                    m(builderType, "item", "com.a.Item"), m(builderType, "build"), m("com.a.Item", "<init>"));
            assertThat(edge(g, build, EdgeKind.CALLS, m(builderType, "build")).attrBoolean("lombok")).isTrue();

            String getters = m("com.a.Caller", "getters", "com.a.Order");
            assertThat(targets(g, getters, EdgeKind.CALLS)).as(cp).containsOnly(
                    m("com.a.Order", "getCustomer"), m("com.a.Customer", "getAddress"), m("com.a.Address", "getCity"),
                    m("com.a.Customer", "validate"));
            // 합성 메서드 뒤에 이어진 선언 메서드
            Edge validate = edge(g, getters, EdgeKind.CALLS, m("com.a.Customer", "validate"));
            assertThat(validate.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(validate.attrBoolean("lombok")).isFalse();
            // o.getCustomer() 세 번(줄마다 하나), var 변수로 받은 뒤의 호출 포함 getAddress 두 번
            assertThat(Fx.edges(g, getters, EdgeKind.CALLS, m("com.a.Order", "getCustomer"))).hasSize(3);
            assertThat(Fx.edges(g, getters, EdgeKind.CALLS, m("com.a.Customer", "getAddress"))).hasSize(2);
            // 호출한 메서드를 선언한 타입으로의 의존
            assertThat(hasEdge(g, type("com.a.Caller"), EdgeKind.DEPENDS_ON, type("com.a.Address"))).as(cp).isTrue();

            String rebuild = m("com.a.Caller", "rebuild", "com.a.Order");
            assertThat(targets(g, rebuild, EdgeKind.CALLS)).as(cp).containsExactlyInAnyOrder(
                    m("com.a.Order", "toBuilder"), m(builderType, "name", "java.lang.String"), m(builderType, "build"), m("com.a.Order", "getName"));

            String child = m("com.a.Caller", "child");
            assertThat(targets(g, child, EdgeKind.CALLS)).as(cp).containsExactlyInAnyOrder(
                    m("com.a.Child", "builder"), m("com.a.Base$BaseBuilder", "id", "java.lang.Long"),
                    m("com.a.Child$ChildBuilder", "name", "java.lang.String"), m("com.a.Child$ChildBuilder", "build"), m("com.a.Child", "getName"));
            assertThat(edge(g, child, EdgeKind.CALLS, m("com.a.Base$BaseBuilder", "id", "java.lang.Long")).attrString("receiverType"))
                    .isEqualTo("com.a.Child$ChildBuilder");

            // 추적이 끊기면 기존대로 미해결
            String broken = m("com.a.Caller", "broken", "com.a.Order");
            assertThat(hasEdge(g, broken, EdgeKind.CALLS, m("com.a.Order", "getCustomer"))).isTrue();
            assertThat(unresolved(g, broken)).as(cp).containsExactlyInAnyOrder(
                    Ids.unresolved("java-call", "o.getCustomer().getNope(…)"), Ids.unresolved("java-call", "o.getCustomer().getNope().foo(…)"));

            // 합성 getter가 돌려준 JDK 타입의 메서드는 JDK 호출이다(기본 옵션에서는 기록하지 않는다)
            String jdk = m("com.a.Caller", "jdk", "com.a.Order");
            assertThat(targets(g, jdk, EdgeKind.CALLS)).as(cp).contains(m("com.a.Order", "getName"), m("com.a.Order", "getItems"));
            assertThat(g.diagnostics()).as(cp).noneMatch(d -> d.severity() == Severity.WARN
                    && d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE) && d.location() != null && d.location().startLine() >= 22);
        });
    }

    @Test
    @DisplayName("F-003-AC16 메서드 참조와 람다 안의 호출도 합성 멤버로 잇고, 그 때문에 인자가 맞지 않게 된 JDK 호출은 미해결로 남기지 않는다")
    void methodReferencesAndLambdas() {
        LombokFx.eachClasspath(with("src/com/a/Caller.java", """
                package com.a;
                import java.util.List;
                import java.util.function.Function;
                import java.util.function.Supplier;
                import java.util.stream.Collectors;
                public class Caller {
                    List<Long> ids(List<Order> orders) {
                        return orders.stream().map(Order::getId).collect(Collectors.toList());
                    }
                    void lambdas(List<Order> orders) {
                        orders.forEach(o -> o.setName("x"));
                        Function<Order, Customer> f = o -> o.getCustomer();
                        Supplier<Order> s = Order::new;
                    }
                }
                """), (cp, g) -> {
            String ids = m("com.a.Caller", "ids", "java.util.List");
            Edge ref = edge(g, ids, EdgeKind.CALLS, m("com.a.Order", "getId"));
            assertThat(ref.attrString("callType")).as(cp).isEqualTo("methodRef");
            assertThat(ref.attrBoolean("lombok")).isTrue();
            assertThat(ref.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(targets(g, ids, EdgeKind.CALLS)).as(cp).containsExactly(m("com.a.Order", "getId"));

            String lambdas = m("com.a.Caller", "lambdas", "java.util.List");
            Edge inLambda = edge(g, lambdas, EdgeKind.CALLS, m("com.a.Order", "setName", "java.lang.String"));
            assertThat(inLambda.attrBoolean("viaLambda")).isTrue();
            assertThat(inLambda.attrString("callType")).isEqualTo("virtual");
            assertThat(edge(g, lambdas, EdgeKind.CALLS, m("com.a.Order", "getCustomer")).attrBoolean("viaLambda")).isTrue();
            // Order::new 는 컴파일러가 인자 없는 생성자로 해석하고, 그 노드는 @NoArgsConstructor의 합성 생성자다
            Edge noArgs = edge(g, lambdas, EdgeKind.CALLS, m("com.a.Order", "<init>"));
            assertThat(noArgs.confidence()).as(cp).isEqualTo(Confidence.RESOLVED);
            assertThat(noArgs.attrString("callType")).isEqualTo("methodRef");
            assertThat(Fx.node(g, m("com.a.Order", "<init>")).attrString("lombok")).isEqualTo("NoArgsConstructor");
            assertThat(hasEdge(g, lambdas, EdgeKind.INSTANTIATES, type("com.a.Order"))).isTrue();
            assertThat(unresolved(g, lambdas)).as(cp).isEmpty();
            assertThat(g.diagnostics()).as(cp).noneMatch(d -> d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE));
        });
    }

    @Test
    @DisplayName("F-003-AC17 체인에서 이름·인자 수가 같은 후보가 여럿이면 INFERRED + ambiguous로 남기고, 인자 타입으로 하나만 남으면 INFERRED로 잇는다")
    void ambiguousOverloads() {
        LombokFx.eachClasspath(with("src/com/a/Caller.java", """
                package com.a;
                public class Caller {
                    void unknownArg(Order o) {
                        o.getCustomer().rename(o.getItems().get(0).toString());
                    }
                    void knownArg(Order o) {
                        o.getCustomer().rename("x");
                    }
                }
                """), (cp, g) -> {
            String byString = m("com.a.Customer", "rename", "java.lang.String");
            String byInteger = m("com.a.Customer", "rename", "java.lang.Integer");

            String unknownArg = m("com.a.Caller", "unknownArg", "com.a.Order");
            Edge first = edge(g, unknownArg, EdgeKind.CALLS, byString);
            assertThat(first.confidence()).as(cp).isEqualTo(Confidence.INFERRED);
            assertThat(first.attrBoolean("ambiguous")).isTrue();
            Edge second = edge(g, unknownArg, EdgeKind.CALLS, byInteger);
            assertThat(second.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(second.attrBoolean("ambiguous")).isTrue();

            String knownArg = m("com.a.Caller", "knownArg", "com.a.Order");
            Edge narrowed = edge(g, knownArg, EdgeKind.CALLS, byString);
            assertThat(narrowed.confidence()).as(cp).isEqualTo(Confidence.INFERRED);
            assertThat(narrowed.attrBoolean("ambiguous")).isFalse();
            assertThat(hasEdge(g, knownArg, EdgeKind.CALLS, byInteger)).isFalse();
        });
    }

    @Test
    @DisplayName("F-003-AC18 @Slf4j의 log 호출은 로거 타입의 external 호출과 log 필드 ACCESSES로 남고, @Log(java.util.logging)는 JDK 호출이라 기록하지 않는다")
    void logField() {
        LombokFx.eachClasspath(Fx.files(
                "src/com/a/Svc.java", """
                        package com.a;
                        import lombok.extern.slf4j.Slf4j;
                        @Slf4j
                        public class Svc {
                            void run(String id) {
                                log.info("start {}", id);
                                Runnable r = new Runnable() { public void run() { log.debug("inner"); } };
                            }
                        }
                        """,
                "src/com/a/Jul.java", """
                        package com.a;
                        import lombok.extern.java.Log;
                        @Log
                        public class Jul {
                            void run() { log.info("x"); }
                        }
                        """,
                "src/com/a/NoLog.java", """
                        package com.a;
                        public class NoLog {
                            void run() { log.info("x"); }
                        }
                        """), (cp, g) -> {
            String run = m("com.a.Svc", "run", "java.lang.String");
            Edge info = edge(g, run, EdgeKind.CALLS, m("org.slf4j.Logger", "info", "?"));
            assertThat(info.confidence()).as(cp).isEqualTo(Confidence.RESOLVED);
            assertThat(info.attrString("callType")).isEqualTo("virtual");
            assertThat(edge(g, run, EdgeKind.ACCESSES, Ids.javaField("com.a.Svc", "log")).attrString("access")).isEqualTo("read");
            // 안쪽 타입에서 바깥 타입의 log
            assertThat(hasEdge(g, m("com.a.Svc$1", "run"), EdgeKind.CALLS, m("org.slf4j.Logger", "debug", "?"))).as(cp).isTrue();

            String jul = m("com.a.Jul", "run");
            assertThat(targets(g, jul, EdgeKind.CALLS)).as(cp).isEmpty();
            assertThat(hasEdge(g, jul, EdgeKind.ACCESSES, Ids.javaField("com.a.Jul", "log"))).isTrue();

            // Lombok 로그 애너테이션이 없으면 기존대로 미해결
            assertThat(unresolved(g, m("com.a.NoLog", "run"))).containsExactly(Ids.unresolved("java-call", "log.info(…)"));
        });
    }

    @Test
    @DisplayName("F-003-AC21 합성 멤버 때문에 컴파일러가 보지 못한 곳(이어진 JDK 체인, 해석되지 않은 호출에 넘긴 람다 본문)도 선언을 따라 잇는다")
    void uncompiledRegions() {
        LombokFx.eachClasspath(with("src/com/a/Caller.java", """
                package com.a;
                import java.util.*;
                public class Caller {
                    private Order current;
                    Optional<Customer> find(Long id) { return Optional.empty(); }
                    void audit(Customer c) { }
                    void audit(String name) { }
                    int jdkChains(Order o) {
                        String upper = o.getName().trim().toUpperCase();
                        return o.getItems().stream().map(Object::toString).sorted().toList().size() + upper.length();
                    }
                    void element(Order o) {
                        o.getItems().get(0).hashCode();
                    }
                    Customer lambdaBody(Order o, List<Order> orders) {
                        Item local = new Item();
                        return find(o.getId()).orElseThrow(() -> {
                            audit(o.getCustomer());
                            current.getName();
                            local.hashCode();
                            Order copy = new Order(o.getId(), "n", null, null, null);
                            copy.setName(String.valueOf(1));
                            Order.builder().build();
                            List<String> names = new ArrayList<>();
                            names.add("x");
                            return new IllegalStateException("no customer " + o.getId());
                        });
                    }
                }
                """), (cp, g) -> {
            // getter가 돌려준 JDK 타입에 이어 부른 호출은 실행 중인 JDK에서 이름·인자 수로 따라간다: 미해결이 없다
            String jdkChains = m("com.a.Caller", "jdkChains", "com.a.Order");
            assertThat(targets(g, jdkChains, EdgeKind.CALLS)).as(cp).containsExactlyInAnyOrder(
                    m("com.a.Order", "getName"), m("com.a.Order", "getItems"));
            // 원소 타입은 추적하지 않는다: List.get의 결과에 이어 부른 호출은 체인당 1건으로 남는다
            String element = m("com.a.Caller", "element", "com.a.Order");
            assertThat(unresolved(g, element)).as(cp).containsExactly(Ids.unresolved("java-call", "o.getItems().get(0).hashCode(…)"));
            assertThat(edge(g, element, EdgeKind.CALLS, unresolved(g, element).get(0)).attrBoolean("externalChain")).isTrue();

            String lambdaBody = m("com.a.Caller", "lambdaBody", "com.a.Order", "java.util.List");
            assertThat(unresolved(g, lambdaBody)).as(cp).isEmpty();
            assertThat(Fx.edges(g, lambdaBody, EdgeKind.CALLS, m("com.a.Order", "getId"))).as(cp).hasSize(3);
            assertThat(hasEdge(g, lambdaBody, EdgeKind.CALLS, m("com.a.Caller", "find", "java.lang.Long"))).isTrue();
            // 람다 안: 매개변수·지역 변수·필드의 선언 타입을 AST에서 읽는다
            Edge getCustomer = edge(g, lambdaBody, EdgeKind.CALLS, m("com.a.Order", "getCustomer"));
            assertThat(getCustomer.attrBoolean("viaLambda")).isTrue();
            assertThat(getCustomer.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(hasEdge(g, lambdaBody, EdgeKind.CALLS, m("com.a.Order", "getName"))).as(cp).isTrue();
            assertThat(hasEdge(g, lambdaBody, EdgeKind.CALLS, m("com.a.Order", "setName", "java.lang.String"))).as(cp).isTrue();
            String allArgs = m("com.a.Order", "<init>", "java.lang.Long", "java.lang.String", "com.a.Customer", "java.util.List", "java.lang.Object");
            assertThat(hasEdge(g, lambdaBody, EdgeKind.CALLS, allArgs)).as(cp).isTrue();
            // 같은 패키지의 타입 이름과 Object의 메서드
            assertThat(edge(g, lambdaBody, EdgeKind.CALLS, m("com.a.Order", "builder")).attrString("callType")).as(cp).isEqualTo("static");
            assertThat(hasEdge(g, lambdaBody, EdgeKind.INSTANTIATES, type("com.a.Order"))).as(cp).isTrue();
            // 컴파일러가 보지 않은 곳의 선언 메서드 호출은 이름·인자 수로 찾는다: 오버로드는 추적한 인자 타입으로 줄이고 INFERRED로 남긴다
            Edge audit = edge(g, lambdaBody, EdgeKind.CALLS, m("com.a.Caller", "audit", "com.a.Customer"));
            assertThat(audit.confidence()).as(cp).isEqualTo(Confidence.INFERRED);
            assertThat(audit.attrBoolean("ambiguous")).isFalse();
            assertThat(hasEdge(g, lambdaBody, EdgeKind.CALLS, m("com.a.Caller", "audit", "java.lang.String"))).as(cp).isFalse();
            assertThat(g.diagnostics()).as(cp).noneMatch(d -> d.severity() == Severity.WARN && d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE));
            assertThat(targets(g, type("com.a.Caller"), EdgeKind.DEPENDS_ON)).as(cp).noneMatch(t -> t.startsWith("unresolved:"));
        });
    }

    @Test
    @DisplayName("F-003-AC22 enum 상수의 인자는 Lombok 합성 생성자 호출로 <clinit>()에서 잇는다")
    void enumConstants() {
        LombokFx.eachClasspath(Fx.files(
                "src/com/a/Grade.java", """
                        package com.a;
                        import lombok.Getter;
                        import lombok.RequiredArgsConstructor;
                        @Getter
                        @RequiredArgsConstructor
                        public enum Grade {
                            BASIC("일반", 0), VIP("우수", 10);
                            private final String label;
                            private final int discount;
                        }
                        """,
                "src/com/a/Plain.java", "package com.a; public enum Plain { A, B }"), (cp, g) -> {
            String ctor = m("com.a.Grade", "<init>", "java.lang.String", "int");
            assertThat(Fx.node(g, ctor).attrString("lombok")).as(cp).isEqualTo("RequiredArgsConstructor");
            assertThat(Fx.edges(g, m("com.a.Grade", "<clinit>"), EdgeKind.CALLS, ctor)).as(cp).hasSize(2);
            assertThat(hasEdge(g, type("com.a.Grade"), EdgeKind.CONTAINS, m("com.a.Grade", "getLabel"))).isTrue();
            assertThat(g.node(m("com.a.Grade", "<init>"))).as(cp).isEmpty();
            assertThat(g.diagnostics()).as(cp).noneMatch(d -> d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE));
        });
    }

    @Test
    @DisplayName("F-003-AC14 super(...)·this(...)·super.m()과 val 변수, 직접 선언한 빌더 클래스도 합성 멤버로 잇는다")
    void superCallsValAndDeclaredBuilder() {
        LombokFx.eachClasspath(Fx.files(
                "src/com/a/Parent.java", """
                        package com.a;
                        import lombok.AllArgsConstructor;
                        import lombok.Getter;
                        @Getter
                        @AllArgsConstructor
                        public class Parent {
                            private final String name;
                        }
                        """,
                "src/com/a/Kid.java", """
                        package com.a;
                        import lombok.Builder;
                        import lombok.RequiredArgsConstructor;
                        import lombok.val;
                        @RequiredArgsConstructor
                        public class Kid extends Parent {
                            private final int age;
                            Kid(String name) { super(name); this.age = 0; }
                            Kid() { this("anonymous"); }
                            String show() {
                                val parent = new Parent("p");
                                return super.getName() + parent.getName();
                            }
                            @Builder
                            static Kid make(String name) { return new Kid(name); }
                            public static class KidBuilder {
                                KidBuilder nick(String nick) { return name(nick); }
                            }
                            static Kid sample() { return Kid.builder().nick("n").name("m").build(); }
                        }
                        """), (cp, g) -> {
            String parentCtor = m("com.a.Parent", "<init>", "java.lang.String");
            assertThat(edge(g, m("com.a.Kid", "<init>", "java.lang.String"), EdgeKind.CALLS, parentCtor).attrString("callType"))
                    .as(cp).isEqualTo("constructor");
            // this("anonymous")는 소스에 선언한 생성자다
            assertThat(edge(g, m("com.a.Kid", "<init>"), EdgeKind.CALLS, m("com.a.Kid", "<init>", "java.lang.String")).attrBoolean("lombok"))
                    .isFalse();
            String show = m("com.a.Kid", "show");
            assertThat(Fx.edges(g, show, EdgeKind.CALLS, m("com.a.Parent", "getName"))).as(cp).hasSize(2);
            assertThat(Fx.edges(g, show, EdgeKind.CALLS, m("com.a.Parent", "getName"))).extracting(e -> e.attrString("callType"))
                    .containsExactlyInAnyOrder("super", "virtual");
            assertThat(hasEdge(g, show, EdgeKind.CALLS, parentCtor)).isTrue();
            assertThat(unresolved(g, show)).as(cp).isEmpty();

            // 소스에 선언한 빌더 클래스에는 없는 메서드만 더한다
            Node builderType = Fx.node(g, type("com.a.Kid$KidBuilder"));
            assertThat(builderType.attrBoolean("synthetic")).as(cp).isFalse();
            assertThat(builderType.attrString("lombok")).isNull();
            String sample = m("com.a.Kid", "sample");
            assertThat(targets(g, sample, EdgeKind.CALLS)).as(cp).containsExactlyInAnyOrder(m("com.a.Kid", "builder"),
                    m("com.a.Kid$KidBuilder", "nick", "java.lang.String"), m("com.a.Kid$KidBuilder", "name", "java.lang.String"),
                    m("com.a.Kid$KidBuilder", "build"));
            assertThat(edge(g, sample, EdgeKind.CALLS, m("com.a.Kid$KidBuilder", "nick", "java.lang.String")).attrBoolean("lombok")).isFalse();
            assertThat(hasEdge(g, m("com.a.Kid$KidBuilder", "nick", "java.lang.String"), EdgeKind.CALLS,
                    m("com.a.Kid$KidBuilder", "name", "java.lang.String"))).as(cp).isTrue();
            // 필드 초기화 코드의 소유자를 정할 때 합성 생성자도 센다: 생성자가 셋(선언 둘 + 합성 하나)
            assertThat(Fx.node(g, m("com.a.Kid", "<init>", "int")).attrString("lombok")).isEqualTo("RequiredArgsConstructor");
        });
    }
}
