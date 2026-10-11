package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-003-R28: 컴파일러가 타입을 주지 않는 인자 식(Lombok 합성 getter 호출, 안쪽 인자를 모르는 프로젝트 메서드 호출, 문자열 사슬 등)의
 * 타입을 읽어 오버로드를 가린다. 타입으로 가릴 수 있는 호출이 {@code ambiguous} 묶음으로 격하되지 않아야 한다.
 * F-003-R29: 후보가 하나뿐인 호출에는 {@code ambiguous}를 붙이지 않는다.
 */
class OverloadArgumentTypeTest {

    private static final String SCRTY = "a.Scrty";

    private static final String[] SOURCES = {
            "src/a/Scrty.java", """
                    package a;
                    public class Scrty {
                        public static String enc(String data) { return data; }
                        public static String enc(String password, String id) { return password + id; }
                        public static String enc(String data, byte[] salt) { return data; }
                        public static String get(String key) { return key; }
                        public static byte[] get(byte[] key) { return key; }
                        public static void set(String key, String value, int seconds) { }
                        public static void set(byte[] key, byte[] value, int seconds) { }
                        public static void put(String key, Object value) { }
                        public static void put(byte[] key, Object value) { }
                    }
                    """,
            "src/a/LombokVO.java", """
                    package a;
                    import lombok.Getter;
                    import lombok.Setter;
                    @Getter
                    @Setter
                    public class LombokVO {
                        private String id;
                        private String password;
                        private byte[] salt;
                        private boolean active;
                    }
                    """,
            "src/a/StrUtil.java", """
                    package a;
                    public class StrUtil {
                        public static String nts(Object o) { return o == null ? "" : o.toString(); }
                        public static String ser(Object o) { return ""; }
                        public static String ser(Object o, int depth) { return ""; }
                        public static byte[] bytes(Object o) { return null; }
                        public static String mixed(Object o) { return ""; }
                        public static byte[] mixed(String s) { return null; }
                    }
                    """,
            "src/a/Caller.java", """
                    package a;
                    import other.Session;
                    public class Caller {
                        private static final String PREFIX = "p";
                        public String viaLombok(LombokVO vo) { return Scrty.enc(vo.getPassword(), vo.getId()); }
                        public String viaLombokLiteral(LombokVO vo) { return Scrty.enc("", vo.getId()); }
                        public String viaLombokBytes(LombokVO vo) { return Scrty.enc("", vo.getSalt()); }
                        public String viaProjectMethod(LombokVO vo) { return Scrty.enc(vo.getPassword(), StrUtil.nts(vo.getId())); }
                        public void sameReturnOverloads(Session session) { Scrty.put(StrUtil.ser(session.attr()), session); }
                        public void arrayReturn(Session session) { Scrty.put(StrUtil.bytes(session.attr()), session); }
                        public void mixedReturn(Session session) { Scrty.put(StrUtil.mixed(session.attr()), session); }
                        public String viaConcat(Session session) { return Scrty.get(PREFIX + "_" + session.getId()); }
                        public void viaConcatSet(Session session) {
                            Scrty.set(PREFIX + "_" + session.getId(), StrUtil.ser(session), (int) session.getTimeout() / 1000);
                        }
                        public void viaTernary(Session session, boolean flag) { Scrty.put(flag ? "a" : PREFIX + session.getId(), session); }
                        public void viaArrayCreation(Session session) { Scrty.put(new byte[session.size()], session); }
                        public void viaCast(Session session) { Scrty.put((String) session.attr(), session); }
                        public void viaNull(Session session) { Scrty.put(null, session); }
                        public void unknown(Session session) { Scrty.put(session.attr(), session); }
                    }
                    """,
    };

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    private static List<Edge> calls(GraphModel g, String from, String namePart) {
        return g.outgoing(from, EdgeKind.CALLS).stream().filter(e -> e.to().contains(namePart)).toList();
    }

