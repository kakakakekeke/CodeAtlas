package io.codeatlas.java;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.hasEdge;
import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-002-R14: Lombok 애너테이션이 만드는 멤버의 합성 노드. */
class LombokStructureTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    private static String type(String name) {
        return Ids.javaType(name);
    }

    /** 타입이 CONTAINS하는 메서드 중 Lombok 합성 노드의 ID에서 타입 접두사를 뗀 것. */
    private static List<String> lombokMethods(GraphModel g, String typeName) {
        String prefix = "java:method:" + typeName + "#";
        return g.successors(type(typeName), EdgeKind.CONTAINS).stream()
                .filter(n -> n.kind() == NodeKind.JAVA_METHOD && n.attrString("lombok") != null)
                .map(n -> n.id().substring(prefix.length())).sorted().toList();
    }

    @Test
    @DisplayName("F-002-AC14 @Getter/@Setter: 클래스·필드 수준, AccessLevel.NONE, static·final 필드, boolean 이름 규칙, 이미 선언한 메서드")
    void gettersAndSetters() {
        LombokFx.eachClasspath(Fx.files(
                "src/com/a/Member.java", """
                        package com.a;
                        import lombok.AccessLevel;
                        import lombok.Getter;
                        import lombok.Setter;
                        @Getter
                        @Setter
                        public class Member {
                            private Long id;
                            private String name;
                            private boolean active;
                            private boolean isAdmin;
                            private Boolean verified;
                            private final String code = "X";
                            private static int count;
                            @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE) private String secret;
                            @Setter(AccessLevel.PRIVATE) private int age;
                            private String uName;
                            private java.util.List<String> tags;
                            public String getName() { return name; }
                        }
                        """,
                "src/com/a/Partial.java", """
                        package com.a;
                        import lombok.*;
                        public class Partial {
                            @Getter private String a;
                            private String b;
                            @Getter @Setter private static String shared;
                            @lombok.Setter private int[] codes;
                        }
                        """,
                "src/com/a/Wild.java", """
                        package com.a;
                        import lombok.*;
                        import lombok.experimental.*;
                        @ToString
                        @Accessors(chain = true)
                        public class Wild { }
                        """), (cp, g) -> {
            assertThat(lombokMethods(g, "com.a.Member")).as(cp).containsExactly(
                    "getAge()", "getCode()", "getId()", "getTags()", "getUName()", "getVerified()", "isActive()", "isAdmin()",
                    "setActive(boolean)", "setAdmin(boolean)", "setAge(int)", "setId(java.lang.Long)", "setName(java.lang.String)",
                    "setTags(java.util.List)", "setUName(java.lang.String)", "setVerified(java.lang.Boolean)");

            Node getId = node(g, m("com.a.Member", "getId"));
            assertThat(getId.declared()).isTrue();
            assertThat(getId.attrBoolean("synthetic")).isTrue();
            assertThat(getId.attrString("lombok")).isEqualTo("Getter");
            assertThat(getId.attrString("returnType")).isEqualTo("Long");
            assertThat(getId.attrString("signature")).isEqualTo("getId(): Long");
            assertThat(getId.attrList("modifiers")).containsExactly("public");
            assertThat(getId.attrBoolean("constructor")).isFalse();
            // 위치는 그 멤버를 만들게 한 필드
            assertThat(getId.location().file()).isEqualTo("src/com/a/Member.java");
            assertThat(getId.location().startLine()).isEqualTo(8);
            assertThat(hasEdge(g, type("com.a.Member"), EdgeKind.CONTAINS, getId.id())).isTrue();

            Node setId = node(g, m("com.a.Member", "setId", "java.lang.Long"));
            assertThat(setId.attrString("lombok")).isEqualTo("Setter");
            assertThat(setId.attrList("parameterTypes")).containsExactly("Long");
            assertThat(setId.attrList("parameterNames")).containsExactly("id");
            assertThat(setId.attrString("returnType")).isEqualTo("void");
            assertThat(node(g, m("com.a.Member", "setAge", "int")).attrList("modifiers")).containsExactly("private");

            // 합성 접근자에서 필드로 ACCESSES
            assertThat(edge(g, getId.id(), EdgeKind.ACCESSES, Ids.javaField("com.a.Member", "id")).attrString("access")).isEqualTo("read");
            assertThat(edge(g, setId.id(), EdgeKind.ACCESSES, Ids.javaField("com.a.Member", "id")).attrString("access")).isEqualTo("write");

            // 소스에 이미 있는 메서드는 그대로다
            Node getName = node(g, m("com.a.Member", "getName"));
            assertThat(getName.attrBoolean("synthetic")).isFalse();
            assertThat(getName.attrString("lombok")).isNull();

            assertThat(lombokMethods(g, "com.a.Partial")).as(cp)
                    .containsExactly("getA()", "getShared()", "setCodes(int[])", "setShared(java.lang.String)");
            assertThat(node(g, m("com.a.Partial", "getShared")).attrList("modifiers")).containsExactly("public", "static");
            // 와일드카드로 import한 Lombok 애너테이션도 이름을 복원한다
            assertThat(hasEdge(g, Ids.javaField("com.a.Partial", "a"), EdgeKind.ANNOTATED_BY, type("lombok.Getter"))).as(cp).isTrue();
            assertThat(hasEdge(g, type("com.a.Wild"), EdgeKind.ANNOTATED_BY, type("lombok.ToString"))).as(cp).isTrue();
            assertThat(hasEdge(g, type("com.a.Wild"), EdgeKind.ANNOTATED_BY, type("lombok.experimental.Accessors"))).as(cp).isTrue();
            assertThat(lombokMethods(g, "com.a.Wild")).as(cp).containsExactly("toString()");
            assertThat(g.diagnostics()).as(cp).noneMatch(d -> d.code().equals("UNRESOLVED_REFERENCE"));
        });
    }

    @Test
    @DisplayName("F-002-AC15 @Data, @Value, @NoArgsConstructor/@AllArgsConstructor/@RequiredArgsConstructor(staticName), @ToString, @EqualsAndHashCode")
    void dataValueAndConstructors() {
        LombokFx.eachClasspath(Fx.files(
                "src/com/a/Dto.java", """
                        package com.a;
                        import lombok.Data;
                        import lombok.NonNull;
                        import java.util.ArrayList;
                        import java.util.List;
                        @Data
                        public class Dto {
                            private final Long id;
                            @NonNull private String name;
                            private int qty;
                            private List<String> tags = new ArrayList<>();
                            private static final int MAX = 1;
                        }
                        """,
                "src/com/a/Money.java", """
                        package com.a;
                        import lombok.Value;
                        @Value
                        public class Money {
                            String currency;
                            long amount;
                        }
                        """,
                "src/com/a/Point.java", """
                        package com.a;
                        import lombok.AllArgsConstructor;
                        import lombok.NoArgsConstructor;
                        import lombok.RequiredArgsConstructor;
                        @NoArgsConstructor(force = true)
                        @AllArgsConstructor
                        @RequiredArgsConstructor(staticName = "of")
                        public class Point {
                            private final int x;
                            private int y;
                            private final int z = 0;
                        }
                        """,
                "src/com/a/Level.java", """
                        package com.a;
                        import lombok.*;
                        @Getter
                        @AllArgsConstructor
                        public enum Level {
                            LOW(1), HIGH(2);
                            private final int rank;
                            int twice() { val doubled = rank * 2; return doubled; }
                        }
                        """,
                "src/com/a/Explicit.java", """
                        package com.a;
                        import lombok.AllArgsConstructor;
                        import lombok.EqualsAndHashCode;
                        import lombok.ToString;
                        @AllArgsConstructor
                        @ToString
                        @EqualsAndHashCode
                        public class Explicit {
                            private int x;
                            public Explicit(int x) { this.x = x; }
                            public int hashCode() { return x; }
                        }
                        """), (cp, g) -> {
            assertThat(lombokMethods(g, "com.a.Dto")).as(cp).containsExactly(
                    "<init>(java.lang.Long,java.lang.String)", "equals(java.lang.Object)", "getId()", "getName()", "getQty()", "getTags()",
                    "hashCode()", "setName(java.lang.String)", "setQty(int)", "setTags(java.util.List)", "toString()");
            Node ctor = node(g, m("com.a.Dto", "<init>", "java.lang.Long", "java.lang.String"));
            assertThat(ctor.attrString("lombok")).isEqualTo("Data");
            assertThat(ctor.attrBoolean("synthetic")).isTrue();
            assertThat(ctor.attrBoolean("constructor")).isTrue();
            assertThat(ctor.attrList("parameterNames")).containsExactly("id", "name");
            assertThat(ctor.attrString("signature")).isEqualTo("Dto(Long, String)");
            // 클래스 애너테이션 위치
            assertThat(ctor.location().startLine()).isEqualTo(6);
            // Lombok이 생성자를 만들면 컴파일러의 기본 생성자는 없다
            assertThat(g.node(m("com.a.Dto", "<init>"))).as(cp).isEmpty();
            assertThat(node(g, m("com.a.Dto", "getId")).attrString("lombok")).isEqualTo("Data");

            assertThat(lombokMethods(g, "com.a.Money")).as(cp).containsExactly(
                    "<init>(java.lang.String,long)", "equals(java.lang.Object)", "getAmount()", "getCurrency()", "hashCode()", "toString()");
            assertThat(node(g, m("com.a.Money", "getAmount")).attrString("lombok")).isEqualTo("Value");

            assertThat(lombokMethods(g, "com.a.Point")).as(cp).containsExactly("<init>()", "<init>(int)", "<init>(int,int)", "of(int)");
            assertThat(node(g, m("com.a.Point", "<init>")).attrString("lombok")).isEqualTo("NoArgsConstructor");
            assertThat(node(g, m("com.a.Point", "<init>", "int", "int")).attrString("lombok")).isEqualTo("AllArgsConstructor");
            assertThat(node(g, m("com.a.Point", "<init>", "int")).attrList("modifiers")).containsExactly("private");
            Node of = node(g, m("com.a.Point", "of", "int"));
            assertThat(of.attrString("lombok")).isEqualTo("RequiredArgsConstructor");
            assertThat(of.attrList("modifiers")).containsExactly("public", "static");
            assertThat(of.attrString("returnType")).isEqualTo("Point");

            // enum의 합성 생성자는 private이고, 와일드카드로 import한 lombok.val도 이름을 복원한다
            assertThat(lombokMethods(g, "com.a.Level")).as(cp).containsExactly("<init>(int)", "getRank()");
            assertThat(node(g, m("com.a.Level", "<init>", "int")).attrList("modifiers")).containsExactly("private");
            assertThat(g.diagnostics()).as(cp).noneMatch(d -> d.code().equals("UNRESOLVED_REFERENCE"));

            // 소스에 같은 시그니처가 있으면 만들지 않는다
            assertThat(lombokMethods(g, "com.a.Explicit")).as(cp).containsExactly("equals(java.lang.Object)", "toString()");
            assertThat(node(g, m("com.a.Explicit", "<init>", "int")).attrBoolean("synthetic")).isFalse();
        });
    }

    @Test
    @DisplayName("F-002-AC16 @Builder(클래스·생성자, toBuilder, @Singular)와 @SuperBuilder: builder(), 중첩 빌더 타입, 필드별 메서드, build()")
    void builders() {
        LombokFx.eachClasspath(Fx.files(
                "src/com/a/Item.java", "package com.a; public class Item { }",
                "src/com/a/Order.java", """
                        package com.a;
                        import lombok.Builder;
                        import lombok.Singular;
                        import java.util.List;
                        @Builder(toBuilder = true)
                        public class Order {
                            private Long id;
                            private String name;
                            @Singular("item") private List<Item> items;
                            @Builder.Default private final String status = "NEW";
                            private final String fixed = "F";
                            private static int n;
                        }
                        """,
                "src/com/a/Coupon.java", """
                        package com.a;
                        import lombok.Builder;
                        public class Coupon {
                            private String code;
                            private int rate;
                            private String memo;
                            @Builder
                            public Coupon(String code, int rate) { this.code = code; this.rate = rate; }
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
                        import lombok.experimental.SuperBuilder;
                        @SuperBuilder
                        public class Child extends Base { private String name; }
                        """), (cp, g) -> {
            Node builderType = node(g, type("com.a.Order$OrderBuilder"));
            assertThat(builderType.kind()).isEqualTo(NodeKind.JAVA_TYPE);
            assertThat(builderType.declared()).isTrue();
            assertThat(builderType.name()).isEqualTo("OrderBuilder");
            assertThat(builderType.attrBoolean("synthetic")).isTrue();
            assertThat(builderType.attrString("lombok")).isEqualTo("Builder");
            assertThat(builderType.attrString("typeKind")).isEqualTo("class");
            assertThat(builderType.location().startLine()).isEqualTo(5);
            assertThat(hasEdge(g, type("com.a.Order"), EdgeKind.CONTAINS, builderType.id())).isTrue();

            assertThat(lombokMethods(g, "com.a.Order")).as(cp).containsExactly(
                    "<init>(java.lang.Long,java.lang.String,java.util.List,java.lang.String)", "builder()", "toBuilder()");
            Node builder = node(g, m("com.a.Order", "builder"));
            assertThat(builder.attrList("modifiers")).containsExactly("public", "static");
            assertThat(builder.attrString("returnType")).isEqualTo("OrderBuilder");
            assertThat(node(g, m("com.a.Order", "<init>", "java.lang.Long", "java.lang.String", "java.util.List", "java.lang.String"))
                    .attrList("modifiers")).isEmpty();

            assertThat(lombokMethods(g, "com.a.Order$OrderBuilder")).as(cp).containsExactly(
                    "build()", "clearItems()", "id(java.lang.Long)", "item(com.a.Item)", "items(java.util.Collection)",
                    "name(java.lang.String)", "status(java.lang.String)");
            Node id = node(g, m("com.a.Order$OrderBuilder", "id", "java.lang.Long"));
            assertThat(id.attrString("lombok")).isEqualTo("Builder");
            assertThat(id.attrString("returnType")).isEqualTo("OrderBuilder");
            assertThat(id.location().startLine()).isEqualTo(7);
            assertThat(node(g, m("com.a.Order$OrderBuilder", "build")).attrString("returnType")).isEqualTo("Order");

            // 생성자에 붙인 @Builder: 그 생성자의 매개변수만
            assertThat(lombokMethods(g, "com.a.Coupon")).as(cp).containsExactly("builder()");
            assertThat(lombokMethods(g, "com.a.Coupon$CouponBuilder")).as(cp).containsExactly("build()", "code(java.lang.String)", "rate(int)");

            assertThat(lombokMethods(g, "com.a.Child")).as(cp).containsExactly("builder()");
            assertThat(lombokMethods(g, "com.a.Child$ChildBuilder")).as(cp).containsExactly("build()", "name(java.lang.String)");
            assertThat(node(g, type("com.a.Child$ChildBuilder")).attrString("lombok")).isEqualTo("SuperBuilder");
            assertThat(hasEdge(g, type("com.a.Child$ChildBuilder"), EdgeKind.EXTENDS, type("com.a.Base$BaseBuilder"))).as(cp).isTrue();
        });
    }

    @Test
    @DisplayName("F-002-AC17 @Slf4j 계열의 log 필드, @With, @Accessors(chain, fluent)")
    void logWithAccessors() {
        LombokFx.eachClasspath(Fx.files(
                "src/com/a/Svc.java", """
                        package com.a;
                        import lombok.extern.slf4j.Slf4j;
                        @Slf4j
                        public class Svc { }
                        """,
                "src/com/a/Jul.java", """
                        package com.a;
                        import lombok.extern.java.Log;
                        @Log
                        public class Jul { }
                        """,
                "src/com/a/Chained.java", """
                        package com.a;
                        import lombok.Getter;
                        import lombok.Setter;
                        import lombok.With;
                        import lombok.experimental.Accessors;
                        @Getter @Setter @With
                        @Accessors(chain = true)
                        public class Chained {
                            private String name;
                            private boolean isDone;
                            @Accessors(fluent = true) private int size;
                        }
                        """), (cp, g) -> {
            Node log = node(g, Ids.javaField("com.a.Svc", "log"));
            assertThat(log.kind()).isEqualTo(NodeKind.JAVA_FIELD);
            assertThat(log.declared()).isTrue();
            assertThat(log.attrBoolean("synthetic")).isTrue();
            assertThat(log.attrString("lombok")).isEqualTo("Slf4j");
            assertThat(log.attrString("type")).isEqualTo("org.slf4j.Logger");
            assertThat(log.attrList("modifiers")).containsExactly("private", "static", "final");
            assertThat(log.location().startLine()).isEqualTo(3);
            assertThat(hasEdge(g, type("com.a.Svc"), EdgeKind.CONTAINS, log.id())).isTrue();
            assertThat(node(g, Ids.javaField("com.a.Jul", "log")).attrString("type")).isEqualTo("java.util.logging.Logger");

            assertThat(lombokMethods(g, "com.a.Chained")).as(cp).containsExactly(
                    "getName()", "isDone()", "setDone(boolean)", "setName(java.lang.String)", "size()", "size(int)",
                    "withDone(boolean)", "withName(java.lang.String)", "withSize(int)");
            assertThat(node(g, m("com.a.Chained", "setName", "java.lang.String")).attrString("returnType")).isEqualTo("Chained");
            assertThat(node(g, m("com.a.Chained", "size", "int")).attrString("returnType")).isEqualTo("Chained");
            assertThat(node(g, m("com.a.Chained", "size")).attrString("returnType")).isEqualTo("int");
            Node with = node(g, m("com.a.Chained", "withName", "java.lang.String"));
            assertThat(with.attrString("lombok")).isEqualTo("With");
            assertThat(with.attrString("returnType")).isEqualTo("Chained");
        });
    }

    @Test
    @DisplayName("F-002-AC18 Lombok을 쓰지 않으면 합성 노드가 없다: 프로젝트 자체 @Getter, Spring @Value, import 없는 @Data는 Lombok이 아니다")
    void nonLombokAnnotations() {
        GraphModel g = Fx.analyze(AnalysisOptions.defaults(), Fx.files(
                "src/com/a/Getter.java", "package com.a; public @interface Getter { }",
                "src/com/a/Plain.java", """
                        package com.a;
                        import org.springframework.beans.factory.annotation.Value;
                        @Getter
                        @Data
                        public class Plain {
                            @Value("${x}") private String x;
                            @Getter private String y;
                        }
                        """,
                "src/com/a/Other.java", """
                        package com.a;
                        import org.example.annotations.*;
                        @Setter @Builder @Slf4j
                        public class Other { private String z; }
                        """));

        assertThat(g.nodes()).noneMatch(n -> n.attrString("lombok") != null);
        assertThat(targets(g, type("com.a.Plain"), EdgeKind.CONTAINS)).containsExactlyInAnyOrder(
                Ids.javaField("com.a.Plain", "x"), Ids.javaField("com.a.Plain", "y"), m("com.a.Plain", "<init>"));
        assertThat(targets(g, type("com.a.Other"), EdgeKind.CONTAINS)).containsExactlyInAnyOrder(
                Ids.javaField("com.a.Other", "z"), m("com.a.Other", "<init>"));
        for (Edge e : g.edges(EdgeKind.CALLS)) {
            assertThat(e.attrBoolean("lombok")).isFalse();
        }
    }
}
