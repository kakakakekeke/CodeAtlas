package com.example.shop.order.service;

import static com.example.shop.common.CodeConstants.isClosed;

import java.util.List;
import java.util.Map;

import com.example.shop.common.OrderStatus;
import com.example.shop.common.PageRequest;
import com.example.shop.order.vo.OrderSearchCond;
import com.example.shop.order.vo.OrderVO;

/**
 * 주문 서비스 인터페이스. 구현체는 OrderServiceImpl 하나다.
 */
public interface OrderService {

    List<OrderVO> findOrders(OrderSearchCond cond, PageRequest page);

    int countOrders(OrderSearchCond cond);

    OrderVO getOrder(Long orderId);

    Long placeOrder(OrderVO order);

    void changeStatus(Long orderId, OrderStatus status, String userId);

    /** 주문 ID 로 취소 (오버로드 1) */
    void cancel(Long orderId);

    /** 주문 객체로 취소 (오버로드 2). 인자 타입으로만 구분된다. */
    void cancel(OrderVO order);

    String closeOrder(Long orderId, String userId);

    List<Map<String, Object>> getHistory(Long orderId);

    List<Map<String, Object>> export(String type);

    /** default 메서드: 취소할 수 있는 주문인지. static import 한 isClosed 를 쓴다. */
    default boolean isCancelable(OrderVO order) {
        return order != null && !isClosed(order.getStatusCode());
    }
}
