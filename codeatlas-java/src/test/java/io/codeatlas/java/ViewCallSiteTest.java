package io.codeatlas.java;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codeatlas.java.Fx.node;
import static org.assertj.core.api.Assertions.assertThat;

/** F-004-R19: 뷰 이름·redirect·forward의 호출 지점을 병렬 목록 attrs({@code viewNameLines}·{@code redirectLines}·{@code forwardLines})로 남긴다. */
class ViewCallSiteTest {

    private static final String[] SOURCES = {
            "src/com/a/OrderController.java", """
                    package com.a;
                    import org.springframework.stereotype.Controller;
                    import org.springframework.web.bind.annotation.RequestMapping;
                    import org.springframework.web.servlet.ModelAndView;
                    import javax.servlet.http.HttpServletRequest;
                    import javax.servlet.http.HttpServletResponse;
                    @Controller
                    public class OrderController {
                        @RequestMapping("/order/save")
                        public String save(Object order, HttpServletRequest request, HttpServletResponse response) throws Exception {
                            if (order == null) {
                                return "order/form";
                            }
                            if (order.hashCode() > 0) {
                                response.sendRedirect("/order/done");
                                return null;
                            }
                            if (order.hashCode() > 1) {
                                response.sendRedirect(request.getContextPath() + "/order/done?again=1");
                            }
                            request.getRequestDispatcher("/order/next").forward(request, response);
                            String view = "order/a";
                            if (order.hashCode() < 0) { view = "order/b"; }
                            if (order.hashCode() == 0) { return "order/form"; }
                            return view;
                        }
                        @RequestMapping("/order/chain")
                        public String chain(Object order) {
                            if (order == null) {
                                return "redirect:/order/list";
                            }
                            return this.detail(order);
                        }
                        @RequestMapping("/order/detail")
                        public String detail(Object order) { return order == null ? "order/none" : "order/detail"; }
                        @RequestMapping("/order/mav")
                        public ModelAndView mav(Object order) {
                            ModelAndView mav = new ModelAndView("order/mav");
                            if (order == null) {
                                mav.setViewName("order/empty");
                            }
                            return mav;
                        }
                    }
                    """,
    };

    private static String m(String name, String... params) {
        return Ids.javaMethod("com.a.OrderController", name, params);
    }

    @Test
    @DisplayName("F-004-AC21 viewNameLines는 viewNames와 같은 순서로 반환문·ModelAndView 생성·setViewName 호출의 줄:열이다(같은 이름은 처음 나온 자리, 지역 변수 경유는 반환문)")
    void viewNameLines() {
        GraphModel g = Fx.analyze(SOURCES);

        Node save = node(g, m("save", "java.lang.Object", "javax.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletResponse"));
        assertThat(save.attrList("viewNames")).containsExactly("order/form", "order/a", "order/b");
        assertThat(save.attrList("viewNameLines")).containsExactly("12:13", "25:9", "25:9");
        Node detail = node(g, m("detail", "java.lang.Object"));
        assertThat(detail.attrList("viewNames")).containsExactly("order/none", "order/detail");
        assertThat(detail.attrList("viewNameLines")).containsExactly("35:42", "35:42");
        Node mav = node(g, m("mav", "java.lang.Object"));
        assertThat(mav.attrList("viewNames")).containsExactly("order/mav", "order/empty");
        // 생성식은 new의 자리, setViewName은 메서드 이름의 자리(그 호출의 CALLS와 같은 자리)
        assertThat(mav.attrList("viewNameLines")).containsExactly("38:28", "40:17");
    }

    @Test
    @DisplayName("F-004-AC21 다른 메서드에서 합쳐 온 뷰 이름의 위치는 이 메서드 안의 그 호출 지점이다")
    void delegatedViewNameLines() {
        GraphModel g = Fx.analyze(SOURCES);

        Node chain = node(g, m("chain", "java.lang.Object"));
        assertThat(chain.attrList("viewNames")).containsExactly("redirect:/order/list", "order/none", "order/detail");
        // detail(order) 호출은 32행: 호출된 메서드의 return(35행)이 아니다
        assertThat(chain.attrList("viewNameLines")).containsExactly("30:13", "32:21", "32:21");
    }

    @Test
    @DisplayName("F-004-AC21 redirectLines·forwardLines는 redirects·forwards와 같은 순서로 sendRedirect·forward 호출의 줄:열이다(같은 경로는 처음 나온 자리)")
    void redirectAndForwardLines() {
        GraphModel g = Fx.analyze(SOURCES);

        Node save = node(g, m("save", "java.lang.Object", "javax.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletResponse"));
        assertThat(save.attrList("redirects")).containsExactly("/order/done");
        assertThat(save.attrList("redirectLines")).containsExactly("15:22");
        assertThat(save.attrList("forwards")).containsExactly("/order/next");
        assertThat(save.attrList("forwardLines")).containsExactly("21:53");
        // 뷰 이름·redirect·forward가 없는 메서드에는 병렬 목록도 없다
        assertThat(node(g, m("detail", "java.lang.Object")).attrs()).doesNotContainKeys("redirectLines", "forwardLines");
    }
}
