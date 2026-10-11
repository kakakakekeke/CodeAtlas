<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%-- 공통 머리말. 각 화면에서 <jsp:include> 로 불러 쓴다. --%>
<c:set var="ctx" value="${pageContext.request.contextPath}"/>
<div id="header">
    <h1><a href="${ctx}/main.do">샘플 쇼핑몰 관리</a></h1>
    <ul class="gnb">
        <li><a href="<c:url value='/order/list'/>">주문 관리</a></li>
        <li><a href="${pageContext.request.contextPath}/member/list">회원 관리</a></li>
        <li><a href="<c:url value='/order/form'/>">주문 등록</a></li>
    </ul>
    <%-- 주석 안의 링크는 요청이 아니다: <a href="/admin/secret.do">관리자</a> --%>
    <span class="login">
        <c:choose>
            <c:when test="${not empty sessionScope.loginMember}">
                <c:out value="${sessionScope.loginMember}"/> 님
            </c:when>
            <c:otherwise>로그인이 필요합니다</c:otherwise>
        </c:choose>
    </span>
</div>
<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
<script src="<c:url value='/resources/js/common.js'/>"></script>
