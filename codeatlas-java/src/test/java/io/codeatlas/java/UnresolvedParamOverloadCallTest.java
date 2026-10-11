package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-003-R21: 매개변수 타입이 classpath에 없는 선언을 컴파일러가 "해석된 타입의 인자"를 넘긴 호출의 대상으로 골랐을 때,
 * 그 선언을 믿지 않고 인자 타입과 맞는 다른 오버로드(상위 타입 포함)를 찾는다.
 */
class UnresolvedParamOverloadCallTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    private static final String BASE = """
            package c;
            import org.ext.ui.Model;
            public class Base {
                protected String thymeleaf(String path) { return path; }
                protected String go(String path, Model model) { return path; }
                protected String both(Object o) { return null; }
                protected String both(CharSequence s) { return null; }
                protected String exact(Object o) { return null; }
                protected String exact(String s) { return s; }
            }
            """;

    @Test
    @DisplayName("F-003-AC36 미해석 타입 매개변수의 자기 메서드 안에서 문자열을 넘겨 부른 상위 오버로드는 자기 자신이 아니라 상위 선언으로 RESOLVED다")
    void superOverloadIsNotSelfCall() {
        GraphModel g = Fx.analyze(
                "src/c/Base.java", BASE,
                "src/c/IndexController.java", """
                        package c;
                        import org.ext.ui.Model;
                        public class IndexController extends Base {
                            public String thymeleaf(Model model) {
                                return thymeleaf("/index");
                            }
                            public String exact(Model model) {
                                return exact("x");
                            }
                        }
                        """);

        String self = m("c.IndexController", "thymeleaf", "org.ext.ui.Model");
        String target = m("c.Base", "thymeleaf", "java.lang.String");
        assertThat(targets(g, self, EdgeKind.CALLS)).containsExactly(target);
        Edge e = edge(g, self, EdgeKind.CALLS, target);
        assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(e.attrString("callType")).isEqualTo("virtual");
        assertThat(e.location().startLine()).isEqualTo(5);
        // 후보가 둘(Object, String)이어도 인자 타입과 정확히 같은 선언이 하나면 그것이다
        assertThat(targets(g, m("c.IndexController", "exact", "org.ext.ui.Model"), EdgeKind.CALLS))
                .containsExactly(m("c.Base", "exact", "java.lang.String"));
        assertThat(Fx.diagnosticCodes(g)).doesNotContain("UNRESOLVED_REFERENCE");
    }

    @Test
    @DisplayName("F-003-AC36 맞는 후보가 없거나 여럿이면 미해결이고, 미해석 인자가 섞인 후보 하나는 INFERRED다")
    void noneOrManyIsUnresolvedAndMixedIsInferred() {
        GraphModel g = Fx.analyze(
                "src/c/Base.java", BASE,
                "src/c/Jump.java", """
                        package c;
                        import org.ext.Task;
                        import org.ext.Act;
                        public class Jump {
                            public void jump(Task task, String key) {
                                Act act = null;
                                jump(task, act);
                            }
                            private void jump(Task task, Act act) { }
                        }
                        """,
                "src/c/Sub.java", """
                        package c;
                        import org.ext.ui.Model;
                        public class Sub extends Base {
                            public String only(Model model) {
                                return only("none");
                            }
                            public String both(Model model) {
                                return both("many");
                            }
                            public String go(Model a, Model b) {
                                return go("/x", b);
                            }
                        }
                        """);

        // 반대 방향: final 클래스(String) 매개변수 자리에 미해석 타입의 값이 온 선언도 믿지 않는다
        String jump = m("c.Jump", "jump", "org.ext.Task", "java.lang.String");
        String jumpAct = m("c.Jump", "jump", "org.ext.Task", "org.ext.Act");
        assertThat(targets(g, jump, EdgeKind.CALLS)).containsExactly(jumpAct);
        assertThat(edge(g, jump, EdgeKind.CALLS, jumpAct).confidence()).isEqualTo(Confidence.INFERRED);

        String only = m("c.Sub", "only", "org.ext.ui.Model");
        assertThat(targets(g, only, EdgeKind.CALLS)).containsExactly("unresolved:java-call:only(…)");
        assertThat(edge(g, only, EdgeKind.CALLS, "unresolved:java-call:only(…)").attrs()).containsEntry("argCount", 1);
        // Object와 CharSequence 둘 다 문자열을 받는다: 고르지 않는다
        assertThat(targets(g, m("c.Sub", "both", "org.ext.ui.Model"), EdgeKind.CALLS)).containsExactly("unresolved:java-call:both(…)");

        String go = m("c.Sub", "go", "org.ext.ui.Model", "org.ext.ui.Model");
        String baseGo = m("c.Base", "go", "java.lang.String", "org.ext.ui.Model");
        assertThat(targets(g, go, EdgeKind.CALLS)).containsExactly(baseGo);
        assertThat(edge(g, go, EdgeKind.CALLS, baseGo).confidence()).isEqualTo(Confidence.INFERRED);
    }

    @Test
    @DisplayName("F-003-AC37 인자도 미해석이거나 미해석 타입을 상속한 프로젝트 타입이면 컴파일러가 고른 선언을 그대로 믿는다")
    void unresolvedOrPossiblySubtypeArgumentKeepsCompilerChoice() {
        GraphModel g = Fx.analyze(
                "src/c/Base.java", BASE,
                "src/c/MyModel.java", """
                        package c;
                        import org.ext.ui.Model;
                        public class MyModel implements Model {
                        }
                        """,
                "src/c/Rec.java", """
                        package c;
                        import org.ext.ui.Model;
                        public class Rec extends Base {
                            public String thymeleaf(Model model) {
                                if (model == null) {
                                    return thymeleaf(new MyModel());
                                }
                                return thymeleaf(model);
                            }
                        }
                        """,
                "src/c/Shadow.java", """
                        package c;
                        import org.ext.model.Process;
                        import org.ext.model.Container;
                        public class Shadow {
                            void each(java.util.List<Process> all) {
                                for (Process process : all) {
                                    post(process, 1);
                                }
                            }
                            private void post(Container parent, int depth) { }
                        }
                        """,
                "src/c/Caller.java", """
                        package c;
                        public class Caller {
                            String run(IndexLike c) {
                                return c.thymeleaf("/index");
                            }
                        }
                        """,
                "src/c/IndexLike.java", """
                        package c;
                        import org.ext.ui.Model;
                        public class IndexLike extends Base {
                            public String thymeleaf(Model model) { return null; }
                        }
                        """);

        String self = m("c.Rec", "thymeleaf", "org.ext.ui.Model");
        List<Edge> recursion = g.edges(self, EdgeKind.CALLS, self);
        assertThat(recursion).extracting(e -> e.location().startLine()).containsExactlyInAnyOrder(6, 8);
        assertThat(recursion).allSatisfy(e -> assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED));
        // import한 미해석 타입과 이름이 같은 java.lang 타입으로 컴파일러가 풀어 버린 인자(Process)는 해석된 타입으로 치지 않는다
        assertThat(targets(g, m("c.Shadow", "each", "java.util.List"), EdgeKind.CALLS))
                .containsExactly(m("c.Shadow", "post", "org.ext.model.Container", "int"));
        // 수신자를 적은 호출도 같다: 하위 타입 변수로 부른 상위 오버로드
        String run = m("c.Caller", "run", "c.IndexLike");
        String target = m("c.Base", "thymeleaf", "java.lang.String");
        assertThat(targets(g, run, EdgeKind.CALLS)).containsExactly(target);
        assertThat(edge(g, run, EdgeKind.CALLS, target).attrString("receiverType")).isEqualTo("c.IndexLike");
    }
}
