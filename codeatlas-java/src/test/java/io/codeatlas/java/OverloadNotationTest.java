package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-003-R24: 인자에 classpath에 없는 타입이 있어 컴파일러가 오버로드를 가리지 못하고 먼저 선언된 것을 고른 호출.
 * 인자와 매개변수의 타입 표기를 위치별로 비교해 맞는 후보를 고르고, 가리지 못하면 후보 전부로 {@code ambiguous=true}로 잇는다.
 */
class OverloadNotationTest {

    private static final String MODEL = "org.springframework.ui.Model";
    private static final String REDIRECT = "org.springframework.web.servlet.mvc.support.RedirectAttributes";
    private static final String BASE = "com.a.web.Base";

    private static final String[] SOURCES = {
            "src/com/a/web/Base.java", """
                    package com.a.web;
                    import java.util.List;
                    import java.util.Map;
                    import org.springframework.ui.Model;
                    import org.springframework.web.multipart.MultipartFile;
                    import org.springframework.web.servlet.mvc.support.RedirectAttributes;
                    public class Base {
                        protected void addMessage(Model model, String... messages) { }
                        protected void addMessage(RedirectAttributes redirectAttributes, String... messages) { }
                        protected List<String> parseFileInf(Map<String, MultipartFile> files, String key) { return null; }
                        protected List<String> parseFileInf(List<MultipartFile> files, String key) { return null; }
                        protected void log(Model model) { }
                        protected void log(RedirectAttributes attributes) { }
                        protected void only(Model model) { }
                    }
                    """,
            "src/com/a/web/Builder.java", """
                    package com.a.web;
                    import java.io.InputStream;
                    import java.io.Reader;
                    public class Builder {
                        public Builder(Reader reader, String name) { }
                        public Builder(InputStream stream, String name) { }
                    }
                    """,
            "src/com/a/web/Ctl.java", """
                    package com.a.web;
                    import java.util.List;
                    import org.springframework.ui.Model;
                    import org.springframework.web.multipart.MultipartFile;
                    import org.springframework.web.servlet.mvc.support.RedirectAttributes;
                    import other.Thing;
                    public class Ctl extends Base {
                        public String save(RedirectAttributes redirectAttributes, List<MultipartFile> files, Thing thing) {
                            addMessage(redirectAttributes, "ok");
                            parseFileInf(files, "k");
                            return null;
                        }
                        public String more(RedirectAttributes redirectAttributes, Model model) {
                            addMessage(redirectAttributes, "a", "b");
                            addMessage(model);
                            only(model);
                            return null;
                        }
                        public void unknown(Thing thing) {
                            log(thing.attributes());
                            new Builder(thing.stream(), "x");
                        }
                    }
                    """,
    };

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    private static List<Edge> calls(GraphModel g, String from, String namePart) {
        return g.outgoing(from, EdgeKind.CALLS).stream().filter(e -> e.to().contains(namePart)).toList();
    }

