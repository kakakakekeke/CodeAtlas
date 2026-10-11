package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** 실전 재검증 N28: 풀 수 있는데 미해결로 남던 참조(구문 ID, 뷰 이름, 상속 호출, 상속 필드의 호출, getBean 체인). */
class ResolvableReferencesTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    // ------------------------------------------------------------------ 구문 ID (F-004-R20)

    private static final String[] DAO = {
            "src/com/a/LoginDao.java", """
                    package com.a;
                    import java.util.Map;
                    import org.mybatis.spring.SqlSessionTemplate;
                    public class LoginDao {
                        SqlSessionTemplate session;
                        int incorrect(Map<String, Object> map) {
                            return session.update("LoginUsr.updateLoginIncorrect" + map.get("USER_SE"), map);
                        }
                        Object one(Object vo) {
                            String queryId = "LoginUsr.selectOne";
                            return session.selectOne(queryId, vo);
                        }
                        Object twice(Object vo, boolean admin) {
                            String queryId = "LoginUsr.selectUser";
                            if (admin) { queryId = "LoginUsr.selectAdmin"; }
                            return session.selectOne(queryId, vo);
                        }
                        Object noNamespace(String suffix) {
                            return session.selectOne("select" + suffix);
                        }
                        Object dynamic(String id) {
                            return session.selectOne(id);
                        }
                    }
                    """,
    };

    @Test
    @DisplayName("F-004-AC22 구문 ID가 '리터럴 접두(namespace. 포함) + 동적'이면 unresolved:mybatis-statement:<접두>{*}로 가는 미해결 EXECUTES에 statementPrefix를 남긴다")
    void statementIdWithLiteralPrefix() {
        GraphModel g = Fx.analyze(DAO);

        Edge e = edge(g, m("com.a.LoginDao", "incorrect", "java.util.Map"), EdgeKind.EXECUTES,
                Ids.unresolved("mybatis-statement", "LoginUsr.updateLoginIncorrect{*}"));
        assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(e.attrString("statementPrefix")).isEqualTo("LoginUsr.updateLoginIncorrect");
        assertThat(e.attrString("operation")).isEqualTo("update");
        assertThat(node(g, e.to()).attrString("category")).isEqualTo("mybatis-statement");
        // namespace가 리터럴이 아니면(점이 없는 접두) 전처럼 식 원문의 미해결이고 statementPrefix가 없다
        Edge plain = g.outgoing(m("com.a.LoginDao", "noNamespace", "java.lang.String"), EdgeKind.EXECUTES).get(0);
        assertThat(plain.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(plain.attrs()).doesNotContainKey("statementPrefix");
        assertThat(targets(g, m("com.a.LoginDao", "dynamic", "java.lang.String"), EdgeKind.EXECUTES))
                .containsExactly(Ids.unresolved("mybatis-statement", "id"));
    }

    @Test
    @DisplayName("F-004-AC22 한 번만 대입된 지역 변수에 담은 구문 ID 상수는 따라가 EXECUTES를 만들고, 여러 번 대입된 변수는 전처럼 미해결이다")
    void statementIdInLocalVariable() {
        GraphModel g = Fx.analyze(DAO);

        Edge e = edge(g, m("com.a.LoginDao", "one", "java.lang.Object"), EdgeKind.EXECUTES, "mybatis:stmt:LoginUsr.selectOne");
        assertThat(e.attrString("namespace")).isEqualTo("LoginUsr");
        assertThat(e.attrString("statementId")).isEqualTo("selectOne");
        assertThat(targets(g, m("com.a.LoginDao", "twice", "java.lang.Object", "boolean"), EdgeKind.EXECUTES))
                .containsExactly(Ids.unresolved("mybatis-statement", "queryId"));
    }

    // ------------------------------------------------------------------ 뷰 이름 후보 (F-004-R21)

    @Test
    @DisplayName("F-004-AC23 '리터럴 + 지역 변수'인 뷰 이름은 그 변수에 대입된 리터럴마다 뷰 이름을 내고, 후보가 여럿이면 viewNameCandidates에 적는다")
    void viewNameCandidatesFromLocalVariable() {
        GraphModel g = Fx.analyze(
                "src/com/a/OaController.java", """
                        package com.a;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.RequestMapping;
                        @Controller
                        public class OaController {
                            @RequestMapping("/oa/form")
                            public String form(Object leave, String param) {
                                String view = "leaveForm";
                                if (leave == null) {
                                    view = "leaveAudit";
                                }
                                return "modules/oa/" + view;
                            }
                            @RequestMapping("/oa/one")
                            public String one() {
                                String view = "leaveList";
                                return "modules/oa/" + view;
                            }
                            @RequestMapping("/oa/dyn")
                            public String dyn(String param) {
                                String view = "leaveForm";
                                if (param != null) {
                                    view = param;
                                }
                                return "modules/oa/" + view;
                            }
                        }
                        """);

        Node form = node(g, m("com.a.OaController", "form", "java.lang.Object", "java.lang.String"));
        assertThat(form.attrList("viewNames")).containsExactly("modules/oa/leaveForm", "modules/oa/leaveAudit");
        assertThat(form.attrList("viewNameCandidates")).containsExactly("modules/oa/leaveForm", "modules/oa/leaveAudit");
        assertThat(form.attrList("viewNameLines")).containsExactly("12:9", "12:9");
        assertThat(targets(g, form.id(), EdgeKind.RENDERS)).isEmpty();
        // 대입이 한 번뿐이면 후보가 아니라 그 값이다
        Node one = node(g, m("com.a.OaController", "one"));
        assertThat(one.attrList("viewNames")).containsExactly("modules/oa/leaveList");
        assertThat(one.attrs()).doesNotContainKey("viewNameCandidates");
        // 값을 모르는 대입이 섞이면 전처럼 {*}다
        Node dyn = node(g, m("com.a.OaController", "dyn", "java.lang.String"));
        assertThat(dyn.attrList("viewNames")).containsExactly("modules/oa/{*}");
        assertThat(dyn.attrs()).doesNotContainKey("viewNameCandidates");
    }

    // ------------------------------------------------------------------ 상속 호출·상속 필드·getBean (F-003-R25~R27)

    private static final String SHIRO = "org.apache.shiro.session.mgt.eis.AbstractSessionDAO";

    private static final String[] INHERITED = {
            "src/com/a/SessionDAO.java",
            "package com.a; public interface SessionDAO extends org.apache.shiro.session.mgt.eis.SessionDAO { void extra(); }",
            "src/com/a/Dao.java", """
                    package com.a;
                    import org.apache.shiro.session.mgt.eis.AbstractSessionDAO;
                    public class Dao extends AbstractSessionDAO implements SessionDAO {
                        public void extra() { }
                        Object create(Object session) {
                            Object id = this.generateSessionId(session);
                            assignSessionId(session, id);
                            return id;
                        }
                    }
                    """,
            "src/com/a/Base.java", """
                    package com.a;
                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    public class Base {
                        protected Logger logger = LoggerFactory.getLogger(getClass());
                    }
                    """,
            "src/com/a/Ctl.java", """
                    package com.a;
                    public class Ctl extends Base {
                        void f() {
                            logger.debug("x {}", 1);
                            this.logger.info("y");
                        }
                    }
                    """,
            "src/com/a/Svc.java", """
                    package com.a;
                    import org.ext.AbstractService;
                    import org.springframework.context.ApplicationContext;
                    public class Svc extends AbstractService {
                        ApplicationContext context;
                        void run() {
                            egovLogger.debug("inherited field of a missing supertype");
                            context.getBean(Helper.class).help();
                            context.getBean("helper", Helper.class).help();
                            context.getBean(name()).toString();
                        }
                        String name() { return "helper"; }
                    }
                    """,
            "src/com/a/Helper.java", "package com.a; public class Helper { public void help() { } }",
    };

    @Test
    @DisplayName("F-003-AC43 this.를 붙였든 아니든, 선언이 없는 상속 호출은 classpath에 없는 상위 타입이 여럿이면 상위 클래스 쪽으로 INFERRED다(후보가 하나뿐이라 ambiguous는 붙이지 않는다, F-003-R29)")
    void inheritedCallWithSeveralMissingSupertypes() {
        GraphModel g = Fx.analyze(INHERITED);

        String create = m("com.a.Dao", "create", "java.lang.Object");
        List<Edge> calls = g.outgoing(create, EdgeKind.CALLS);
        assertThat(calls).extracting(Edge::to).containsExactlyInAnyOrder(m(SHIRO, "generateSessionId", "?"), m(SHIRO, "assignSessionId", "?"));
        // 2026-10-11 고쳐 씀(F-003-R29): 전에는 ambiguous=true였다
        assertThat(calls).allMatch(e -> e.confidence() == Confidence.INFERRED && !e.attrs().containsKey("ambiguous")
                && SHIRO.equals(e.attrString("inheritedFrom")));
    }

    @Test
    @DisplayName("F-003-AC44 프로젝트 상위 타입에 선언한 필드의 타입이 classpath에 없으면 그 필드를 선언한 파일의 import로 타입을 복원해 소스 없는 메서드 호출로 잇는다")
    void callOnInheritedFieldOfUnresolvedType() {
        GraphModel g = Fx.analyze(INHERITED);

        assertThat(targets(g, m("com.a.Ctl", "f"), EdgeKind.CALLS))
                .containsExactlyInAnyOrder(m("org.slf4j.Logger", "debug", "?"), m("org.slf4j.Logger", "info", "?"));
    }

    @Test
    @DisplayName("F-003-AC45 classpath에 없는 상위 타입에서 물려받았을 이름(해석되지 않은 소문자 이름)의 호출은 WARN 미해결이 아니라 체인 미해결(externalChain, INFO)이다")
    void callOnFieldInheritedFromMissingSupertype() {
        GraphModel g = Fx.analyze(INHERITED);

        Edge e = edge(g, m("com.a.Svc", "run"), EdgeKind.CALLS, Ids.unresolved("java-call", "egovLogger.debug(…)"));
        assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(e.attrBoolean("externalChain")).isTrue();
        assertThat(g.diagnostics()).filteredOn(d -> d.message().contains("egovLogger.debug")).hasSize(1)
                .allMatch(d -> d.severity() == Severity.INFO);
    }

    @Test
    @DisplayName("F-003-AC46 소스 없는 타입의 getBean(X.class)·getBean(이름, X.class) 결과에 이은 호출은 X의 메서드로 INFERRED로 잇는다")
    void callOnGetBeanResult() {
        GraphModel g = Fx.analyze(INHERITED);

        String run = m("com.a.Svc", "run");
        List<Edge> help = g.edges(run, EdgeKind.CALLS, m("com.a.Helper", "help"));
        assertThat(help).hasSize(2).allMatch(e -> e.confidence() == Confidence.INFERRED);
        // 타입을 알 수 없는 getBean(이름)의 결과는 전처럼 체인 미해결이다
        assertThat(targets(g, run, EdgeKind.CALLS)).contains(Ids.unresolved("java-call", "context.getBean(name()).toString(…)"));
    }
}
