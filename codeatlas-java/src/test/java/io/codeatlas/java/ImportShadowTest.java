package io.codeatlas.java;

import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codeatlas.java.Fx.hasEdge;
import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-002-R20, F-003-R23: 단일 타입 import로 선언한 이름은 그 import의 FQN이다. import한 타입이 classpath에 없으면 컴파일러가 그 이름을
 * {@code java.lang}이나 같은 패키지의 같은 이름 타입으로 풀어 버리는데, 그 바인딩을 쓰지 않는다.
 */
class ImportShadowTest {

    private static final String ACTIVITI = "org.activiti.bpmn.model.Process";
    private static final String COMMONS = "org.apache.commons.lang3.StringUtils";

    private static final String[] SOURCES = {
            // java.lang.Process와 이름이 겹치는, classpath에 없는 import
            "src/com/a/Deployer.java", """
                    package com.a;
                    import org.activiti.bpmn.model.Process;
                    public class Deployer {
                        Process current;
                        public void deploy(Process process, Process[] more) {
                            helper(process);
                            process.getId();
                            Process created = new Process();
                            created.destroy();
                        }
                        void helper(Process p) { }
                    }
                    """,
            "src/com/a/Caller.java", """
                    package com.a;
                    public class Caller {
                        void run(Deployer d) { d.deploy(null, null); }
                    }
                    """,
            // 같은 패키지의 프로젝트 타입과 이름이 겹치는, classpath에 없는 import
            "src/com/a/util/StringUtils.java", """
                    package com.a.util;
                    public class StringUtils extends org.apache.commons.lang3.StringUtils {
                        public static String abbr(String s) { return s; }
                    }
                    """,
            "src/com/a/util/Coll.java", """
                    package com.a.util;
                    import org.apache.commons.lang3.StringUtils;
                    public class Coll {
                        StringUtils holder;
                        public static String j(Iterable<?> x) { return StringUtils.join(x, ",") + StringUtils.abbr("x"); }
                        String k(StringUtils s) { return null; }
                    }
                    """,
            "src/com/a/util/Local.java", """
                    package com.a.util;
                    public class Local {
                        StringUtils mine;
                        String f() { return StringUtils.abbr("y"); }
                    }
                    """,
            // import 없이 쓰면 같은 패키지의 프로젝트 타입이 java.lang의 같은 이름을 가린다
            "src/com/b/Process.java", "package com.b; public class Process { public void start() { } }",
            "src/com/b/Runner.java", """
                    package com.b;
                    public class Runner {
                        Process p;
                        void go(Process q) { q.start(); }
                    }
                    """,
            // 단일 import는 같은 패키지의 타입도 가린다
            "src/com/b/Flow.java", """
                    package com.b;
                    import org.activiti.bpmn.model.Process;
                    public class Flow {
                        Process p;
                        void go(Process q) { q.start(); }
                    }
                    """,
            // 해석되는 import는 건드리지 않는다: 전체 이름으로 쓴 다른 타입, 프로젝트 타입의 import
            "src/com/c/Dates.java", """
                    package com.c;
                    import java.util.Date;
                    import com.b.Process;
                    public class Dates {
                        java.lang.Process real;
                        void f(java.sql.Date d, Date u, java.lang.Process real, Process mine) { mine.start(); }
                    }
                    """,
    };

    private static String type(String name) {
        return Ids.javaType(name);
    }

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    @Test
    @DisplayName("F-002-AC25 classpath에 없는 단일 import의 이름이 java.lang 타입과 겹쳐도 ASSOCIATES·DEPENDS_ON의 대상과 메서드 ID의 매개변수 타입은 import의 FQN이다")
    void javaLangNameDoesNotReplaceUnresolvedImport() {
        GraphModel g = Fx.analyze(SOURCES);

        assertThat(hasEdge(g, type("com.a.Deployer"), EdgeKind.ASSOCIATES, type(ACTIVITI))).isTrue();
        Node external = node(g, type(ACTIVITI));
        assertThat(external.declared()).isFalse();
        assertThat(external.attrBoolean("external")).isTrue();
        String deploy = m("com.a.Deployer", "deploy", ACTIVITI, ACTIVITI + "[]");
        assertThat(node(g, deploy).declared()).isTrue();
        assertThat(node(g, m("com.a.Deployer", "helper", ACTIVITI)).declared()).isTrue();
        // java.lang.Process는 그것을 전체 이름으로 쓴 Dates에만 있다
        assertThat(g.nodes()).extracting(Node::id).filteredOn(id -> !id.contains("com.c.Dates")).noneMatch(id -> id.contains("java.lang.Process"));
        // 다른 파일의 호출도 같은 ID로 간다(선언 쪽과 호출 쪽이 같은 규칙)
        assertThat(hasEdge(g, m("com.a.Caller", "run", "com.a.Deployer"), EdgeKind.CALLS, deploy)).isTrue();
    }