    @Test
    @DisplayName("F-003-AC41 두 오버로드의 매개변수 타입이 모두 classpath에 없어도 인자의 타입 표기와 같은 쪽을 고른다(먼저 선언된 쪽이 아니다). INFERRED")
    void picksOverloadByArgumentNotation() {
        GraphModel g = Fx.analyze(SOURCES);

        String save = m("com.a.web.Ctl", "save", REDIRECT, "java.util.List", "other.Thing");
        List<Edge> addMessage = calls(g, save, "#addMessage(");
        assertThat(addMessage).extracting(Edge::to).containsExactly(m(BASE, "addMessage", REDIRECT, "java.lang.String[]"));
        assertThat(addMessage.get(0).confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(addMessage.get(0).attrs()).doesNotContainKey("ambiguous");
        // 제네릭은 원시 타입으로 비교한다: List<MultipartFile> 인자는 (List, String)이지 (Map, String)이 아니다
        assertThat(calls(g, save, "#parseFileInf(")).extracting(Edge::to)
                .containsExactly(m(BASE, "parseFileInf", "java.util.List", "java.lang.String"));
    }

    @Test
    @DisplayName("F-003-AC41 가변 인자에 값이 여럿이거나 없어도 같은 규칙으로 고르고, 후보가 하나뿐인 호출은 전과 같다")
    void varargsAndSingleCandidate() {
        GraphModel g = Fx.analyze(SOURCES);

        String more = m("com.a.web.Ctl", "more", REDIRECT, MODEL);
        assertThat(calls(g, more, "#addMessage(")).extracting(Edge::to).containsExactlyInAnyOrder(
                m(BASE, "addMessage", REDIRECT, "java.lang.String[]"), m(BASE, "addMessage", MODEL, "java.lang.String[]"));
        assertThat(calls(g, more, "#addMessage(")).noneMatch(e -> e.attrBoolean("ambiguous"));
        Edge only = calls(g, more, "#only(").get(0);
        assertThat(only.to()).isEqualTo(m(BASE, "only", MODEL));
        assertThat(only.attrs()).doesNotContainKey("ambiguous");
    }

    @Test
    @DisplayName("F-003-AC42 인자의 타입을 알 수 없어 가리지 못하면 한 후보를 고르지 않고 후보 전부로 INFERRED + ambiguous=true로 잇는다(생성자 포함)")
    void undecidableCallGoesToAllCandidates() {
        GraphModel g = Fx.analyze(SOURCES);

        String unknown = m("com.a.web.Ctl", "unknown", "other.Thing");
        List<Edge> log = calls(g, unknown, "#log(");
        assertThat(log).extracting(Edge::to).containsExactlyInAnyOrder(m(BASE, "log", MODEL), m(BASE, "log", REDIRECT));
        assertThat(log).allMatch(e -> e.confidence() == Confidence.INFERRED && e.attrBoolean("ambiguous"));
        List<Edge> constructors = calls(g, unknown, "Builder#<init>(");
        assertThat(constructors).extracting(Edge::to).containsExactlyInAnyOrder(
                m("com.a.web.Builder", "<init>", "java.io.Reader", "java.lang.String"),
                m("com.a.web.Builder", "<init>", "java.io.InputStream", "java.lang.String"));
        assertThat(constructors).allMatch(e -> e.confidence() == Confidence.INFERRED && e.attrBoolean("ambiguous")
                && "constructor".equals(e.attrString("callType")));
        assertThat(g.outgoing(unknown, EdgeKind.INSTANTIATES)).extracting(Edge::to).containsExactly(Ids.javaType("com.a.web.Builder"));
    }

    @Test
    @DisplayName("F-003-AC42 컴파일러가 인자 불일치로 본 호출도 불일치의 원인이 classpath에 없는 타입이면(X.class 등) 타입 표기로 맞는 후보가 하나일 때 그것으로 INFERRED로 잇는다")
    void mismatchCausedByUnresolvedTypeLiteral() {
        GraphModel g = Fx.analyze(
                "src/com/a/Holder.java", """
                        package com.a;
                        public class Holder {
                            public static <T> T getBean(String name) { return null; }
                            public static <T> T getBean(Class<T> requiredType) { return null; }
                        }
                        """,
                "src/com/a/Uses.java", """
                        package com.a;
                        import redis.clients.jedis.JedisPool;
                        public class Uses {
                            static JedisPool pool = Holder.getBean(JedisPool.class);
                            void f() { Object o = Holder.getBean(JedisPool.class); }
                        }
                        """);

        List<Edge> calls = calls(g, m("com.a.Uses", "f"), "#getBean(");
        assertThat(calls).extracting(Edge::to).containsExactly(m("com.a.Holder", "getBean", "java.lang.Class"));
        assertThat(calls.get(0).confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(g.outgoing(m("com.a.Uses", "<clinit>"), EdgeKind.CALLS)).extracting(Edge::to)
                .containsExactly(m("com.a.Holder", "getBean", "java.lang.Class"));
        assertThat(g.nodes(io.codeatlas.core.model.NodeKind.UNRESOLVED)).isEmpty();
    }
}
