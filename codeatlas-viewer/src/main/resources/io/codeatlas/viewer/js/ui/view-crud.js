// CRUD 매트릭스 화면: 행은 SQL 구문, Java 메서드, 엔드포인트, 루틴 가운데 하나, 열은 테이블(과 (동적)).
// 칸의 글자와 CSV는 표준 산출물(crud/table-*.csv)과 같다: 대문자는 직접, 소문자는 루틴·트리거를 거친 간접 접근이고,
// 끝의 ?는 추론한 관계를 거쳐서만 닿은 칸이다(F-015-R52).
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var CRUD = CA.crud;
  var ui = CA.ui;
  var h = ui.h;
  var MAX_COLUMNS = 80;
  var LABEL_WIDTH = 380;
  var CELL_WIDTH = 30;
  var HEAD_HEIGHT = 132;
  CA.views = CA.views || {};

  /** 칸 끝의 추론 표시(?)를 뗀 글자와, 그 표시가 있었는지. */
  function splitCell(text) {
    var mark = CRUD.INFERRED_MARK;
    var inferred = !!text && text.lastIndexOf(mark) === text.length - mark.length;
    return { letters: inferred ? text.substring(0, text.length - mark.length) : (text || ''), inferred: inferred };
  }

  function cellClass(text) {
    if (!text) return 'crud-cell';
    var cell = splitCell(text);
    var letters = cell.letters;
    var upper = letters.toUpperCase();
    // 대문자가 하나도 없으면 루틴·트리거를 거쳐서만 닿는 간접 접근이다. ?가 붙었으면 추론한 관계를 거쳐서만 닿은 칸이다.
    var extra = (letters === letters.toLowerCase() ? ' indirect' : '') + (cell.inferred ? ' inferred' : '');
    if (upper === 'R') return 'crud-cell r' + extra;
    return 'crud-cell ' + (upper.indexOf('R') >= 0 ? 'rw' : 'w') + extra;
  }

  /** 칸의 글자를 풀어 쓴 말. 예: `Ru` → "직접 R · 루틴 경유 u", `R?` → "직접 R · 추론한 관계를 거쳐서만 닿음". */
  function cellTitle(text) {
    var cell = splitCell(text);
    var direct = cell.letters.replace(/[a-z]/g, '');
    var indirect = cell.letters.replace(/[A-Z]/g, '');
    var parts = [];
    if (direct) parts.push('직접 ' + direct);
    if (indirect) parts.push('루틴·트리거 경유 ' + indirect);
    if (cell.inferred) parts.push('추론한 관계를 거쳐서만 닿음(?): 확정 경로가 없다. 어느 관계가 추론인지는 콜트리에서 추론·수신자?·트리거? 표시가 붙은 줄을 본다');
    return parts.join(' · ');
  }

  CA.views.crud = {
    title: 'CRUD',
    /** 엔드포인트 기준을 계산할 때 한 번에 쓰는 시간(ms). 이만큼 계산하고 화면에 진행을 보인 뒤 이어서 한다. */
    STEP_BUDGET_MS: 40,
    create: function (app) {
      var index = app.index;
      var state = { rowKind: 'statement', transitive: false, includeDataLoad: false };
      var cache = {};
      var jobs = {};       // 끝나지 않은 계산(엔드포인트 기준). 다른 기준을 보다가 돌아오면 이어서 한다.
      var ticket = 0;      // 화면이 기다리는 계산. 기준을 바꾸면 앞의 계산은 화면을 건드리지 않는다.
      var full = null;     // 필터 전
      var shown = null;    // 필터 후
      var columns = [];    // 화면에 그리는 열(상한 적용). 테이블과, 있으면 마지막에 (동적)
      var pending = false;

      var head = h('div', { class: 'crud-head' });
      var rowsBox = h('div', { class: 'crud-rows' });
      var grid = h('div', { class: 'crud-grid' }, [head, rowsBox]);
      var wrap = h('div', { class: 'crud-wrap' }, [grid]);
      var summary = h('span', { class: 'muted crud-summary' });
      var warning = h('div', { class: 'banner', attrs: { style: 'display:none' } });
      var kindHint = h('span', { class: 'muted crud-kind-hint' });
      var progress = h('div', { class: 'banner crud-progress', attrs: { style: 'display:none', role: 'status' } });
      var dynamicBox = h('div', { class: 'crud-dynamic', attrs: { style: 'display:none' } });
      var rowFilter = h('input', { class: 'input', type: 'search', placeholder: '행 이름으로 거르기', attrs: { autocomplete: 'off', spellcheck: 'false' } });
      var tableFilter = h('input', { class: 'input', type: 'search', placeholder: '테이블 이름으로 거르기', attrs: { autocomplete: 'off', spellcheck: 'false' } });

      function kindMeta() {
        for (var i = 0; i < CRUD.ROW_KINDS.length; i++) if (CRUD.ROW_KINDS[i].key === state.rowKind) return CRUD.ROW_KINDS[i];
        return CRUD.ROW_KINDS[0];
      }
      function matrixKey() {
        return state.rowKind + (state.rowKind === 'method' && state.transitive ? '+t' : '') + (state.includeDataLoad ? '+d' : '');
      }
      /**
       * 지금 고른 기준의 매트릭스를 구해 then에 준다. 구문·메서드·루틴 기준은 바로 계산한다.
       * 엔드포인트 기준은 엔드포인트마다 호출 사슬을 따라가므로(큰 그래프에서 수 초~수십 초) 고를 때 나눠서 계산하고 진행을 보인다.
       */
      function withMatrix(then) {
        var key = matrixKey();
        var mine = ++ticket;
        if (cache[key]) { progress.style.display = 'none'; then(cache[key]); return; }
        var job = jobs[key] || (jobs[key] = CRUD.createJob(index, {
          rowKind: state.rowKind, transitive: state.rowKind === 'method' && state.transitive, includeDataLoad: state.includeDataLoad
        }));
        if (job.rowKind !== 'endpoint') {
          job.step(0);
          cache[key] = job.result();
          delete jobs[key];
          progress.style.display = 'none';
          then(cache[key]);
          return;
        }
        var started = Date.now();
        var show = function () {
          progress.textContent = '엔드포인트 기준 계산 중… ' + ui.formatCount(job.done()) + ' / ' + ui.formatCount(job.total)
            + ' (엔드포인트마다 호출 사슬을 따라간다. 다른 기준을 보다가 돌아와도 이어서 계산한다)';
          progress.style.display = '';
        };
        show();
        full = null;
        shown = null;
        rowsBox.replaceChildren();
        summary.textContent = '';
        var tick = function () {
          if (mine !== ticket) return;
          var done = job.step(CA.views.crud.STEP_BUDGET_MS);
          if (!done) { show(); root.setTimeout(tick, 0); return; }
          cache[key] = job.result();
          cache[key].elapsedMs = Date.now() - started;
          delete jobs[key];
          progress.style.display = 'none';
          then(cache[key]);
        };
        root.setTimeout(tick, 0);
      }

      function renderRows() {
        pending = false;
        var count = shown ? shown.rows.length : 0;
        var range = CA.vlist.visibleRange({
          scrollTop: wrap.scrollTop - HEAD_HEIGHT, viewportHeight: wrap.clientHeight || 600, rowHeight: ui.ROW_HEIGHT, count: count, overscan: 8
        });
        rowsBox.style.height = range.totalHeight + 'px';
        var out = [];
        for (var i = range.start; i < range.end && shown; i++) out.push(renderRow(shown.rows[i], i));
        rowsBox.replaceChildren.apply(rowsBox, out);
      }
      function renderRow(row, i) {
        var cells = [h('div', { class: 'crud-label' + (shown.rowKind === 'endpoint' ? ' head' : ''), title: row.label + '\n' + row.id,
          on: { click: function () { app.showDetail(row.id); } } }, [h('span', { text: row.label })])];
        for (var c = 0; c < columns.length; c++) {
          var letters = row.cells[columns[c].id] || '';
          cells.push(h('div', { class: cellClass(letters), text: letters, title: letters ? row.label + ' → ' + columns[c].name + ': ' + cellTitle(letters) : null }));
        }
        var el = h('div', { class: 'crud-row' }, cells);
        el.style.top = (i * ui.ROW_HEIGHT) + 'px';
        return el;
      }
      function renderHead() {
        var cells = [h('div', { class: 'crud-corner', text: kindMeta().label + ' ╲ 테이블' })];
        columns.forEach(function (t) {
          if (t.dynamic) {
            cells.push(h('div', { class: 'crud-th dynamic', title: '(동적): 대상 테이블을 정적으로 알 수 없는 구문(동적 SQL)의 접근' }, [h('span', { text: t.name })]));
            return;
          }
          var undeclared = t.node && t.node.attrs.declared === false;
          cells.push(h('div', { class: 'crud-th' + (undeclared ? ' undeclared' : ''), title: t.name + (undeclared ? ' (DDL 없음)' : ''), on: { click: function () { app.showDetail(t.id); } } }, [h('span', { text: t.name })]));
        });
        head.replaceChildren.apply(head, cells);
        grid.style.width = (LABEL_WIDTH + columns.length * CELL_WIDTH) + 'px';
      }
      /** 구문 기준일 때 표 아래에 붙는 "대상 테이블을 정적으로 알 수 없는 구문" 목록(구문, CRUD, 위치, 알아낸 원문 조각). */
      function renderDynamic() {
        var list = state.rowKind === 'statement' && full ? full.dynamicStatements : [];
        dynamicBox.style.display = list.length ? '' : 'none';
        if (!list.length) { dynamicBox.replaceChildren(); return; }
        var rows = list.map(function (d) {
          return h('div', { class: 'crud-dynamic-row', title: d.id, on: { click: function () { app.showDetail(d.id); } } }, [
            h('span', { class: 'name', text: CA.graph.idRemainder(d.id) }),
            h('span', { class: 'access', text: d.access }),
            h('span', { class: 'loc', text: d.location || '' }),
            h('span', { class: 'text', text: d.text || '', title: d.text || '' })
          ]);
        });
        dynamicBox.replaceChildren.apply(dynamicBox, [
          h('div', { class: 'crud-dynamic-title', text: '대상 테이블을 정적으로 알 수 없는 구문 (' + list.length + ')' }),
          h('div', { class: 'muted', text: '동적 SQL이라 읽고 쓰는 테이블을 알 수 없다. 위 표의 (동적) 열에 접근 종류만 나온다.' })
        ].concat(rows));
      }
      function apply() {
        withMatrix(render);
      }
      function render(matrix) {
        full = matrix;
        shown = CRUD.filterMatrix(full, { rowText: rowFilter.value, tableText: tableFilter.value });
        var all = CRUD.columnsOf(shown);
        // 열이 상한을 넘어도 (동적) 열은 남긴다.
        columns = all.length > MAX_COLUMNS ? all.slice(0, MAX_COLUMNS - (shown.dynamicColumn ? 1 : 0)).concat(shown.dynamicColumn ? [all[all.length - 1]] : []) : all;
        summary.textContent = '행 ' + ui.formatCount(shown.rows.length) + '개 · 테이블 ' + ui.formatCount(shown.tables.length) + '개'
          + (shown.dynamicColumn ? ' + (동적)' : '')
          + (full.dataLoadExcluded > 0 ? ' · 데이터 적재 구문 ' + ui.formatCount(full.dataLoadExcluded) + '개 제외' : '');
        dataLoadCheck.el.style.display = full.dataLoads > 0 ? '' : 'none';
        kindHint.textContent = kindMeta().description;
        var over = all.length > MAX_COLUMNS;
        warning.textContent = over
          ? '테이블이 ' + ui.formatCount(shown.tables.length) + '개라 앞의 ' + MAX_COLUMNS + '개만 열로 보인다. 테이블 이름으로 거른다. CSV에는 모두 들어간다.' : '';
        warning.style.display = over ? '' : 'none';
        renderHead();
        renderDynamic();
        wrap.scrollTop = 0;
        renderRows();
        if (!shown.rows.length) rowsBox.replaceChildren(h('div', { class: 'empty', text: full.rows.length ? '거른 결과가 없다.' : '테이블을 읽거나 쓰는 ' + kindMeta().label + '이(가) 그래프에 없다.' }));
      }
      // 거르기는 이미 만든 매트릭스에만 한다(계산 중이면 끝난 뒤에 반영된다).
      var applySoon = ui.debounce(function () { if (full) render(full); }, 150);
      wrap.addEventListener('scroll', function () {
        if (pending) return;
        pending = true;
        root.requestAnimationFrame(renderRows);
      });
      rowFilter.addEventListener('input', applySoon);
      tableFilter.addEventListener('input', applySoon);

      var transitiveCheck = ui.createCheck('호출 경유 포함', false, function (on) { state.transitive = on; apply(); },
        '구문을 직접 실행하는 메서드뿐 아니라 그 메서드를 호출하는 메서드(인터페이스 포함)까지 행으로 넣는다. 뷰어에만 있는 보기다(표준 산출물 table-method.csv는 직접 실행하는 메서드만 낸다)');
      transitiveCheck.input.disabled = true;
      var dataLoadCheck = ui.createCheck('데이터 적재 포함', false, function (on) { state.includeDataLoad = on; apply(); },
        '끄면(기본) 데이터 적재 구문(dataLoad=true: SQL 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 것)을 뺀다. 애플리케이션이 실행하는 구문이 아니라서다. 표준 산출물의 export.includeDataLoad와 같다');
      dataLoadCheck.el.classList.add('include-data-load');
      var kindSeg = ui.createSegmented(CRUD.ROW_KINDS.map(function (k) { return [k.key, k.label]; }), state.rowKind, function (v) {
        state.rowKind = v;
        transitiveCheck.input.disabled = v !== 'method';
        apply();
      });
      kindSeg.el.classList.add('crud-kind');
      var csvButton = h('button', { class: 'btn crud-csv', type: 'button', text: 'CSV 저장', title: '표준 산출물 crud/table-*.csv와 같은 모양(UTF-8 BOM)으로 저장한다. 거른 결과만 들어간다', on: { click: function () {
        if (shown) ui.download('table-' + state.rowKind + '.csv', 'text/csv;charset=utf-8', CRUD.toCsv(shown, { bom: true }));
      } } });

      var legend = h('div', { class: 'crud-legend' }, CRUD.LEGEND.map(function (item) {
        return h('span', { class: 'crud-legend-item', title: item.description }, [h('b', { text: item.key }), ' ' + item.description]);
      }));

      var built = false;
      return {
        el: h('div', { class: 'view view-crud', attrs: { style: 'flex-direction:column' } }, [
          h('div', { class: 'pane', attrs: { style: 'flex:none' } }, [
            h('div', { class: 'pane-head' }, [
              h('span', { class: 'pane-title', text: '행' }), kindSeg.el, transitiveCheck.el, dataLoadCheck.el, rowFilter, tableFilter, summary,
              h('span', { class: 'spacer' }), csvButton
            ]),
            h('div', { class: 'pane-head crud-notes' }, [kindHint]),
            legend,
            progress,
            warning
          ]),
          wrap,
          dynamicBox
        ]),
        show: function () {
          if (!built) { built = true; apply(); } else if (shown) renderRows();
        }
      };
    }
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
