// SQL 본문의 정리(줄바꿈과 들여쓰기). DOM을 쓰지 않는다.
// SQL을 해석하지 않고 낱말 단위로 훑는다. 공백만 바꾸고 글자(대소문자, 문자열, 주석, 자리표시)는 그대로 둔다(F-016-R46).
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var INDENT = '  ';

  // 조건을 잇는 AND·OR에서 줄을 바꾸는 절
  var CONDITION_CLAUSES = { WHERE: true, HAVING: true, JOIN: true, CONNECT: true, START: true };
  // 쉼표에서 줄을 바꾸는 절과 그 줄의 들여쓰기(절 줄 기준)
  var COMMA_CLAUSES = { SELECT: 1, SET: 1, FROM: 1, WITH: 0 };
  // 괄호 안이 이 낱말로 시작하면 하위 쿼리로 보고 들여 쓴다(PostgreSQL의 WITH x AS (UPDATE …) 포함)
  var SUBQUERY_STARTS = { SELECT: true, WITH: true, INSERT: true, UPDATE: true, DELETE: true };
  var JOIN_PREFIXES = { LEFT: true, RIGHT: true, FULL: true, INNER: true, CROSS: true, NATURAL: true, OUTER: true };
  var DOLLAR_QUOTE = /\$([A-Za-z_][A-Za-z0-9_]*)?\$/y;

  function isWordChar(c) {
    return /[A-Za-z0-9_$#]/.test(c) || c > '\u007f';
  }

  /** 따옴표로 시작하는 글자의 끝(닫는 따옴표 다음). 두 번 쓴 따옴표는 글자다. 닫히지 않으면 끝까지. */
  function quotedEnd(sql, from, quote) {
    var i = from + 1;
    for (;;) {
      var k = sql.indexOf(quote, i);
      if (k < 0) return sql.length;
      if (sql.charAt(k + 1) !== quote) return k + 1;
      i = k + 2;
    }
  }

  /** 낱말로 나눈다. [{ text, type: 'word'|'string'|'comment'|'line'|'punct', space: 앞에 공백이 있었는가 }] */
  function tokenize(sql) {
    var out = [];
    var n = sql.length;
    var i = 0;
    var space = false;
    while (i < n) {
      var c = sql.charAt(i);
      if (/\s/.test(c)) { space = true; i++; continue; }
      var start = i;
      var type = 'punct';
      var j;
      if (c === '-' && sql.charAt(i + 1) === '-') {
        j = sql.indexOf('\n', i);
        i = j < 0 ? n : j;
        type = 'line';
      } else if (c === '/' && sql.charAt(i + 1) === '*') {
        j = sql.indexOf('*/', i + 2);
        i = j < 0 ? n : j + 2;
        type = 'comment';
      } else if (c === '\'' || c === '"' || c === '`') {
        i = quotedEnd(sql, i, c);
        type = 'string';
      } else if ((c === '#' || c === '$') && sql.charAt(i + 1) === '{') {
        // MyBatis 자리표시 #{…}, ${…}
        j = sql.indexOf('}', i + 2);
        i = j < 0 ? n : j + 1;
        type = 'word';
      } else if (c === '{' && sql.substring(i, i + 3) === '{*}') {
        i += 3;
        type = 'word';
      } else if (c === '$' && (DOLLAR_QUOTE.lastIndex = i, DOLLAR_QUOTE.test(sql))) {
        // PostgreSQL 달러 인용 $$…$$, $tag$…$tag$
        var tag = sql.substring(i, DOLLAR_QUOTE.lastIndex);
        j = sql.indexOf(tag, i + tag.length);
        i = j < 0 ? n : j + tag.length;
        type = 'string';
      } else if (isWordChar(c)) {
        while (i < n && isWordChar(sql.charAt(i))) i++;
        type = 'word';
      } else {
        i++;
      }
      var text = sql.substring(start, i);
      out.push({ text: type === 'line' ? text.replace(/\s+$/, '') : text, type: type, space: space });
      space = false;
    }
    return out;
  }

  function baseFrame() {
    return { block: true, paren: false, indent: 0, openIndent: 0, clause: null, between: false, start: true };
  }

  /**
   * SQL 본문을 정리한다. 절(SELECT, FROM, WHERE, 조인 …)마다 줄을 바꾸고, 선택 목록·SET·FROM의 쉼표와 조건의 AND·OR에서
   * 줄을 바꿔 들여 쓰며, 하위 쿼리는 괄호 안을 한 단계 더 들여 쓴다. 함수 인자·IN 목록·OVER(…)·CASE … END 안은 한 줄로 둔다.
   * 잘린 본문, 조각, 짝이 맞지 않는 괄호·따옴표도 받는다(글자를 잃지 않는다).
   */
  function format(sql) {
    if (sql == null) return '';
    var toks = tokenize(String(sql));
    var lines = [];
    var line = '';
    var lineIndent = 0;
    var stack = [baseFrame()];
    var pending = null;       // 다음 낱말 앞에서 바꿀 줄: { indent, comment }
    var forceSpace = false;
    var prev = null;          // 바로 앞의 주석이 아닌 낱말(대문자)

    function top() { return stack[stack.length - 1]; }
    function newline(indent) {
      if (line !== '') {
        lines.push(new Array(lineIndent + 1).join(INDENT) + line);
        line = '';
      }
      lineIndent = indent;
    }
    function emit(t) {
      var space = forceSpace || (t.space && t.text !== ',' && t.text !== ';');
      line += line === '' ? t.text : (space ? ' ' : '') + t.text;
      forceSpace = false;
    }
    function applyPending() {
      if (pending) { newline(pending.indent); pending = null; }
    }
    /** k 다음의 n번째(1부터) 주석이 아닌 낱말. 낱말이면 대문자로. */
    function next(k, n) {
      for (var j = k + 1; j < toks.length; j++) {
        var t = toks[j];
        if (t.type === 'comment' || t.type === 'line') continue;
        if (--n === 0) return t.type === 'word' ? t.text.toUpperCase() : t.text;
      }
      return null;
    }
    /** 블록(문장이나 하위 쿼리)에서 이 낱말이 새 절을 여는가. 열면 절 이름. */
    function clauseOf(k, up, f) {
      switch (up) {
        case 'SELECT': case 'WHERE': case 'HAVING': case 'RETURNING': case 'LIMIT':
          return up;
        case 'FROM':
          return prev === 'DELETE' || prev === 'DISTINCT' ? null : 'FROM';
        case 'GROUP': case 'CONNECT':
          return next(k, 1) === 'BY' ? up : null;
        case 'ORDER':
          return next(k, 1) === 'BY' || next(k, 1) === 'SIBLINGS' ? 'ORDER' : null;
        case 'START':
          return next(k, 1) === 'WITH' ? 'START' : null;
        case 'UNION': case 'INTERSECT': case 'MINUS': case 'EXCEPT':
          return 'SET_OPERATOR';
        case 'INSERT': case 'UPDATE': case 'DELETE': case 'MERGE':
          // MERGE의 WHEN … THEN UPDATE, FOR UPDATE, ON DUPLICATE KEY UPDATE, ON CONFLICT DO UPDATE
          return prev === 'THEN' || prev === 'FOR' || prev === 'KEY' || prev === 'DO' ? null : up;
        case 'VALUES':
          return f.clause === 'WHEN' ? null : 'VALUES';
        case 'SET':
          return prev === 'UPDATE' ? null : 'SET';
        case 'USING':
          return f.clause === 'MERGE' ? 'USING' : null;
        case 'WHEN':
          return next(k, 1) === 'MATCHED' || next(k, 1) === 'NOT' ? 'WHEN' : null;
        case 'JOIN': case 'STRAIGHT_JOIN':
          return JOIN_PREFIXES[prev] ? null : 'JOIN';
        case 'LEFT': case 'RIGHT': case 'FULL': case 'INNER': case 'CROSS': case 'NATURAL':
          if (JOIN_PREFIXES[prev]) return null;
          return [next(k, 1), next(k, 2)].some(function (w) { return w === 'JOIN' || w === 'APPLY'; }) ? 'JOIN' : null;
        case 'OUTER':
          return next(k, 1) === 'APPLY' && !JOIN_PREFIXES[prev] ? 'JOIN' : null;
        case 'WITH':
          return f.start ? 'WITH' : null;
        case 'OFFSET':
          return f.clause === 'LIMIT' ? null : 'OFFSET';
        case 'FETCH':
          return next(k, 1) === 'FIRST' || next(k, 1) === 'NEXT' ? 'FETCH' : null;
        case 'FOR':
          return next(k, 1) === 'UPDATE' || next(k, 1) === 'READ' || next(k, 1) === 'FETCH' ? 'FOR' : null;
        case 'ON':
          return next(k, 1) === 'CONFLICT' ? 'ON_CONFLICT' : null;
        default:
          return null;
      }
    }

    toks.forEach(function (t, k) {
      var f = top();
      var up = t.type === 'word' ? t.text.toUpperCase() : null;

      // 줄 주석은 지금 줄 끝에 붙이고 뒤는 다음 줄로 넘긴다
      if (t.type === 'line') {
        emit(t);
        pending = { indent: pending ? pending.indent : lineIndent, comment: true };
        return;
      }
      if (t.text === ')') {
        var at = -1;
        for (var i = stack.length - 1; i > 0; i--) if (stack[i].paren) { at = i; break; }
        var closed = at > 0 ? stack[at] : null;
        if (closed) stack.length = at;
        if (closed && closed.block) { pending = null; newline(closed.openIndent); } else applyPending();
        emit(t);
        top().start = false;
        prev = ')';
        return;
      }
      if (t.text === ';') {
        emit(t);
        stack = [baseFrame()];
        pending = { indent: 0 };
        prev = ';';
        return;
      }

      var brk = null;
      var leadingComma = false;
      if (f.block && up) {
        var clause = clauseOf(k, up, f);
        if (clause) {
          brk = f.indent;
          f.clause = clause;
          f.between = false;
        } else if ((up === 'AND' || up === 'OR') && CONDITION_CLAUSES[f.clause]) {
          if (up === 'AND' && f.between) f.between = false;
          else brk = f.indent + 1;
        }
      } else if (f.block && t.text === ',' && pending && pending.comment && COMMA_CLAUSES[f.clause] != null) {
        // 쉼표를 앞에 두는 원문(`A -- 설명` 다음 줄의 `, B`)은 그 모양을 살린다
        brk = f.indent + COMMA_CLAUSES[f.clause];
        leadingComma = true;
      }
      if (brk != null) { pending = null; newline(brk); } else applyPending();
      emit(t);
      f.start = false;
      if (t.type !== 'comment') prev = up || t.text;

      if (up === 'BETWEEN') f.between = true;
      if (up === 'CASE') stack.push({ block: false, paren: false, isCase: true });
      if (up === 'END') {
        for (var c = stack.length - 1; c > 0 && !stack[c].paren; c--) {
          if (stack[c].isCase) { stack.length = c; break; }
        }
      }
      if (t.text === '(') {
        if (SUBQUERY_STARTS[next(k, 1)] === true) {
          var sub = { block: true, paren: true, indent: lineIndent + 1, openIndent: lineIndent, clause: null, between: false, start: true };
          stack.push(sub);
          pending = { indent: sub.indent };
        } else {
          stack.push({ block: false, paren: true });
        }
      }
      if (t.text === ',' && f.block) {
        if (leadingComma) forceSpace = true;
        else if (COMMA_CLAUSES[f.clause] != null) pending = { indent: f.indent + COMMA_CLAUSES[f.clause] };
      }
    });
    newline(0);
    return lines.join('\n');
  }

  var api = { format: format };
  CA.sqlFormat = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
