#!/usr/bin/env python3
"""정답 목록(expected/*.json) 자체 점검 스크립트.

분석기를 쓰지 않고, 정답에 적힌 ID 가 샘플 소스에 실제로 근거를 두고 있는지 텍스트 수준에서 확인한다.
  - ID 형식 (접두사, 대소문자, 메서드 시그니처 모양)
  - java:type / java:method: 소스 파일과 선언이 있는가
  - db:table / db:column / db:routine / db:sequence / db:view / db:synonym: DDL·SQL 에 나오는가
  - mybatis:stmt / fragment: 매퍼 XML(또는 @Select)에 있는가
  - READS/WRITES/JOINS: 구문 본문에 그 테이블·컬럼 이름이 있는가
  - EXECUTES / ROUTES_TO / RENDERS / INCLUDES / MATCHES: 근거 문자열이 소스에 있는가
  - must-have 와 must-not 이 서로 모순되지 않는가

사용법:  python3 expected/check_expected.py [--facts <javap 로 뽑은 facts.json>]
(--facts 는 선택. 컴파일한 클래스에서 뽑은 메서드 시그니처·호출 목록과 대조할 때만 쓴다.)
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
P = "com.example.shop."
errors, checked = [], {}

def err(msg):
    errors.append(msg)

def ok(cat):
    checked[cat] = checked.get(cat, 0) + 1

def read(rel):
    raw = open(os.path.join(ROOT, rel), "rb").read()
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("cp949")

def exists(rel):
    return os.path.isfile(os.path.join(ROOT, rel))

def load(name):
    return json.load(open(os.path.join(HERE, name), encoding="utf-8"))

def strip_sql_comments(text):
    text = re.sub(r"/\*(?!\+).*?\*/", " ", text, flags=re.S)   # 힌트(/*+ */)는 남긴다
    return re.sub(r"--[^\n]*", " ", text)

# ---------------------------------------------------------------- 소스 색인
DB_FILES = [os.path.join(d, f).replace(os.sep, "/")[len(ROOT) + 1:] for d, _, fs in os.walk(os.path.join(ROOT, "db")) for f in fs]
DDL = {f: strip_sql_comments(read(f)) for f in DB_FILES}
ALL_DB = "\n".join(DDL.values())
MAPPER_FILES = [os.path.join(d, f).replace(os.sep, "/")[len(ROOT) + 1:]
                for d, _, fs in os.walk(os.path.join(ROOT, "src/main/resources/mapper")) for f in fs if f.endswith(".xml")]
STMT, FRAG, NAMESPACES = {}, {}, set()
for f in MAPPER_FILES:
    text = re.sub(r"<!--.*?-->", " ", read(f), flags=re.S)
    ns = re.search(r'<mapper\s+namespace="([^"]+)"', text).group(1)
    NAMESPACES.add(ns)
    for m in re.finditer(r'<(select|insert|update|delete)\s+id="(\w+)"[^>]*>(.*?)</\1>', text, flags=re.S):
        STMT[ns + "." + m.group(2)] = strip_sql_comments(m.group(3))
    for m in re.finditer(r'<sql\s+id="(\w+)"[^>]*>(.*?)</sql>', text, flags=re.S):
        FRAG[ns + "." + m.group(1)] = m.group(2)
JAVA_ROOT = "src/main/java/"
def java_file(binary):
    return JAVA_ROOT + binary.split("$")[0].replace(".", "/") + ".java"
for f in [java_file(P + "member.mapper.MemberMapper")]:   # 애너테이션 SQL
    text = read(f)
    for m in re.finditer(r'@(Select|Insert|Update|Delete)\(\s*(\{.*?\}|".*?")\s*\)\s*[\w<>, .\[\]]+\s+(\w+)\(', text, flags=re.S):
        STMT[P + "member.mapper.MemberMapper." + m.group(3)] = " ".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2)))

TABLES_DDL = set(re.findall(r"CREATE\s+TABLE\s+(?:\w+\.)?(\w+)", ALL_DB))
def table_block(t):
    m = re.search(r"CREATE\s+TABLE\s+(?:\w+\.)?%s\s*\((.*?)\n\)" % t, ALL_DB, flags=re.S)
    return m.group(1) if m else ""

FACTS = None
if "--facts" in sys.argv:
    FACTS = json.load(open(sys.argv[sys.argv.index("--facts") + 1]))
    FACT_METHODS = set(FACTS["methods"])
    def norm_caller(mid):   # lambda$findOrders$0(...) → 감싼 메서드는 이름만으로 찾는다
        return re.sub(r"#lambda\$(\w+)\$\d+\(.*\)$", r"#\1(", mid)
    FACT_CALLS = set()
    for c in FACTS["calls"]:
        frm = c["from"]
        if "#lambda$" in frm:
            FACT_CALLS.add((norm_caller(frm), c["to"]))
        else:
            FACT_CALLS.add((frm, c["to"]))
    FACT_HANDLES = {(h["inClass"], h["to"]) for h in FACTS["handles"]}

# ---------------------------------------------------------------- ID 점검
ID_PATTERNS = {
    "java:type:": r"^java:type:[\w.$]+$",
    "java:method:": r"^java:method:[\w.$]+#(<init>|\w+)\(([\w.$\[\]]+(,[\w.$\[\]]+)*)?\)$",
    "java:field:": r"^java:field:[\w.$]+#\w+$",
    "spring:bean:": r"^spring:bean:\w+$",
    "http:endpoint:": r"^http:endpoint:(GET|POST|PUT|DELETE|PATCH|ANY) /[\w/{}.\-]*$",
    "batch:job:": r"^batch:job:\w+$",
    "batch:step:": r"^batch:step:\w+/\w+$",
    "mybatis:mapper:": r"^mybatis:mapper:[\w.]+$",
    "mybatis:stmt:": r"^mybatis:stmt:[\w.]+\.\w+$",
    "mybatis:fragment:": r"^mybatis:fragment:[\w.]+\.\w+$",
    "sql:stmt:": r"^sql:stmt:[\w./]+:\d+$",
    "db:table:": r"^db:table:[A-Z0-9_]+$",
    "db:view:": r"^db:view:[A-Z0-9_]+$",
    "db:column:": r"^db:column:[A-Z0-9_]+\.[A-Z0-9_]+$",
    "db:routine:": r"^db:routine:[A-Z0-9_]+(\.[A-Z0-9_]+)?$",
    "db:package:": r"^db:package:[A-Z0-9_]+$",
    "db:sequence:": r"^db:sequence:[A-Z0-9_]+$",
    "db:synonym:": r"^db:synonym:[A-Z0-9_]+$",
    "js:module:": r"^js:module:[\w./\-]+$",
    "js:function:": r"^js:function:[\w./\-]+#[\w.$]+$",
    "jsp:page:": r"^jsp:page:[\w./\-]+$",
}
EXTERNAL_OK = ("java.", "jakarta.", "org.springframework.", "org.apache.ibatis.", "org.mybatis.", "int", "long", "boolean")

def check_java_type(binary, where):
    if not binary.startswith(P):
        if not binary.startswith(EXTERNAL_OK):
            err("%s: 알 수 없는 외부 타입 %s" % (where, binary))
        return
    f = java_file(binary)
    if not exists(f):
        err("%s: 소스 파일 없음 %s" % (where, f)); return
    parts = binary.split(".")[-1].split("$")
    text = read(f)
    for inner in parts[1:]:
        if inner.isdigit():
            if "new Comparator<OrderVO>()" not in text: err("%s: 익명 클래스 근거 없음" % where)
        elif not re.search(r"\b(class|interface|enum|record)\s+%s\b" % inner, text):
            err("%s: 중첩 타입 선언 없음 %s" % (where, inner))
    ok("java type")

def check_id(nid, where, declared_only=True):
    if nid == "*" or nid.endswith("*"):
        return
    for prefix, pat in ID_PATTERNS.items():
        if nid.startswith(prefix):
            if not re.match(pat, nid):
                err("%s: ID 형식 오류 %s" % (where, nid))
            break
    else:
        err("%s: 모르는 ID 접두사 %s" % (where, nid)); return
    body = nid.split(":", 2)[2]
    if nid.startswith("java:type:"):
        check_java_type(body, where)
    elif nid.startswith("java:method:"):
        cls, rest = body.split("#", 1)
        name, params = rest[:-1].split("(", 1)
        check_java_type(cls, where)
        for p_ in filter(None, params.split(",")):
            check_java_type(p_.replace("[]", ""), where)
        if cls.startswith(P):
            if not method_body(nid) and not re.search(r"\b%s\s*\(" % re.escape(name), read(java_file(cls))):
                err("%s: 메서드 선언 없음 %s" % (where, nid))
            elif not method_body(nid) and name != "<init>" and not read(java_file(cls)).count(" interface "):
                err("%s: 매개변수 타입이 맞는 선언 없음 %s" % (where, nid))
            if FACTS and nid not in FACT_METHODS:
                err("%s: 컴파일 결과에 없는 메서드 시그니처 %s" % (where, nid))
            ok("java method")
    elif nid.startswith("java:field:"):
        cls, fld = body.split("#")
        if not re.search(r"\b%s\b\s*(=|;)" % fld, read(java_file(cls))): err("%s: 필드 없음 %s" % (where, nid))
        ok("java field")
    elif nid.startswith("db:table:"):
        if body not in TABLES_DDL and not any(re.search(r"\b%s\b" % body, s) for s in STMT.values()):
            err("%s: DDL 과 SQL 어디에도 없는 테이블 %s" % (where, body))
        ok("db table")
    elif nid.startswith("db:column:"):
        t, c = body.split(".")
        blk = table_block(t)
        if blk:
            if not re.search(r"^\s*%s\s" % c, blk, flags=re.M): err("%s: 테이블 정의에 없는 컬럼 %s" % (where, body))
        elif not re.search(r"VIEW\s+%s\s*\([^)]*\b%s\b" % (t, c), ALL_DB): err("%s: 컬럼 근거 없음 %s" % (where, body))
        ok("db column")
    elif nid.startswith("db:routine:"):
        name = body.split(".")[-1]
        if not re.search(r"(PROCEDURE|FUNCTION|TRIGGER)\s+%s\b" % name, ALL_DB): err("%s: 루틴 선언 없음 %s" % (where, body))
        if "." in body and not re.search(r"PACKAGE\s+BODY\s+%s\b" % body.split(".")[0], ALL_DB): err("%s: 패키지 없음 %s" % (where, body))
        ok("db routine")
    elif nid.startswith(("db:view:", "db:sequence:", "db:synonym:", "db:package:")):
        kw = {"view": r"VIEW", "sequence": "SEQUENCE", "synonym": "SYNONYM", "package": "PACKAGE"}[nid.split(":")[1]]
        if not re.search(r"%s\s+%s\b" % (kw, body), ALL_DB): err("%s: 선언 없음 %s" % (where, nid))
        ok("db object")
    elif nid.startswith("mybatis:stmt:"):
        if body not in STMT: err("%s: 매퍼에 없는 구문 %s" % (where, body))
        ok("mybatis stmt")
    elif nid.startswith("mybatis:fragment:"):
        if body not in FRAG: err("%s: 매퍼에 없는 조각 %s" % (where, body))
        ok("mybatis fragment")
    elif nid.startswith("mybatis:mapper:"):
        if body not in NAMESPACES: err("%s: 없는 namespace %s" % (where, body))
    elif nid.startswith("sql:stmt:"):
        target, line = body.rsplit(":", 1)
        check_sql_stmt(target, int(line), where)
    elif nid.startswith(("jsp:page:", "js:module:")):
        if not exists(body): err("%s: 파일 없음 %s" % (where, body))
        ok("file")
    elif nid.startswith("js:function:"):
        f, name = body.split("#")
        if not exists(f): err("%s: 파일 없음 %s" % (where, f))
        elif not re.search(r"\b%s\b" % re.escape(name.split(".")[-1]), read(f)): err("%s: 함수 이름 없음 %s" % (where, body))
        ok("js function")

ROUTINE_FILES = {}
for f, text in DDL.items():
    if f.endswith(".pks"):   # 패키지 명세에는 본문이 없다
        continue
    for m in re.finditer(r"(?:PROCEDURE|FUNCTION|TRIGGER)\s+(\w+)", text):
        ROUTINE_FILES.setdefault(m.group(1), f)

def sql_text_at(target, line):
    """(파일, 그 줄부터 구문 끝까지의 텍스트) — 루틴 이름이거나 파일 경로."""
    f = target if "/" in target else ROUTINE_FILES.get(target.split(".")[-1])
    if not f or not exists(f): return None, ""
    lines = read(f).split("\n")
    chunk = []
    for l in lines[line - 1:line + 30]:
        chunk.append(l)
        if ";" in re.sub(r"'[^']*'", "''", l): break
    return f, strip_sql_comments("\n".join(chunk))

def check_sql_stmt(target, line, where):
    f, text = sql_text_at(target, line)
    if not f:
        err("%s: 구문 위치를 찾을 수 없음 %s:%d" % (where, target, line)); return
    if not re.match(r"\s*(FOR \w+ IN \()?(SELECT|INSERT|UPDATE|DELETE|MERGE)\b", text):
        err("%s: %s:%d 는 SQL 구문의 첫 줄이 아니다: %r" % (where, f, line, text[:40]))
    if "/" not in target:   # 그 줄이 정말 그 루틴 안인가
        head = "\n".join(read(f).split("\n")[:line])
        last = re.findall(r"(?:PROCEDURE|FUNCTION|TRIGGER)\s+(\w+)", strip_sql_comments(head))
        if not last or last[-1] != target.split(".")[-1]:
            err("%s: %s:%d 는 루틴 %s 안이 아니다 (직전 선언 %s)" % (where, f, line, target, last[-1:] or None))
    ok("sql stmt line")

def stmt_text(nid):
    if nid.startswith("mybatis:stmt:"): return STMT.get(nid[len("mybatis:stmt:"):], "")
    if nid.startswith("sql:stmt:"):
        target, line = nid[len("sql:stmt:"):].rsplit(":", 1)
        return sql_text_at(target, int(line))[1]
    return ""

def method_parts(mid):
    cls, rest = mid[len("java:method:"):].split("#", 1)
    return cls, rest.split("(")[0]

def simple_params(arglist):
    """소스의 매개변수 목록 → 타입 단순 이름 목록 (애너테이션, 제네릭, final 제거)."""
    a = arglist
    while re.search(r"<[^<>]*>", a): a = re.sub(r"<[^<>]*>", "", a)
    a = re.sub(r'@\w+(\((?:[^()"]|"[^"]*")*\))?', "", a)
    out = []
    for part in a.split(","):
        toks = [t for t in part.split() if t != "final"]
        if len(toks) >= 2: out.append(toks[-2].split(".")[-1])
    return out

def method_body(mid):
    cls, rest = mid[len("java:method:"):].split("#", 1)
    name, params = rest[:-1].split("(", 1)
    want = [p_.split(".")[-1].split("$")[-1] for p_ in params.split(",") if p_]
    text = read(java_file(cls))
    decl = cls.split(".")[-1].split("$")[-1] if name == "<init>" else name
    # 타입 변수(<T extends BaseVO>)는 erasure(상한)로 바꿔서 비교한다
    tvars = dict(re.findall(r"<(\w+) extends (\w+)>", text))
    for m in re.finditer(r"\b%s\s*\(((?:[^()]|\([^()]*\))*)\)[^{;=(]*([{;])" % re.escape(decl), text):
        if [tvars.get(x, x) for x in simple_params(m.group(1))] != want: continue
        if m.group(2) == ";":   # 추상 메서드, 인터페이스 메서드
            return text[m.start():m.end()]
        depth, i = 1, m.end()
        while depth and i < len(text):
            depth += {"{": 1, "}": -1}.get(text[i], 0); i += 1
        return text[m.start():i]
    return ""

# ---------------------------------------------------------------- 엣지 점검
def check_edge(d, where, must_have=True):
    kind = d["kind"]
    ids = [d[k] for k in ("from", "to", "fromRoutine", "statement", "statementRoutine") if k in d]
    ids += d.get("altTo", []) + d.get("altFrom", [])
    if "fromFile" in d and not exists(d["fromFile"]): err("%s: 파일 없음 %s" % (where, d["fromFile"]))
    if not must_have:
        # 함정 항목의 to 는 일부러 없는 ID 일 수 있다. Java ID 와 from 쪽(실제로 있는 노드)만 확인한다.
        for k in ("from", "fromRoutine", "to"):
            nid = d.get(k, "*")
            if nid.startswith("java:") or (k != "to" and nid.startswith(("mybatis:stmt:", "db:routine:PKG_ORDER.CLOSE_DAILY", "db:table:TB_", "batch:"))):
                if not nid.startswith("batch:"): check_id(nid, where)
        frm, to = d.get("from", "*"), d["to"]
        if FACTS and "*" not in frm + to:
            if d["kind"] == "CALLS" and frm.startswith("java:method:") and (frm, to) in FACT_CALLS:
                err("%s: 있으면 안 된다고 한 호출이 컴파일 결과에 있다 %s → %s" % (where, frm, to))
            if d["kind"] in ("EXTENDS", "IMPLEMENTS") and to[len("java:type:"):] in FACTS["supers"].get(frm[len("java:type:"):], []):
                err("%s: 있으면 안 된다고 한 상속/구현이 컴파일 결과에 있다" % where)
            ok("must-not vs bytecode")
        if d["kind"] in ("READS", "WRITES") and frm.startswith("mybatis:stmt:") and to.startswith("db:table:TB_"):
            # 함정이 성립하려면 그 이름이 구문에 실제로 나와야 한다 (읽기만 하는 테이블을 쓰기로 오인하는 경우)
            if not re.search(r"\b%s\b" % to.split(":")[2], stmt_text(frm).upper()): err("%s: 함정 근거 없음 (구문에 이름이 없다)" % where)
            ok("must-not trap text")
        return
    for nid in ids:
        check_id(nid, where)
    frm, to = d.get("from", ""), d["to"]
    if kind in ("READS", "WRITES"):
        text = stmt_text(frm).upper()
        names = [to.split(":")[2]] + [a.split(":")[2] for a in d.get("altTo", [])]
        if not any(re.search(r"\b%s\b" % n, text) for n in names):
            err("%s: 구문 본문에 테이블 이름이 없다 %s → %s" % (where, frm, to))
        if kind == "WRITES":
            op = d["attrs"]["op"]
            if not re.search(r"\b%s\b" % op, text): err("%s: 구문에 %s 가 없다 %s" % (where, op, frm))
        if "fromRoutine" in d and d["fromRoutine"].split(":")[2] != frm[len("sql:stmt:"):].rsplit(":", 1)[0]:
            err("%s: fromRoutine 불일치" % where)
        ok("reads/writes text")
    elif kind == "JOINS":
        text = stmt_text(d["statement"]).upper()
        a, b = frm.split(":")[2], to.split(":")[2]
        for n in (a, b) + tuple(d["attrs"]["leftColumns"]) + tuple(d["attrs"]["rightColumns"]):
            if not re.search(r"\b%s\b" % n, text): err("%s: JOINS 근거 구문에 %s 가 없다 (%s)" % (where, n, d["statement"]))
        blk_a, blk_b = table_block(a), table_block(b)
        if blk_a and not re.search(r"^\s*%s\s" % d["attrs"]["leftColumns"][0], blk_a, flags=re.M): err("%s: %s 에 컬럼 %s 없음" % (where, a, d["attrs"]["leftColumns"][0]))
        if blk_b and not re.search(r"^\s*%s\s" % d["attrs"]["rightColumns"][0], blk_b, flags=re.M): err("%s: %s 에 컬럼 %s 없음" % (where, b, d["attrs"]["rightColumns"][0]))
        ok("joins text")
    elif kind == "REFERENCES":
        child, parent = frm.split(":")[2], to.split(":")[2]
        a = d["attrs"]
        pat = r"FOREIGN\s+KEY\s*\(\s*%s\s*\)\s*REFERENCES\s+(?:\w+\.)?%s\b" % (a["columns"][0], parent)
        inline = r"\b%s\s+[\w(),]+\s+CONSTRAINT\s+\w+\s+REFERENCES\s+(?:\w+\.)?%s\b" % (a["columns"][0], parent)
        has_fk = bool(re.search(pat, ALL_DB)) and (bool(re.search(pat, table_block(child))) or bool(
            re.search(r"ALTER\s+TABLE\s+%s\b[^;]*?%s" % (child, pat), ALL_DB, flags=re.S))) or bool(re.search(inline, table_block(child)))
        if a["source"] == "DDL":
            if not has_fk: err("%s: DDL 에 FK 가 없다 %s → %s" % (where, child, parent))
            if a.get("constraint") and a["constraint"] not in ALL_DB: err("%s: 제약 이름 없음 %s" % (where, a["constraint"]))
        else:
            if has_fk: err("%s: 추론이라고 했지만 DDL 에 FK 가 있다 %s → %s" % (where, child, parent))
            joined = any(re.search(r"\b%s\b" % child, s.upper()) and re.search(r"\b%s\b" % parent, s.upper()) for s in list(STMT.values()) + [ALL_DB])
            if not joined: err("%s: 추론 근거 조인이 없다 %s → %s" % (where, child, parent))
        if not re.search(r"^\s*%s\s" % a["columns"][0], table_block(child), flags=re.M): err("%s: 자식 컬럼 없음" % where)
        if not re.search(r"^\s*%s\s" % a["refColumns"][0], table_block(parent), flags=re.M): err("%s: 부모 컬럼 없음" % where)
        ok("references ddl")
    elif kind == "EXECUTES":
        sid = to[len("mybatis:stmt:"):]
        ns, name = sid.rsplit(".", 1)
        if frm.startswith("batch:step:"):
            if not any(sid in read(f) for f in ["src/main/resources/batch/settle-job.xml", JAVA_ROOT + "com/example/shop/batch/config/DailySalesJobConfig.java"]):
                err("%s: 배치 설정에 구문 ID 문자열이 없다 %s" % (where, sid))
        else:
            cls, mname = method_parts(frm)
            if cls == ns:
                if mname != name: err("%s: 매퍼 메서드 이름과 구문 id 가 다르다" % where)
            else:
                body, text = method_body(frm), read(java_file(cls))
                consts = dict(re.findall(r'String\s+(\w+)\s*=\s*([^;]+);', text))
                if '"%s"' % sid in body: pass
                elif '".%s"' % name in body and 'NAMESPACE = "%s"' % ns in text: pass
                elif any(c in body and '".%s"' % name in v for c, v in consts.items()) and 'NAMESPACE = "%s"' % ns in text: pass
                else: err("%s: 메서드 본문에 구문 ID 근거가 없다 %s → %s" % (where, frm, sid))
        ok("executes text")
    elif kind == "CALLS" and to.startswith("db:routine:"):
        callee = to.split(":")[2]
        if frm.startswith("mybatis:stmt:"):
            if callee not in stmt_text(frm).upper().replace(" ", ""): err("%s: 구문에 루틴 호출이 없다 %s" % (where, callee))
        else:
            f = ROUTINE_FILES[frm.split(":")[2].split(".")[-1]]
            if not re.search(r"\b%s\s*\(" % callee.split(".")[-1], DDL[f]): err("%s: 루틴 본문에 호출이 없다 %s" % (where, callee))
        ok("db calls text")
    elif kind == "CALLS" and frm.startswith("java:method:"):
        callee = method_parts(to)[1]
        body = method_body(frm)
        if callee == "<init>":
            if not re.search(r"\b(this|super|new \w+)\s*\(", body): err("%s: 생성자 호출 근거 없음" % where)
        elif not re.search(r"(\b|::)%s\b" % re.escape(callee), body): err("%s: 호출 근거 없음 %s → %s" % (where, frm, to))
        if FACTS and frm.startswith("java:method:" + P):
            direct = (frm, to) in FACT_CALLS or (frm.split("(")[0] + "(", to) in FACT_CALLS
            via_handle = (method_parts(frm)[0], to) in FACT_HANDLES
            if not (direct or via_handle): err("%s: 컴파일 결과에 없는 호출 %s → %s" % (where, frm, to))
        ok("java calls")
    elif kind in ("EXTENDS", "IMPLEMENTS") and FACTS:
        sub, sup = frm[len("java:type:"):], to[len("java:type:"):]
        if sup not in FACTS["supers"].get(sub, []): err("%s: 컴파일 결과에 없는 상속/구현 %s → %s" % (where, sub, sup))
        ok("java hierarchy")
    elif kind == "OVERRIDES":
        if method_parts(frm)[1] != method_parts(to)[1]: err("%s: OVERRIDES 메서드 이름 불일치" % where)
        ok("overrides")
    elif kind == "ROUTES_TO":
        text = read(java_file(method_parts(to)[0]))
        path = frm.split(" ", 1)[1]
        tail = path.rstrip("/").split("/")[-1]
        if '"%s"' % ("/" + tail) not in text and '/%s"' % tail not in text: err("%s: 컨트롤러에 경로 조각이 없다 %s" % (where, path))
        ok("routes text")
    elif kind == "RENDERS":
        view = to[len("jsp:page:src/main/webapp/WEB-INF/views/"):-len(".jsp")]
        if '"%s"' % view not in method_body(frm): err("%s: 뷰 이름 근거 없음 %s" % (where, view))
        ok("renders text")
    elif kind == "INCLUDES":
        if frm.startswith("mybatis:"):
            ns, fid = to[len("mybatis:fragment:"):].rsplit(".", 1)
            own_ns = frm.split(":")[2].rsplit(".", 1)[0]
            ref = fid if ns == own_ns else ns + "." + fid
            if 'refid="%s"' % ref not in stmt_text(frm): err("%s: include refid 근거 없음 %s" % (where, ref))
        else:
            target = to.split(":", 2)[2].replace("src/main/webapp", "")
            if target not in read(frm.split(":", 2)[2]): err("%s: JSP 에 include 경로가 없다 %s" % (where, target))
        ok("includes text")
    elif kind == "MATCHES":
        line = read(d["fromFile"]).split("\n")[d["line"] - 1:d["line"] + 1]
        text = "\n".join(line)
        frags = [x for x in re.split(r"\{\*\}", d.get("rawUrl", d["requestUrl"])) if x]
        lits = re.findall(r"/[\w./\-]+", " ".join(frags))
        if not lits or not all(l.rstrip("/") in text for l in lits): err("%s: %s:%d 에 URL 근거가 없다 %s" % (where, d["fromFile"], d["line"], lits))
        ok("matches text")

def main():
    have = load("must-have-edges.json") + load("must-have-edges-extra.json")
    have_not = load("must-not-have-edges.json") + load("must-not-have-edges-extra.json")
    nodes = load("must-have-nodes.json")
    endpoints = {d["from"] for d in have if d["kind"] == "ROUTES_TO"}
    for i, d in enumerate(have):
        where = "must-have[%d] %s" % (i, d["kind"])
        check_edge(d, where)
        if d["kind"] == "MATCHES" and d["to"] not in endpoints: err("%s: ROUTES_TO 에 없는 엔드포인트 %s" % (where, d["to"]))
    for i, d in enumerate(have_not):
        check_edge(d, "must-not[%d] %s" % (i, d["kind"]), must_have=False)
    keys = {(d["kind"], d.get("from") or (d["fromFile"], d["line"]), d["to"]) for d in have}
    for d in have_not:
        k = (d["kind"], d.get("from") or (d["fromFile"], d["line"]), d["to"])
        if k in keys and "attrs" not in d: err("must-have 와 must-not 이 모순: %s" % (k,))
    seen = set()
    for d in nodes:
        if d["id"] in seen: err("노드 중복 %s" % d["id"])
        seen.add(d["id"])
        check_id(d["id"], "node " + d["kind"])
        if d["kind"] == "DB_COLUMN" and "attrs" in d:
            t, c = d["id"].split(":")[2].split(".")
            m = re.search(r"^\s*%s\s+([A-Z0-9]+(?:\([\d,]+\))?)(.*)$" % c, table_block(t), flags=re.M)
            if not m or m.group(1) != d["attrs"]["dataType"]: err("컬럼 타입 불일치 %s: %s" % (d["id"], m and m.group(1)))
            elif ("NOT NULL" in m.group(2)) == d["attrs"]["nullable"]: err("컬럼 NULL 여부 불일치 %s" % d["id"])
            cols = [x.group(1) for x in re.finditer(r"^\s*([A-Z0-9_]+)\s+[A-Z0-9]+(?:\([\d,]+\))?", table_block(t), flags=re.M) if x.group(1) not in ("CONSTRAINT", "PRIMARY")]
            if cols.index(c) + 1 != d["attrs"]["ordinal"]: err("컬럼 순번 불일치 %s" % d["id"])
            ok("column attrs")
    # 빠짐 점검: DDL 테이블과 매퍼 구문은 노드 목록에 전부 있어야 한다
    for t in TABLES_DDL:
        if "db:table:" + t not in seen: err("노드 목록에 빠진 테이블 %s" % t)
    for s in STMT:
        if "mybatis:stmt:" + s not in seen: err("노드 목록에 빠진 구문 %s" % s)
    for e in endpoints:
        if e not in seen: err("노드 목록에 빠진 엔드포인트 %s" % e)
    print("확인한 항목:", ", ".join("%s %d" % kv for kv in sorted(checked.items())))
    print("must-have %d, must-not %d, nodes %d" % (len(have), len(have_not), len(nodes)))
    if errors:
        print("\n오류 %d건:" % len(errors))
        for e in errors: print("  -", e)
        sys.exit(1)
    print("오류 없음")

main()
