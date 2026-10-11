package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.hasEdge;
import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-002 Java 구조 분석. */
class JavaStructureTest {

    private static String type(String name) {
        return Ids.javaType(name);
    }

    @Test
    @DisplayName("F-002-AC1 소스 루트가 여럿이어도 한 번에 해석하고 패키지와 경로가 안 맞는 파일은 진단을 남기고 분석한다")
    void multipleSourceRootsAndMismatch() {
        GraphModel g = Fx.analyze(
                "src/main/java/com/a/Order.java", "package com.a; import com.b.Item; public class Order { Item item; }",
                "module-b/src/com/b/Item.java", "package com.b; public class Item { com.a.Order owner; }",
                "misc/Stray.java", "package com.c; public class Stray extends com.a.Order { }");

        assertThat(edge(g, type("com.a.Order"), EdgeKind.ASSOCIATES, type("com.b.Item")).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(edge(g, type("com.b.Item"), EdgeKind.ASSOCIATES, type("com.a.Order")).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(node(g, type("com.b.Item")).declared()).isTrue();
        assertThat(hasEdge(g, type("com.c.Stray"), EdgeKind.EXTENDS, type("com.a.Order"))).isTrue();
        assertThat(g.diagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("SOURCE_ROOT_MISMATCH");
            assertThat(d.location().file()).isEqualTo("misc/Stray.java");
        });
    }

    @Test
    @DisplayName("F-002-AC2 모든 종류의 타입과 중첩·로컬·익명 타입이 바이너리 이름 ID로 만들어지고 CONTAINS로 이어진다")
    void typeNodes() {
        GraphModel g = Fx.analyze("src/main/java/com/a/Outer.java", """
                package com.a;
                public abstract class Outer<T extends Comparable<T>> {
                    public static final class Inner { class Deep { } }
                    interface Callback { void call(); }
                    enum Color { RED, BLUE }
                    record Point(int x, int y) { }
                    @interface Marker { String value() default ""; }
                    void run() {
                        Callback c = new Callback() { public void call() { } };
                        class Local { }
                        Runnable r = new Runnable() { public void run() { } };
                    }
                }
                """);

        Node outer = node(g, type("com.a.Outer"));
        assertThat(outer.kind()).isEqualTo(NodeKind.JAVA_TYPE);
        assertThat(outer.name()).isEqualTo("Outer");
        assertThat(outer.qualifiedName()).isEqualTo("com.a.Outer");
        assertThat(outer.attrString("typeKind")).isEqualTo("class");
        assertThat(outer.attrList("modifiers")).containsExactly("public", "abstract");
        assertThat(outer.attrList("typeParameters")).containsExactly("T extends Comparable<T>");
        assertThat(outer.location().file()).isEqualTo("src/main/java/com/a/Outer.java");
        assertThat(outer.location().startLine()).isEqualTo(2);
        // 위치는 이름에서 시작하고 끝은 마지막 글자 다음(제외)이다
        assertThat(outer.location().startColumn()).isEqualTo(23);
        assertThat(outer.location().endLine()).isEqualTo(13);
        assertThat(outer.location().endColumn()).isEqualTo(2);

        assertThat(node(g, type("com.a.Outer$Inner")).attrList("modifiers")).containsExactly("public", "static", "final");
        assertThat(node(g, type("com.a.Outer$Inner")).qualifiedName()).isEqualTo("com.a.Outer$Inner");
        assertThat(node(g, type("com.a.Outer$Inner$Deep")).attrString("typeKind")).isEqualTo("class");
        assertThat(node(g, type("com.a.Outer$Callback")).attrString("typeKind")).isEqualTo("interface");
        assertThat(node(g, Ids.javaMethod("com.a.Outer$Callback", "call")).attrList("modifiers")).isEmpty();
        assertThat(node(g, type("com.a.Outer$Color")).attrString("typeKind")).isEqualTo("enum");
        assertThat(node(g, type("com.a.Outer$Point")).attrString("typeKind")).isEqualTo("record");
        assertThat(node(g, type("com.a.Outer$Marker")).attrString("typeKind")).isEqualTo("annotation");
        assertThat(node(g, type("com.a.Outer$1")).attrBoolean("anonymous")).isTrue();
        assertThat(node(g, type("com.a.Outer$1Local")).attrBoolean("local")).isTrue();
        assertThat(node(g, type("com.a.Outer$2")).attrBoolean("anonymous")).isTrue();
        assertThat(node(g, type("com.a.Outer$1")).attrString("enclosingMethod")).isEqualTo(Ids.javaMethod("com.a.Outer", "run"));

        assertThat(node(g, Ids.javaPackage("com.a")).kind()).isEqualTo(NodeKind.JAVA_PACKAGE);
        assertThat(hasEdge(g, Ids.file("src/main/java/com/a/Outer.java"), EdgeKind.CONTAINS, type("com.a.Outer"))).isTrue();
        assertThat(hasEdge(g, Ids.javaPackage("com.a"), EdgeKind.CONTAINS, type("com.a.Outer"))).isTrue();
        assertThat(hasEdge(g, type("com.a.Outer"), EdgeKind.CONTAINS, type("com.a.Outer$Inner"))).isTrue();
        assertThat(hasEdge(g, type("com.a.Outer$Inner"), EdgeKind.CONTAINS, type("com.a.Outer$Inner$Deep"))).isTrue();
        assertThat(hasEdge(g, type("com.a.Outer"), EdgeKind.CONTAINS, type("com.a.Outer$1"))).isTrue();
        assertThat(hasEdge(g, Ids.javaPackage("com.a"), EdgeKind.CONTAINS, type("com.a.Outer$Inner"))).isFalse();
        // 익명 타입은 구현한 인터페이스로 IMPLEMENTS
        assertThat(hasEdge(g, type("com.a.Outer$1"), EdgeKind.IMPLEMENTS, type("com.a.Outer$Callback"))).isTrue();
    }

    @Test
    @DisplayName("F-002-AC3 메서드·생성자·필드·enum 상수 노드의 ID와 attrs")
    void memberNodes() {
        GraphModel g = Fx.analyze(
                "src/com/a/B.java", """
                        package com.a;
                        import java.util.List;
                        import java.util.Map;
                        public class B<T extends Number> {
                            private static final String NAME = "b";
                            protected List<String> names;
                            int[] counts, more[];
                            public B(String name) { }
                            public String save(String name, int[] ids) throws java.io.IOException { return name; }
                            void save(List<String> names, Map<String, Object>... rest) { }
                            <E> T generic(T value, E[] items, Order order) { return value; }
                        }
                        """,
                "src/com/a/Order.java", "package com.a; public class Order { }",
                "src/com/a/Status.java", "package com.a; public enum Status { OPEN, CLOSED; private int code; }");

        Node ctor = node(g, "java:method:com.a.B#<init>(java.lang.String)");
        assertThat(ctor.kind()).isEqualTo(NodeKind.JAVA_METHOD);
        assertThat(ctor.name()).isEqualTo("<init>");
        assertThat(ctor.attrBoolean("constructor")).isTrue();
        assertThat(ctor.attrString("signature")).isEqualTo("B(String)");

        Node save = node(g, "java:method:com.a.B#save(java.lang.String,int[])");
        assertThat(save.name()).isEqualTo("save");
        assertThat(save.qualifiedName()).isEqualTo("com.a.B#save(String,int[])");
        assertThat(save.attrString("returnType")).isEqualTo("String");
        assertThat(save.attrList("parameterTypes")).containsExactly("String", "int[]");
        assertThat(save.attrList("parameterNames")).containsExactly("name", "ids");
        assertThat(save.attrList("modifiers")).containsExactly("public");
        assertThat(save.attrString("signature")).isEqualTo("save(String, int[]): String");
        assertThat(save.attrBoolean("constructor")).isFalse();
        assertThat(save.location().startLine()).isEqualTo(9);

        Node varargs = node(g, "java:method:com.a.B#save(java.util.List,java.util.Map[])");
        assertThat(varargs.attrList("parameterTypes")).containsExactly("List<String>", "Map<String,Object>...");
        assertThat(varargs.attrBoolean("varargs")).isTrue();

        // 타입 변수는 erasure: T extends Number → java.lang.Number, E[] → java.lang.Object[]
        node(g, "java:method:com.a.B#generic(java.lang.Number,java.lang.Object[],com.a.Order)");

        Node name = node(g, Ids.javaField("com.a.B", "NAME"));
        assertThat(name.kind()).isEqualTo(NodeKind.JAVA_FIELD);
        assertThat(name.attrString("type")).isEqualTo("String");
        assertThat(name.attrList("modifiers")).containsExactly("private", "static", "final");
        assertThat(name.attrString("constantValue")).isEqualTo("b");
        assertThat(node(g, Ids.javaField("com.a.B", "names")).attrString("type")).isEqualTo("List<String>");
        assertThat(node(g, Ids.javaField("com.a.B", "counts")).attrString("type")).isEqualTo("int[]");
        assertThat(node(g, Ids.javaField("com.a.B", "more")).attrString("type")).isEqualTo("int[][]");

        Node open = node(g, Ids.javaField("com.a.Status", "OPEN"));
        assertThat(open.attrBoolean("enumConstant")).isTrue();
        assertThat(node(g, Ids.javaField("com.a.Status", "code")).attrBoolean("enumConstant")).isFalse();

        assertThat(targets(g, type("com.a.B"), EdgeKind.CONTAINS)).contains(
                ctor.id(), save.id(), varargs.id(), name.id(), Ids.javaField("com.a.B", "counts"));
        assertThat(targets(g, type("com.a.Status"), EdgeKind.CONTAINS)).contains(open.id(), Ids.javaField("com.a.Status", "CLOSED"));
    }

    @Test
    @DisplayName("F-002-AC4 EXTENDS와 IMPLEMENTS를 만들고 Object·Enum으로는 만들지 않는다")
    void inheritance() {
        GraphModel g = Fx.analyze(
                "src/com/a/Base.java", "package com.a; public abstract class Base<T> { }",
                "src/com/a/Api.java", "package com.a; public interface Api { }",
                "src/com/a/SubApi.java", "package com.a; public interface SubApi extends Api, java.io.Serializable { }",
                "src/com/a/Impl.java", "package com.a; public class Impl extends Base<String> implements SubApi { }",
                "src/com/a/Kind.java", "package com.a; public enum Kind implements Api { A }",
                "src/com/a/Plain.java", "package com.a; public class Plain extends Object { }");

        assertThat(hasEdge(g, type("com.a.Impl"), EdgeKind.EXTENDS, type("com.a.Base"))).isTrue();
        assertThat(hasEdge(g, type("com.a.Impl"), EdgeKind.IMPLEMENTS, type("com.a.SubApi"))).isTrue();
        assertThat(hasEdge(g, type("com.a.SubApi"), EdgeKind.EXTENDS, type("com.a.Api"))).isTrue();
        assertThat(hasEdge(g, type("com.a.Kind"), EdgeKind.IMPLEMENTS, type("com.a.Api"))).isTrue();
        // 명시적으로 쓴 JDK 인터페이스는 external 참조로 남긴다
        assertThat(hasEdge(g, type("com.a.SubApi"), EdgeKind.EXTENDS, type("java.io.Serializable"))).isTrue();
        assertThat(node(g, type("java.io.Serializable")).attrBoolean("external")).isTrue();
        assertThat(g.node(type("java.lang.Object"))).isEmpty();
        assertThat(g.node(type("java.lang.Enum"))).isEmpty();
        assertThat(targets(g, type("com.a.Plain"), EdgeKind.EXTENDS)).isEmpty();
    }

    @Test
    @DisplayName("F-002-AC5 필드 타입으로 ASSOCIATES를 만들고 컬렉션·배열·Map·Optional은 원소 타입으로 푼다")
    void associations() {
        GraphModel g = Fx.analyze(
                "src/com/a/Cart.java", """
                        package com.a;
                        import java.util.*;
                        public class Cart {
                            private Order order;
                            private List<Item> items;
                            private Item[] array;
                            private Map<String, Item> byName;
                            private Optional<Order> last;
                            private Map<Long, List<Coupon>> coupons;
                            private Set<? extends Gift> gifts;
                            private int count;
                            private String name;
                            private List<String> tags;
                            private Cart parent;
                        }
                        """,
                "src/com/a/Order.java", "package com.a; public class Order { }",
                "src/com/a/Item.java", "package com.a; public class Item { }",
                "src/com/a/Coupon.java", "package com.a; public class Coupon { }",
                "src/com/a/Gift.java", "package com.a; public class Gift { }");

        String cart = type("com.a.Cart");
        List<Edge> toOrder = Fx.edges(g, cart, EdgeKind.ASSOCIATES, type("com.a.Order"));
        assertThat(toOrder).extracting(e -> e.attrString("field") + ":" + e.attrString("multiplicity"))
                .containsExactlyInAnyOrder("order:1", "last:1");
        List<Edge> toItem = Fx.edges(g, cart, EdgeKind.ASSOCIATES, type("com.a.Item"));
        assertThat(toItem).extracting(e -> e.attrString("field") + ":" + e.attrString("multiplicity"))
                .containsExactlyInAnyOrder("items:*", "array:*", "byName:*");
        assertThat(edge(g, cart, EdgeKind.ASSOCIATES, type("com.a.Coupon")).attrString("multiplicity")).isEqualTo("*");
        assertThat(edge(g, cart, EdgeKind.ASSOCIATES, type("com.a.Gift")).attrString("multiplicity")).isEqualTo("*");
        assertThat(edge(g, cart, EdgeKind.ASSOCIATES, cart).attrString("field")).isEqualTo("parent");
        assertThat(targets(g, cart, EdgeKind.ASSOCIATES)).containsOnly(
                type("com.a.Order"), type("com.a.Item"), type("com.a.Coupon"), type("com.a.Gift"), cart);
        assertThat(targets(g, cart, EdgeKind.DEPENDS_ON)).isEmpty();
    }

    private static final Map<String, String> DEPENDS = Fx.files(
            "src/com/a/Svc.java", """
                    package com.a;
                    import java.util.ArrayList;
                    import java.util.List;
                    public class Svc extends Base {
                        private Repo repo;
                        public Result run(Param p) throws BizException {
                            List<Local> list = new ArrayList<>();
                            Local l = new Local();
                            Object o = (Casted) null;
                            String s = Util.name();
                            int n = Consts.MAX;
                            repo.load();
                            Base b = this;
                            Class<?> k = Literal.class;
                            return repo.find().first();
                        }
                    }
                    """,
            "src/com/a/Base.java", "package com.a; public class Base { }",
            "src/com/a/Repo.java", "package com.a; public class Repo { void load() { } Chain find() { return null; } }",
            "src/com/a/Chain.java", "package com.a; public class Chain { Result first() { return null; } }",
            "src/com/a/Result.java", "package com.a; public class Result { }",
            "src/com/a/Param.java", "package com.a; public class Param { }",
            "src/com/a/BizException.java", "package com.a; public class BizException extends Exception { }",
            "src/com/a/Local.java", "package com.a; public class Local { }",
            "src/com/a/Casted.java", "package com.a; public class Casted { }",
            "src/com/a/Literal.java", "package com.a; public class Literal { }",
            "src/com/a/Util.java", "package com.a; public class Util { static String name() { return null; } }",
            "src/com/a/Consts.java", "package com.a; public class Consts { static final int MAX = 1; }");

    @Test
    @DisplayName("F-002-AC6 시그니처·본문에서 쓰는 타입으로 DEPENDS_ON을 한 번씩 만들고 필드 타입·java.lang·JDK 타입은 뺀다")
    void dependencies() {
        GraphModel g = Fx.analyze(AnalysisOptions.defaults(), DEPENDS);

        String svc = type("com.a.Svc");
        assertThat(targets(g, svc, EdgeKind.DEPENDS_ON)).containsExactlyInAnyOrder(
                type("com.a.Result"), type("com.a.Param"), type("com.a.BizException"), type("com.a.Local"),
                type("com.a.Casted"), type("com.a.Util"), type("com.a.Consts"), type("com.a.Literal"), type("com.a.Chain"));
        assertThat(edge(g, svc, EdgeKind.DEPENDS_ON, type("com.a.Local")).location().startLine()).isEqualTo(7);
        assertThat(hasEdge(g, svc, EdgeKind.ASSOCIATES, type("com.a.Repo"))).isTrue();
        assertThat(hasEdge(g, svc, EdgeKind.EXTENDS, type("com.a.Base"))).isTrue();
        assertThat(g.node(type("java.util.ArrayList"))).isEmpty();
        assertThat(g.node(type("java.lang.String"))).isEmpty();
    }

    @Test
    @DisplayName("F-002-AC6 java.includeJdkTypes=true면 JDK 타입으로도 DEPENDS_ON을 만든다")
    void dependenciesIncludingJdk() {
        GraphModel g = Fx.analyze(Fx.extra("java.includeJdkTypes", "true"), DEPENDS);

        String svc = type("com.a.Svc");
        assertThat(targets(g, svc, EdgeKind.DEPENDS_ON)).contains(type("java.util.ArrayList"), type("java.util.List"));
        assertThat(node(g, type("java.util.ArrayList")).attrBoolean("external")).isTrue();
        assertThat(node(g, type("java.util.ArrayList")).declared()).isFalse();
        assertThat(g.node(type("java.lang.String"))).isEmpty();
    }

    @Test
    @DisplayName("F-002-AC7 타입·메서드·필드의 애너테이션으로 ANNOTATED_BY를 만들고 java.lang 애너테이션은 뺀다")
    void annotations() {
        GraphModel g = Fx.analyze(
                "src/com/a/Audited.java", "package com.a; public @interface Audited { String value() default \"\"; }",
                "src/com/a/Svc.java", """
                        package com.a;
                        @Audited("type")
                        public class Svc {
                            @Audited private String name;
                            @Audited @Deprecated public void run() { }
                            @Override public String toString() { return name; }
                        }
                        """);

        String audited = type("com.a.Audited");
        assertThat(hasEdge(g, type("com.a.Svc"), EdgeKind.ANNOTATED_BY, audited)).isTrue();
        assertThat(hasEdge(g, Ids.javaField("com.a.Svc", "name"), EdgeKind.ANNOTATED_BY, audited)).isTrue();
        assertThat(hasEdge(g, Ids.javaMethod("com.a.Svc", "run"), EdgeKind.ANNOTATED_BY, audited)).isTrue();
        assertThat(g.edges(EdgeKind.ANNOTATED_BY)).hasSize(3);
        assertThat(g.node(type("java.lang.Override"))).isEmpty();
    }

    @Test
    @DisplayName("F-002-AC8 classpath 옵션의 라이브러리 타입은 external 참조 노드가 되고 엣지는 RESOLVED다")
    void externalTypeFromClasspathOption() {
        GraphModel g = Fx.analyzeWithStubs(
                "src/com/a/Mine.java", "package com.a; import lib.util.LibBase; public class Mine extends LibBase { }");

        Edge e = edge(g, type("com.a.Mine"), EdgeKind.EXTENDS, type("lib.util.LibBase"));
        assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
        Node lib = node(g, type("lib.util.LibBase"));
        assertThat(lib.declared()).isFalse();
        assertThat(lib.attrBoolean("external")).isTrue();
        assertThat(g.diagnostics()).noneMatch(d -> d.severity() != Severity.INFO);
    }

    @Test
    @DisplayName("F-002-AC8 프로젝트 안에서 스캔된 jar도 타입 해석에 쓴다")
    void externalTypeFromProjectJar() {
        try (TestProject project = TestProject.create()
                .bytes("lib/stubs.jar", Fx.jar(Fx.stubClasses()))
                .file("src/com/a/Mine.java", "package com.a; import lib.util.*; public class Mine extends LibBase implements LibCallback { public void onEvent(String n) { } }")) {
            GraphModel g = project.run(new JavaAnalyzer()).graph();

            assertThat(edge(g, type("com.a.Mine"), EdgeKind.EXTENDS, type("lib.util.LibBase")).confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(hasEdge(g, type("com.a.Mine"), EdgeKind.IMPLEMENTS, type("lib.util.LibCallback"))).isTrue();
            assertThat(node(g, type("lib.util.LibCallback")).attrBoolean("external")).isTrue();
        }
    }

    @Test
    @DisplayName("F-002-AC9 classpath에 없는 타입은 import로 FQN을 복원하고 복원할 수 없으면 미해결로 남긴다")
    void missingTypes() {
        GraphModel g = Fx.analyze(
                "src/com/a/Dao.java", """
                        package com.a;
                        import org.mybatis.spring.SqlSessionTemplate;
                        import org.ext.wild.*;
                        public class Dao extends org.ext.Base implements Wild {
                            private SqlSessionTemplate sqlSession;
                            private Vo vo;
                            public Vo find(SqlSessionTemplate t, Unknown u) { return vo; }
                        }
                        """,
                "src/com/a/Vo.java", "package com.a; public class Vo { }");

        String dao = type("com.a.Dao");
        // 단일 import → FQN 복원
        Edge assoc = edge(g, dao, EdgeKind.ASSOCIATES, type("org.mybatis.spring.SqlSessionTemplate"));
        assertThat(assoc.confidence()).isEqualTo(Confidence.RESOLVED);
        Node tpl = node(g, type("org.mybatis.spring.SqlSessionTemplate"));
        assertThat(tpl.attrBoolean("external")).isTrue();
        assertThat(tpl.declared()).isFalse();
        // 전체 이름
        edge(g, dao, EdgeKind.EXTENDS, type("org.ext.Base"));
        // 와일드카드 import만 있는 이름은 복원할 수 없다
        Edge wild = edge(g, dao, EdgeKind.IMPLEMENTS, Ids.unresolved("java-type", "Wild"));
        assertThat(wild.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(edge(g, dao, EdgeKind.DEPENDS_ON, Ids.unresolved("java-type", "Unknown")).confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(g.diagnostics()).anyMatch(d -> d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE) && d.message().contains("Wild"));
        // 프로젝트 타입끼리는 그대로 해석된다
        assertThat(edge(g, dao, EdgeKind.ASSOCIATES, type("com.a.Vo")).confidence()).isEqualTo(Confidence.RESOLVED);
        // 매개변수의 미해석 타입은 프로젝트 전체의 단일 import로 이름을 정한다
        node(g, "java:method:com.a.Dao#find(org.mybatis.spring.SqlSessionTemplate,Unknown)");
    }

    @Test
    @DisplayName("F-002-AC10 구문 오류가 있는 파일은 PARSE_FAILED를 남기고 복구된 만큼 분석하며 다른 파일은 영향받지 않는다")
    void brokenFile() {
        GraphModel g = Fx.analyze(
                "src/broken/BrokenService.java", """
                        package broken;
                        import java.util.List;
                        public class BrokenService {
                            private List<String> names;
                            public int count() {
                                return names.size()
                            }
                            public void unbalanced( {
                                if (names != null {
                                    names.clear();
                                }
                        """,
                "src/com/a/Fine.java", "package com.a; public class Fine { void ok() { } }");

        assertThat(g.diagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo(DiagnosticCodes.PARSE_FAILED);
            assertThat(d.severity()).isEqualTo(Severity.WARN);
            assertThat(d.location().file()).isEqualTo("src/broken/BrokenService.java");
        });
        node(g, type("broken.BrokenService"));
        node(g, Ids.javaField("broken.BrokenService", "names"));
        node(g, Ids.javaMethod("broken.BrokenService", "count"));
        node(g, Ids.javaMethod("com.a.Fine", "ok"));
        assertThat(g.diagnostics()).noneMatch(d -> d.code().equals(DiagnosticCodes.PARSE_FAILED) && d.location().file().contains("Fine"));
    }

    @Test
    @DisplayName("F-002-AC11 MS949 파일의 한글 식별자와 문자열 상수가 깨지지 않는다")
    void ms949() {
        try (TestProject project = TestProject.create()
                .file("src/com/a/Kor.java", "package com.a; public class Kor { static final String 이름 = \"주문\"; int 수량; }", Charset.forName("MS949"))
                .file("src/com/a/Utf.java", "package com.a; public class Utf { String 제목 = Kor.이름; }")) {
            GraphModel g = project.run(new JavaAnalyzer()).graph();

            Node field = node(g, Ids.javaField("com.a.Kor", "이름"));
            assertThat(field.name()).isEqualTo("이름");
            assertThat(field.attrString("constantValue")).isEqualTo("주문");
            node(g, Ids.javaField("com.a.Kor", "수량"));
            node(g, Ids.javaField("com.a.Utf", "제목"));
            assertThat(g.diagnostics()).noneMatch(d -> d.code().equals(DiagnosticCodes.PARSE_FAILED));
        }
    }

    @Test
    @DisplayName("F-002-AC11 BOM이 붙은 UTF-8 파일과 package-info.java도 오류 없이 읽는다")
    void bomAndPackageInfo() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "package com.a; public class Bom { String 이름; }".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] content = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, content, 0, bom.length);
        System.arraycopy(body, 0, content, bom.length, body.length);
        try (TestProject project = TestProject.create()
                .bytes("src/com/a/Bom.java", content)
                .file("src/com/a/package-info.java", "/** 문서 */\npackage com.a;\n")) {
            GraphModel g = project.run(new JavaAnalyzer()).graph();

            node(g, Ids.javaField("com.a.Bom", "이름"));
            node(g, Ids.javaPackage("com.a"));
            assertThat(g.diagnostics()).noneMatch(d -> d.severity() != Severity.INFO);
        }
    }

    @Test
    @DisplayName("F-002-AC1 Java 파일이 수백 개여도 한 번의 일괄 파싱으로 서로를 해석한다")
    void manyFiles() {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        int count = 300;
        for (int i = 0; i < count; i++) {
            String next = "C" + ((i + 1) % count);
            files.put("src/com/big/C" + i + ".java",
                    "package com.big; public class C" + i + " { " + next + " next; int run() { return next.run() + 1; } }");
        }
        GraphModel g = Fx.analyze(AnalysisOptions.defaults(), files);

        assertThat(g.nodes(NodeKind.JAVA_TYPE)).hasSize(count);
        assertThat(g.edges(EdgeKind.CALLS)).hasSize(count).allMatch(e -> e.confidence() == Confidence.RESOLVED);
        assertThat(g.edges(EdgeKind.ASSOCIATES)).hasSize(count);
    }

    @Test
    @DisplayName("F-002 ServiceLoader 등록과 표준 order")
    void registered() {
        JavaAnalyzer analyzer = new JavaAnalyzer();
        assertThat(analyzer.order()).isEqualTo(100);
        assertThat(java.util.ServiceLoader.load(io.codeatlas.core.spi.Analyzer.class).stream()
                .map(p -> p.type().getName())).contains(JavaAnalyzer.class.getName());
        assertThat(Path.of("src/main/resources/META-INF/services/io.codeatlas.core.spi.Analyzer")).exists();
    }
}
