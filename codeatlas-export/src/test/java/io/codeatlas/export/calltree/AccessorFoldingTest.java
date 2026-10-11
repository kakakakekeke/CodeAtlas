package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.fixture.GraphFixture;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 접근자 접기의 조건(F-015-R43): 이름만 보지 않고 대응하는 필드, 매개변수 수, static 여부를 본다. */
class AccessorFoldingTest {

    private static final String VO = "com.v.BoardVO";

    private static String folded(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.builder().hideAccessors(true).build()), false);
    }

    @Test
    @DisplayName("F-015-AC54 접근자로 접는 것은 Lombok 합성 멤버와, 같은 타입에 대응하는 필드가 있고 매개변수 수가 맞고(getter 0, setter 1) static이 아니며 호출이 나가지 않는 getXxx/isXxx/setXxx뿐이다")
    void foldsOnlyRealAccessors() {
        GraphFixture f = GraphFixture.create();
        f.type(VO, "class", "public");
        f.field(VO, "title", "java.lang.String", "private");
        f.field(VO, "open", "boolean", "private");
        f.field(VO, "isNotice", "boolean", "private");
        f.field(VO, "URL", "java.lang.String", "private");
        f.field(VO, "item", "java.lang.String", "private");
        f.field(VO, "instance", VO, "private", "static");
        f.field(VO, "writer", "java.lang.String", "private");
        String caller = f.method("com.v.BoardService", "run");
        // 접는다: 필드 title·open·isNotice·URL에 대응하는 접근자
        List<String> accessors = List.of(
                f.method(VO, "getTitle", List.of(), "java.lang.String", "public"),
                f.method(VO, "setTitle", List.of("java.lang.String"), "void", "public"),
                f.method(VO, "isOpen", List.of(), "boolean", "public"),
                f.method(VO, "isNotice", List.of(), "boolean", "public"),
                f.method(VO, "getURL", List.of(), "java.lang.String", "public"));
        // 접지 않는다
        List<String> kept = List.of(
                // 대응하는 필드가 없다: 계산하는 메서드, 상태를 바꾸는 메서드
                f.method(VO, "getNextStringId", List.of(), "java.lang.String", "public"),
                f.method(VO, "isAuthenticated", List.of(), "boolean", "public"),
                f.method(VO, "setComplete", List.of(), "void", "public"),
                // 필드는 있지만 매개변수 수가 접근자가 아니다
                f.method(VO, "getItem", List.of("int"), "java.lang.String", "public"),
                f.method(VO, "setItem", List.of("int", "java.lang.String"), "void", "public"),
                // static
                f.method(VO, "getInstance", List.of(), VO, "public", "static"),
                // 이름이 소문자로 이어진다
                f.method(VO, "getter", List.of(), "void", "public"),
                // ThreadLocal을 바꾸는 static 메서드(필드 없음)
                f.method("com.v.DynamicDataSource", "setDataSource", List.of("java.lang.String"), "void", "public", "static"));
        // Lombok이 만들 멤버는 필드 대응을 따지지 않는다(이름이 접근자 꼴이 아니어도 접는다)
        String lombok = f.method(VO, "withWriter", List.of("java.lang.String"), VO, "public");
        f.attr(lombok, "synthetic", true).attr(lombok, "lombok", "With");
        // 소스에 없는 합성 메서드(record 접근자 등: lombok 속성이 없다)는 접근자 접기 대상이 아니다
        String record = f.method(VO, "values", List.of(), VO, "public");
        f.attr(record, "synthetic", true);
        // external 타입의 메서드: 접근자 접기가 아니라 external 접기만 따른다
        f.externalType("org.lib.Status", "interface");
        String external = f.externalMethod("org.lib.Status", "isCompleted");
        accessors.forEach(m -> f.calls(caller, m));
        kept.forEach(m -> f.calls(caller, m));
        f.calls(caller, lombok);
        f.calls(caller, record);
        f.calls(caller, external);
        GraphModel g = f.graph();

        assertThat(folded(g, caller)).isEqualTo("""
                BoardService#run()
                ├─ BoardVO#getNextStringId()
                ├─ BoardVO#isAuthenticated()
                ├─ BoardVO#setComplete()
                ├─ BoardVO#getItem(int)
                ├─ BoardVO#setItem(int, String)
                ├─ BoardVO#getInstance()
                ├─ BoardVO#getter()
                ├─ DynamicDataSource#setDataSource(String)
                ├─ BoardVO#values()
                └─ Status#isCompleted()
                """);
        // external 접기를 켜면 external 메서드만 더 빠진다
        assertThat(CallTrees.toText(CallTrees.build(g, caller, CallTreeOptions.builder().hideAccessors(true).hideExternal(true).build()), false))
                .doesNotContain("Status#isCompleted").contains("BoardVO#values()");
        // 접근자 접기를 끄면 전부 보인다
        assertThat(CallTrees.toText(CallTrees.build(g, caller, CallTreeOptions.callees()), false))
                .contains("BoardVO#getTitle()").contains("BoardVO#setTitle(String)").contains("BoardVO#isOpen()").contains("BoardVO#isNotice()")
                .contains("BoardVO#getURL()").contains("BoardVO#withWriter(String)");
    }

    @Test
    @DisplayName("F-015-AC54 필드가 있는 getter라도 자신이나 재정의한 메서드에서 호출이 나가면 접지 않는다")
    void keepsAccessorsThatCall() {
        GraphFixture f = GraphFixture.create();
        f.type(VO, "class", "public");
        f.field(VO, "total", "long", "private");
        f.field(VO, "name", "java.lang.String", "private");
        String getTotal = f.method(VO, "getTotal", List.of(), "long", "public");
        String getName = f.method(VO, "getName", List.of(), "java.lang.String", "public");
        String sub = "com.v.NoticeVO";
        f.type(sub, "class", "public");
        f.typeEdge(sub, VO, io.codeatlas.core.model.EdgeKind.EXTENDS);
        String override = f.method(sub, "getName", List.of(), "java.lang.String", "public");
        f.edge(override, getName, io.codeatlas.core.model.EdgeKind.OVERRIDES);
        String helper = f.method("com.v.Texts", "trim");
        f.calls(getTotal, helper);
        f.calls(override, helper);
        String caller = f.method("com.v.BoardService", "run");
        f.calls(caller, getTotal);
        f.calls(caller, getName);
        GraphModel g = f.graph();

        assertThat(folded(g, caller)).contains("BoardVO#getTotal()").contains("BoardVO#getName()");
        FlowNavigator navigator = new FlowNavigator(g, true, true);
        assertThat(navigator.isAccessor(getTotal)).isFalse();
        assertThat(navigator.isAccessor(Ids.javaMethod("com.none.Missing", "getX"))).isFalse();
        assertThat(g.node(getName).map(Node::kind)).contains(NodeKind.JAVA_METHOD);
    }
}
