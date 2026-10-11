package com.example.shop.order.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.shop.common.OrderStatus;
import com.example.shop.common.PageRequest;
import com.example.shop.order.service.OrderService;
import com.example.shop.order.vo.OrderItemVO;
import com.example.shop.order.vo.OrderSearchCond;
import com.example.shop.order.vo.OrderVO;
import com.example.shop.payment.PaymentService;

/**
 * 주문 REST API (React 화면용). 생성자 주입을 쓴다.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderApiController {

    private final OrderService orderService;
    private final PaymentService paymentService;

    public OrderApiController(OrderService orderService, PaymentService paymentService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
    }

    @GetMapping
    public Map<String, Object> list(OrderSearchCond cond,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> body = new HashMap<>();
        body.put("items", orderService.findOrders(cond, PageRequest.of(page, size)));
        body.put("total", orderService.countOrders(cond));
        return body;
    }

    /** 리터럴 경로. "/{id}" 보다 우선한다. */
    @GetMapping("/export")
    public List<Map<String, Object>> export(@RequestParam String type) {
        return orderService.export(type);
    }

    @GetMapping("/{id}")
    public OrderVO get(@PathVariable("id") Long id) {
        return orderService.getOrder(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody OrderVO order) {
        Map<String, Object> body = new HashMap<>();
        body.put("orderId", orderService.placeOrder(order));
        return body;
    }

    @PutMapping("/{id}/status")
    public void changeStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        OrderStatus status = OrderStatus.fromCode(body.get("status"));
        orderService.changeStatus(id, status, body.get("userId"));
    }

    /** 삭제 요청은 주문 취소로 처리한다. Long 인자이므로 cancel(Long) 이 불린다. */
    @DeleteMapping("/{id}")
    public void remove(@PathVariable Long id) {
        orderService.cancel(id);
    }

    @GetMapping("/{id}/items")
    public List<OrderItemVO> items(@PathVariable Long id) {
        return orderService.getOrder(id).getItems();
    }

    @GetMapping("/{id}/payments")
    public List<Map<String, Object>> payments(@PathVariable Long id) {
        return paymentService.getPayments(id);
    }

    @PostMapping("/{id}/payments")
    public Map<String, Object> pay(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String method = (String) body.get("method");
        long amount = ((Number) body.get("amount")).longValue();

        Map<String, Object> result = new HashMap<>();
        result.put("paymentId", paymentService.pay(id, method, amount));
        return result;
    }
}
