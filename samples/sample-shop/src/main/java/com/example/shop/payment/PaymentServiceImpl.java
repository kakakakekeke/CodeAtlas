package com.example.shop.payment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.shop.order.dao.OrderDao;

/**
 * 결제 서비스 구현.
 * 애너테이션이 없다. applicationContext.xml 의 &lt;bean id="paymentService"&gt; 로 등록되고
 * 생성자 인자(constructor-arg)와 setter(property)로 주입받는다.
 */
public class PaymentServiceImpl implements PaymentService {

    private final DiscountPolicy discountPolicy;
    private OrderDao orderDao;

    public PaymentServiceImpl(DiscountPolicy discountPolicy) {
        this.discountPolicy = discountPolicy;
    }

    public void setOrderDao(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    @Override
    public long pay(Long orderId, String method, long amount) {
        Map<String, Object> payment = new HashMap<>();
        payment.put("orderId", orderId);
        payment.put("payMethodCd", method);
        payment.put("payAmt", discountPolicy.discount(null, amount));

        orderDao.insertPayment(payment);
        Long paymentId = ((Number) payment.get("paymentId")).longValue();

        orderDao.updateOrderByPayment(paymentId);
        return paymentId;
    }

    @Override
    public void refund(Long orderId) {
        orderDao.cancelPayment(orderId);
    }

    @Override
    public List<Map<String, Object>> getPayments(Long orderId) {
        return orderDao.selectPaymentsByOrder(orderId);
    }
}
