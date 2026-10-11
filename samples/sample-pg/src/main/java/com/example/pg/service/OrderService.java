package com.example.pg.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.pg.mapper.CustomerMapper;
import com.example.pg.mapper.OrderMapper;

/**
 * 주문 서비스. 인터페이스 없이 클래스 하나다.
 */
@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final CustomerMapper customerMapper;

    public OrderService(OrderMapper orderMapper, CustomerMapper customerMapper) {
        this.orderMapper = orderMapper;
        this.customerMapper = customerMapper;
    }

    public List<Map<String, Object>> findOrders(Map<String, Object> cond) {
        return orderMapper.selectOrderPage(cond);
    }

    /** 고객을 등록·갱신하고 주문과 주문 상세를 넣는다. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Long placeOrder(Map<String, Object> request) {
        Long customerId = customerMapper.upsertCustomer(request);
        Map<String, Object> order = new HashMap<>(request);
        order.put("customerId", customerId);
        orderMapper.insertOrder(order);
        Long orderId = (Long) order.get("id");
        List<Map<String, Object>> items = (List<Map<String, Object>>) request.get("items");
        orderMapper.insertOrderItems(orderId, items);
        customerMapper.putSession("order:" + orderId, "{}");
        return orderId;
    }

    /** 주문 마감은 DB 함수 close_order 가 한다. */
    public int closeOrder(Long orderId, String memo) {
        Integer closed = orderMapper.callCloseOrder(orderId, memo);
        return closed == null ? 0 : closed;
    }

    public List<Map<String, Object>> timeline(Long orderId) {
        return orderMapper.selectTimeline(orderId);
    }

    /** 야간 정리: 결제 반영, 취소 주문의 결제 삭제, 오래된 주문 보관(프로시저). */
    public int nightlyMaintenance(LocalDate before) {
        orderMapper.markPaidOrders();
        int deleted = orderMapper.deleteCancelledPayments(before);
        Map<String, Object> param = new HashMap<>();
        param.put("before", before);
        param.put("moved", 0);
        orderMapper.callArchiveOrders(param);
        return deleted;
    }
}
