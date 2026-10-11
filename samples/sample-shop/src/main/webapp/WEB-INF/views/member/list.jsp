<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<c:set var="ctx" value="${pageContext.request.contextPath}"/>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>회원 목록</title>
</head>
<body>
<jsp:include page="/WEB-INF/views/common/header.jsp"/>

<h2>회원 목록</h2>

<%-- 검색 폼: method 를 쓰지 않았으므로 GET --%>
<form action="${ctx}/member/list">
    <input type="text" name="keyword" value="<c:out value='${keyword}'/>">
    <button type="submit">검색</button>
</form>

<p>
    <label>아이디 중복 확인 <input type="text" id="loginId"></label>
    <button type="button" onclick="MemberPage.checkLoginId(document.getElementById('loginId').value)">확인</button>
    <span id="checkResult"></span>
</p>

<table>
    <thead><tr><th>번호</th><th>아이디</th><th>이름</th><th>등급</th><th>변경</th></tr></thead>
    <tbody>
    <c:forEach var="m" items="${members}">
        <tr>
            <td>${m.memberId}</td>
            <td><c:out value="${m.loginId}"/></td>
            <td><c:out value="${m.memberNm}"/></td>
            <td><c:out value="${m.gradeCd}"/></td>
            <td>
                <button type="button" onclick="MemberPage.updateGrade(${m.memberId}, 'VIP')">VIP 로</button>
                <button type="button" onclick="MemberPage.updateGrade(${m.memberId}, 'NORMAL')">일반으로</button>
            </td>
        </tr>
    </c:forEach>
    </tbody>
</table>

<script src="${ctx}/resources/js/member.js"></script>

<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</body>
</html>
