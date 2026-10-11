package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-003-R22: Lombok 합성 메서드가 낀 재정의의 OVERRIDES. */
class LombokOverridesTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    @Test
    @DisplayName("F-003-AC38 인터페이스·상위 클래스의 메서드를 구현한 Lombok 합성 getter·setter에서 그 선언으로 OVERRIDES(RESOLVED, lombok=true)가 있다")
    void syntheticAccessorImplementsDeclaredMethod() {
        LombokFx.eachClasspath(Fx.files(
                "src/d/HasId.java", """
                        package d;
                        public interface HasId {
                            Long getId();
                            void setId(Long id);
                            String label();
                            static String getKind() { return "k"; }
                        }
                        """,
                "src/d/Identifiable.java", """
                        package d;
                        public interface Identifiable<K, V extends Number> {
                            K getKey();
                            void setKey(K key);
                            void setRank(V rank);
                        }
                        """,
                "src/d/AbstractNamed.java", """
                        package d;
                        public abstract class AbstractNamed {
                            public abstract String getName();
                            public void setName(Integer wrongType) { }
                        }
                        """,
                "src/d/Mid.java", "package d; public abstract class Mid extends AbstractNamed implements Identifiable<String, Integer> { }",
                "src/d/Order.java", """
                        package d;
                        import lombok.Getter;
                        import lombok.Setter;
                        @Getter
                        @Setter
                        public class Order extends Mid implements HasId {
                            private Long id;
                            private String name;
                            private String key;
                            private Integer rank;
                            private String kind;
                            public String label() { return name; }
                        }
                        """), (cp, g) -> {
            String getId = m("d.Order", "getId");
            Edge e = edge(g, getId, EdgeKind.OVERRIDES, m("d.HasId", "getId"));
            assertThat(e.confidence()).as(cp).isEqualTo(Confidence.RESOLVED);
            assertThat(e.attrs()).as(cp).containsEntry("lombok", true);
            assertThat(e.location().file()).as(cp).isEqualTo("src/d/Order.java");
            assertThat(e.location().startLine()).as(cp).isEqualTo(7);
            assertThat(targets(g, m("d.Order", "setId", "java.lang.Long"), EdgeKind.OVERRIDES)).as(cp)
                    .containsExactly(m("d.HasId", "setId", "java.lang.Long"));
            // 상위 클래스 사슬을 건너 가장 가까운 선언으로
            assertThat(targets(g, m("d.Order", "getName"), EdgeKind.OVERRIDES)).as(cp).containsExactly(m("d.AbstractNamed", "getName"));
            // 매개변수 타입이 다르면 재정의가 아니다
            assertThat(targets(g, m("d.Order", "setName", "java.lang.String"), EdgeKind.OVERRIDES)).as(cp).isEmpty();
            // 제네릭 상위 타입은 타입 인자를 넣어 비교한다
            assertThat(targets(g, m("d.Order", "getKey"), EdgeKind.OVERRIDES)).as(cp).containsExactly(m("d.Identifiable", "getKey"));
            assertThat(targets(g, m("d.Order", "setKey", "java.lang.String"), EdgeKind.OVERRIDES)).as(cp)
                    .containsExactly(m("d.Identifiable", "setKey", "java.lang.Object"));
            assertThat(targets(g, m("d.Order", "setRank", "java.lang.Integer"), EdgeKind.OVERRIDES)).as(cp)
                    .containsExactly(m("d.Identifiable", "setRank", "java.lang.Number"));
            // static 메서드는 재정의 대상이 아니다
            assertThat(targets(g, m("d.Order", "getKind"), EdgeKind.OVERRIDES)).as(cp).isEmpty();
            // 소스에 선언한 메서드의 재정의는 컴파일러가 판정한 그대로다(lombok 표시 없음)
            assertThat(edge(g, m("d.Order", "label"), EdgeKind.OVERRIDES, m("d.HasId", "label")).attrs()).as(cp).doesNotContainKey("lombok");
        });
    }

    @Test
    @DisplayName("F-003-AC39 상위 타입의 Lombok 합성 메서드를 하위 타입이 선언하거나 합성 메서드로 재정의하면 OVERRIDES(lombok=true)가 있고 private 합성 메서드로는 없다")
    void declaredOrSyntheticOverridesSyntheticOfSupertype() {
        LombokFx.eachClasspath(Fx.files(
                "src/d/Base.java", """
                        package d;
                        import lombok.AccessLevel;
                        import lombok.Data;
                        import lombok.Getter;
                        @Data
                        public class Base {
                            protected String name;
                            protected int count;
                            @Getter(AccessLevel.PRIVATE) protected String secret;
                        }
                        """,
                "src/d/Middle.java", "package d; public class Middle extends Base { }",
                "src/d/Sub.java", """
                        package d;
                        public class Sub extends Middle {
                            @Override public String getName() { return "sub"; }
                            public void setCount(long other) { }
                            public String getSecret() { return null; }
                            @Override public String toString() { return "s"; }
                        }
                        """,
                "src/d/Leaf.java", """
                        package d;
                        import lombok.Getter;
                        public class Leaf extends Sub {
                            @Getter private String name;
                        }
                        """), (cp, g) -> {
            Edge e = edge(g, m("d.Sub", "getName"), EdgeKind.OVERRIDES, m("d.Base", "getName"));
            assertThat(e.confidence()).as(cp).isEqualTo(Confidence.RESOLVED);
            assertThat(e.attrs()).as(cp).containsEntry("lombok", true);
            assertThat(e.location().startLine()).as(cp).isEqualTo(3);
            assertThat(targets(g, m("d.Sub", "toString"), EdgeKind.OVERRIDES)).as(cp).containsExactly(m("d.Base", "toString"));
            assertThat(targets(g, m("d.Sub", "setCount", "long"), EdgeKind.OVERRIDES)).as(cp).isEmpty();
            assertThat(targets(g, m("d.Sub", "getSecret"), EdgeKind.OVERRIDES)).as(cp).isEmpty();
            // 합성 메서드가 가장 가까운 선언(Sub의 선언 메서드)을 재정의한다
            assertThat(targets(g, m("d.Leaf", "getName"), EdgeKind.OVERRIDES)).as(cp).containsExactly(m("d.Sub", "getName"));
            assertThat(edge(g, m("d.Leaf", "getName"), EdgeKind.OVERRIDES, m("d.Sub", "getName")).attrs()).as(cp).containsEntry("lombok", true);
        });
    }
}
