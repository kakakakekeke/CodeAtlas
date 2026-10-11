<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<c:set var="ctx" value="${pageContext.request.contextPath}"/>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>주문 상세</title>
</head>
<body>
<jsp:include page="/WEB-INF/views/common/header.jsp">
    <jsp:param name="menu" value="order"/>
</jsp:include>

<h2>주문 상세 #<c:out value="${order.orderId}"/></h2>
<dl>
    <dt>주문자</dt><dd><c:out value="${order.member.memberNm}"/></dd>
    <dt>주문일자</dt><dd><c:out value="${order.orderDt}"/></dd>
    <dt>금액</dt><dd><fmt:formatNumber value="${order.totalAmt}" pattern="#,###"/></dd>
</dl>

<table>
    <thead><tr><th>순번</th><th>상품</th><th>수량</th><th>단가</th><th>금액</th></tr></thead>
    <tbody>
    <c:forEach var="item" items="${order.items}">
        <tr>
            <td>${item.itemSeq}</td>
            <td><c:out value="${item.productNm}"/></td>
            <td>${item.qty}</td>
            <td>${item.unitPrice}</td>
            <td>${item.amt}</td>
        </tr>
    </c:forEach>
    </tbody>
</table>

<h3>상태 이력</h3>
<ul id="history"></ul>

<h3>결제 내역</h3>
<ul id="payments"></ul>

<p>
    <a href="${ctx}/order/list">목록으로</a>
    <button type="button" id="btnCancel">주문 취소</button>
</p>

<script src="<c:url value='/resources/js/order.js'/>"></script>
<script>
    var orderId = ${order.orderId};

    // 이력: $.getJSON. 엔드포인트는 HTTP 메서드를 지정하지 않은 @RequestMapping("/history.do") 이다.
    $.getJSON('${ctx}/order/history.do', { orderId: orderId }, function (rows) {
        $.each(rows, function (i, row) {
            $('#history').append('<li>' + row.CHG_DT + ' ' + row.STATUS_NM + '</li>');
        });
    });

    // 결제 내역: fetch + 문자열 연결 URL (경로 변수 자리가 동적)
    fetch('/api/orders/' + orderId + '/payments')
        .then(function (res) { return res.json(); })
        .then(function (rows) {
            rows.forEach(function (p) {
                $('#payments').append('<li>' + p.PAY_METHOD_NM + ' ' + p.PAY_AMT + '</li>');
            });
        });

    $('#btnCancel').on('click', function () {
        OrderModule.cancel(orderId);
    });
</script>

<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</body>
</html>
