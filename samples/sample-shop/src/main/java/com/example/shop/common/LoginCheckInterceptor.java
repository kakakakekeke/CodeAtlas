package com.example.shop.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 로그인 확인 인터셉터. WebConfig 에서 new 로 만들어 등록한다. (스프링 빈이 아니다)
 */
public class LoginCheckInterceptor implements HandlerInterceptor {

    public static final String SESSION_KEY = "loginMember";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SESSION_KEY) == null) {
            response.sendRedirect(request.getContextPath() + "/main.do");
            return false;
        }
        return true;
    }
}
