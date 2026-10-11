package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-003-R19: classpath에 없는 외부 타입을 상속한 프로젝트 타입으로 부르는, 그 타입에 선언이 없는 메서드 호출. */
class InheritedExternalCallTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    @Test
    @DisplayName("F-003-AC32 외부 클래스를 상속한 프로젝트 클래스로 부르는 static 호출은 <외부 상위 FQN>#<이름>(?)로 INFERRED CALLS(inheritedFrom)를 만든다")
    void staticCallInheritedFromMissingSuperclass() {
        GraphModel g = Fx.analyze(
                "src/com/a/StringUtils.java", """
                        package com.a;
                        public class StringUtils extends org.apache.commons.lang3.StringUtils {
                            public static String own(String s) { return s; }
                        }
                        """,
                "src/com/a/MyDates.java", "package com.a; import ext.lib.BaseDates; public class MyDates extends BaseDates implements ext.lib.Marker { }",
                "src/com/a/OnlyMarker.java", "package com.a; public class OnlyMarker implements ext.lib.Marker { }",
                "src/com/a/U.java", """
                        package com.a;
                        public class U {
                            boolean blank(String x) {
                                return StringUtils.isBlank(x);
                            }
                            String declared(String x) { return StringUtils.own(x); }
                            String otherArity(String x) { return StringUtils.own(x, 1); }
                            Object viaImport() { return MyDates.parse("2020"); }
                            Object noSuperclass() { return OnlyMarker.make(); }
                        }
                        """);

        String blank = m("com.a.U", "blank", "java.lang.String");
        String target = m("org.apache.commons.lang3.StringUtils", "isBlank", "?");
        Edge e = edge(g, blank, EdgeKind.CALLS, target);
        assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(e.attrString("callType")).isEqualTo("static");
        assertThat(e.attrString("inheritedFrom")).isEqualTo("org.apache.commons.lang3.StringUtils");
        assertThat(e.attrs()).doesNotContainKeys("ambiguous", "receiverType");
        assertThat(e.location().startLine()).isEqualTo(4);
        assertThat(node(g, target).attrBoolean("signatureUnknown")).isTrue();
        assertThat(targets(g, blank, EdgeKind.CALLS)).containsExactly(target);

        // 프로젝트 타입에 선언한 메서드는 그대로 그 메서드로 잇는다
        assertThat(targets(g, m("com.a.U", "declared", "java.lang.String"), EdgeKind.CALLS))
                .containsExactly(m("com.a.StringUtils", "own", "java.lang.String"));
        // 같은 이름이지만 인자 수가 맞는 선언이 프로젝트 타입에 없다: 외부 상위 클래스의 오버로드로 본다
        assertThat(edge(g, m("com.a.U", "otherArity", "java.lang.String"), EdgeKind.CALLS, m("org.apache.commons.lang3.StringUtils", "own", "?"))
                .confidence()).isEqualTo(Confidence.INFERRED);
        // static 메서드는 인터페이스에서 물려받지 않는다: 외부 인터페이스가 함께 있어도 상위 클래스의 것이고 모호하지 않다
        Edge parse = edge(g, m("com.a.U", "viaImport"), EdgeKind.CALLS, m("ext.lib.BaseDates", "parse", "?"));
        assertThat(parse.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(parse.attrs()).doesNotContainKey("ambiguous");
        // 외부 상위 클래스가 없으면 미해결 그대로다
        assertThat(edge(g, m("com.a.U", "noSuperclass"), EdgeKind.CALLS, Ids.unresolved("java-call", "OnlyMarker.make(…)")).confidence())
                .isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    @DisplayName("F-003-AC33 인스턴스 호출도 같은 규칙이고(receiverType), 외부 상위가 클래스와 인터페이스로 여럿이면 상위 클래스 쪽으로 INFERRED다(후보가 하나뿐이라 ambiguous는 붙이지 않는다, F-003-R29)")
    void instanceCallInheritedFromMissingSupertype() {
        GraphModel g = Fx.analyze(
                "src/com/a/Dao.java", "package com.a; import org.ext.BaseDao; public class Dao extends BaseDao { void own() { } }",
                "src/com/a/SubDao.java", "package com.a; public class SubDao extends Dao { }",
                "src/com/a/Multi.java", "package com.a; import org.ext.One; import org.ext.Other; public class Multi extends One implements Other { }",
                "src/com/a/Faces.java", "package com.a; public class Faces implements org.ext.A, org.ext.B { }",
                "src/com/a/Svc.java", """
                        package com.a;
                        public class Svc {
                            Dao dao;
                            SubDao sub;
                            Multi multi;
                            Faces faces;
                            void direct() { dao.save("x"); }
                            void indirect() { sub.save("y"); }
                            void ambiguous() { multi.go(); }
                            void interfacesOnly() { faces.go(); }
                            void declared() { dao.own(); }
                        }
                        """);

        Edge direct = edge(g, m("com.a.Svc", "direct"), EdgeKind.CALLS, m("org.ext.BaseDao", "save", "?"));
        assertThat(direct.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(direct.attrString("callType")).isEqualTo("virtual");
        assertThat(direct.attrString("inheritedFrom")).isEqualTo("org.ext.BaseDao");
        assertThat(direct.attrString("receiverType")).isEqualTo("com.a.Dao");
        assertThat(direct.attrs()).doesNotContainKey("ambiguous");

        Edge indirect = edge(g, m("com.a.Svc", "indirect"), EdgeKind.CALLS, m("org.ext.BaseDao", "save", "?"));
        assertThat(indirect.attrString("receiverType")).isEqualTo("com.a.SubDao");

        Edge ambiguous = edge(g, m("com.a.Svc", "ambiguous"), EdgeKind.CALLS, m("org.ext.One", "go", "?"));
        assertThat(ambiguous.confidence()).isEqualTo(Confidence.INFERRED);
        // 2026-10-11 고쳐 씀(F-003-R29): 전에는 ambiguous=true였다. 잇는 대상이 하나뿐인 엣지에는 붙이지 않는다
        assertThat(ambiguous.attrs()).doesNotContainKey("ambiguous");
        assertThat(ambiguous.attrString("inheritedFrom")).isEqualTo("org.ext.One");

        // 외부 인터페이스만 둘이면 어느 쪽인지 알 수 없다
        assertThat(edge(g, m("com.a.Svc", "interfacesOnly"), EdgeKind.CALLS, Ids.unresolved("java-call", "faces.go(…)")).confidence())
                .isEqualTo(Confidence.UNRESOLVED);
        assertThat(targets(g, m("com.a.Svc", "declared"), EdgeKind.CALLS)).containsExactly(m("com.a.Dao", "own"));
        assertThat(g.diagnostics()).filteredOn(d -> d.code().equals("UNRESOLVED_REFERENCE") && d.message().contains("java-call")).hasSize(1);
    }

    @Test
    @DisplayName("F-003-AC33 상위 타입이 classpath에 있으면 컴파일러의 판정을 믿는다: 없는 메서드 호출은 미해결이다")
    void resolvedSuperclassIsNotGuessed() {
        GraphModel g = Fx.analyzeWithStubs(
                "src/com/a/Mine.java", "package com.a; import lib.util.LibBase; public class Mine extends LibBase { }",
                "src/com/a/Use.java", """
                        package com.a;
                        public class Use {
                            Mine mine;
                            void run() { mine.nothing(); Mine.none(); mine.libMethod(); }
                        }
                        """);

        String run = m("com.a.Use", "run");
        assertThat(targets(g, run, EdgeKind.CALLS)).containsExactlyInAnyOrder(
                Ids.unresolved("java-call", "mine.nothing(…)"), Ids.unresolved("java-call", "Mine.none(…)"), m("lib.util.LibBase", "libMethod"));
    }
}