    @Test
    @DisplayName("F-003-AC40 그 타입의 값에 대한 호출과 생성은 import한 타입의 소스 없는 메서드(?)로 간다(java.lang의 같은 이름 메서드가 아니다)")
    void callsOnShadowedTypeGoToImportedType() {
        GraphModel g = Fx.analyze(SOURCES);

        String deploy = m("com.a.Deployer", "deploy", ACTIVITI, ACTIVITI + "[]");
        assertThat(targets(g, deploy, EdgeKind.CALLS)).containsExactlyInAnyOrder(
                m("com.a.Deployer", "helper", ACTIVITI),
                m(ACTIVITI, "getId", "?"),
                m(ACTIVITI, "<init>", "?"),
                m(ACTIVITI, "destroy", "?"));
        assertThat(targets(g, deploy, EdgeKind.INSTANTIATES)).containsExactly(type(ACTIVITI));
    }

    @Test
    @DisplayName("F-002-AC26 classpath에 없는 단일 import의 이름이 같은 패키지의 프로젝트 타입과 겹쳐도 import의 FQN이 먼저다. import가 없는 파일은 같은 패키지의 타입이다")
    void samePackageTypeDoesNotReplaceUnresolvedImport() {
        GraphModel g = Fx.analyze(SOURCES);

        String coll = type("com.a.util.Coll");
        assertThat(hasEdge(g, coll, EdgeKind.ASSOCIATES, type(COMMONS))).isTrue();
        assertThat(g.outgoing(coll)).extracting(e -> e.to()).doesNotContain(type("com.a.util.StringUtils"));
        assertThat(node(g, m("com.a.util.Coll", "k", COMMONS)).declared()).isTrue();
        // 정적 호출: 프로젝트 타입에 같은 이름의 메서드가 있어도 import한 타입의 호출이다
        assertThat(targets(g, m("com.a.util.Coll", "j", "java.lang.Iterable"), EdgeKind.CALLS))
                .containsExactlyInAnyOrder(m(COMMONS, "join", "?"), m(COMMONS, "abbr", "?"));
        // import 없이 쓴 같은 패키지의 파일은 프로젝트 타입 그대로다
        assertThat(hasEdge(g, type("com.a.util.Local"), EdgeKind.ASSOCIATES, type("com.a.util.StringUtils"))).isTrue();
        assertThat(targets(g, m("com.a.util.Local", "f"), EdgeKind.CALLS))
                .containsExactly(m("com.a.util.StringUtils", "abbr", "java.lang.String"));
    }

    @Test
    @DisplayName("F-002-AC26 import 없이 쓴 이름은 같은 패키지의 프로젝트 타입이 java.lang의 같은 이름을 가리고, 단일 import가 있으면 import가 그 둘을 가린다")
    void samePackageTypeShadowsJavaLang() {
        GraphModel g = Fx.analyze(SOURCES);

        assertThat(hasEdge(g, type("com.b.Runner"), EdgeKind.ASSOCIATES, type("com.b.Process"))).isTrue();
        String go = m("com.b.Runner", "go", "com.b.Process");
        assertThat(node(g, go).declared()).isTrue();
        assertThat(targets(g, go, EdgeKind.CALLS)).containsExactly(m("com.b.Process", "start"));

        assertThat(hasEdge(g, type("com.b.Flow"), EdgeKind.ASSOCIATES, type(ACTIVITI))).isTrue();
        assertThat(g.outgoing(type("com.b.Flow"))).extracting(e -> e.to()).doesNotContain(type("com.b.Process"));
        String flowGo = m("com.b.Flow", "go", ACTIVITI);
        assertThat(node(g, flowGo).declared()).isTrue();
        assertThat(targets(g, flowGo, EdgeKind.CALLS)).containsExactly(m(ACTIVITI, "start", "?"));
    }

    @Test
    @DisplayName("F-002-AC25 해석되는 import는 바꾸지 않는다: 전체 이름으로 쓴 java.sql.Date·java.lang.Process는 그대로고 프로젝트 타입 import는 그 타입이다")
    void resolvedImportsAreLeftAlone() {
        GraphModel g = Fx.analyze(SOURCES);

        String f = m("com.c.Dates", "f", "java.sql.Date", "java.util.Date", "java.lang.Process", "com.b.Process");
        assertThat(node(g, f).declared()).isTrue();
        assertThat(targets(g, f, EdgeKind.CALLS)).containsExactly(m("com.b.Process", "start"));
        assertThat(hasEdge(g, type("com.c.Dates"), EdgeKind.DEPENDS_ON, type("com.b.Process"))).isTrue();
    }
}
