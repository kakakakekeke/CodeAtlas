/**
 * 공통 스크립트 (jQuery 기반 레거시).
 * 함수 선언, 함수 표현식, 범용 AJAX 래퍼가 있다.
 */

// 함수 선언
function getContextPath() {
    var path = window.location.pathname;
    var idx = path.indexOf('/', 1);
    return idx > 0 && path.indexOf('/shop') === 0 ? path.substring(0, idx) : '';
}

// 함수 선언
function formatAmt(value) {
    if (value === null || value === undefined) {
        return '0';
    }
    return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

// 함수 표현식
var showError = function (xhr) {
    var message = xhr && xhr.responseJSON ? xhr.responseJSON.message : '처리 중 오류가 발생했습니다.';
    alert(message);
};

/**
 * 범용 POST 래퍼.
 * URL 이 인자로 들어오므로 이 지점에서는 어떤 엔드포인트를 부르는지 정적으로 알 수 없다.
 */
function ajaxPost(url, data, callback) {
    $.ajax({
        url: url,
        type: 'POST',
        data: data,
        dataType: 'json',
        success: callback,
        error: showError
    });
}

$(document).ajaxError(function (event, xhr) {
    if (xhr.status === 401) {
        location.href = getContextPath() + '/main.do';
    }
});
