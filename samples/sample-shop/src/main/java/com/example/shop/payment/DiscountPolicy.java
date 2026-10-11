package com.example.shop.payment;

import com.example.shop.member.vo.MemberVO;

/**
 * 할인 정책. 구현체(빈)가 둘이다: defaultDiscountPolicy, vipDiscountPolicy.
 */
public interface DiscountPolicy {

    /** 할인 적용 후 금액 */
    long discount(MemberVO member, long amount);

    default String policyName() {
        return getClass().getSimpleName();
    }
}
