package com.example.shop.member.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.shop.common.BizException;
import com.example.shop.common.CodeConstants;
import com.example.shop.common.PageRequest;
import com.example.shop.member.mapper.MemberMapper;
import com.example.shop.member.vo.MemberVO;
import com.example.shop.payment.DiscountPolicy;

/**
 * 회원 서비스 구현. 필드 주입(@Autowired, @Resource(name=...)).
 */
@Service("memberService")
public class MemberServiceImpl implements MemberService {

    @Autowired
    private MemberMapper memberMapper;

    /** 이름으로 주입: 두 구현체 중 defaultDiscountPolicy */
    @Resource(name = "defaultDiscountPolicy")
    private DiscountPolicy discountPolicy;

    @Override
    public List<MemberVO> findMembers(String keyword, PageRequest page) {
        Map<String, Object> param = new HashMap<>();
        param.put("keyword", keyword);
        param.put("startRow", page.startRow());
        param.put("endRow", page.endRow());
        return memberMapper.selectMemberList(param);
    }

    @Override
    public MemberVO getMember(Long memberId) {
        MemberVO member = memberMapper.selectMember(memberId);
        if (member == null) {
            throw new BizException("E404", CodeConstants.MSG_NOT_FOUND);
        }
        return member;
    }

    @Override
    public Long join(MemberVO member) {
        if (isLoginIdUsed(member.getLoginId())) {
            throw new BizException("E300", "이미 사용 중인 아이디입니다.");
        }
        memberMapper.insertMember(member);
        return member.getMemberId();
    }

    @Override
    public void changeGrade(Long memberId, String gradeCd) {
        getMember(memberId);
        memberMapper.updateMemberGrade(memberId, gradeCd);
    }

    @Override
    public boolean isLoginIdUsed(String loginId) {
        return memberMapper.countByLoginId(loginId) > 0;
    }

    @Override
    public int recalcGrades() {
        int updated = memberMapper.updateGradeByOrderAmt();
        int deleted = memberMapper.deleteDormantMembers();
        return updated + deleted;
    }

    /** 할인 미리보기 (인터페이스에 없는 메서드) */
    public long previewDiscount(Long memberId, long amount) {
        return discountPolicy.discount(getMember(memberId), amount);
    }
}
