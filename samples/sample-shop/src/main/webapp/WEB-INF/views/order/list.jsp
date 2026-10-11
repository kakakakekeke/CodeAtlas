<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.text.SimpleDateFormat, java.util.Date" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%
    // 스크립틀릿: 오늘 날짜
    String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    request.setAttribute("today", today);
%>
<c:set var="ctx" value="${pageContext.request.contextPath}"/>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>주문 목록</title>
</head>
<body>
<jsp:include page="/WEB-INF/views/common/header.jsp"/>

<h2>주문 목록 <small>(<%= today %> 기준, 총 <c:out value="${totalCount}"/> 건)</small></h2>

<%-- 검색 폼: GET /order/list --%>
<form id="searchForm" action="<c:url value='/order/list'/>" method="get">
    <label>회원명 <input type="text" name="memberNm" value="<c:out value='${cond.memberNm}'/>"></label>
    <label>정렬
        <select name="sortColumn">
            <option value="O.ORDER_ID DESC">주문번호</option>
            <option value="O.ORDER_DT DESC">주문일자</option>
            <option value="O.TOTAL_AMT DESC">금액</option>
        </select>
    </label>
    <button type="submit">검색</button>
    <a href="${ctx}/order/form">주문 등록</a>
</form>

<table id="orderTable">
    <thead>
    <tr><th>주문번호</th><th>주문일자</th><th>회원</th><th>상태</th><th>금액</th><th>처리</th></tr>
    </thead>
    <tbody>
    <c:forEach var="o" items="${orders}" varStatus="st">
        <tr data-order-id="${o.orderId}">
            <td><a href="${ctx}/order/detail?orderId=${o.orderId}"><c:out value="${o.orderId}"/></a></td>
            <td><c:out value="${o.orderDt}"/></td>
            <td><c:out value="${o.member.memberNm}"/></td>
            <td><c:out value="${o.status.label}"/></td>
            <td><fmt:formatNumber value="${o.totalAmt}" pattern="#,###"/></td>
            <td>
                <c:if test="${o.closeYn ne 'Y'}">
                    <button type="button" class="btnClose">마감</button>
                    <button type="button" class="btnCancel">취소</button>
                </c:if>
            </td>
        </tr>
    </c:forEach>
    <c:if test="${empty orders}">
        <tr><td colspan="6">조회된 주문이 없습니다.</td></tr>
    </c:if>
    </tbody>
</table>

<script src="${ctx}/resources/js/order.js"></script>
<script type="text/javascript">
    $(function () {
        // 마감: jQuery $.post + <c:url>
        $('#orderTable').on('click', '.btnClose', function () {
            var orderId = $(this).closest('tr').data('orderId');
            $.post('<c:url value="/order/close.do"/>', { orderId: orderId }, function (res) {
                if (res.resultCd === '00') {
                    location.reload();
                } else {
                    alert('마감 실패: ' + res.resultCd);
                }
            });
        });

        // 취소: 외부 스크립트(order.js)의 모듈 함수 호출
        $('#orderTable').on('click', '.btnCancel', function () {
            OrderModule.cancel($(this).closest('tr').data('orderId'));
        });
    });
</script>

<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</body>
</html>
