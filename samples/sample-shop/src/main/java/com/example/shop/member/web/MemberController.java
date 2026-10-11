package com.example.shop.member.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import com.example.shop.common.PageRequest;
import com.example.shop.member.service.MemberService;
import com.example.shop.member.vo.MemberVO;

/**
 * 회원 화면 컨트롤러. 클래스 수준 @RequestMapping 이 없다.
 */
@Controller
public class MemberController {

    @Resource(name = "memberService")
    private MemberService memberService;

    /** 메인 화면. ModelAndView 로 뷰 이름을 준다. */
    @GetMapping("/main.do")
    public ModelAndView main() {
        ModelAndView mav = new ModelAndView("main");
        mav.addObject("recentMembers", memberService.findMembers(null, PageRequest.of(1, 5)));
        return mav;
    }

    /** 회원 목록 화면 */
    @GetMapping("/member/list")
    public String memberList(@RequestParam(required = false) String keyword,
                             @RequestParam(defaultValue = "1") int page,
                             Model model) {
        model.addAttribute("members", memberService.findMembers(keyword, PageRequest.of(page, 20)));
        model.addAttribute("keyword", keyword);
        return "member/list";
    }

    /** 회원 검색 (AJAX) */
    @GetMapping("/member/search.do")
    @ResponseBody
    public List<MemberVO> search(@RequestParam String keyword) {
        return memberService.findMembers(keyword, PageRequest.of(1, 10));
    }

    /** 등급 변경 (AJAX) */
    @PostMapping("/member/updateGrade.do")
    @ResponseBody
    public Map<String, Object> updateGrade(@RequestParam Long memberId, @RequestParam String gradeCd) {
        memberService.changeGrade(memberId, gradeCd);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    /** 아이디 중복 확인 (AJAX) */
    @GetMapping("/member/checkLoginId.do")
    @ResponseBody
    public boolean checkLoginId(@RequestParam String loginId) {
        return memberService.isLoginIdUsed(loginId);
    }
}
