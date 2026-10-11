/**
 * 회원 화면 스크립트 (jQuery + XMLHttpRequest 레거시).
 */
var MemberPage = {

    // 아이디 중복 확인: $.getJSON
    checkLoginId: function (loginId) {
        $.getJSON('/member/checkLoginId.do', { loginId: loginId }, function (used) {
            document.getElementById('checkResult').innerText = used ? '사용 중' : '사용 가능';
        });
    },

    // 등급 변경: XMLHttpRequest 를 직접 쓴다
    updateGrade: function (memberId, gradeCd) {
        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/member/updateGrade.do', true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.onreadystatechange = function () {
            if (xhr.readyState === 4 && xhr.status === 200) {
                window.location.reload();
            }
        };
        xhr.send('memberId=' + encodeURIComponent(memberId) + '&gradeCd=' + encodeURIComponent(gradeCd));
    },

    // 회원 검색: $.ajax (method 옵션)
    search: function (keyword, callback) {
        $.ajax({
            url: '/member/search.do',
            method: 'GET',
            data: { keyword: keyword },
            success: callback
        });
    }
};
