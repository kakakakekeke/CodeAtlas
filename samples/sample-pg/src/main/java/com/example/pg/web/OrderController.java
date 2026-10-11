package com.example.pg.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.pg.service.OrderService;

/**
 * 주문 REST API.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam Map<String, Object> cond) {
        return orderService.findOrders(cond);
    }

    @PostMapping
    public Long create(@RequestBody Map<String, Object> request) {
        return orderService.placeOrder(request);
    }

    @PostMapping("/{id}/close")
    public int close(@PathVariable("id") Long id, @RequestParam(required = false) String memo) {
        return orderService.closeOrder(id, memo);
    }
}
