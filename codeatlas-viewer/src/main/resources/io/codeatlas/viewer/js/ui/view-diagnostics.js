// 진단·미해결 화면: 미해결 참조(엣지 하나가 한 건)와 진단을 따로 보인다. 분류와 코드로 거른다.
// report.md의 "Java 호출자가 없는 매퍼 구문", "대상 테이블을 정적으로 알 수 없는 구문", "DDL에 없는 테이블", "데이터 적재 스크립트",
// "SQL과 DDL의 컬럼 불일치", "화면 요청"에 해당하는 목록도 보기로 둔다.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var D = CA.diagnostics;
  var ui = CA.ui;
  var h = ui.h;
  CA.views = CA.views || {};

  // 화면을 열 때마다 다시 모으지 않게 색인에 붙여 둔다.
  function collected(index) {
    if (!index._diagnostics) index._diagnostics = D.collect(index);
    return index._diagnostics;
  }

  CA.views.diagnostics = {
    title: '진단·미해결',
    /** 탭에 붙일 개수: 미해결 참조 수와 진단 수. */
    badges: function (app) {
      var data = collected(app.index);
      return [
        { text: '미해결 ' + ui.formatCount(data.unresolved.length), title: '해석하지 못한 참조. 엣지 하나를 한 건으로 센다', alert: data.unresolved.length > 0 },
        { text: '진단 ' + ui.formatCount(data.diagnostics.length), title: '분석 중에 남긴 진단', alert: data.diagnostics.some(function (d) { return d.severity === 'ERROR'; }) }
      ];
    },
    create: function (app) {
      var index = app.index;
      var data = collected(index);
      var mode = data.unresolved.length || !data.diagnostics.length ? 'unresolved' : 'diagnostics';
      var picked = { categories: new Set(), codes: new Set(), severities: new Set() };
      var texts = {};
      D.VIEWS.forEach(function (v) { texts[v.key] = ''; });
      function viewOf(key) {
        for (var i = 0; i < D.VIEWS.length; i++) if (D.VIEWS[i].key === key) return D.VIEWS[i];
        return D.VIEWS[0];
      }
      var textInput = h('input', { class: 'input', type: 'search', attrs: { autocomplete: 'off', spellcheck: 'false' } });
      var summary = h('span', { class: 'muted diag-summary' });
      var filters = h('div', { class: 'pane-head diag-filters' });
      var header = h('div', { class: 'vrow dhead' });
      var detail = h('div', { class: 'diag-detail', attrs: { style: 'display:none' } });

      function renderUnresolved(r) {
        var row = h('div', { class: 'vrow drow clickable', title: r.fromLabel + ' -' + r.edgeKind + '→ ' + r.target }, [
          h('span', { class: 'code', text: r.categoryLabel, title: '분류: ' + r.categoryLabel }),
          h('span', { class: 'from', text: r.fromId ? CA.graph.shortLabel(index.node(r.fromId), r.fromId) : '(참조한 곳 없음)', title: r.fromLabel }),
          h('span', { class: 'rel', text: r.edgeKind }),
          h('span', { class: 'msg', title: r.target + (r.note ? ' (' + r.note + ')' : '') }, [r.target,
            r.note ? h('span', { class: 'note', text: r.note, title: '미해결 호출의 원문은 인자를 적지 않는다(name(…)). 인자 수는 엣지의 argCount다' }) : null]),
          h('span', { class: 'loc', text: r.location || '', title: r.location || '위치 없음' })
        ]);
        row.addEventListener('click', function () { app.showDetail(r.nodeId); });
        return row;
      }
      function renderUncalled(r) {
        var executors = r.executors.map(function (m) { return m.label; }).join(', ');
        var row = h('div', { class: 'vrow drow clickable', title: r.id + ' ← ' + executors }, [
          h('span', { class: 'stmt', text: r.statementLabel, title: r.id }),
          h('span', { class: 'msg', text: '← ' + executors, title: '이 구문을 실행하는 메서드. 이 메서드를 부르는 곳이 없다' }),
          h('span', { class: 'loc', text: r.location || '', title: r.location || '위치 없음' })
        ]);
        row.addEventListener('click', function () { app.showDetail(r.nodeId); });
        return row;
      }
      function renderDynamic(r) {
        var row = h('div', { class: 'vrow drow clickable', title: r.id }, [
          h('span', { class: 'stmt', text: CA.graph.idRemainder(r.id), title: r.id }),
          h('span', { class: 'access', text: r.access, title: '접근 종류 (CRUD 매트릭스의 (동적) 열과 같다)' }),
          h('span', { class: 'msg', text: r.text || '', title: r.text || '알아낸 원문 조각 없음' }),
          h('span', { class: 'loc', text: r.location || '', title: r.location || '위치 없음' })
        ]);
        row.addEventListener('click', function () { app.showDetail(r.nodeId); });
        return row;
      }
      /** 진단 한 건의 전체 메시지. 요약 진단(이름별 건수를 한 건에 모은 것 등)은 한 줄에 다 보이지 않는다. */
      function showMessage(r) {
        var description = D.codeDescription(r.code);
        var parts = [
          h('div', { class: 'diag-detail-head' }, [h('span', { class: 'sev ' + r.severity, text: r.severity }), h('span', { class: 'code', text: r.code }),
            h('span', { class: 'loc', text: r.where }), h('span', { class: 'spacer' }),
            h('button', { class: 'btn small', type: 'button', text: '닫기', on: { click: function () { detail.style.display = 'none'; } } })]),
          h('div', { class: 'diag-detail-msg', text: r.message })
        ];
        if (description) parts.push(h('div', { class: 'hint', text: r.code + ': ' + description }));
        detail.replaceChildren.apply(detail, parts);
        detail.style.display = '';
      }
      function renderDiagnostic(r) {
        var row = h('div', { class: 'vrow drow clickable', title: r.message }, [
          h('span', { class: 'sev ' + r.severity, text: r.severity }),
          h('span', { class: 'code', text: r.code, title: D.codeDescription(r.code) || r.code }),
          h('span', { class: 'msg', text: r.message }),
          r.unresolved ? h('span', { class: 'tag', text: '미해결 ' + r.unresolved.categoryLabel, title: '같은 지점의 미해결 참조가 "미해결 참조" 목록에 있다. 누르면 참조한 노드가 열린다' }) : null,
          h('span', { class: 'loc' + (r.location ? '' : ' muted'), text: r.where, title: r.location ? r.where : '위치가 없는 진단이다(분석 전체를 요약한 한 건)' })
        ]);
        row.addEventListener('click', function () {
          showMessage(r);
          if (r.nodeId) app.showDetail(r.nodeId);
        });
        return row;
      }
      function renderUndeclared(r) {
        var names = r.statements.map(function (x) { return x.label; }).join(', ') + (r.statementCount > r.statements.length ? ' 외 ' + (r.statementCount - r.statements.length) + '개' : '');
        var row = h('div', { class: 'vrow drow clickable', title: r.id }, [
          h('span', { class: 'stmt', text: r.name, title: r.id }),
          h('span', { class: 'access', text: String(r.statementCount), title: '참조하는 구문 수(데이터 적재 구문은 뺀 수)' }),
          h('span', { class: 'msg', text: names, title: names || '참조하는 구문 없음' }),
          r.note ? h('span', { class: 'tag' + (r.suspicious ? ' warn' : ''), text: r.note,
            title: r.suspicious ? '참조하는 구문이 하나뿐이다: 오타이거나, 구문을 끝까지 파싱하지 못해 복구하면서 나온 이름일 수 있다' : '데이터 적재 구문(스크립트의 INSERT)만 이 테이블을 참조한다' }) : null
        ]);
        row.addEventListener('click', function () { app.showDetail(r.nodeId); });
        return row;
      }
      function renderDataLoad(r) {
        var row = h('div', { class: 'vrow drow clickable', title: r.file }, [
          h('span', { class: 'stmt', text: r.file + (r.borrowedNote ? ' (' + r.borrowedNote + ')' : ''),
            title: r.borrowedNote ? 'borrowedFrom: 고른 방언의 파일에 그 테이블의 적재가 없어 다른 방언 파일의 묶음을 남겼다' : r.file }),
          h('span', { class: 'access', text: String(r.tableCount), title: '이 파일이 적재하는 테이블 수' }),
          h('span', { class: 'access', text: r.rowsText, title: '행 수(rowCount의 합). 행 수를 모르는 구문이 섞이면 "이상"이 붙는다' }),
          h('span', { class: 'msg', text: r.tables.join(', '), title: r.tables.join(', ') })
        ]);
        row.addEventListener('click', function () { app.showDetail(r.nodeId); });
        return row;
      }
      /** DDL에 없는데 SQL이 참조하는 컬럼 한 건: 테이블, 컬럼, 구문 수, 참조하는 구문(5개까지). 누르면 그 컬럼의 상세가 열린다. */
      function renderColumnMismatch(r) {
        var names = r.statements.map(function (x) { return x.label; }).join(', ') + (r.statementCount > r.statements.length ? ' 외 ' + (r.statementCount - r.statements.length) + '개' : '');
        var row = h('div', { class: 'vrow drow clickable', title: r.id }, [
          h('span', { class: 'stmt', text: r.table, title: r.tableId }),
          h('span', { class: 'from', text: r.column, title: 'DDL에 없는 컬럼(declared=false). SQL에서만 보인다' }),
          h('span', { class: 'access', text: String(r.statementCount), title: '이 컬럼을 읽거나 쓰는 구문 수' }),
          h('span', { class: 'msg', text: names, title: names || '참조하는 구문 없음' })
        ]);
        row.addEventListener('click', function () { app.showDetail(r.nodeId); });
        return row;
      }
      /** 화면 요청 통계 한 줄: 구분, 건수, 무엇을 센 것인지. */
      function renderRequestStat(r) {
        return h('div', { class: 'vrow drow', title: r.description }, [
          h('span', { class: 'stmt', text: r.label }),
          h('span', { class: 'access', text: ui.formatCount(r.count) }),
          h('span', { class: 'msg', text: r.description })
        ]);
      }
      /** 미연결 요청 한 건(화면 요청 보기의 통계 줄 아래). 누르면 그 요청의 상세가 열린다. */
      function renderUnmatched(r) {
        var row = h('div', { class: 'vrow drow clickable', title: r.id }, [
          h('span', { class: 'stmt', text: r.location || '', title: r.location || '위치 없음' }),
          h('span', { class: 'access', text: '미연결' }),
          h('span', { class: 'msg', text: r.label })
        ]);
        row.addEventListener('click', function () { app.showDetail(r.nodeId); });
        return row;
      }
      function renderBorrowed(r) {
        var row = h('div', { class: 'vrow drow clickable', title: r.id }, [
          h('span', { class: 'stmt', text: r.statementLabel, title: r.id }),
          h('span', { class: 'access', text: r.borrowedFrom, title: '이 구문의 정의를 가져온 방언(borrowedFrom)' }),
          h('span', { class: 'msg', text: '' }),
          h('span', { class: 'loc', text: r.location || '', title: r.location || '위치 없음' })
        ]);
        row.addEventListener('click', function () { app.showDetail(r.nodeId); });
        return row;
      }
      var list = ui.createVirtualList({ render: function (r) {
        if (r.type === 'REQUEST_UNMATCHED') return renderUnmatched(r);
        if (r.type === 'BORROWED') return renderBorrowed(r);
        return r.type === 'UNRESOLVED' ? renderUnresolved(r) : r.type === 'UNCALLED' ? renderUncalled(r) : r.type === 'DYNAMIC' ? renderDynamic(r)
          : r.type === 'UNDECLARED_TABLE' ? renderUndeclared(r) : r.type === 'DATALOAD' ? renderDataLoad(r)
            : r.type === 'COLUMN_MISMATCH' ? renderColumnMismatch(r) : r.type === 'REQUEST_STAT' ? renderRequestStat(r) : renderDiagnostic(r);
      } });

      function apply() {
        // 화면 요청: 통계 줄 뒤에 미연결 요청을 위치순으로 잇는다.
        var all = mode === 'requests' ? data.requestRows.concat(data.unmatchedRequests) : data[viewOf(mode).rows];
        var rows = mode === 'unresolved' ? D.filter(all, { categories: Array.from(picked.categories), text: textInput.value })
          : mode === 'diagnostics' ? D.filter(all, { codes: Array.from(picked.codes), severities: Array.from(picked.severities), text: textInput.value })
            : D.filter(all, { text: textInput.value });
        // 통계 보기("화면 요청")의 행은 건수를 적은 줄이라 행 수 대신 요청 수를 적는다.
        summary.textContent = mode === 'requests' ? '요청 ' + ui.formatCount(data.requestStats.total) + '건'
          : ui.formatCount(rows.length) + ' / ' + ui.formatCount(all.length) + '건';
        list.setItems(rows);
      }

      function chip(label, count, set, key, title) {
        var el = h('button', { class: 'code-chip', type: 'button', title: title || null, attrs: { 'aria-pressed': String(set.has(key)) } }, [label, h('span', { class: 'n', text: ui.formatCount(count) })]);
        el.addEventListener('click', function () {
          if (set.has(key)) set.delete(key); else set.add(key);
          el.setAttribute('aria-pressed', String(set.has(key)));
          apply();
        });
        return el;
      }

      /** 고른 목록에 맞는 필터 줄과 머리 줄을 다시 만든다. 고른 값은 목록마다 따로 남는다. */
      function renderFilters() {
        var children;
        if (mode === 'unresolved') {
          var chips = data.categories.map(function (c) { return chip(c.label, c.count, picked.categories, c.category); });
          children = [h('span', { class: 'pane-title', text: '분류' }),
            chips.length ? h('div', { class: 'code-chips' }, chips) : h('span', { class: 'muted', text: '미해결 참조가 없다' })];
          // 신뢰도 UNRESOLVED 엣지 수(그래프 그대로)와 이 목록의 건수가 다른 까닭(report.md와 같은 문장)
          if (data.unresolvedDifference) children.push(h('span', { class: 'view-note unresolved-difference', text: data.unresolvedDifference }));
          header.replaceChildren(h('span', { class: 'code', text: '분류' }), h('span', { class: 'from', text: '참조한 곳' }), h('span', { class: 'rel', text: '엣지' }),
            h('span', { class: 'msg', text: '대상 (원문)' }), h('span', { class: 'loc', text: '참조 지점' }));
          textInput.placeholder = '참조한 곳, 대상, 위치로 거르기';
        } else if (mode === 'uncalled') {
          children = [h('span', { class: 'view-note', text: viewOf(mode).description })];
          header.replaceChildren(h('span', { class: 'stmt', text: '매퍼 구문' }), h('span', { class: 'msg', text: '실행하는 메서드 (부르는 곳 없음)' }), h('span', { class: 'loc', text: '구문 위치' }));
          textInput.placeholder = '구문, 메서드, 위치로 거르기';
        } else if (mode === 'undeclared') {
          children = [h('span', { class: 'view-note', text: viewOf(mode).description + (data.undeclaredSuspicious ? ' — 의심으로 표시한 테이블 ' + data.undeclaredSuspicious + '개' : '')
            + (data.undeclaredCatalogs ? ' — DB가 제공하는 시스템 카탈로그(데이터 사전) ' + data.undeclaredCatalogs + '개는 뒤에 따로 묶었다(의심으로 치지 않는다)' : '') })];
          header.replaceChildren(h('span', { class: 'stmt', text: '테이블' }), h('span', { class: 'access', text: '구문 수' }), h('span', { class: 'msg', text: '참조하는 구문 (최대 5개)' }), h('span', { class: 'loc', text: '비고' }));
          textInput.placeholder = '테이블, 구문, 비고로 거르기';
        } else if (mode === 'dataload') {
          children = [h('span', { class: 'view-note', text: (data.dataLoadSummary ? data.dataLoadSummary.text + ' (묶은 구문 ' + data.dataLoadSummary.statements + '개). '
            + (data.dataLoadSummary.borrowedText ? data.dataLoadSummary.borrowedText + ' ' : '') : '데이터 적재 구문이 없다. ') + viewOf(mode).description })];
          header.replaceChildren(h('span', { class: 'stmt', text: '파일' }), h('span', { class: 'access', text: '테이블' }), h('span', { class: 'access', text: '행 수' }), h('span', { class: 'msg', text: '적재하는 테이블' }));
          textInput.placeholder = '파일, 테이블로 거르기';
        } else if (mode === 'columns') {
          children = [h('span', { class: 'view-note', text: viewOf(mode).description })];
          header.replaceChildren(h('span', { class: 'stmt', text: '테이블' }), h('span', { class: 'from', text: '컬럼' }), h('span', { class: 'access', text: '구문 수' }), h('span', { class: 'msg', text: '참조하는 구문 (최대 5개)' }));
          textInput.placeholder = '테이블, 컬럼, 구문으로 거르기';
        } else if (mode === 'requests') {
          children = [h('span', { class: 'view-note', text: (data.requestStats.total ? data.requestStats.summaryText + ' ' + (data.requestStats.noteText ? data.requestStats.noteText + ' ' : '') : '화면 요청이 없다. ') + viewOf(mode).description })];
          header.replaceChildren(h('span', { class: 'stmt', text: '구분 / 미연결 요청의 위치' }), h('span', { class: 'access', text: '건수' }), h('span', { class: 'msg', text: '무엇을 센 것인가 / 요청' }));
          textInput.placeholder = '구분, 요청, 위치로 거르기';
        } else if (mode === 'borrowed') {
          children = [h('span', { class: 'view-note', text: viewOf(mode).description })];
          header.replaceChildren(h('span', { class: 'stmt', text: '구문' }), h('span', { class: 'access', text: '방언' }), h('span', { class: 'msg', text: '' }), h('span', { class: 'loc', text: '위치' }));
          textInput.placeholder = '구문, 방언, 위치로 거르기';
        } else if (mode === 'dynamic') {
          children = [h('span', { class: 'view-note', text: viewOf(mode).description })];
          header.replaceChildren(h('span', { class: 'stmt', text: '구문' }), h('span', { class: 'access', text: 'CRUD' }), h('span', { class: 'msg', text: '알아낸 원문 조각' }), h('span', { class: 'loc', text: '위치' }));
          textInput.placeholder = '구문, 원문, 위치로 거르기';
        } else {
          var codeChips = data.codes.map(function (c) { return chip(c.code, c.count, picked.codes, c.code, c.description); });
          var severityChecks = ['ERROR', 'WARN', 'INFO'].map(function (s) {
            return ui.createCheck(s, picked.severities.has(s), function (on) {
              if (on) picked.severities.add(s); else picked.severities.delete(s);
              apply();
            }).el;
          });
          children = [h('span', { class: 'pane-title', text: '심각도' })].concat(severityChecks, [h('span', { class: 'pane-title', text: '코드' }),
            codeChips.length ? h('div', { class: 'code-chips' }, codeChips) : h('span', { class: 'muted', text: '진단이 없다' })]);
          header.replaceChildren(h('span', { class: 'sev', text: '심각도' }), h('span', { class: 'code', text: '코드' }),
            h('span', { class: 'msg', text: '메시지' }), h('span', { class: 'loc', text: '위치' }));
          textInput.placeholder = '메시지나 위치로 거르기';
        }
        filters.replaceChildren.apply(filters, children);
      }

      var modeSeg = ui.createSegmented(D.VIEWS.map(function (v) {
        return [v.key, v.label + ' ' + ui.formatCount(D.viewCount(v, data))];
      }), mode, function (v) {
        texts[mode] = textInput.value;
        mode = v;
        detail.style.display = 'none';
        textInput.value = texts[mode];
        renderFilters();
        apply();
      });
      modeSeg.el.classList.add('diag-mode');
      textInput.addEventListener('input', ui.debounce(apply, 150));

      renderFilters();
      apply();
      return {
        el: h('div', { class: 'view view-diagnostics', attrs: { style: 'flex-direction:column' } }, [
          h('div', { class: 'pane', attrs: { style: 'flex:1' } }, [
            h('div', { class: 'pane-head' }, [modeSeg.el, textInput, summary, h('span', { class: 'spacer' }),
              h('span', { class: 'muted', text: '미해결 참조는 엣지 하나가 한 건이다. 같은 참조를 알린 진단에는 "미해결" 표시가 붙는다.' })]),
            filters,
            header,
            list.el,
            detail
          ])
        ]),
        show: function () { list.refresh(); }
      };
    }
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
