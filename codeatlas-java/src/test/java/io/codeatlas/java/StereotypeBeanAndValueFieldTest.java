package io.codeatlas.java;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codeatlas.java.Fx.node;
import static org.assertj.core.api.Assertions.assertThat;

/** F-004-R22(이름이 같은 스테레오타입 빈), F-004-R23({@code @Value("${키}")} 필드를 이어 붙인 뷰 이름·redirect 경로). */
class StereotypeBeanAndValueFieldTest {

    private static String controller(String pkg) {
        return "package " + pkg + ";\nimport org.springframework.stereotype.Controller;\n@Controller\npublic class ManageController { }\n";
    }

    @Test
    @DisplayName("F-004-AC24 여러 모듈이 같은 이름으로 정의한 스테레오타입 빈: 노드의 attrs·위치는 main 쪽·경로순 첫 정의의 것이고 나머지는 otherDefinitions, DEFINES_BEAN은 정의마다 하나다")
    void sameNamedStereotypeBeans() {
        // 파일 순서와 상관없이 같은 결과여야 한다: 경로순으로 뒤인 것을 먼저 준다
        GraphModel g = Fx.analyze(
                "web-b/src/main/java/com/b/ManageController.java", controller("com.b"),
                "web-a/src/test/java/com/t/ManageController.java", controller("com.t"),
                "web-a/src/main/java/com/a/ManageController.java", controller("com.a"),
                "web-a/src/main/java/com/a/OtherController.java",
                "package com.a;\nimport org.springframework.stereotype.Controller;\n@Controller\npublic class OtherController { }\n");

        Node bean = node(g, Ids.springBean("manageController"));
        assertThat(bean.attrString("class")).isEqualTo("com.a.ManageController");
        assertThat(bean.attrString("beanType")).isEqualTo("com.a.ManageController");
        assertThat(bean.location().file()).isEqualTo("web-a/src/main/java/com/a/ManageController.java");
        assertThat(bean.attrList("otherDefinitions")).containsExactly(
                "web-b/src/main/java/com/b/ManageController.java:4 com.b.ManageController",
                "web-a/src/test/java/com/t/ManageController.java:4 com.t.ManageController");
        assertThat(g.incoming(bean.id(), EdgeKind.DEFINES_BEAN)).extracting(Edge::from).containsExactlyInAnyOrder(
                Ids.javaType("com.a.ManageController"), Ids.javaType("com.b.ManageController"), Ids.javaType("com.t.ManageController"));
        assertThat(g.nodes(NodeKind.SPRING_BEAN)).hasSize(2);
        // 정의가 하나뿐인 빈에는 otherDefinitions가 없다
        Node other = node(g, Ids.springBean("otherController"));
        assertThat(other.attrs()).doesNotContainKey("otherDefinitions");
        assertThat(other.attrString("class")).isEqualTo("com.a.OtherController");
    }

    @Test
    @DisplayName("F-004-AC25 @Value(\"${키}\") 필드(상위 클래스의 것 포함)를 이어 붙인 뷰 이름·redirect 경로에는 그 자리에 ${키}를 남긴다. 그런 필드가 아니면 전처럼 {*}다")
    void valueFieldInViewNameAndRedirect() {
        GraphModel g = Fx.analyze(
                // 하위 클래스를 먼저 본다: 상위 클래스의 필드는 모든 파일을 본 뒤에야 알 수 있다
                "src/com/a/AController.java", """
                        package com.a;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.RequestMapping;
                        import javax.servlet.http.HttpServletResponse;
                        @Controller
                        public class AController extends BaseController {
                            String plain;
                            @RequestMapping("/a/login")
                            public String login() {
                                return "redirect:" + adminPath;
                            }
                            @RequestMapping("/a/front")
                            public String front() {
                                return "redirect:" + this.adminPath + "/login?x=" + frontPath;
                            }
                            @RequestMapping("/a/plain")
                            public String plain() {
                                return "redirect:" + plain;
                            }
                            @RequestMapping("/a/send")
                            public void send(HttpServletResponse response) throws Exception {
                                response.sendRedirect(adminPath + "/sys/user");
                            }
                        }
                        """,
                "src/com/a/BaseController.java", """
                        package com.a;
                        import org.springframework.beans.factory.annotation.Value;
                        public abstract class BaseController {
                            @Value("${adminPath}")
                            protected String adminPath;
                            @Value("${frontPath:/f}")
                            protected String frontPath;
                        }
                        """);

        assertThat(node(g, Ids.javaMethod("com.a.AController", "login")).attrList("viewNames")).containsExactly("redirect:${adminPath}");
        assertThat(node(g, Ids.javaMethod("com.a.AController", "login")).attrList("viewNameLines")).containsExactly("10:9");
        assertThat(node(g, Ids.javaMethod("com.a.AController", "front")).attrList("viewNames"))
                .containsExactly("redirect:${adminPath}/login?x=${frontPath:/f}");
        assertThat(node(g, Ids.javaMethod("com.a.AController", "plain")).attrList("viewNames")).containsExactly("redirect:{*}");
        Node send = node(g, Ids.javaMethod("com.a.AController", "send", "javax.servlet.http.HttpServletResponse"));
        assertThat(send.attrList("redirects")).containsExactly("${adminPath}/sys/user");
        assertThat(send.attrList("redirectLines")).containsExactly("22:18");
        // 그래프에 내부 표식이 남지 않는다
        assertThat(g.nodes()).allSatisfy(n -> assertThat(String.valueOf(n.attrs())).doesNotContain("\\u0001").doesNotContain("\u0001"));
    }
}
