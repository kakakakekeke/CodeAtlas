/**
 * 주문 화면 스크립트 (jQuery 기반 레거시).
 * IIFE 모듈 패턴과 객체 리터럴 메서드를 쓴다.
 */
var OrderModule = (function ($) {
    'use strict';

    var BASE = '/order';

    // 모듈 내부 함수
    function reload() {
        window.location.reload();
    }

    // 취소: $.ajax 에 URL 리터럴을 직접 쓴다
    function cancel(orderId) {
        if (!confirm('주문을 취소하시겠습니까?')) {
            return;
        }
        $.ajax({
            url: '/order/cancel.do',
            type: 'POST',
            data: { orderId: orderId },
            dataType: 'json',
            success: function (res) {
                if (res.success === 'Y') {
                    reload();
                }
            },
            error: showError
        });
    }

    // 마감: 공통 래퍼(common.js 의 ajaxPost)를 거친다. 실제 요청은 래퍼 안에서 나간다.
    function close(orderId) {
        ajaxPost(BASE + '/close.do', { orderId: orderId }, function (res) {
            if (res.resultCd === '00') {
                reload();
            }
        });
    }

    return {
        cancel: cancel,
        close: close,
        // 객체 리터럴에 바로 쓴 함수
        loadHistory: function (orderId, callback) {
            $.getJSON('/order/history.do', { orderId: orderId }, callback);
        }
    };
})(jQuery);

// 객체 리터럴 메서드 (함수 표현식 프로퍼티와 메서드 축약)
var OrderGrid = {
    rows: [],

    render: function (rows) {
        this.rows = rows;
        var html = rows.map(function (row) {
            return '<tr><td>' + row.orderId + '</td><td>' + formatAmt(row.totalAmt) + '</td></tr>';
        }).join('');
        $('#orderGrid tbody').html(html);
    },

    refresh() {
        var self = this;
        // 주석 속 요청은 요청이 아니다: $.get('/order/old-list.do')
        $.get('/api/orders', { page: 1, size: 50 }, function (body) {
            self.render(body.items);
        });
    }
};
