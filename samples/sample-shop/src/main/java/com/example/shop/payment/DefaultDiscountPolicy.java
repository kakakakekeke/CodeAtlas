package com.example.shop.payment;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.example.shop.member.mapper.MemberMapper;
import com.example.shop.member.vo.MemberVO;

/**
 * 기본 할인 정책: 회원 등급의 할인율을 적용한다.
 */
@Component("defaultDiscountPolicy")
@Primary
public class DefaultDiscountPolicy implements DiscountPolicy {

    @Autowired
    protected MemberMapper memberMapper;

    @Override
    public long discount(MemberVO member, long amount) {
        if (member == null || member.getMemberId() == null) {
            return amount;
        }
        Map<String, Object> row = memberMapper.selectMemberDiscount(member.getMemberId());
        double rate = row == null ? 0 : ((Number) row.get("DISCOUNT_RATE")).doubleValue();
        return Math.round(amount * (1 - rate / 100.0));
    }
}
