package io.codeatlas.export.classdiagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.classdiagram.ClassDiagramModel.Member;
import io.codeatlas.export.classdiagram.ClassDiagramModel.Relation;
import io.codeatlas.export.classdiagram.ClassDiagramModel.TypeBox;
import io.codeatlas.export.classdiagram.ClassDiagramOptions.MemberLevel;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.MermaidSyntax;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClassDiagramTest {

    /**
     * com.shop.order: OrderService(interface), OrderServiceImpl(@Service), AbstractBase(abstract), OrderStatus(enum), Repo&lt;T&gt;
     * com.shop.order.dto: OrderDto(record)
     * com.shop.member: Member
     */
    static GraphFixture shop() {
        GraphFixture f = GraphFixture.create();
        f.type("com.shop.order.OrderService", "interface", "public");
        f.method("com.shop.order.OrderService", "find", List.of("java.lang.Long"), "com.shop.order.dto.OrderDto", "public", "abstract");

        f.type("com.shop.order.AbstractBase", "class", "public", "abstract");
        f.method("com.shop.order.AbstractBase", "validate", List.of(), "void", "protected", "abstract");
        f.method("com.shop.order.AbstractBase", "log", List.of("java.lang.String"), "void");

        String impl = f.type("com.shop.order.OrderServiceImpl", "class", "public");
        f.attr(impl, "stereotype", "service");
        f.field("com.shop.order.OrderServiceImpl", "repo", "com.shop.order.Repo<com.shop.order.dto.OrderDto>", "private", "final");
        f.field("com.shop.order.OrderServiceImpl", "COUNT", "int", "public", "static");
        f.field("com.shop.order.OrderServiceImpl", "status", "com.shop.order.OrderStatus", "private");
        f.method("com.shop.order.OrderServiceImpl", "<init>", List.of("com.shop.order.Repo"), null, "public");
        f.method("com.shop.order.OrderServiceImpl", "find", List.of("java.lang.Long"), "com.shop.order.dto.OrderDto", "public");
        f.method("com.shop.order.OrderServiceImpl", "of", List.of(), "com.shop.order.OrderServiceImpl", "public", "static");
        f.method("com.shop.order.OrderServiceImpl", "validate", List.of(), "void", "protected");
        f.method("com.shop.order.OrderServiceImpl", "getStatus", List.of(), "com.shop.order.OrderStatus", "public");
        f.method("com.shop.order.OrderServiceImpl", "setStatus", List.of("com.shop.order.OrderStatus"), "void", "public");
        f.method("com.shop.order.OrderServiceImpl", "isReady", List.of(), "boolean", "public");
        f.method("com.shop.order.OrderServiceImpl", "helper", List.of("java.util.Map<java.lang.String,java.util.List<java.lang.Long>>"), "void", "private");
        f.method("com.shop.order.OrderServiceImpl", "lambda$find$0", List.of(), "void", "private", "static");

        f.type("com.shop.order.OrderStatus", "enum", "public");
        String repo = f.type("com.shop.order.Repo", "interface", "public");
        f.attr(repo, "typeParameters", List.of("T"));
        f.type("com.shop.order.dto.OrderDto", "record", "public");
        f.type("com.shop.member.Member", "class", "public");

        f.typeEdge("com.shop.order.OrderServiceImpl", "com.shop.order.AbstractBase", EdgeKind.EXTENDS);
        f.typeEdge("com.shop.order.OrderServiceImpl", "com.shop.order.OrderService", EdgeKind.IMPLEMENTS);
        f.edge(Ids.javaType("com.shop.order.OrderServiceImpl"), Ids.javaType("com.shop.order.Repo"), EdgeKind.ASSOCIATES,
                Map.of("field", "repo", "multiplicity", "1"));
        f.edge(Ids.javaType("com.shop.order.OrderServiceImpl"), Ids.javaType("com.shop.order.OrderStatus"), EdgeKind.ASSOCIATES,
                Map.of("field", "status", "multiplicity", "*"));
        f.typeEdge("com.shop.order.OrderServiceImpl", "com.shop.order.dto.OrderDto", EdgeKind.DEPENDS_ON);
        f.typeEdge("com.shop.order.OrderService", "com.shop.order.dto.OrderDto", EdgeKind.DEPENDS_ON);
        f.typeEdge("com.shop.order.OrderServiceImpl", "com.shop.member.Member", EdgeKind.DEPENDS_ON);
        f.edge(Ids.javaType("com.shop.order.OrderServiceImpl"), Ids.javaType("com.shop.order.Repo"), EdgeKind.INJECTS,
                Map.of("via", "constructor"));
        return f;
    }

    private static List<String> names(ClassDiagramModel model) {
        return model.types().stream().map(TypeBox::displayName).toList();
    }

    @Test
    @DisplayName("F-014-AC1 범위의 타입·멤버·관계가 ID 순으로 모델에 나온다")
    void buildsModel() {
        ClassDiagramModel model = ClassDiagrams.model(shop().graph(),
                ClassDiagramOptions.builder().packagePrefix("com.shop.order").includeSubpackages(false).build());

        assertThat(names(model)).containsExactly("AbstractBase", "OrderService", "OrderServiceImpl", "OrderStatus", "Repo");
        TypeBox impl = model.type("com.shop.order.OrderServiceImpl").orElseThrow();
        assertThat(impl.stereotype()).isEqualTo("service");
        assertThat(impl.fields()).extracting(Member::name).containsExactly("COUNT", "repo", "status");
        assertThat(impl.methods()).extracting(Member::name)
                .containsExactly("OrderServiceImpl", "find", "getStatus", "helper", "isReady", "of", "setStatus", "validate");
        // 범위 밖(dto, member)으로 가는 관계는 없다. INJECTS는 기본으로 끈다.
        assertThat(model.relations()).extracting(Relation::kind)
                .containsExactlyInAnyOrder(EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.ASSOCIATES, EdgeKind.ASSOCIATES);
    }

    @Test
    @DisplayName("F-014-AC2 Mermaid: 가시성, static, abstract, 스테레오타입, 제네릭, 생성자")
    void rendersMermaidMembers() {
        String out = ClassDiagrams.render(shop().graph(),
                ClassDiagramOptions.builder().packagePrefix("com.shop.order").includeSubpackages(false).build(),
                ClassDiagramFormat.MERMAID);

        assertThat(out).startsWith("classDiagram\n");
        assertThat(out).contains("    class OrderService {\n        <<interface>>\n        +find(Long p0) OrderDto*\n    }\n");
        assertThat(out).contains("    class AbstractBase {\n        <<abstract>>\n        ~log(String p0) void\n        #validate() void*\n    }\n");
        assertThat(out).contains("    class OrderServiceImpl {\n        <<Service>>\n"
                + "        +int COUNT$\n"
                + "        -Repo~OrderDto~ repo\n"
                + "        -OrderStatus status\n"
                + "        +OrderServiceImpl(Repo p0)\n"
                + "        +find(Long p0) OrderDto\n"
                + "        +getStatus() OrderStatus\n"
                + "        -helper(Map~String,List~Long~~ p0) void\n"
                + "        +isReady() boolean\n"
                + "        +of() OrderServiceImpl$\n"
                + "        +setStatus(OrderStatus p0) void\n"
                + "        #validate() void\n"
                + "    }\n");
        assertThat(out).contains("    class OrderStatus {\n        <<enumeration>>\n    }\n");
        assertThat(out).contains("    class Repo~T~ {\n        <<interface>>\n    }\n");
        assertThat(out).doesNotContain("lambda$");
        assertThat(MermaidSyntax.checkClass(out)).isEmpty();
    }

    @Test
    @DisplayName("F-014-AC3 Mermaid: 상속 <|--, 구현 <|.., 연관 --> 다중도와 필드명, 의존 ..>")
    void rendersMermaidRelations() {
        String out = ClassDiagrams.render(shop().graph(), ClassDiagramOptions.defaults(), ClassDiagramFormat.MERMAID);

        assertThat(out).contains("    AbstractBase <|-- OrderServiceImpl\n");
        assertThat(out).contains("    OrderService <|.. OrderServiceImpl\n");
        assertThat(out).contains("    OrderServiceImpl --> \"1\" Repo : repo\n");
        assertThat(out).contains("    OrderServiceImpl --> \"*\" OrderStatus : status\n");
        assertThat(out).contains("    OrderServiceImpl ..> OrderDto\n");
        assertThat(out).contains("    OrderServiceImpl ..> Member\n");
        assertThat(out).contains("    class OrderDto {\n        <<record>>\n    }\n");
        assertThat(MermaidSyntax.checkClass(out)).isEmpty();
    }

    @Test
    @DisplayName("F-014-AC4 PlantUML: interface, abstract class, enum, {static}, {abstract}, 관계 화살표")
    void rendersPlantUml() {
        String out = ClassDiagrams.render(shop().graph(), ClassDiagramOptions.defaults(), ClassDiagramFormat.PLANTUML);

        assertThat(out).startsWith("@startuml\n").endsWith("@enduml\n");
        assertThat(out).contains("interface \"OrderService\" as com_shop_order_OrderService {\n  + {abstract} find(p0 : Long) : OrderDto\n}\n");
        assertThat(out).contains("abstract class \"AbstractBase\" as com_shop_order_AbstractBase {\n");
        assertThat(out).contains("  # {abstract} validate() : void\n");
        assertThat(out).contains("class \"OrderServiceImpl\" as com_shop_order_OrderServiceImpl <<Service>> {\n");
        assertThat(out).contains("  + {static} COUNT : int\n");
        assertThat(out).contains("  - repo : Repo<OrderDto>\n");
        assertThat(out).contains("  + OrderServiceImpl(p0 : Repo)\n");
        assertThat(out).contains("  + {static} of() : OrderServiceImpl\n");
        assertThat(out).contains("enum \"OrderStatus\" as com_shop_order_OrderStatus {\n}\n");
        assertThat(out).contains("interface \"Repo<T>\" as com_shop_order_Repo {\n}\n");
        assertThat(out).contains("class \"OrderDto\" as com_shop_order_dto_OrderDto <<record>> {\n}\n");
        assertThat(out).contains("com_shop_order_AbstractBase <|-- com_shop_order_OrderServiceImpl\n");
        assertThat(out).contains("com_shop_order_OrderService <|.. com_shop_order_OrderServiceImpl\n");
        assertThat(out).contains("com_shop_order_OrderServiceImpl --> \"1\" com_shop_order_Repo : repo\n");
        assertThat(out).contains("com_shop_order_OrderServiceImpl ..> com_shop_member_Member\n");
    }

    @Test
    @DisplayName("F-014-AC5 범위: 패키지 접두(하위 포함 여부), 타입 목록 + 이웃 깊이")
    void scopes() {
        GraphModel g = shop().graph();

        assertThat(names(ClassDiagrams.model(g, ClassDiagramOptions.builder().packagePrefix("com.shop.order").build())))
                .containsExactly("AbstractBase", "OrderService", "OrderServiceImpl", "OrderStatus", "Repo", "OrderDto");
        assertThat(names(ClassDiagrams.model(g, ClassDiagramOptions.builder().packagePrefix("com.shop.order").includeSubpackages(false).build())))
                .doesNotContain("OrderDto").hasSize(5);
        // 접두는 패키지 경계에서만 맞는다
        assertThat(names(ClassDiagrams.model(g, ClassDiagramOptions.builder().packagePrefix("com.shop.ord").build()))).isEmpty();

        assertThat(names(ClassDiagrams.model(g, ClassDiagramOptions.builder().types("com.shop.order.OrderService").neighborDepth(0).build())))
                .containsExactly("OrderService");
        assertThat(names(ClassDiagrams.model(g, ClassDiagramOptions.builder().types("OrderService").neighborDepth(1).build())))
                .containsExactly("OrderService", "OrderServiceImpl", "OrderDto");
        assertThat(names(ClassDiagrams.model(g, ClassDiagramOptions.builder().types("java:type:com.shop.member.Member").neighborDepth(2).build())))
                .contains("Member", "OrderServiceImpl", "OrderService", "AbstractBase", "Repo");
    }

    @Test
    @DisplayName("F-014-AC6 멤버 표시 수준과 getter·setter 숨김")
    void memberLevels() {
        GraphModel g = shop().graph();

        TypeBox publicOnly = ClassDiagrams.model(g, ClassDiagramOptions.builder().members(MemberLevel.PUBLIC).build())
                .type("com.shop.order.OrderServiceImpl").orElseThrow();
        assertThat(publicOnly.fields()).extracting(Member::name).containsExactly("COUNT");
        assertThat(publicOnly.methods()).extracting(Member::name).doesNotContain("helper", "validate").contains("find", "of");
        // 인터페이스 멤버는 수정자가 없어도 public이다
        assertThat(ClassDiagrams.model(g, ClassDiagramOptions.builder().members(MemberLevel.PUBLIC).build())
                .type("com.shop.order.OrderService").orElseThrow().methods()).hasSize(1);

        ClassDiagramModel none = ClassDiagrams.model(g, ClassDiagramOptions.builder().members(MemberLevel.NONE).build());
        assertThat(none.types()).allMatch(t -> t.fields().isEmpty() && t.methods().isEmpty());
        assertThat(ClassDiagrams.render(g, ClassDiagramOptions.builder().members(MemberLevel.NONE).build(), ClassDiagramFormat.MERMAID))
                .contains("    class OrderStatus {\n        <<enumeration>>\n    }\n").doesNotContain("find(");

        TypeBox noAccessors = ClassDiagrams.model(g, ClassDiagramOptions.builder().hideAccessors(true).build())
                .type("com.shop.order.OrderServiceImpl").orElseThrow();
        assertThat(noAccessors.methods()).extracting(Member::name).doesNotContain("getStatus", "setStatus", "isReady")
                .contains("find", "helper");
    }

    @Test
    @DisplayName("F-014-AC7 external 타입은 기본 제외, 옵션으로 상위 타입만 이름으로 또는 전부 포함")
    void externalTypes() {
        GraphFixture f = shop();
        f.externalType("org.lib.BaseSupport", "class");
        f.externalType("java.io.Serializable", "interface");
        f.externalType("org.lib.Util", "class");
        f.externalMethod("org.lib.BaseSupport", "init");
        f.edge(Ids.javaType("org.lib.BaseSupport"), Ids.javaMethod("org.lib.BaseSupport", "init"), EdgeKind.CONTAINS);
        f.typeEdge("com.shop.member.Member", "org.lib.BaseSupport", EdgeKind.EXTENDS);
        f.typeEdge("com.shop.member.Member", "java.io.Serializable", EdgeKind.IMPLEMENTS);
        f.typeEdge("com.shop.member.Member", "org.lib.Util", EdgeKind.DEPENDS_ON);
        // 그래프에 노드가 없는 상위 타입(마무리 단계 전)
        f.typeEdge("com.shop.member.Member", "org.missing.Gone", EdgeKind.IMPLEMENTS);
        GraphModel g = f.graph();
        ClassDiagramOptions.Builder scope = ClassDiagramOptions.builder().packagePrefix("com.shop.member");

        ClassDiagramModel byDefault = ClassDiagrams.model(g, scope.build());
        assertThat(names(byDefault)).containsExactly("Member");
        assertThat(byDefault.relations()).isEmpty();

        ClassDiagramModel supers = ClassDiagrams.model(g, scope.showExternalSupertypes(true).build());
        assertThat(names(supers)).containsExactly("Member", "Serializable", "BaseSupport", "Gone");
        assertThat(supers.type("org.lib.BaseSupport").orElseThrow().nameOnly()).isTrue();
        assertThat(supers.type("org.lib.BaseSupport").orElseThrow().methods()).isEmpty();
        assertThat(supers.relations()).extracting(Relation::kind)
                .containsExactlyInAnyOrder(EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.IMPLEMENTS);
        String mermaid = ClassDiagrams.render(g, scope.build(), ClassDiagramFormat.MERMAID);
        assertThat(mermaid).contains("    class BaseSupport\n").contains("    BaseSupport <|-- Member\n").doesNotContain("Util");
        assertThat(MermaidSyntax.checkClass(mermaid)).isEmpty();

        ClassDiagramModel all = ClassDiagrams.model(g, ClassDiagramOptions.builder().includeExternal(true).build());
        assertThat(names(all)).contains("BaseSupport", "Serializable", "Util");
        assertThat(all.type("org.lib.BaseSupport").orElseThrow().methods()).extracting(Member::name).containsExactly("init");
    }

    @Test
    @DisplayName("F-014-AC8 익명·로컬 타입은 기본 제외, 중첩 타입 제외 옵션")
    void nestedAndAnonymous() {
        GraphFixture f = GraphFixture.create();
        f.type("com.a.Outer", "class", "public");
        f.type("com.a.Outer$Inner", "class", "public", "static");
        f.type("com.a.Outer$1", "class");
        f.type("com.a.Outer$1Local", "class");
        f.type("com.a.Outer$Inner$2", "class");
        f.typeEdge("com.a.Outer$1", "com.a.Outer", EdgeKind.EXTENDS);
        GraphModel g = f.graph();

        assertThat(names(ClassDiagrams.model(g, ClassDiagramOptions.defaults()))).containsExactly("Outer", "Outer.Inner");
        assertThat(names(ClassDiagrams.model(g, ClassDiagramOptions.builder().includeNested(false).build()))).containsExactly("Outer");
        assertThat(names(ClassDiagrams.model(g, ClassDiagramOptions.builder().includeAnonymous(true).build())))
                .containsExactly("Outer", "Outer.1", "Outer.1Local", "Outer.Inner", "Outer.Inner.2");
        String mermaid = ClassDiagrams.render(g, ClassDiagramOptions.defaults(), ClassDiagramFormat.MERMAID);
        assertThat(mermaid).contains("    class Outer_Inner[\"Outer.Inner\"]\n");
        assertThat(MermaidSyntax.checkClass(mermaid)).isEmpty();
        assertThat(ClassDiagrams.render(g, ClassDiagramOptions.defaults(), ClassDiagramFormat.PLANTUML))
                .contains("class \"Outer.Inner\" as com_a_Outer_Inner {\n}\n");
    }

    @Test
    @DisplayName("F-014-AC9 타입 수가 상한을 넘으면 멤버를 생략하고 경고 주석을 남긴다")
    void maxTypes() {
        GraphModel g = shop().graph();
        ClassDiagramOptions options = ClassDiagramOptions.builder().maxTypes(3).build();

        ClassDiagramModel model = ClassDiagrams.model(g, options);
        assertThat(model.types()).hasSize(7).allMatch(t -> t.fields().isEmpty() && t.methods().isEmpty());
        assertThat(model.warnings()).hasSize(1);

        String mermaid = ClassDiagrams.render(g, options, ClassDiagramFormat.MERMAID);
        assertThat(mermaid).contains("    %% 경고: 타입 7개가 상한 3개를 넘어 멤버를 생략했다.\n").doesNotContain("find(");
        assertThat(MermaidSyntax.checkClass(mermaid)).isEmpty();
        String puml = ClassDiagrams.render(g, options, ClassDiagramFormat.PLANTUML);
        assertThat(puml).contains("' 경고: 타입 7개가 상한 3개를 넘어 멤버를 생략했다.\n").doesNotContain("find(");

        assertThat(ClassDiagrams.model(g, ClassDiagramOptions.builder().maxTypes(7).build()).warnings()).isEmpty();
    }

    @Test
    @DisplayName("F-014-AC10 패키지 묶음: Mermaid namespace, PlantUML package")
    void groupsByPackage() {
        GraphModel g = shop().graph();
        ClassDiagramOptions options = ClassDiagramOptions.builder().groupByPackage(true).members(MemberLevel.NONE).build();

        String mermaid = ClassDiagrams.render(g, options, ClassDiagramFormat.MERMAID);
        assertThat(mermaid).contains("    namespace com_shop_member {\n        class Member\n    }\n");
        assertThat(mermaid).contains("    namespace com_shop_order_dto {\n        class OrderDto {\n            <<record>>\n        }\n    }\n");
        assertThat(mermaid.indexOf("namespace com_shop_order {")).isLessThan(mermaid.indexOf("AbstractBase <|-- OrderServiceImpl"));
        assertThat(MermaidSyntax.checkClass(mermaid)).isEmpty();

        String puml = ClassDiagrams.render(g, options, ClassDiagramFormat.PLANTUML);
        assertThat(puml).contains("package \"com.shop.member\" {\n  class \"Member\" as com_shop_member_Member {\n  }\n}\n");
    }

    @Test
    @DisplayName("F-014-AC11 관계 종류 선택: EXTENDS만, INJECTS 포함")
    void relationKinds() {
        GraphModel g = shop().graph();

        ClassDiagramModel onlyExtends = ClassDiagrams.model(g, ClassDiagramOptions.builder().relationKinds(EdgeKind.EXTENDS).build());
        assertThat(onlyExtends.relations()).extracting(Relation::kind).containsExactly(EdgeKind.EXTENDS);

        ClassDiagramOptions withInjects = ClassDiagramOptions.builder()
                .relationKinds(EdgeKind.EXTENDS, EdgeKind.INJECTS).build();
        String mermaid = ClassDiagrams.render(g, withInjects, ClassDiagramFormat.MERMAID);
        assertThat(mermaid).contains("    OrderServiceImpl ..> Repo : inject\n").doesNotContain("<|..").doesNotContain("-->");
        assertThat(MermaidSyntax.checkClass(mermaid)).isEmpty();
        assertThat(ClassDiagrams.render(g, withInjects, ClassDiagramFormat.PLANTUML))
                .contains("com_shop_order_OrderServiceImpl ..> com_shop_order_Repo : <<inject>>\n");
    }

    @Test
    @DisplayName("F-014-AC12 입력 순서와 무관하게 같은 출력, 속성 없는 노드·특수 이름에도 예외 없음")
    void deterministicAndDefensive() {
        GraphFixture f = shop();
        // 속성이 전혀 없는 타입과 멤버, 같은 단순 이름, 한글·$ 이름
        f.graph().declare(Node.of(Ids.javaType("com.x.Bare"), NodeKind.JAVA_TYPE, "Bare"));
        f.graph().declare(Node.of(Ids.javaMethod("com.x.Bare", "run", "int[]", "java.lang.String..."), NodeKind.JAVA_METHOD, "run"));
        f.graph().declare(Node.of(Ids.javaField("com.x.Bare", "value"), NodeKind.JAVA_FIELD, "value"));
        f.edge(Ids.javaType("com.x.Bare"), Ids.javaMethod("com.x.Bare", "run", "int[]", "java.lang.String..."), EdgeKind.CONTAINS);
        f.edge(Ids.javaType("com.x.Bare"), Ids.javaField("com.x.Bare", "value"), EdgeKind.CONTAINS);
        f.type("com.x.Member", "class", "public");
        f.type("com.x.주문", "class", "public");
        f.type("com.x.end", "class");
        f.method("com.x.주문", "처리{}", List.of("T<A>"), "java.util.List<? extends com.x.주문>", "public");
        f.typeEdge("com.x.주문", "com.x.Bare", EdgeKind.ASSOCIATES);
        f.typeEdge("com.x.주문", "com.shop.member.Member", EdgeKind.DEPENDS_ON);
        GraphModel g = f.graph();
        GraphModel reversed = f.reversedCopy();
        ClassDiagramOptions options = ClassDiagramOptions.builder().groupByPackage(true).build();

        for (ClassDiagramFormat format : ClassDiagramFormat.values()) {
            assertThatCode(() -> ClassDiagrams.render(g, options, format)).as(format.name()).doesNotThrowAnyException();
            assertThat(ClassDiagrams.render(reversed, options, format)).as(format.name()).isEqualTo(ClassDiagrams.render(g, options, format));
        }
        String mermaid = ClassDiagrams.render(g, ClassDiagramOptions.defaults(), ClassDiagramFormat.MERMAID);
        assertThat(MermaidSyntax.checkClass(mermaid)).isEmpty();
        // 속성이 없으면 ID에서 매개변수를 읽고 가시성은 패키지(~)로 둔다
        assertThat(mermaid).contains("    class Bare {\n        ~value\n        ~run(int[], String...)\n    }\n");
        // 단순 이름이 겹치면 전체 이름으로 식별자를 만들고 라벨을 붙인다
        assertThat(mermaid).contains("class com_shop_member_Member[\"Member\"]").contains("class com_x_Member[\"Member\"]");
        assertThat(mermaid).contains("    주문 --> Bare\n").contains("    주문 ..> com_shop_member_Member\n");
        assertThat(mermaid).contains("+처리()(T~A~ p0) List~? extends 주문~");
        assertThat(mermaid).contains("    class end_[\"end\"]\n");
    }

    @Test
    @DisplayName("F-014-AC15 F-001-AC49 시작 타입 이름 중 그래프에서 찾지 못한 것을 모델과 함께 unresolvedNames()로 돌려준다")
    void reportsUnresolvedTypeNames() {
        GraphFixture f = shop();
        f.externalType("org.lib.External", "class");
        GraphModel g = f.graph();

        ClassDiagramModel model = ClassDiagrams.model(g,
                ClassDiagramOptions.builder().types("OrderService", "Nope", "com.gone.Missing", "External", "", "Nope").neighborDepth(0).build());
        assertThat(names(model)).containsExactly("OrderService");
        // external 타입은 includeExternal을 주지 않으면 후보가 아니다
        assertThat(model.unresolvedNames()).containsExactly("Nope", "com.gone.Missing", "External");

        ClassDiagramModel withExternal = ClassDiagrams.model(g,
                ClassDiagramOptions.builder().types("Nope", "External").neighborDepth(0).includeExternal(true).build());
        assertThat(names(withExternal)).containsExactly("External");
        assertThat(withExternal.unresolvedNames()).containsExactly("Nope");

        assertThat(ClassDiagrams.model(g, ClassDiagramOptions.defaults()).unresolvedNames()).isEmpty();
        assertThat(ClassDiagrams.model(g, ClassDiagramOptions.builder().packagePrefix("com.none").build()).unresolvedNames()).isEmpty();
        assertThat(new ClassDiagramModel(List.of(), List.of(), List.of()).unresolvedNames()).isEmpty();
    }
}
