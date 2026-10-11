package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-003-R20: 리플렉션 기반 공통 서비스({@code X.getClass().getDeclaredMethod("이름", ..).invoke(X, ..)})의 추론 호출. */
class ReflectionCallTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    private static final String[] SOURCES = {
            "src/com/a/BaseServiceImpl.java", """
                    package com.a;
                    import java.lang.reflect.Method;
                    import java.util.List;
                    public abstract class BaseServiceImpl<M, R, E> {
                        public M mapper;
                        @SuppressWarnings("unchecked")
                        public List<R> selectByExample(E example) {
                            try {
                                Method method = mapper.getClass().getDeclaredMethod("selectByExample", example.getClass());
                                Object result = method.invoke(mapper, example);
                                return (List<R>) result;
                            } catch (Exception ex) {
                                return null;
                            }
                        }
                        public int insert(R record) throws Exception {
                            return (Integer) this.mapper.getClass().getMethod("insert", record.getClass()).invoke(this.mapper, record);
                        }
                        public Object byName(String name) throws Exception {
                            Method method = mapper.getClass().getDeclaredMethod(name);
                            return method.invoke(mapper);
                        }
                        public Object otherTarget(Object other) throws Exception {
                            Method method = mapper.getClass().getDeclaredMethod("insert", Object.class);
                            return method.invoke(other, "x");
                        }
                        public int noReflection(R record) { return 0; }
                    }
                    """,
            "src/com/a/User.java", "package com.a; public class User { }",
            "src/com/a/UserExample.java", "package com.a; public class UserExample { }",
            "src/com/a/Role.java", "package com.a; public class Role { }",
            "src/com/a/BaseMapper.java", "package com.a; public interface BaseMapper<R> { int insert(R record); }",
            "src/com/a/UserMapper.java", """
                    package com.a;
                    import java.util.List;
                    public interface UserMapper extends BaseMapper<User> {
                        List<User> selectByExample(UserExample example);
                        List<User> selectByExample(UserExample example, int limit);
                    }
                    """,
            "src/com/a/RoleMapper.java", """
                    package com.a;
                    import java.util.List;
                    public interface RoleMapper {
                        List<Role> selectByExample(Object example);
                        int insert(Role record);
                        int insert(Role record, boolean selective);
                    }
                    """,
            "src/com/a/UserServiceImpl.java", "package com.a; public class UserServiceImpl extends BaseServiceImpl<UserMapper, User, UserExample> { }",
            "src/com/a/AdminUserServiceImpl.java", "package com.a; public class AdminUserServiceImpl extends UserServiceImpl { }",
            "src/com/a/RoleServiceImpl.java", "package com.a; public class RoleServiceImpl extends BaseServiceImpl<RoleMapper, Role, Object> { }",
            "src/com/a/Mid.java", "package com.a; public abstract class Mid<X> extends BaseServiceImpl<X, Role, Object> { }",
            "src/com/a/MidRoleServiceImpl.java", "package com.a; public class MidRoleServiceImpl extends Mid<RoleMapper> { }",
            "src/com/a/ExtServiceImpl.java", "package com.a; public class ExtServiceImpl extends BaseServiceImpl<org.ext.ExtMapper, User, UserExample> { }",
            "src/com/a/Controller.java", """
                    package com.a;
                    public class Controller {
                        UserServiceImpl users;
                        Object list(UserExample e) { return users.selectByExample(e); }
                    }
                    """,
            "src/com/a/Direct.java", """
                    package com.a;
                    public class Direct {
                        RoleMapper roles;
                        Object call(Role r) throws Exception {
                            return roles.getClass().getMethod("insert", Role.class).invoke(roles, r);
                        }
                    }
                    """,
    };

    @Test
    @DisplayName("F-003-AC34 타입 매개변수로 선언한 객체의 getClass().getDeclaredMethod(\"상수\").invoke(그 객체)는 타입 인자를 프로젝트 타입으로 고정한 하위 클래스마다 그 타입의 메서드로 INFERRED CALLS(callType=reflection)를 만든다")
    void reflectionThroughTypeParameter() {
        GraphModel g = Fx.analyze(SOURCES);

        String select = m("com.a.BaseServiceImpl", "selectByExample", "java.lang.Object");
        Edge user = edge(g, select, EdgeKind.CALLS, m("com.a.UserMapper", "selectByExample", "com.a.UserExample"));
        assertThat(user.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(user.attrString("callType")).isEqualTo("reflection");
        assertThat(user.attrString("receiverType")).isEqualTo("com.a.UserMapper");
        assertThat(user.attrList("viaTypes")).containsExactly("com.a.AdminUserServiceImpl", "com.a.UserServiceImpl");
        assertThat(user.location().file()).isEqualTo("src/com/a/BaseServiceImpl.java");
        assertThat(user.location().startLine()).isEqualTo(10);
        Edge role = edge(g, select, EdgeKind.CALLS, m("com.a.RoleMapper", "selectByExample", "java.lang.Object"));
        assertThat(role.attrString("receiverType")).isEqualTo("com.a.RoleMapper");
        // 중간 제네릭 클래스를 거쳐 타입 인자가 정해진 하위 클래스도 포함한다
        assertThat(role.attrList("viaTypes")).containsExactly("com.a.MidRoleServiceImpl", "com.a.RoleServiceImpl");
        // 이름과 인자 수가 맞는 메서드만: selectByExample(UserExample, int)는 아니다. 타입 인자가 외부 타입이면 잇지 않는다
        assertThat(targets(g, select, EdgeKind.CALLS)).containsExactlyInAnyOrder(user.to(), role.to());

        // 변수 없이 한 식으로 쓴 꼴, this.mapper, 상위 인터페이스에 선언한 메서드
        String insert = m("com.a.BaseServiceImpl", "insert", "java.lang.Object");
        Edge inherited = edge(g, insert, EdgeKind.CALLS, m("com.a.BaseMapper", "insert", "java.lang.Object"));
        assertThat(inherited.attrString("receiverType")).isEqualTo("com.a.UserMapper");
        assertThat(inherited.attrString("callType")).isEqualTo("reflection");
        assertThat(targets(g, insert, EdgeKind.CALLS)).containsExactlyInAnyOrder(inherited.to(), m("com.a.RoleMapper", "insert", "com.a.Role"));

        // 호출하는 쪽: 하위 타입 변수로 부른 상위 메서드 호출에는 receiverType이 있어 소비자가 viaTypes와 맞춰 좁힌다
        Edge caller = edge(g, m("com.a.Controller", "list", "com.a.UserExample"), EdgeKind.CALLS, select);
        assertThat(caller.attrString("receiverType")).isEqualTo("com.a.UserServiceImpl");

        // 선언 타입이 프로젝트 타입이면 그 타입의 메서드로 바로 잇는다
        Edge direct = edge(g, m("com.a.Direct", "call", "com.a.Role"), EdgeKind.CALLS, m("com.a.RoleMapper", "insert", "com.a.Role"));
        assertThat(direct.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(direct.attrString("callType")).isEqualTo("reflection");
        assertThat(direct.attrString("receiverType")).isEqualTo("com.a.RoleMapper");
        assertThat(direct.attrs()).doesNotContainKey("viaTypes");
    }

    @Test
    @DisplayName("F-003-AC35 그 꼴이 아닌 리플렉션 호출(이름이 상수가 아님, 다른 객체에 invoke)은 엣지를 만들지 않고 메서드당 INFO 진단 REFLECTION_CALL 1건을 남긴다")
    void otherReflectionIsReported() {
        GraphModel g = Fx.analyze(SOURCES);

        assertThat(targets(g, m("com.a.BaseServiceImpl", "byName", "java.lang.String"), EdgeKind.CALLS)).isEmpty();
        assertThat(targets(g, m("com.a.BaseServiceImpl", "otherTarget", "java.lang.Object"), EdgeKind.CALLS)).isEmpty();
        List<Diagnostic> reflection = g.diagnostics().stream().filter(d -> d.code().equals("REFLECTION_CALL")).toList();
        assertThat(reflection).hasSize(2).allMatch(d -> d.severity() == Severity.INFO);
        assertThat(reflection).extracting(d -> d.location().startLine()).containsExactlyInAnyOrder(21, 25);
        assertThat(reflection).allMatch(d -> d.location().file().equals("src/com/a/BaseServiceImpl.java"));
        assertThat(g.edges(EdgeKind.CALLS)).filteredOn(e -> "reflection".equals(e.attrString("callType")))
                .allMatch(e -> e.confidence() == Confidence.INFERRED);
    }

    @Test
    @DisplayName("F-003-AC35 꼴은 맞지만 타입 인자를 프로젝트 타입으로 고정한 하위 클래스가 없으면 그 메서드도 REFLECTION_CALL 진단으로 남긴다")
    void patternWithoutSubclassIsReported() {
        GraphModel g = Fx.analyze(SOURCES[0], SOURCES[1]);

        assertThat(g.edges(EdgeKind.CALLS)).noneMatch(e -> "reflection".equals(e.attrString("callType")));
        assertThat(g.diagnostics()).filteredOn(d -> d.code().equals("REFLECTION_CALL")).hasSize(4);
    }
}
