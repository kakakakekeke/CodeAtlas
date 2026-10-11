package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.hasEdge;
import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.sources;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-003 Java 콜 그래프. */
class JavaCallGraphTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    private static String type(String name) {
        return Ids.javaType(name);
    }

    @Test
    @DisplayName("F-003-AC1 인자 타입으로 오버로드를 구분해 CALLS를 만들고 호출 위치를 남긴다")
    void overloads() {
        GraphModel g = Fx.analyze(
                "src/com/a/Repo.java", """
                        package com.a;
                        public class Repo {
                            void save(String s) { }
                            void save(int n) { }
                            void save(Order o) { }
                        }
                        """,
                "src/com/a/Order.java", "package com.a; public class Order { }",
                "src/com/a/Caller.java", """
                        package com.a;
                        public class Caller {
                            Repo repo;
                            void a() { repo.save("x"); }
                            void b() { repo.save(1); }
                            void c() {
                                repo.save(new Order());
                            }
                        }
                        """);

        assertThat(targets(g, m("com.a.Caller", "a"), EdgeKind.CALLS)).containsExactly(m("com.a.Repo", "save", "java.lang.String"));
        assertThat(targets(g, m("com.a.Caller", "b"), EdgeKind.CALLS)).containsExactly(m("com.a.Repo", "save", "int"));
        Edge c = edge(g, m("com.a.Caller", "c"), EdgeKind.CALLS, m("com.a.Repo", "save", "com.a.Order"));
        assertThat(c.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(c.attrString("callType")).isEqualTo("virtual");
        assertThat(c.location().file()).isEqualTo("src/com/a/Caller.java");
        assertThat(c.location().startLine()).isEqualTo(7);
    }

    @Test
    @DisplayName("F-003-AC2 인터페이스 경유 호출은 인터페이스 메서드로 잇고 구현 메서드는 OVERRIDES로 잇는다")
    void interfaceCallAndOverrides() {
        GraphModel g = Fx.analyze(
                "src/com/a/OrderService.java", "package com.a; public interface OrderService { Order find(long id); void close(); }",
                "src/com/a/Order.java", "package com.a; public class Order { }",
                "src/com/a/AbstractService.java", "package com.a; public abstract class AbstractService { public void close() { } protected abstract void hook(); }",
                "src/com/a/OrderServiceImpl.java", """
                        package com.a;
                        public class OrderServiceImpl extends AbstractService implements OrderService {
                            public Order find(long id) { return null; }
                            @Override public void close() { }
                            @Override protected void hook() { }
                            public String toString() { return ""; }
                        }
                        """,
                "src/com/a/Controller.java", """
                        package com.a;
                        public class Controller {
                            private OrderService orderService;
                            Order get() { return orderService.find(1L); }
                        }
                        """);

        Edge call = edge(g, m("com.a.Controller", "get"), EdgeKind.CALLS, m("com.a.OrderService", "find", "long"));
        assertThat(call.attrString("callType")).isEqualTo("interface");
        assertThat(hasEdge(g, m("com.a.Controller", "get"), EdgeKind.CALLS, m("com.a.OrderServiceImpl", "find", "long"))).isFalse();

        assertThat(targets(g, m("com.a.OrderServiceImpl", "find", "long"), EdgeKind.OVERRIDES))
                .containsExactly(m("com.a.OrderService", "find", "long"));
        // 상위 클래스와 인터페이스 양쪽의 선언을 모두 잇는다
        assertThat(targets(g, m("com.a.OrderServiceImpl", "close"), EdgeKind.OVERRIDES))
                .containsExactlyInAnyOrder(m("com.a.AbstractService", "close"), m("com.a.OrderService", "close"));
        assertThat(targets(g, m("com.a.OrderServiceImpl", "hook"), EdgeKind.OVERRIDES)).containsExactly(m("com.a.AbstractService", "hook"));
        // java.lang.Object의 메서드는 대상이 아니다
        assertThat(targets(g, m("com.a.OrderServiceImpl", "toString"), EdgeKind.OVERRIDES)).isEmpty();
    }

    @Test
    @DisplayName("F-003-AC3 제네릭 상위 클래스의 메서드 호출은 선언으로 잇고 타입 인자를 바꾼 재정의도 OVERRIDES로 잡는다")
    void genericSuperclass() {
        GraphModel g = Fx.analyze(
                "src/com/a/BaseService.java", """
                        package com.a;
                        public abstract class BaseService<T> {
                            public void save(T entity) { validate(entity); }
                            protected void validate(T entity) { }
                            public T load(long id) { return null; }
                        }
                        """,
                "src/com/a/Order.java", "package com.a; public class Order { }",
                "src/com/a/OrderService.java", """
                        package com.a;
                        public class OrderService extends BaseService<Order> {
                            @Override public void save(Order order) { super.save(order); }
                        }
                        """,
                "src/com/a/Caller.java", """
                        package com.a;
                        public class Caller {
                            void run(OrderService service, BaseService<Order> base) {
                                Order o = service.load(1L);
                                service.save(o);
                                base.save(o);
                            }
                        }
                        """);

        String run = m("com.a.Caller", "run", "com.a.OrderService", "com.a.BaseService");
        Edge load = edge(g, run, EdgeKind.CALLS, m("com.a.BaseService", "load", "long"));
        assertThat(load.attrString("receiverType")).isEqualTo("com.a.OrderService");
        assertThat(hasEdge(g, run, EdgeKind.CALLS, m("com.a.OrderService", "save", "com.a.Order"))).isTrue();
        assertThat(hasEdge(g, run, EdgeKind.CALLS, m("com.a.BaseService", "save", "java.lang.Object"))).isTrue();
        assertThat(targets(g, m("com.a.OrderService", "save", "com.a.Order"), EdgeKind.OVERRIDES))
                .containsExactly(m("com.a.BaseService", "save", "java.lang.Object"));
        Edge superCall = edge(g, m("com.a.OrderService", "save", "com.a.Order"), EdgeKind.CALLS, m("com.a.BaseService", "save", "java.lang.Object"));
        assertThat(superCall.attrString("callType")).isEqualTo("super");
        assertThat(hasEdge(g, m("com.a.BaseService", "save", "java.lang.Object"), EdgeKind.CALLS, m("com.a.BaseService", "validate", "java.lang.Object"))).isTrue();
    }

    @Test
    @DisplayName("F-003-AC4 static import, 클래스 이름 static 호출, super.method(), default 메서드, 가변 인자, 중첩 호출")
    void callKinds() {
        GraphModel g = Fx.analyze(
                "src/com/a/Util.java", """
                        package com.a;
                        public class Util {
                            public static String trim(String s) { return s; }
                            public static String join(String sep, Object... parts) { return sep; }
                        }
                        """,
                "src/com/a/Greeter.java", "package com.a; public interface Greeter { default String greet() { return name(); } String name(); }",
                "src/com/a/Base.java", "package com.a; public class Base { void init() { } A a() { return new A(); } }",
                "src/com/a/A.java", "package com.a; public class A { B b() { return new B(); } }",
                "src/com/a/B.java", "package com.a; public class B { void c() { } }",
                "src/com/a/Sub.java", """
                        package com.a;
                        import static com.a.Util.trim;
                        public class Sub extends Base implements Greeter {
                            public String name() { return "x"; }
                            @Override void init() { super.init(); }
                            void run(Greeter other) {
                                trim("a");
                                Util.trim("b");
                                other.greet();
                                Util.join(",", 1, 2, 3);
                                a().b().c();
                            }
                        }
                        """);

        String run = m("com.a.Sub", "run", "com.a.Greeter");
        List<Edge> trims = Fx.edges(g, run, EdgeKind.CALLS, m("com.a.Util", "trim", "java.lang.String"));
        assertThat(trims).hasSize(2).allMatch(e -> "static".equals(e.attrString("callType")));
        assertThat(edge(g, run, EdgeKind.CALLS, m("com.a.Greeter", "greet")).attrString("callType")).isEqualTo("interface");
        assertThat(edge(g, run, EdgeKind.CALLS, m("com.a.Util", "join", "java.lang.String", "java.lang.Object[]")).attrString("callType")).isEqualTo("static");
        assertThat(edge(g, run, EdgeKind.CALLS, m("com.a.Base", "a")).attrString("callType")).isEqualTo("virtual");
        assertThat(hasEdge(g, run, EdgeKind.CALLS, m("com.a.A", "b"))).isTrue();
        assertThat(hasEdge(g, run, EdgeKind.CALLS, m("com.a.B", "c"))).isTrue();
        assertThat(edge(g, m("com.a.Sub", "init"), EdgeKind.CALLS, m("com.a.Base", "init")).attrString("callType")).isEqualTo("super");
        // default 메서드 본문의 호출
        assertThat(hasEdge(g, m("com.a.Greeter", "greet"), EdgeKind.CALLS, m("com.a.Greeter", "name"))).isTrue();
    }

    @Test
    @DisplayName("F-003-AC5 new, this(...), super(...)는 생성자로 CALLS를 만들고 new는 INSTANTIATES도 만든다")
    void constructors() {
        GraphModel g = Fx.analyze(
                "src/com/a/Base.java", "package com.a; public class Base { Base(String name) { } }",
                "src/com/a/Order.java", """
                        package com.a;
                        public class Order extends Base {
                            Order() { this(1); }
                            Order(int n) { super("o"); }
                        }
                        """,
                "src/com/a/Plain.java", "package com.a; public class Plain { }",
                "src/com/a/Factory.java", """
                        package com.a;
                        public class Factory {
                            Object make() {
                                new Plain();
                                return new Order(1);
                            }
                        }
                        """);

        String make = m("com.a.Factory", "make");
        assertThat(edge(g, make, EdgeKind.CALLS, m("com.a.Order", "<init>", "int")).attrString("callType")).isEqualTo("constructor");
        assertThat(hasEdge(g, make, EdgeKind.INSTANTIATES, type("com.a.Order"))).isTrue();
        assertThat(hasEdge(g, m("com.a.Order", "<init>"), EdgeKind.CALLS, m("com.a.Order", "<init>", "int"))).isTrue();
        assertThat(edge(g, m("com.a.Order", "<init>", "int"), EdgeKind.CALLS, m("com.a.Base", "<init>", "java.lang.String")).attrString("callType")).isEqualTo("constructor");
        // 선언하지 않은 기본 생성자
        assertThat(hasEdge(g, make, EdgeKind.CALLS, m("com.a.Plain", "<init>"))).isTrue();
        Node defaultCtor = node(g, m("com.a.Plain", "<init>"));
        assertThat(defaultCtor.attrBoolean("synthetic")).isTrue();
        assertThat(defaultCtor.declared()).isTrue();
        assertThat(hasEdge(g, type("com.a.Plain"), EdgeKind.CONTAINS, defaultCtor.id())).isTrue();
        assertThat(hasEdge(g, make, EdgeKind.INSTANTIATES, type("com.a.Plain"))).isTrue();
    }

    @Test
    @DisplayName("F-003-AC6 람다 안 호출은 감싼 메서드에(viaLambda), 메서드 참조는 methodRef로, 익명·내부 클래스의 호출은 그 타입의 메서드에 귀속한다")
    void lambdasMethodRefsAnonymous() {
        GraphModel g = Fx.analyze(
                "src/com/a/Order.java", "package com.a; public class Order { Order() { } Long getId() { return 1L; } }",
                "src/com/a/Outer.java", """
                        package com.a;
                        import java.util.List;
                        import java.util.function.Supplier;
                        public class Outer {
                            void handle(Order o) { }
                            void helper() { }
                            void lambdas(List<Order> orders) {
                                orders.forEach(o -> handle(o));
                                orders.forEach(this::handle);
                                orders.stream().map(Order::getId);
                                Supplier<Order> s = Order::new;
                                Runnable r = () -> { Runnable inner = () -> helper(); };
                            }
                            void anonymous() {
                                Runnable r = new Runnable() {
                                    public void run() { helper(); }
                                };
                                r.run();
                            }
                            class Inner {
                                void work() { helper(); }
                            }
                        }
                        """);

        String lambdas = m("com.a.Outer", "lambdas", "java.util.List");
        String handle = m("com.a.Outer", "handle", "com.a.Order");
        List<Edge> toHandle = Fx.edges(g, lambdas, EdgeKind.CALLS, handle);
        assertThat(toHandle).hasSize(2);
        assertThat(toHandle).filteredOn(e -> e.attrBoolean("viaLambda")).hasSize(1).allMatch(e -> "virtual".equals(e.attrString("callType")));
        assertThat(toHandle).filteredOn(e -> "methodRef".equals(e.attrString("callType"))).hasSize(1);
        assertThat(edge(g, lambdas, EdgeKind.CALLS, m("com.a.Order", "getId")).attrString("callType")).isEqualTo("methodRef");
        assertThat(edge(g, lambdas, EdgeKind.CALLS, m("com.a.Order", "<init>")).attrString("callType")).isEqualTo("methodRef");
        assertThat(hasEdge(g, lambdas, EdgeKind.INSTANTIATES, type("com.a.Order"))).isTrue();
        // 중첩 람다도 가장 바깥의 메서드로
        assertThat(edge(g, lambdas, EdgeKind.CALLS, m("com.a.Outer", "helper")).attrBoolean("viaLambda")).isTrue();

        String anonRun = m("com.a.Outer$1", "run");
        node(g, anonRun);
        assertThat(hasEdge(g, anonRun, EdgeKind.CALLS, m("com.a.Outer", "helper"))).isTrue();
        assertThat(hasEdge(g, m("com.a.Outer", "anonymous"), EdgeKind.CALLS, m("com.a.Outer", "helper"))).isFalse();
        assertThat(hasEdge(g, m("com.a.Outer", "anonymous"), EdgeKind.INSTANTIATES, type("com.a.Outer$1"))).isTrue();
        assertThat(hasEdge(g, m("com.a.Outer", "anonymous"), EdgeKind.CALLS, m("com.a.Outer$1", "<init>"))).isTrue();
        assertThat(hasEdge(g, m("com.a.Outer$Inner", "work"), EdgeKind.CALLS, m("com.a.Outer", "helper"))).isTrue();
    }

    @Test
    @DisplayName("F-003-AC7 필드 초기화식·초기화 블록·enum 상수의 코드는 합성 소유자에 귀속한다")
    void initializerOwners() {
        GraphModel g = Fx.analyze(
                "src/com/a/Dep.java", "package com.a; public class Dep { static Dep make() { return null; } static int size() { return 0; } }",
                "src/com/a/NoCtor.java", "package com.a; public class NoCtor { Dep dep = Dep.make(); }",
                "src/com/a/OneCtor.java", "package com.a; public class OneCtor { Dep dep = Dep.make(); OneCtor(int n) { } }",
                "src/com/a/TwoCtors.java", "package com.a; public class TwoCtors { Dep dep = Dep.make(); { Dep.size(); } TwoCtors() { } TwoCtors(int n) { } }",
                "src/com/a/Statics.java", "package com.a; public class Statics { static final Dep DEP = Dep.make(); static { Dep.size(); } }",
                "src/com/a/Op.java", """
                        package com.a;
                        public enum Op {
                            PLUS(Dep.size()) { int apply(int a) { return a + helper(); } },
                            NOOP(0);
                            Op(int code) { }
                            int apply(int a) { return a; }
                            static int helper() { return 1; }
                        }
                        """);

        String make = m("com.a.Dep", "make");
        String size = m("com.a.Dep", "size");
        assertThat(sources(g, make, EdgeKind.CALLS)).containsExactlyInAnyOrder(
                m("com.a.NoCtor", "<init>"), m("com.a.OneCtor", "<init>", "int"), m("com.a.TwoCtors", "<instance-init>"), m("com.a.Statics", "<clinit>"));
        assertThat(node(g, m("com.a.NoCtor", "<init>")).attrBoolean("synthetic")).isTrue();
        assertThat(node(g, m("com.a.OneCtor", "<init>", "int")).attrBoolean("synthetic")).isFalse();
        Node instanceInit = node(g, m("com.a.TwoCtors", "<instance-init>"));
        assertThat(instanceInit.attrBoolean("synthetic")).isTrue();
        assertThat(hasEdge(g, type("com.a.TwoCtors"), EdgeKind.CONTAINS, instanceInit.id())).isTrue();
        assertThat(node(g, m("com.a.Statics", "<clinit>")).attrBoolean("synthetic")).isTrue();
        assertThat(sources(g, size, EdgeKind.CALLS)).containsExactlyInAnyOrder(
                m("com.a.TwoCtors", "<instance-init>"), m("com.a.Statics", "<clinit>"), m("com.a.Op", "<clinit>"));

        // enum 상수 본문의 메서드는 익명 타입의 메서드, enum 생성자 호출은 <clinit>에서
        String constantBody = m("com.a.Op$1", "apply", "int");
        node(g, constantBody);
        assertThat(hasEdge(g, constantBody, EdgeKind.CALLS, m("com.a.Op", "helper"))).isTrue();
        assertThat(targets(g, constantBody, EdgeKind.OVERRIDES)).containsExactly(m("com.a.Op", "apply", "int"));
        assertThat(hasEdge(g, m("com.a.Op", "<clinit>"), EdgeKind.CALLS, m("com.a.Op", "<init>", "int"))).isTrue();
    }

    @Test
    @DisplayName("F-003-AC8 프로젝트 필드의 읽기와 쓰기로 ACCESSES를 만든다")
    void fieldAccess() {
        GraphModel g = Fx.analyze(
                "src/com/a/Consts.java", "package com.a; public class Consts { public static final String YES = \"Y\"; }",
                "src/com/a/Status.java", "package com.a; public enum Status { OPEN, CLOSED }",
                "src/com/a/Counter.java", """
                        package com.a;
                        public class Counter {
                            private int count;
                            private String flag;
                            Counter other;
                            int read() { return count; }
                            void write(int n) { this.count = n; }
                            void inc() { count++; }
                            void flags() { flag = Consts.YES; Status s = Status.OPEN; int len = flag.length(); int max = Integer.MAX_VALUE; }
                            void nested() { other.other.count = 1; }
                        }
                        """);

        String count = Ids.javaField("com.a.Counter", "count");
        assertThat(edge(g, m("com.a.Counter", "read"), EdgeKind.ACCESSES, count).attrString("access")).isEqualTo("read");
        assertThat(edge(g, m("com.a.Counter", "write", "int"), EdgeKind.ACCESSES, count).attrString("access")).isEqualTo("write");
        assertThat(edge(g, m("com.a.Counter", "inc"), EdgeKind.ACCESSES, count).attrString("access")).isEqualTo("write");
        String flags = m("com.a.Counter", "flags");
        assertThat(Fx.edges(g, flags, EdgeKind.ACCESSES, Ids.javaField("com.a.Counter", "flag")))
                .extracting(e -> e.attrString("access")).containsExactlyInAnyOrder("write", "read");
        assertThat(edge(g, flags, EdgeKind.ACCESSES, Ids.javaField("com.a.Consts", "YES")).attrString("access")).isEqualTo("read");
        assertThat(edge(g, flags, EdgeKind.ACCESSES, Ids.javaField("com.a.Status", "OPEN")).attrString("access")).isEqualTo("read");
        assertThat(targets(g, flags, EdgeKind.ACCESSES)).noneMatch(t -> t.contains("java.lang"));
        String nested = m("com.a.Counter", "nested");
        assertThat(Fx.edges(g, nested, EdgeKind.ACCESSES, Ids.javaField("com.a.Counter", "other")))
                .hasSize(2).allMatch(e -> "read".equals(e.attrString("access")));
        assertThat(edge(g, nested, EdgeKind.ACCESSES, count).attrString("access")).isEqualTo("write");
    }

    private static final Map<String, String> EXTERNAL = Fx.files(
            "src/com/a/Caller.java", """
                    package com.a;
                    import java.util.ArrayList;
                    import java.util.List;
                    import lib.util.LibBase;
                    public class Caller {
                        void run(LibBase lib) {
                            List<String> list = new ArrayList<>();
                            list.add(LibBase.util("x"));
                            lib.libMethod();
                            String.valueOf(1);
                        }
                    }
                    """);

    @Test
    @DisplayName("F-003-AC9 기본 옵션은 라이브러리 호출만 external 메서드로 기록하고 JDK 호출 수는 INFO 진단 1건으로 요약한다")
    void externalCallsDefault() {
        GraphModel g = Fx.analyze(AnalysisOptions.builder().classpath(Fx.stubClasses()).build(), EXTERNAL);

        String run = m("com.a.Caller", "run", "lib.util.LibBase");
        assertThat(targets(g, run, EdgeKind.CALLS)).containsExactlyInAnyOrder(
                m("lib.util.LibBase", "util", "java.lang.String"), m("lib.util.LibBase", "libMethod"));
        Node ext = node(g, m("lib.util.LibBase", "libMethod"));
        assertThat(ext.declared()).isFalse();
        assertThat(ext.attrBoolean("external")).isTrue();
        assertThat(edge(g, run, EdgeKind.CALLS, ext.id()).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(targets(g, run, EdgeKind.INSTANTIATES)).isEmpty();
        assertThat(g.diagnostics()).filteredOn(d -> d.code().equals("EXTERNAL_CALLS_SKIPPED")).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("JDK 3건");
        });
    }

    @Test
    @DisplayName("F-003-AC9 java.externalCalls=none이면 외부 호출을 기록하지 않고 all이면 JDK 호출도 기록한다")
    void externalCallsOption() {
        GraphModel none = Fx.analyze(AnalysisOptions.builder().classpath(Fx.stubClasses()).extra("java.externalCalls", "none").build(), EXTERNAL);
        String run = m("com.a.Caller", "run", "lib.util.LibBase");
        assertThat(targets(none, run, EdgeKind.CALLS)).isEmpty();

        GraphModel all = Fx.analyze(AnalysisOptions.builder().classpath(Fx.stubClasses()).extra("java.externalCalls", "all").build(), EXTERNAL);
        assertThat(targets(all, run, EdgeKind.CALLS)).contains(
                m("lib.util.LibBase", "libMethod"), m("java.util.List", "add", "java.lang.Object"),
                m("java.util.ArrayList", "<init>"), m("java.lang.String", "valueOf", "int"));
        assertThat(hasEdge(all, run, EdgeKind.INSTANTIATES, type("java.util.ArrayList"))).isTrue();
        assertThat(all.diagnostics()).noneMatch(d -> d.code().equals("EXTERNAL_CALLS_SKIPPED"));
    }

    @Test
    @DisplayName("F-003-AC10 jar 없는 타입의 호출은 external 메서드로, 그 결과에 이은 호출과 프로젝트 타입에 없는 메서드 호출은 미해결로 남긴다")
    void unresolvedCalls() {
        GraphModel g = Fx.analyze(
                "src/com/a/Vo.java", "package com.a; public class Vo { String name; }",
                "src/com/a/Dao.java", """
                        package com.a;
                        import org.mybatis.spring.SqlSessionTemplate;
                        import org.apache.commons.lang3.StringUtils;
                        import static org.junit.Assert.assertEquals;
                        public class Dao {
                            private SqlSessionTemplate sqlSession;
                            Object list(Vo vo) {
                                helper();
                                StringUtils.isEmpty("a");
                                assertEquals(1, 1);
                                vo.getName();
                                sqlSession.getMapper(Vo.class).selectAll();
                                return sqlSession.selectList("a.b", vo);
                            }
                            void helper() { }
                        }
                        """);

        String list = m("com.a.Dao", "list", "com.a.Vo");
        // 프로젝트 내부 호출은 그대로 해석
        assertThat(edge(g, list, EdgeKind.CALLS, m("com.a.Dao", "helper")).confidence()).isEqualTo(Confidence.RESOLVED);
        // jar 없는 타입의 필드로 부른 호출 → <FQN>#<이름>(?)
        String selectList = m("org.mybatis.spring.SqlSessionTemplate", "selectList", "?");
        assertThat(edge(g, list, EdgeKind.CALLS, selectList).confidence()).isEqualTo(Confidence.RESOLVED);
        Node ext = node(g, selectList);
        assertThat(ext.attrBoolean("external")).isTrue();
        assertThat(ext.attrBoolean("signatureUnknown")).isTrue();
        assertThat(hasEdge(g, list, EdgeKind.CALLS, m("org.mybatis.spring.SqlSessionTemplate", "getMapper", "?"))).isTrue();
        // import한 미해석 타입의 static 호출, static import
        assertThat(edge(g, list, EdgeKind.CALLS, m("org.apache.commons.lang3.StringUtils", "isEmpty", "?")).attrString("callType")).isEqualTo("static");
        assertThat(hasEdge(g, list, EdgeKind.CALLS, m("org.junit.Assert", "assertEquals", "?"))).isTrue();
        // 프로젝트 타입에 없는 메서드(Lombok 등) → 미해결
        Edge getter = edge(g, list, EdgeKind.CALLS, Ids.unresolved("java-call", "vo.getName(…)"));
        assertThat(getter.confidence()).isEqualTo(Confidence.UNRESOLVED);
        // 타입을 알 수 없는 결과에 이은 호출 → 미해결
        assertThat(edge(g, list, EdgeKind.CALLS, Ids.unresolved("java-call", "sqlSession.getMapper(Vo.class).selectAll(…)")).confidence())
                .isEqualTo(Confidence.UNRESOLVED);
        assertThat(g.diagnostics()).filteredOn(d -> d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE) && d.message().contains("java-call")).hasSize(2);
    }

    @Test
    @DisplayName("F-003-AC10 classpath에 없는 상위 타입이 하나면 수신자 없는 미해석 호출을 그 타입의 메서드로 보고, 여럿이면 상위 클래스 쪽으로 INFERRED다(F-003-AC43, ambiguous 없음: F-003-R29)")
    void inheritedFromMissingSupertype() {
        GraphModel g = Fx.analyze(
                "src/com/a/Dao.java", """
                        package com.a;
                        import org.mybatis.spring.support.SqlSessionDaoSupport;
                        public class Dao extends SqlSessionDaoSupport {
                            Object session() { return getSqlSession(); }
                        }
                        """,
                "src/com/a/Two.java", """
                        package com.a;
                        import org.ext.One;
                        import org.ext.Other;
                        public class Two extends One implements Other {
                            void run() { inherited(); }
                        }
                        """);

        Edge e = edge(g, m("com.a.Dao", "session"), EdgeKind.CALLS, m("org.mybatis.spring.support.SqlSessionDaoSupport", "getSqlSession", "?"));
        assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
        // 2026-10-11 고쳐 씀(F-003-R25): 전에는 상위 타입이 여럿이면 미해결이었다. 수신자를 적은 호출(F-003-R19)과 같은 규칙으로 바꿨다
        Edge several = edge(g, m("com.a.Two", "run"), EdgeKind.CALLS, m("org.ext.One", "inherited", "?"));
        assertThat(several.confidence()).isEqualTo(Confidence.INFERRED);
        // 2026-10-11 고쳐 씀(F-003-R29): 후보가 하나뿐인 엣지에는 ambiguous를 붙이지 않는다
        assertThat(several.attrs()).doesNotContainKey("ambiguous");
        assertThat(several.attrString("inheritedFrom")).isEqualTo("org.ext.One");
    }

    @Test
    @DisplayName("F-003-AC11 인자가 맞지 않아 컴파일러가 가장 가까운 후보를 고른 호출은 그 후보로 잇지 않고 미해결로 남긴다")
    void closestMatchIsNotTrusted() {
        GraphModel g = Fx.analyze(
                "src/com/a/Vo.java", "package com.a; public class Vo { Vo(String name) { } void set(String a, String b) { } }",
                "src/com/a/Caller.java", """
                        package com.a;
                        public class Caller {
                            void run() {
                                Vo vo = new Vo();
                                vo.set("only-one");
                            }
                        }
                        """);

        String run = m("com.a.Caller", "run");
        assertThat(hasEdge(g, run, EdgeKind.CALLS, m("com.a.Vo", "<init>", "java.lang.String"))).isFalse();
        assertThat(hasEdge(g, run, EdgeKind.CALLS, m("com.a.Vo", "set", "java.lang.String", "java.lang.String"))).isFalse();
        assertThat(edge(g, run, EdgeKind.CALLS, Ids.unresolved("java-call", "new Vo(…)")).confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(edge(g, run, EdgeKind.CALLS, Ids.unresolved("java-call", "vo.set(…)")).confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    @DisplayName("F-003-AC12 classpath에 있는 라이브러리 인터페이스의 구현은 external 메서드로 OVERRIDES를 만든다")
    void overridesExternal() {
        GraphModel g = Fx.analyzeWithStubs(
                "src/com/a/Listener.java", """
                        package com.a;
                        import lib.util.LibCallback;
                        public class Listener implements LibCallback, Runnable {
                            @Override public void onEvent(String name) { }
                            @Override public void run() { }
                        }
                        """);

        String target = m("lib.util.LibCallback", "onEvent", "java.lang.String");
        assertThat(edge(g, m("com.a.Listener", "onEvent", "java.lang.String"), EdgeKind.OVERRIDES, target).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(node(g, target).attrBoolean("external")).isTrue();
        // JDK 메서드의 재정의는 기본 옵션에서 만들지 않는다
        assertThat(targets(g, m("com.a.Listener", "run"), EdgeKind.OVERRIDES)).isEmpty();
    }

    @Test
    @DisplayName("F-003-AC12 classpath에 없는 인터페이스를 @Override로 구현하면 <FQN>#<이름>(?)로 INFERRED OVERRIDES를 만든다")
    void overridesMissingSupertype() {
        GraphModel g = Fx.analyze(
                "src/com/a/CloseTasklet.java", """
                        package com.a;
                        import org.springframework.batch.core.step.tasklet.Tasklet;
                        public class CloseTasklet implements Tasklet {
                            @Override public Object execute(Object contribution, Object context) { return null; }
                            public void notOverride() { }
                        }
                        """);

        Edge e = edge(g, m("com.a.CloseTasklet", "execute", "java.lang.Object", "java.lang.Object"), EdgeKind.OVERRIDES,
                m("org.springframework.batch.core.step.tasklet.Tasklet", "execute", "?"));
        assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(targets(g, m("com.a.CloseTasklet", "notOverride"), EdgeKind.OVERRIDES)).isEmpty();
    }

    @Test
    @DisplayName("F-003-AC19 external 타입의 반환값에 이어 부르는 체인은 링크마다가 아니라 체인당 미해결 엣지 1건과 INFO 진단 1건으로 남긴다")
    void externalChainCollapses() {
        GraphModel g = Fx.analyze(
                "src/com/a/JobConfig.java", """
                        package com.a;
                        import org.springframework.batch.core.step.builder.StepBuilder;
                        import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
                        public class JobConfig {
                            private Object repo;
                            private Object writer;
                            Object step() {
                                return new StepBuilder("s", repo)
                                        .chunk(10)
                                        .reader(reader())
                                        .writer(writer)
                                        .build();
                            }
                            Object reader() { return null; }
                            void register(InterceptorRegistry registry) {
                                registry.addInterceptor(null).addPathPatterns("/a").excludePathPatterns("/b");
                                var added = registry.addInterceptor(null);
                                added.order(1);
                            }
                        }
                        """);

        String step = m("com.a.JobConfig", "step");
        String builder = "org.springframework.batch.core.step.builder.StepBuilder";
        // 첫 링크까지는 external 호출, 체인 중간 인자 안의 호출은 그대로 해석한다
        assertThat(hasEdge(g, step, EdgeKind.CALLS, m(builder, "<init>", "?"))).isTrue();
        assertThat(hasEdge(g, step, EdgeKind.CALLS, m(builder, "chunk", "?"))).isTrue();
        assertThat(edge(g, step, EdgeKind.CALLS, m("com.a.JobConfig", "reader")).confidence()).isEqualTo(Confidence.RESOLVED);
        // 뒤따르는 링크 셋(reader, writer, build)은 체인 전체 식으로 미해결 1건
        String chain = Ids.unresolved("java-call", "new StepBuilder(\"s\",repo).chunk(10).reader(reader()).writer(writer).build(…)");
        List<String> unresolved = targets(g, step, EdgeKind.CALLS).stream().filter(t -> t.startsWith("unresolved:")).toList();
        assertThat(unresolved).containsExactly(chain);
        Edge e = edge(g, step, EdgeKind.CALLS, chain);
        assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(e.attrBoolean("externalChain")).isTrue();
        // 위치는 체인 식 전체
        assertThat(e.location().startLine()).isEqualTo(8);
        assertThat(e.location().endLine()).isEqualTo(12);

        String register = m("com.a.JobConfig", "register", "org.springframework.web.servlet.config.annotation.InterceptorRegistry");
        assertThat(targets(g, register, EdgeKind.CALLS).stream().filter(t -> t.startsWith("unresolved:")).toList()).containsExactlyInAnyOrder(
                Ids.unresolved("java-call", "registry.addInterceptor(null).addPathPatterns(\"/a\").excludePathPatterns(…)"),
                Ids.unresolved("java-call", "added.order(…)"));

        List<io.codeatlas.core.model.Diagnostic> diagnostics = g.diagnostics().stream()
                .filter(d -> d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE)).toList();
        assertThat(diagnostics).hasSize(3);
        assertThat(diagnostics).allSatisfy(d -> {
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("java-call");
        });
    }

    @Test
    @DisplayName("F-003-AC20 소스 없는 타입의 메서드를 인자 불일치로 고르지 못하면 가까운 후보가 아니라 <FQN>#<이름>(?) external 호출로 남긴다")
    void mismatchOnExternalType() {
        GraphModel g = Fx.analyzeWithStubs(
                "src/com/a/Caller.java", """
                        package com.a;
                        import lib.util.LibBase;
                        public class Caller {
                            void run(LibBase lib, StringBuilder sb) {
                                LibBase.util(1, 2);
                                sb.append(1, 2, 3, 4, 5);
                            }
                        }
                        """);

        String run = m("com.a.Caller", "run", "lib.util.LibBase", "java.lang.StringBuilder");
        Edge e = edge(g, run, EdgeKind.CALLS, m("lib.util.LibBase", "util", "?"));
        assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(e.attrString("callType")).isEqualTo("static");
        assertThat(node(g, m("lib.util.LibBase", "util", "?")).attrBoolean("signatureUnknown")).isTrue();
        assertThat(hasEdge(g, run, EdgeKind.CALLS, m("lib.util.LibBase", "util", "java.lang.String"))).isFalse();
        // JDK 타입이면 기본 옵션에서는 기록하지 않는 호출로 센다
        assertThat(targets(g, run, EdgeKind.CALLS)).containsExactly(m("lib.util.LibBase", "util", "?"));
        assertThat(g.diagnostics()).noneMatch(d -> d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE));
    }

    private static List<String> unresolved(GraphModel g, String from) {
        return targets(g, from, EdgeKind.CALLS).stream().filter(t -> t.startsWith("unresolved:")).toList();
    }

    private static final Map<String, String> EXTERNAL_CONSTRUCTORS = Fx.files(
            "src/com/a/Config.java", """
                    package com.a;
                    import org.apache.ibatis.session.ExecutorType;
                    import org.apache.ibatis.session.SqlSessionFactory;
                    import org.mybatis.spring.SqlSessionTemplate;
                    import org.springframework.batch.core.job.builder.JobBuilder;
                    import org.springframework.batch.core.repository.JobRepository;
                    import org.springframework.web.servlet.ModelAndView;
                    public class Config extends SqlSessionTemplate {
                        private JobRepository jobRepository;
                        Config(SqlSessionFactory f) { super(f, ExecutorType.BATCH); }
                        Object session(SqlSessionFactory f) { return new SqlSessionTemplate(f, ExecutorType.BATCH); }
                        Object job() { return new JobBuilder("j", jobRepository); }
                        Object view() { return new ModelAndView("main"); }
                    }
                    """);

    @Test
    @DisplayName("F-003-AC23 jar 없는 타입의 생성자를 미해석 타입의 인자로 불러도(new, super(..)) <FQN>#<init>(?) external 호출로 남긴다")
    void externalConstructorWithUnresolvedArguments() {
        GraphModel g = Fx.analyze(AnalysisOptions.defaults(), EXTERNAL_CONSTRUCTORS);

        String template = "org.mybatis.spring.SqlSessionTemplate";
        String builder = "org.springframework.batch.core.job.builder.JobBuilder";
        String session = m("com.a.Config", "session", "org.apache.ibatis.session.SqlSessionFactory");
        Edge created = edge(g, session, EdgeKind.CALLS, m(template, "<init>", "?"));
        assertThat(created.attrString("callType")).isEqualTo("constructor");
        assertThat(created.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(created.location().startLine()).isEqualTo(11);
        assertThat(hasEdge(g, session, EdgeKind.INSTANTIATES, type(template))).isTrue();
        String job = m("com.a.Config", "job");
        assertThat(edge(g, job, EdgeKind.CALLS, m(builder, "<init>", "?")).attrString("callType")).isEqualTo("constructor");
        assertThat(hasEdge(g, job, EdgeKind.INSTANTIATES, type(builder))).isTrue();
        // 대조군: 인자가 모두 해석되는 생성
        assertThat(hasEdge(g, m("com.a.Config", "view"), EdgeKind.CALLS, m("org.springframework.web.servlet.ModelAndView", "<init>", "?"))).isTrue();
        // super(..): classpath에 없는 상위 클래스의 생성자
        String constructor = m("com.a.Config", "<init>", "org.apache.ibatis.session.SqlSessionFactory");
        assertThat(edge(g, constructor, EdgeKind.CALLS, m(template, "<init>", "?")).attrString("callType")).isEqualTo("constructor");
        // java.lang.Object의 생성자 호출로 잘못 세지 않는다
        assertThat(g.diagnostics()).noneMatch(d -> d.code().equals("EXTERNAL_CALLS_SKIPPED"));
        assertThat(g.diagnostics()).noneMatch(d -> d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE));
    }

    @Test
    @DisplayName("F-003-AC23 java.externalCalls=none이면 그 생성자 호출을 라이브러리 호출로 세고, jar가 있어도 인자 수가 맞지 않으면 (?)로 남긴다")
    void externalConstructorWithUnresolvedArgumentsOptions() {
        GraphModel none = Fx.analyze(Fx.extra("java.externalCalls", "none"), EXTERNAL_CONSTRUCTORS);
        assertThat(none.edges(EdgeKind.CALLS)).isEmpty();
        assertThat(none.diagnostics()).filteredOn(d -> d.code().equals("EXTERNAL_CALLS_SKIPPED")).singleElement()
                .satisfies(d -> assertThat(d.message()).contains("JDK 0건").contains("라이브러리 4건"));

        // 스텁의 SqlSessionTemplate에는 (SqlSessionFactory) 생성자만 있고 ExecutorType은 없다
        GraphModel stubs = Fx.analyze(AnalysisOptions.builder().classpath(Fx.stubClasses()).build(), EXTERNAL_CONSTRUCTORS);
        String template = "org.mybatis.spring.SqlSessionTemplate";
        String session = m("com.a.Config", "session", "org.apache.ibatis.session.SqlSessionFactory");
        assertThat(targets(stubs, session, EdgeKind.CALLS)).containsExactly(m(template, "<init>", "?"));
        assertThat(hasEdge(stubs, session, EdgeKind.INSTANTIATES, type(template))).isTrue();
        String constructor = m("com.a.Config", "<init>", "org.apache.ibatis.session.SqlSessionFactory");
        assertThat(targets(stubs, constructor, EdgeKind.CALLS)).containsExactly(m(template, "<init>", "?"));
        // 인자 수가 맞는 생성은 해석된 생성자 그대로
        assertThat(targets(stubs, m("com.a.Config", "job"), EdgeKind.CALLS)).containsExactly(
                m("org.springframework.batch.core.job.builder.JobBuilder", "<init>", "java.lang.String", "org.springframework.batch.core.repository.JobRepository"));
    }

    @Test
    @DisplayName("F-003-AC23 타입 인자만 classpath에 없는 프로젝트 타입의 생성(new Page<Missing>(..), super(..))은 해석된 생성자로 잇는다")
    void constructorOfTypeWithUnresolvedTypeArgument() {
        GraphModel g = Fx.analyze(
                "src/com/a/Page.java", "package com.a; public class Page<T> { public Page(Object request, int size) { } }",
                "src/com/a/ModelPage.java", """
                        package com.a;
                        import org.ext.Model;
                        import org.ext.Request;
                        public class ModelPage extends Page<Model> {
                            ModelPage(Request request) { super(request, 10); }
                            static Object of(Request request) { return new Page<Model>(request, 20); }
                        }
                        """);

        String constructor = m("com.a.Page", "<init>", "java.lang.Object", "int");
        String of = m("com.a.ModelPage", "of", "org.ext.Request");
        assertThat(targets(g, of, EdgeKind.CALLS)).containsExactly(constructor);
        assertThat(hasEdge(g, of, EdgeKind.INSTANTIATES, type("com.a.Page"))).isTrue();
        assertThat(targets(g, m("com.a.ModelPage", "<init>", "org.ext.Request"), EdgeKind.CALLS)).containsExactly(constructor);
    }

    @Test
    @DisplayName("F-003-AC24 인자에 미해석 타입의 값이 있어 컴파일러가 오류 없이 고른 후보라도 인자 수가 맞지 않으면 그 후보로 잇지 않고 미해결로 남긴다")
    void guessedCandidateWithWrongArity() {
        GraphModel g = Fx.analyze(
                "src/com/a/Base.java", """
                        package com.a;
                        public class Base {
                            Base(String a) { }
                            void m(String a) { }
                            static void s(String a) { }
                        }
                        """,
                "src/com/a/Sub.java", """
                        package com.a;
                        import org.ext.Missing;
                        public class Sub extends Base {
                            Sub(Missing m) { super(m, 1); }
                            Sub(Missing m, int x) { this(m, x, 3); }
                            void own(Missing m) { }
                            void calls(Missing m, Base b) {
                                b.m(m, 1);
                                super.m(m, 2);
                                own(m, 3);
                                Base.s(m, 4);
                                new Base(m, 5);
                                own(m);
                            }
                        }
                        """);

        String calls = m("com.a.Sub", "calls", "org.ext.Missing", "com.a.Base");
        // 인자 수가 맞는 호출만 해석된 대상으로 잇는다
        assertThat(targets(g, calls, EdgeKind.CALLS).stream().filter(t -> !t.startsWith("unresolved:")).toList())
                .containsExactly(m("com.a.Sub", "own", "org.ext.Missing"));
        assertThat(unresolved(g, calls)).containsExactlyInAnyOrder(
                Ids.unresolved("java-call", "b.m(…)"), Ids.unresolved("java-call", "super.m(…)"), Ids.unresolved("java-call", "own(…)"),
                Ids.unresolved("java-call", "Base.s(…)"), Ids.unresolved("java-call", "new Base(…)"));
        assertThat(targets(g, calls, EdgeKind.INSTANTIATES)).isEmpty();
        assertThat(targets(g, m("com.a.Sub", "<init>", "org.ext.Missing"), EdgeKind.CALLS))
                .containsExactly(Ids.unresolved("java-call", "super(…)"));
        assertThat(targets(g, m("com.a.Sub", "<init>", "org.ext.Missing", "int"), EdgeKind.CALLS))
                .containsExactly(Ids.unresolved("java-call", "this(…)"));
    }

    @Test
    @DisplayName("F-003-AC25 인자 있는 enum 상수에 맞는 생성자가 없으면 가까운 후보나 java.lang.Enum의 생성자로 잇지 않고 미해결로 남긴다")
    void enumConstantWithoutMatchingConstructor() {
        GraphModel g = Fx.analyze(
                "src/com/a/Color.java", """
                        package com.a;
                        import org.ext.Missing;
                        public enum Color {
                            RED(1), GREEN(1, 2), BLUE, BLACK(Missing.X, 1);
                            Color(int a) { }
                            Color() { }
                        }
                        """,
                "src/com/a/Plain.java", """
                        package com.a;
                        import org.ext.Missing;
                        public enum Plain { A(1), B, C(Missing.X, 2) }
                        """);

        String color = m("com.a.Color", "<clinit>");
        assertThat(Fx.edges(g, color, EdgeKind.CALLS, m("com.a.Color", "<init>", "int"))).hasSize(1)
                .allSatisfy(e -> assertThat(e.location().startLine()).isEqualTo(4));
        assertThat(hasEdge(g, color, EdgeKind.CALLS, m("com.a.Color", "<init>"))).isTrue();
        assertThat(unresolved(g, color)).containsExactlyInAnyOrder(
                Ids.unresolved("java-call", "GREEN(…)"), Ids.unresolved("java-call", "BLACK(…)"));
        assertThat(edge(g, color, EdgeKind.CALLS, Ids.unresolved("java-call", "GREEN(…)")).attrs().get("argCount")).isEqualTo(2);

        // 생성자를 선언하지 않은 enum: 인자 없는 상수에는 아무것도 만들지 않고 인자 있는 상수는 미해결이다
        String plain = m("com.a.Plain", "<clinit>");
        assertThat(targets(g, plain, EdgeKind.CALLS)).containsExactlyInAnyOrder(
                Ids.unresolved("java-call", "A(…)"), Ids.unresolved("java-call", "C(…)"));
        // java.lang.Enum의 생성자 호출로 세지 않는다
        assertThat(g.diagnostics()).noneMatch(d -> d.code().equals("EXTERNAL_CALLS_SKIPPED"));
    }

    @Test
    @DisplayName("F-003-AC26 체인의 안쪽 링크는 바깥 링크가 체인 미해결을 남기지 않으면 직접 남긴다")
    void innerChainLinkIsNotDropped() {
        try (io.codeatlas.core.testing.TestProject fake = io.codeatlas.core.testing.TestProject.create()) {
            io.codeatlas.core.spi.AnalysisContext ctx = fake.context();
            GraphModel g = ctx.graph();
            JavaProject project = new JavaProject(ctx, JavaOptions.from(ctx.options(), g), Map.of(), java.util.Set.of());
            Unit.Owner owner = new Unit.Owner(m("com.a.C", "run"), false);
            io.codeatlas.core.model.SourceLocation at = io.codeatlas.core.model.SourceLocation.of("src/com/a/C.java", 3);

            // x.a().inner().outer(1, 2): inner의 수신자는 소스 없는 타입의 반환값, outer는 가까운 후보가 소스 없는 타입의 메서드(F-003-R13)
            PendingCall inner = new PendingCall(PendingCall.Kind.METHOD, owner, "inner", 0, List.of(), at, at, "x.a().inner(…)");
            inner.receiver = PendingCall.Marker.EXTERNAL_TAIL;
            inner.usedAsReceiver = true;
            PendingCall outer = new PendingCall(PendingCall.Kind.METHOD, owner, "outer", 2, List.of(), at, at, "x.a().inner().outer(…)");
            outer.receiver = new PendingCall.ResultOf(inner);
            outer.fallback = PendingCall.Fallback.external("lib.Ext", "virtual", false);
            project.calls.add(outer);
            project.calls.add(inner);
            project.calls.resolvePending();

            assertThat(hasEdge(g, owner.methodId(), EdgeKind.CALLS, m("lib.Ext", "outer", "?"))).isTrue();
            Edge chain = edge(g, owner.methodId(), EdgeKind.CALLS, Ids.unresolved("java-call", "x.a().inner(…)"));
            assertThat(chain.attrBoolean("externalChain")).isTrue();
            assertThat(chain.attrs().get("argCount")).isEqualTo(0);
            assertThat(targets(g, owner.methodId(), EdgeKind.CALLS)).hasSize(2);
        }
    }

    @Test
    @DisplayName("F-003-AC27 미해결 호출의 원문은 <수신자>.<이름>(…)이고 인자 수는 엣지 attrs.argCount에 남긴다")
    void unresolvedCallText() {
        GraphModel g = Fx.analyze(
                "src/com/a/Vo.java", "package com.a; public class Vo { }",
                "src/com/a/Caller.java", """
                        package com.a;
                        import java.util.function.Supplier;
                        import org.ext.Registry;
                        public class Caller {
                            void run(Vo vo, Registry registry) {
                                vo.set("a");
                                vo.set("a", "b", "c");
                                new Vo(1, 2);
                                Supplier<Object> s = vo::nothing;
                                registry.add(null).order(1, 2);
                            }
                        }
                        """);

        String run = m("com.a.Caller", "run", "com.a.Vo", "org.ext.Registry");
        // 인자 수만 다른 호출은 미해결 노드 하나로 모이고 엣지는 호출마다 남는다
        String set = Ids.unresolved("java-call", "vo.set(…)");
        assertThat(node(g, set).name()).isEqualTo("vo.set(…)");
        assertThat(Fx.edges(g, run, EdgeKind.CALLS, set)).hasSize(2)
                .allSatisfy(e -> assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED))
                .extracting(e -> e.attrs().get("argCount"), e -> e.location().startLine())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(1, 6), org.assertj.core.groups.Tuple.tuple(3, 7));
        assertThat(edge(g, run, EdgeKind.CALLS, Ids.unresolved("java-call", "new Vo(…)")).attrs().get("argCount")).isEqualTo(2);
        // 메서드 참조는 인자 수를 모른다
        assertThat(edge(g, run, EdgeKind.CALLS, Ids.unresolved("java-call", "vo::nothing")).attrs()).doesNotContainKey("argCount");
        // 체인당 미해결: 안쪽 링크의 인자는 원문 그대로, 가장 바깥 링크만 (…)
        Edge chain = edge(g, run, EdgeKind.CALLS, Ids.unresolved("java-call", "registry.add(null).order(…)"));
        assertThat(chain.attrBoolean("externalChain")).isTrue();
        assertThat(chain.attrs().get("argCount")).isEqualTo(2);
        assertThat(unresolved(g, run)).hasSize(5).noneMatch(t -> t.matches(".*\\(\\d+\\)$"));
    }

    @Test
    @DisplayName("F-003-AC28 상위 클래스의 메서드와 별개의 인터페이스 메서드를 함께 구현하면 직접 상위마다 OVERRIDES를 만든다")
    void overridesEachDirectSupertype() {
        GraphModel g = Fx.analyze(
                "src/com/a/I.java", "package com.a; public interface I { void m(); void only(); }",
                "src/com/a/J.java", "package com.a; public interface J extends I { void m(); }",
                "src/com/a/K.java", "package com.a; public interface K { void m(); }",
                "src/com/a/A.java", "package com.a; public class A { public void m() { } public void deep() { } }",
                "src/com/a/B.java", "package com.a; public class B extends A { public void m() { } public void only() { } }",
                // 상위 클래스의 m()과, B가 구현하지 않는 인터페이스의 m()
                "src/com/a/C.java", "package com.a; public class C extends B implements I { public void m() { } public void deep() { } }",
                // only()는 B에서 물려받아 I를 충족한다: D에 선언이 없으므로 엣지를 만들 노드가 없다
                "src/com/a/D.java", "package com.a; public class D extends B implements I { public void m() { } }",
                // 인터페이스 계통마다 가장 가까운 선언 하나: J(그 위의 I는 아님)와 K
                "src/com/a/E.java", "package com.a; public class E extends B implements J, K { public void m() { } }",
                // 상위 클래스가 이미 구현한 인터페이스: 가장 가까운 상위 하나
                "src/com/a/P.java", "package com.a; public class P implements I { public void m() { } public void only() { } }",
                "src/com/a/Q.java", "package com.a; public class Q extends P { public void m() { } }");

        assertThat(targets(g, m("com.a.C", "m"), EdgeKind.OVERRIDES)).containsExactlyInAnyOrder(m("com.a.B", "m"), m("com.a.I", "m"));
        assertThat(targets(g, m("com.a.C", "deep"), EdgeKind.OVERRIDES)).containsExactly(m("com.a.A", "deep"));
        assertThat(targets(g, m("com.a.D", "m"), EdgeKind.OVERRIDES)).containsExactlyInAnyOrder(m("com.a.B", "m"), m("com.a.I", "m"));
        assertThat(g.node(m("com.a.D", "only"))).isEmpty();
        assertThat(targets(g, m("com.a.B", "only"), EdgeKind.OVERRIDES)).isEmpty();
        assertThat(targets(g, m("com.a.E", "m"), EdgeKind.OVERRIDES)).containsExactlyInAnyOrder(
                m("com.a.B", "m"), m("com.a.J", "m"), m("com.a.K", "m"));
        assertThat(targets(g, m("com.a.Q", "m"), EdgeKind.OVERRIDES)).containsExactly(m("com.a.P", "m"));
        assertThat(targets(g, m("com.a.P", "m"), EdgeKind.OVERRIDES)).containsExactly(m("com.a.I", "m"));
        assertThat(targets(g, m("com.a.B", "m"), EdgeKind.OVERRIDES)).containsExactly(m("com.a.A", "m"));
    }
}
