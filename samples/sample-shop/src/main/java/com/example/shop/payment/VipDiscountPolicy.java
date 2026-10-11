package com.example.shop.payment;

import org.springframework.stereotype.Component;

import com.example.shop.member.vo.MemberVO;

/**
 * VIP 할인 정책: 기본 할인에 정액 할인을 더한다. 상위 메서드를 super 로 부른다.
 */
@Component("vipDiscountPolicy")
public class VipDiscountPolicy extends DefaultDiscountPolicy {

    private static final long VIP_EXTRA = 1000L;

    @Override
    public long discount(MemberVO member, long amount) {
        long base = super.discount(member, amount);
        return Math.max(0L, base - VIP_EXTRA);
    }

    @Override
    public String policyName() {
        return "VIP";
    }
}