    private static void assertSingle(GraphModel g, String from, String namePart, String expected) {
        List<Edge> found = calls(g, from, namePart);
        assertThat(found).as(from + " → " + namePart).extracting(Edge::to).containsExactly(expected);
        assertThat(found.get(0).attrs()).as(from + " → " + namePart).doesNotContainKey("ambiguous");
        assertThat(found.get(0).confidence()).isNotEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    @DisplayName("F-003-AC47 Lombok 합성 getter 호출을 인자로 넘기면 그 필드의 선언 타입으로 오버로드를 가린다(String 필드는 (String,String), byte[] 필드는 (String,byte[]))")
    void lombokGetterArgument() {
        GraphModel g = Fx.analyze(SOURCES);

        String stringString = m(SCRTY, "enc", "java.lang.String", "java.lang.String");
        assertSingle(g, m("a.Caller", "viaLombok", "a.LombokVO"), "Scrty#enc(", stringString);
        assertSingle(g, m("a.Caller", "viaLombokLiteral", "a.LombokVO"), "Scrty#enc(", stringString);
        assertSingle(g, m("a.Caller", "viaLombokBytes", "a.LombokVO"), "Scrty#enc(", m(SCRTY, "enc", "java.lang.String", "byte[]"));
        // 합성 getter 자체의 호출도 그대로 있다
        assertThat(calls(g, m("a.Caller", "viaLombok", "a.LombokVO"), "LombokVO#get")).hasSize(2);
    }

    @Test
    @DisplayName("F-003-AC47 호출하는 파일이 Lombok 타입의 파일보다 먼저 분석돼도 같다(합성 멤버 색인은 모든 파일을 본 뒤에 쓴다)")
    void lombokGetterArgumentRegardlessOfFileOrder() {
        // 파일 이름 순서를 뒤집는다: 호출하는 쪽(Aaa)이 먼저, Lombok 타입(Zzz)이 나중
        GraphModel g = Fx.analyze(
                "src/a/Scrty.java", SOURCES[1],
                "src/a/Aaa.java", """
                        package a;
                        public class Aaa {
                            public String f(Zzz vo) { return Scrty.enc("", vo.getId()); }
                        }
                        """,
                "src/a/Zzz.java", """
                        package a;
                        @lombok.Data
                        public class Zzz {
                            private String id;
                        }
                        """);

        assertSingle(g, m("a.Aaa", "f", "a.Zzz"), "Scrty#enc(", m(SCRTY, "enc", "java.lang.String", "java.lang.String"));
    }

    @Test
    @DisplayName("F-003-AC48 프로젝트 메서드 호출식은 안쪽 인자를 몰라도 그 메서드의 선언 반환 타입으로 본다(이름·인자 수로 유일하거나 모든 오버로드의 반환 타입이 같을 때만)")
    void projectMethodCallArgument() {
        GraphModel g = Fx.analyze(SOURCES);

        assertSingle(g, m("a.Caller", "viaProjectMethod", "a.LombokVO"), "Scrty#enc(",
                m(SCRTY, "enc", "java.lang.String", "java.lang.String"));
        String putString = m(SCRTY, "put", "java.lang.String", "java.lang.Object");
        String putBytes = m(SCRTY, "put", "byte[]", "java.lang.Object");
        assertSingle(g, m("a.Caller", "sameReturnOverloads", "other.Session"), "Scrty#put(", putString);
        assertSingle(g, m("a.Caller", "arrayReturn", "other.Session"), "Scrty#put(", putBytes);
        // 오버로드마다 반환 타입이 다르면(String, byte[]) 모른다: 후보 전부로 ambiguous
        List<Edge> mixed = calls(g, m("a.Caller", "mixedReturn", "other.Session"), "Scrty#put(");
        assertThat(mixed).extracting(Edge::to).containsExactlyInAnyOrder(putString, putBytes);
        assertThat(mixed).allMatch(e -> e.confidence() == Confidence.INFERRED && e.attrBoolean("ambiguous"));
    }

    @Test
    @DisplayName("F-003-AC49 문자열 리터럴이 낀 + 사슬은 String이다: CONST + \"_\" + ext.call()을 넘긴 get·set은 String 오버로드로만 간다")
    void stringConcatenationArgument() {
        GraphModel g = Fx.analyze(SOURCES);

        assertSingle(g, m("a.Caller", "viaConcat", "other.Session"), "Scrty#get(", m(SCRTY, "get", "java.lang.String"));
        assertSingle(g, m("a.Caller", "viaConcatSet", "other.Session"), "Scrty#set(",
                m(SCRTY, "set", "java.lang.String", "java.lang.String", "int"));
    }

    @Test
    @DisplayName("F-003-AC50 인자의 선언 타입 표기가 한 후보의 매개변수 타입 표기와 정확히 같으면 그 후보다(외부 타입 MultipartFile vs File, catch 변수의 예외 타입 vs Set)")
    void exactNotationWins() {
        GraphModel g = Fx.analyze(
                "src/a/ImportExcel.java", """
                        package a;
                        import java.io.File;
                        import org.springframework.web.multipart.MultipartFile;
                        public class ImportExcel {
                            public ImportExcel(File file, int headerNum, int sheetIndex) { }
                            public ImportExcel(MultipartFile multipartFile, int headerNum, int sheetIndex) { }
                        }
                        """,
                "src/a/Validators.java", """
                        package a;
                        import java.util.List;
                        import java.util.Set;
                        import javax.validation.ConstraintViolation;
                        import javax.validation.ConstraintViolationException;
                        public class Validators {
                            public static List<String> extract(Set<? extends ConstraintViolation> violations, String separator) { return null; }
                            public static List<String> extract(ConstraintViolationException e, String separator) {
                                return extract(e.getConstraintViolations(), separator);
                            }
                            public static List<String> viaSet(Set<? extends ConstraintViolation> violations) { return extract(violations, " "); }
                        }
                        """,
                "src/a/Ctl.java", """
                        package a;
                        import javax.validation.ConstraintViolationException;
                        import org.springframework.web.multipart.MultipartFile;
                        public class Ctl {
                            public void importFile(MultipartFile file) { new ImportExcel(file, 1, 0); }
                            public void handle() {
                                try { importFile(null); } catch (ConstraintViolationException ex) { Validators.extract(ex, ": "); }
                            }
                        }
                        """);

        String multipart = "org.springframework.web.multipart.MultipartFile";
        assertSingle(g, m("a.Ctl", "importFile", multipart), "ImportExcel#<init>(", m("a.ImportExcel", "<init>", multipart, "int", "int"));
        String byException = m("a.Validators", "extract", "javax.validation.ConstraintViolationException", "java.lang.String");
        String bySet = m("a.Validators", "extract", "java.util.Set", "java.lang.String");
        assertSingle(g, m("a.Ctl", "handle"), "Validators#extract(", byException);
        assertSingle(g, m("a.Validators", "viaSet", "java.util.Set"), "Validators#extract(", bySet);
        // 타입을 알 수 없는 인자(외부 타입의 메서드가 돌려준 값)는 전처럼 후보 전부다
        List<Edge> unknown = calls(g, byException, "Validators#extract(");
        assertThat(unknown).extracting(Edge::to).containsExactlyInAnyOrder(byException, bySet);
        assertThat(unknown).allMatch(e -> e.attrBoolean("ambiguous"));
    }

    @Test
    @DisplayName("F-003-AC51 삼항(양쪽이 같은 타입)·new T[]·캐스트는 그 타입으로 읽고, null은 가르는 근거로 쓰지 않으며, 알 수 없는 인자는 후보 전부로 ambiguous다")
    void otherExpressions() {
        GraphModel g = Fx.analyze(SOURCES);

        String putString = m(SCRTY, "put", "java.lang.String", "java.lang.Object");
        String putBytes = m(SCRTY, "put", "byte[]", "java.lang.Object");
        assertSingle(g, m("a.Caller", "viaTernary", "other.Session", "boolean"), "Scrty#put(", putString);
        assertSingle(g, m("a.Caller", "viaArrayCreation", "other.Session"), "Scrty#put(", putBytes);
        assertSingle(g, m("a.Caller", "viaCast", "other.Session"), "Scrty#put(", putString);
        for (String method : List.of("viaNull", "unknown")) {
            List<Edge> both = calls(g, m("a.Caller", method, "other.Session"), "Scrty#put(");
            assertThat(both).as(method).extracting(Edge::to).containsExactlyInAnyOrder(putString, putBytes);
            assertThat(both).as(method).allMatch(e -> e.confidence() == Confidence.INFERRED && e.attrBoolean("ambiguous"));
        }
    }

    @Test
    @DisplayName("F-003-AC52 classpath에 없는 상위 타입이 여럿이어서 상위 클래스 쪽으로 고른 상속 호출은 후보가 하나뿐이므로 ambiguous를 붙이지 않는다(static 중첩 클래스 포함)")
    void singleInheritedCandidateIsNotAmbiguous() {
        GraphModel g = Fx.analyze(
                "src/a/LogoutFilter.java", """
                        package a;
                        import jakarta.servlet.http.HttpServletRequest;
                        import jakarta.servlet.http.HttpServletRequestWrapper;
                        import org.springframework.web.filter.GenericFilterBean;
                        public class LogoutFilter extends GenericFilterBean implements Runnable, ext.Marker {
                            public void run() { }
                            static class Wrapper extends HttpServletRequestWrapper implements ext.Marker {
                                Wrapper(HttpServletRequest request) { super(request); }
                                String url() { return getContextPath() + this.getScheme(); }
                            }
                        }
                        """);

        String wrapper = "jakarta.servlet.http.HttpServletRequestWrapper";
        List<Edge> calls = g.outgoing(m("a.LogoutFilter$Wrapper", "url"), EdgeKind.CALLS);
        assertThat(calls).extracting(Edge::to).containsExactlyInAnyOrder(m(wrapper, "getContextPath", "?"), m(wrapper, "getScheme", "?"));
        assertThat(calls).allMatch(e -> e.confidence() == Confidence.INFERRED && wrapper.equals(e.attrString("inheritedFrom")));
        assertThat(calls).noneMatch(e -> e.attrs().containsKey("ambiguous"));
    }

    @Test
    @DisplayName("F-003-AC52 java 분석기가 만든 엣지 가운데 ambiguous=true인데 같은 (출발, 호출 지점)에서 나가는 후보가 하나뿐인 것은 없다")
    void noAmbiguousEdgeWithSingleCandidate() {
        List<String> all = new ArrayList<>(List.of(SOURCES));
        all.addAll(List.of(
                "src/a/Multi.java", """
                        package a;
                        public class Multi extends org.ext.One implements org.ext.Two {
                            void own() { helper(); this.other(1); }
                        }
                        """,
                "src/a/Svc.java", """
                        package a;
                        public class Svc {
                            Multi multi;
                            void go() { multi.go(); Multi.make(); }
                        }
                        """));
        GraphModel g = Fx.analyze(all.toArray(String[]::new));

        Map<String, List<Edge>> bySite = new LinkedHashMap<>();
        for (Edge e : g.edges()) {
            if (e.attrBoolean("ambiguous")) {
                bySite.computeIfAbsent(e.kind() + " " + e.from() + " @" + e.location(), k -> new ArrayList<>()).add(e);
            }
        }
        assertThat(bySite).isNotEmpty();
        assertThat(bySite).allSatisfy((site, edges) -> assertThat(edges).as(site).hasSizeGreaterThan(1));
    }
}
