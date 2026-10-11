<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>주문 등록</title>
</head>
<body>
<jsp:include page="/WEB-INF/views/common/header.jsp"/>

<h2>주문 등록</h2>

<%-- 등록 폼: POST /order/save --%>
<form id="orderForm" action="${pageContext.request.contextPath}/order/save" method="post">
    <p>
        <label>회원 검색 <input type="text" id="memberKeyword"></label>
        <button type="button" id="btnSearchMember">검색</button>
        <select name="memberId" id="memberId"></select>
    </p>
    <p>
        <label>상품
            <select name="items[0].productId" id="productId">
                <c:forEach var="p" items="${products}">
                    <option value="${p.productId}" data-price="${p.price}"><c:out value="${p.productNm}"/></option>
                </c:forEach>
            </select>
        </label>
        <label>수량 <input type="number" name="items[0].qty" value="1" min="1"></label>
        <input type="hidden" name="items[0].unitPrice" id="unitPrice" value="0">
    </p>
    <button type="submit">저장</button>
    <a href="<c:url value='/order/list'/>">취소</a>
</form>

<script>
    $(function () {
        // 회원 검색: $.ajax (type 을 지정하지 않으면 GET)
        $('#btnSearchMember').on('click', function () {
            $.ajax({
                url: '<c:url value="/member/search.do"/>',
                data: { keyword: $('#memberKeyword').val() },
                dataType: 'json',
                success: function (members) {
                    var $sel = $('#memberId').empty();
                    $.each(members, function (i, m) {
                        $sel.append($('<option>').val(m.memberId).text(m.memberNm));
                    });
                }
            });
        });

        // 상품을 고르면 단가를 채운다: $.get + 문자열 연결 URL
        $('#productId').on('change', function () {
            $.get('/api/products/' + $(this).val(), function (product) {
                $('#unitPrice').val(product.price);
            });
        });
    });
</script>

<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</body>
</html>
