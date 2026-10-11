package com.example.shop.payment;

import java.util.List;
import java.util.Map;

/**
 * 결제 서비스 인터페이스.
 */
public interface PaymentService {

    /** 결제하고 결제 ID 를 돌려준다. */
    long pay(Long orderId, String method, long amount);

    void refund(Long orderId);

    List<Map<String, Object>> getPayments(Long orderId);
}
