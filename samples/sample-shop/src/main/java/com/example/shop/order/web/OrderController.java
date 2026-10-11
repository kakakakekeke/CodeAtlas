package com.example.shop.order.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.example.shop.common.CodeConstants;
import com.example.shop.common.LoginCheckInterceptor;
import com.example.shop.common.PageRequest;
import com.example.shop.order.service.OrderService;
import com.example.shop.order.vo.OrderSearchCond;
import com.example.shop.order.vo.OrderVO;
import com.example.shop.product.service.ProductService;

/**
 * 주문 화면 컨트롤러 (JSP 뷰). 필드 주입을 쓴다.
 */
@Controller
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    /** 주문 목록 화면 */
    @GetMapping("/list")
    public String list(@ModelAttribute("cond") OrderSearchCond cond,
                       @RequestParam(defaultValue = "1") int page,
                       Model model) {
        PageRequest pageRequest = PageRequest.of(page, 20);
        model.addAttribute("orders", orderService.findOrders(cond, pageRequest));
        model.addAttribute("totalCount", orderService.countOrders(cond));
        model.addAttribute("page", pageRequest);
        return "order/list";
    }

    /** 주문 상세 화면 */
    @GetMapping("/detail")
    public String detail(@RequestParam("orderId") Long orderId, Model model) {
        model.addAttribute("order", orderService.getOrder(orderId));
        return "order/detail";
    }

    /** 주문 등록 화면 */
    @GetMapping("/form")
    public String form(Model model) {
        model.addAttribute("products", productService.getProducts(new HashMap<String, Object>()));
        return "order/form";
    }

    /** 주문 저장 후 목록으로 리다이렉트 (JSP 를 렌더하지 않는다) */
    @PostMapping("/save")
    public String save(OrderVO order) {
        orderService.placeOrder(order);
        return "redirect:/order/list";
    }

    /** 주문 마감 (AJAX) */
    @PostMapping("/close.do")
    @ResponseBody
    public Map<String, Object> close(@RequestParam Long orderId, HttpSession session) {
        Object loginId = session.getAttribute(LoginCheckInterceptor.SESSION_KEY);
        String userId = loginId == null ? CodeConstants.DEFAULT_USER : loginId.toString();

        Map<String, Object> result = new HashMap<>();
        result.put("resultCd", orderService.closeOrder(orderId, userId));
        return result;
    }

    /** 주문 취소 (AJAX). 옛 방식 @RequestMapping(method = POST) */
    @RequestMapping(value = "/cancel.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> cancel(@RequestParam Long orderId) {
        orderService.cancel(orderId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", CodeConstants.YES);
        return result;
    }

    /** 주문 이력 (AJAX). HTTP 메서드를 지정하지 않았다. */
    @RequestMapping("/history.do")
    @ResponseBody
    public List<Map<String, Object>> history(@RequestParam Long orderId) {
        return orderService.getHistory(orderId);
    }

    /** 팝업. 뷰 이름을 문자열 조합으로 만들어서 어떤 JSP 인지 정적으로 알 수 없다. */
    @GetMapping("/popup/{type}")
    public String popup(@PathVariable String type, Model model) {
        model.addAttribute("type", type);
        return "order/popup_" + type;
    }
}
