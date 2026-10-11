package com.example.shop.member.service;

import java.util.List;

import com.example.shop.common.PageRequest;
import com.example.shop.member.vo.MemberVO;

/**
 * 회원 서비스 인터페이스.
 */
public interface MemberService {

    List<MemberVO> findMembers(String keyword, PageRequest page);

    MemberVO getMember(Long memberId);

    Long join(MemberVO member);

    void changeGrade(Long memberId, String gradeCd);

    boolean isLoginIdUsed(String loginId);

    /** 등급 재산정 + 휴면 회원 정리. 처리 건수를 돌려준다. */
    int recalcGrades();
}
