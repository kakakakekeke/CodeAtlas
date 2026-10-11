package com.example.shop.member.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.example.shop.member.vo.MemberVO;

/**
 * 회원 매퍼. 대부분은 mapper/member/MemberMapper.xml 에 있고, 두 건은 애너테이션 SQL 이다.
 */
@Mapper
public interface MemberMapper {

    List<MemberVO> selectMemberList(Map<String, Object> param);

    MemberVO selectMember(Long memberId);

    List<Map<String, Object>> selectMemberOrders(@Param("memberId") Long memberId);

    int insertMember(MemberVO member);

    int updateMemberGrade(@Param("memberId") Long memberId, @Param("gradeCd") String gradeCd);

    int updateGradeByOrderAmt();

    int deleteDormantMembers();

    /** 애너테이션 SQL (한 줄) */
    @Select("SELECT COUNT(*) FROM TB_MEMBER WHERE LOGIN_ID = #{loginId}")
    int countByLoginId(String loginId);

    /** 애너테이션 SQL (문자열 배열. MyBatis 가 공백으로 이어 붙인다) */
    @Select({
        "SELECT M.MEMBER_ID, M.MEMBER_NM, G.GRADE_NM, G.DISCOUNT_RATE",
        "  FROM TB_MEMBER M",
        "  JOIN TB_MEMBER_GRADE G ON G.GRADE_CD = M.GRADE_CD",
        " WHERE M.MEMBER_ID = #{memberId}"
    })
    Map<String, Object> selectMemberDiscount(Long memberId);
}
