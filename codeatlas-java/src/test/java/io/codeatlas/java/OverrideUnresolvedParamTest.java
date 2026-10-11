package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-003-R17, R18: 매개변수에 classpath에 없는 타입이 있는 메서드의 OVERRIDES와 재정의 미해결 원문. */
class OverrideUnresolvedParamTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    @Test
    @DisplayName("F-003-AC29 매개변수에 classpath에 없는 타입이 있어도 프로젝트 인터페이스의 같은 표기 메서드로 OVERRIDES(RESOLVED)를 만들고 외부 상위 클래스로 추측하지 않는다")
    void overridesProjectInterfaceDespiteMissingParameterType() {
        GraphModel g = Fx.analyze(
                "src/b/Svc.java", """
                        package b;
                        import ext.lib.PaginationInfo;
                        public interface Svc {
                            String list(String q, PaginationInfo p);
                            String one(String q);
                        }
                        """,
                "src/b/SvcImpl.java", """
                        package b;
                        import ext.lib.PaginationInfo;
                        import ext.lib.AbstractBase;
                        public class SvcImpl extends AbstractBase implements Svc {
                            @Override public String list(String q, PaginationInfo p) { return q; }
                            @Override public String one(String q) { return q; }
                        }
                        """,
                "src/b/SvcImpl2.java", """
                        package b;
                        import ext.lib.PaginationInfo;
                        public class SvcImpl2 implements Svc {
                            public String list(String q, PaginationInfo p) { return q; }
                            public String one(String q) { return q; }
                        }
                        """);

        String target = m("b.Svc", "list", "java.lang.String", "ext.lib.PaginationInfo");
        String impl2 = m("b.SvcImpl2", "list", "java.lang.String", "ext.lib.PaginationInfo");
        Edge e = edge(g, impl2, EdgeKind.OVERRIDES, target);
        assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(e.location().file()).isEqualTo("src/b/SvcImpl2.java");
        assertThat(e.location().startLine()).isEqualTo(4);

        // 구현 클래스가 미해석 외부 클래스도 상속한다: 프로젝트 상위에서 찾았으므로 외부 클래스로의 추측 엣지는 없다
        String impl = m("b.SvcImpl", "list", "java.lang.String", "ext.lib.PaginationInfo");
        assertThat(targets(g, impl, EdgeKind.OVERRIDES)).containsExactly(target);
        assertThat(edge(g, impl, EdgeKind.OVERRIDES, target).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(g.node(m("ext.lib.AbstractBase", "list", "?"))).isEmpty();
        // 외부 타입 매개변수가 없는 메서드는 원래대로다
        assertThat(targets(g, m("b.SvcImpl", "one", "java.lang.String"), EdgeKind.OVERRIDES)).containsExactly(m("b.Svc", "one", "java.lang.String"));
    }

    @Test
    @DisplayName("F-003-AC29 표기 비교는 위치별이다: 제네릭 상위의 타입 인자, 오버로드, 배열 차원, static·private, 가지마다 가장 가까운 선언")
    void notationIsComparedByPosition() {
        GraphModel g = Fx.analyze(
                "src/b/Base.java", """
                        package b;
                        import ext.lib.P;
                        public interface Base<T> {
                            String find(T key, P p);
                            String find(Integer key, P p, int n);
                            String each(P[] ps);
                            String byKey(java.util.Map<String, P> ps, java.util.List<? extends P> list);
                            static String util(P p) { return null; }
                        }
                        """,
                "src/b/Impl.java", """
                        package b;
                        import ext.lib.P;
                        public class Impl implements Base<String> {
                            public String find(String key, P p) { return key; }
                            public String find(Integer key, P p) { return null; }
                            public String each(P ps) { return null; }
                            public String byKey(java.util.Map<String, P> ps, java.util.List<? extends P> list) { return null; }
                            public String util(P p) { return null; }
                        }
                        """,
                "src/b/Parent.java", """
                        package b;
                        import ext.lib.P;
                        public class Parent {
                            public void m(P p) { }
                            private void hidden(P p) { }
                        }
                        """,
                "src/b/I.java", "package b; import ext.lib.P; public interface I { void m(P p); }",
                "src/b/Mid.java", "package b; import ext.lib.P; public class Mid extends Parent implements I { public void m(P p) { } public void hidden(P p) { } }",
                "src/b/Leaf.java", "package b; import ext.lib.P; public class Leaf extends Mid { @Override public void m(P p) { } }");

        // 제네릭 상위 타입의 타입 인자(T=String)를 넣어 비교한다
        assertThat(targets(g, m("b.Impl", "find", "java.lang.String", "ext.lib.P"), EdgeKind.OVERRIDES))
                .containsExactly(m("b.Base", "find", "java.lang.Object", "ext.lib.P"));
        // 타입 인자만 classpath에 없는 매개변수(Map<String, P>)도 컴파일러는 판정하지 못한다: erasure로 비교한다
        assertThat(edge(g, m("b.Impl", "byKey", "java.util.Map", "java.util.List"), EdgeKind.OVERRIDES,
                m("b.Base", "byKey", "java.util.Map", "java.util.List")).confidence()).isEqualTo(Confidence.RESOLVED);
        // 해석된 자리의 타입이 다르거나(Integer ≠ String), 매개변수 수·배열 차원이 다르거나, 상위가 static이면 잇지 않는다
        assertThat(targets(g, m("b.Impl", "find", "java.lang.Integer", "ext.lib.P"), EdgeKind.OVERRIDES)).isEmpty();
        assertThat(targets(g, m("b.Impl", "each", "ext.lib.P"), EdgeKind.OVERRIDES)).isEmpty();
        assertThat(targets(g, m("b.Impl", "util", "ext.lib.P"), EdgeKind.OVERRIDES)).isEmpty();
        // 상위 클래스와 인터페이스 양쪽, private은 대상이 아니다
        assertThat(targets(g, m("b.Mid", "m", "ext.lib.P"), EdgeKind.OVERRIDES))
                .containsExactlyInAnyOrder(m("b.Parent", "m", "ext.lib.P"), m("b.I", "m", "ext.lib.P"));
        assertThat(targets(g, m("b.Mid", "hidden", "ext.lib.P"), EdgeKind.OVERRIDES)).isEmpty();
        // 가지마다 가장 가까운 선언만
        assertThat(targets(g, m("b.Leaf", "m", "ext.lib.P"), EdgeKind.OVERRIDES)).containsExactly(m("b.Mid", "m", "ext.lib.P"));
    }

    @Test
    @DisplayName("F-003-AC30 FQN을 복원하지 못한 자리가 있어 단순 이름으로만 비교했으면 INFERRED이고, 표기가 다르면 잇지 않으며, 프로젝트 상위에 후보가 없을 때만 @Override로 외부 상위 타입을 추론한다")
    void partialNotationIsInferred() {
        GraphModel g = Fx.analyze(
                // 와일드카드 import라 FQN을 복원할 수 없다(프로젝트 어디에도 단일 import가 없다)
                "src/b/Wild.java", "package b; import ext.lib.*; public interface Wild { void run(Q q); }",
                "src/b/WildImpl.java", "package b; public class WildImpl implements Wild { public void run(ext.lib.Q q) { } }",
                // 같은 단순 이름인데 FQN이 다르다
                "src/b/Svc.java", "package b; import ext.lib.P; public interface Svc { void run(P p); }",
                "src/c/Other.java", "package c; import other.P; public class Other implements b.Svc { public void run(P p) { } }",
                // 프로젝트 상위에 후보가 없다: 기존대로 classpath에 없는 상위 타입의 메서드로 추론한다
                "src/b/Job.java", """
                        package b;
                        import ext.lib.P;
                        import ext.lib.Tasklet;
                        public class Job implements Tasklet, Svc {
                            @Override public void execute(P p) { }
                            public void run(P p) { }
                        }
                        """);

        Edge wild = edge(g, m("b.WildImpl", "run", "ext.lib.Q"), EdgeKind.OVERRIDES, m("b.Wild", "run", "Q"));
        assertThat(wild.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(targets(g, m("c.Other", "run", "P"), EdgeKind.OVERRIDES)).isEmpty();
        Edge external = edge(g, m("b.Job", "execute", "P"), EdgeKind.OVERRIDES, m("ext.lib.Tasklet", "execute", "?"));
        assertThat(external.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(edge(g, m("b.Job", "run", "P"), EdgeKind.OVERRIDES, m("b.Svc", "run", "P")).confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    @DisplayName("F-003-AC31 재정의 미해결의 원문은 <이름>(…)이고 매개변수 수는 엣지 attrs.argCount에 남긴다")
    void unresolvedOverrideText() {
        GraphModel g = Fx.analyze(
                "src/com/a/Two.java", """
                        package com.a;
                        import org.ext.One;
                        import org.ext.Other;
                        public class Two extends One implements Other {
                            @Override public void run(String a, int b) { }
                            @Override public void stop() { }
                        }
                        """);

        Edge run = edge(g, m("com.a.Two", "run", "java.lang.String", "int"), EdgeKind.OVERRIDES, Ids.unresolved("java-override", "run(…)"));
        assertThat(run.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(run.attrs()).containsEntry("argCount", 2);
        assertThat(edge(g, m("com.a.Two", "stop"), EdgeKind.OVERRIDES, Ids.unresolved("java-override", "stop(…)")).attrs())
                .containsEntry("argCount", 0);
        assertThat(g.edges(EdgeKind.OVERRIDES)).noneMatch(e -> e.to().matches(".*\\(\\d+\\)$"));
        assertThat(g.diagnostics()).filteredOn(d -> d.code().equals("UNRESOLVED_REFERENCE") && d.message().contains("java-override")).hasSize(2);
    }
}
