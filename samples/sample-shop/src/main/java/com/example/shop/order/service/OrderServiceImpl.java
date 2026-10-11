package com.example.shop.order.service;

import static com.example.shop.common.CodeConstants.DEFAULT_USER;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.shop.common.BaseService;
import com.example.shop.common.BizException;
import com.example.shop.common.OrderStatus;
import com.example.shop.common.PageRequest;
import com.example.shop.order.dao.OrderDao;
import com.example.shop.order.mapper.OrderMapper;
import com.example.shop.order.vo.OrderItemVO;
import com.example.shop.order.vo.OrderSearchCond;
import com.example.shop.order.vo.OrderVO;
import com.example.shop.payment.DiscountPolicy;
import com.example.shop.payment.PaymentService;
import com.example.shop.product.service.ProductService;

/**
 * 주문 서비스 구현.
 * 제네릭 상위 클래스 상속, 생성자 주입(@Qualifier 포함), 오버로드, 람다, 메서드 참조, 익명/내부 클래스.
 */
@Service("orderService")
public class OrderServiceImpl extends BaseService<OrderVO> implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderDao orderDao;
    private final DiscountPolicy discountPolicy;
    private final ProductService productService;
    private final PaymentService paymentService;

    /** 생성자 주입. DiscountPolicy 구현체가 둘이라 @Qualifier 로 고른다. */
    public OrderServiceImpl(OrderMapper orderMapper,
                            OrderDao orderDao,
                            @Qualifier("vipDiscountPolicy") DiscountPolicy discountPolicy,
                            ProductService productService,
                            PaymentService paymentService) {
        this.orderMapper = orderMapper;
        this.orderDao = orderDao;
        this.discountPolicy = discountPolicy;
        this.productService = productService;
        this.paymentService = paymentService;
    }

    // ------------------------------------------------------ BaseService 구현/재정의

    @Override
    protected OrderVO doFind(Long id) {
        return orderMapper.selectOrderDetail(id);
    }

    @Override
    protected void validate(OrderVO vo) {
        super.validate(vo);
        if (vo.getItems() == null || vo.getItems().isEmpty()) {
            throw new BizException("E100", "주문 상품이 없습니다.");
        }
    }

    // ------------------------------------------------------ OrderService 구현

    @Override
    public List<OrderVO> findOrders(OrderSearchCond cond, PageRequest page) {
        cond.setStartRow(page.startRow());
        cond.setEndRow(page.endRow());
        List<OrderVO> orders = orderMapper.selectOrderList(cond);

        // 람다
        orders.forEach(o -> o.setStatus(OrderStatus.fromCode(o.getStatusCode())));

        // 익명 클래스
        Collections.sort(orders, new Comparator<OrderVO>() {
            @Override
            public int compare(OrderVO a, OrderVO b) {
                return b.getOrderId().compareTo(a.getOrderId());
            }
        });
        return orders;
    }

    @Override
    public int countOrders(OrderSearchCond cond) {
        return orderMapper.selectOrderCount(cond);
    }

    @Override
    public OrderVO getOrder(Long orderId) {
        OrderVO order = getOrThrow(orderId);
        order.setItems(orderMapper.selectOrderItems(orderId));
        return order;
    }

    @Override
    @Transactional
    public Long placeOrder(OrderVO order) {
        validate(order);

        long amount = new AmountCalculator().total(order.getItems());
        order.setTotalAmt(discountPolicy.discount(order.getMember(), amount));
        order.setStatusCode(OrderStatus.ORDERED.getCode());
        audit(order, DEFAULT_USER);

        orderMapper.insertOrder(order);
        orderMapper.insertOrderItems(order.getOrderId(), order.getItems());

        for (OrderItemVO item : order.getItems()) {
            productService.decreaseStock(item.getProductId(), item.getQty());
        }

        orderDao.insertOrderHist(order.getOrderId(), order.getRegId());
        return order.getOrderId();
    }

    @Override
    @Transactional
    public void changeStatus(Long orderId, OrderStatus status, String userId) {
        orderMapper.updateOrderStatus(orderId, status.getCode(), userId);
        if (status == OrderStatus.SHIPPED) {
            orderDao.updateDeliveryShip(orderId);
        }
    }

    /** 오버로드 1: ID 로 조회한 뒤 오버로드 2 를 부른다. (자기 자신을 다시 부르는 것이 아니다) */
    @Override
    public void cancel(Long orderId) {
        cancel(getOrThrow(orderId));
    }

    /** 오버로드 2 */
    @Override
    @Transactional
    public void cancel(OrderVO order) {
        if (!isCancelable(order)) {
            throw new BizException("E101", "취소할 수 없는 주문입니다.");
        }
        orderDao.callCancelOrder(order.getOrderId(), DEFAULT_USER);
        paymentService.refund(order.getOrderId());
    }

    @Override
    public String closeOrder(Long orderId, String userId) {
        Map<String, Object> param = new HashMap<>();
        param.put("orderId", orderId);
        param.put("userId", userId);
        orderMapper.callCloseOrder(param);
        return (String) param.get("resultCd");
    }

    @Override
    public List<Map<String, Object>> getHistory(Long orderId) {
        return orderDao.selectOrderHistory(orderId);
    }

    /**
     * 내보내기. 구문 ID 를 문자열 조합으로 만들기 때문에 실행되는 구문을 정적으로 알 수 없다.
     */
    @Override
    public List<Map<String, Object>> export(String type) {
        String queryId = "orderDao.select" + type;
        return orderDao.selectByQueryId(queryId, new HashMap<String, Object>());
    }

    // ------------------------------------------------------ 그 밖

    /** 메서드 참조 */
    public List<Long> findOrderIds(Long memberId) {
        Map<String, Object> param = new HashMap<>();
        param.put("memberId", memberId);
        return orderDao.selectOrders(param).stream()
                .map(OrderVO::getOrderId)
                .collect(Collectors.toList());
    }

    /** 내부 클래스 (static 아님): 주문 금액 합계 */
    private class AmountCalculator {

        long total(List<OrderItemVO> items) {
            return items.stream().mapToLong(OrderItemVO::getAmt).sum();
        }
    }
}
