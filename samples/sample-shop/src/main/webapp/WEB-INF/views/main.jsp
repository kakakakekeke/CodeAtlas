<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>메인</title>
</head>
<body>
<jsp:include page="/WEB-INF/views/common/header.jsp"/>

<h2>최근 가입 회원</h2>
<ul>
    <c:forEach var="m" items="${recentMembers}">
        <li><c:out value="${m.memberNm}"/> (<c:out value="${m.loginId}"/>)</li>
    </c:forEach>
</ul>

<h2>안전 재고 미달 상품</h2>
<ul id="lowStock"></ul>

<h2>카테고리</h2>
<div id="categoryTree"></div>

<script>
    var ctx = '${pageContext.request.contextPath}';

    // fetch (GET)
    fetch('/api/products/low-stock?limit=5')
        .then(function (res) { return res.json(); })
        .then(function (rows) {
            rows.forEach(function (p) {
                $('#lowStock').append('<li>' + p.productNm + ' : ' + p.stockQty + '</li>');
            });
        });

    // jQuery getJSON, 컨텍스트 경로 변수를 앞에 붙인 URL
    $.getJSON(ctx + '/api/products/categories/tree', function (tree) {
        $('#categoryTree').text(JSON.stringify(tree));
    });
</script>

<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</body>
</html>
