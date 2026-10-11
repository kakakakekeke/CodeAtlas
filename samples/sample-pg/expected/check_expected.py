#!/usr/bin/env python3
"""sample-pg 정답 목록을 소스 텍스트와 대조하는 점검 스크립트 (분석기와 무관).

    cd samples/sample-pg
    python3 expected/check_expected.py

보는 것
- JSON 형식, ID 접두사와 kind 의 짝
- 테이블·뷰·시퀀스·루틴이 DDL 에 있는지, 컬럼의 순번·타입·NOT NULL (LIKE, INHERITS, PARTITION OF 를 펼쳐서)
- 루틴 안 구문 ID 의 줄이 그 루틴 본문 안이고 SQL 키워드로 시작하는지
- fromFile+line 이 가리키는 줄이 구문의 시작인지
- 매퍼 구문·조각·Java 메서드가 소스에 있는지, 구문 본문에 그 테이블·컬럼·루틴 이름이 있는지
- DDL 외래 키가 주석 밖에 있는지 / 추론 관계에 DDL 외래 키가 없는지
- must-have 와 must-not 의 모순, 함정이 실제 테이블·컬럼을 가리키지 않는지

간단한 정규식으로 읽는다. SQL 을 제대로 파싱하지 않으므로 이 샘플의 꼴에만 맞는다.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXPECTED = ROOT / "expected"
SCHEMA = "db/01_schema.sql"
ROUTINES = "db/02_routines.sql"
MAPPER_DIR = ROOT / "src/main/resources/mapper"
JAVA_DIR = ROOT / "src/main/java"

PREFIX_KIND = {
    "db:table:": "DB_TABLE", "db:view:": "DB_VIEW", "db:column:": "DB_COLUMN", "db:sequence:": "DB_SEQUENCE",
    "db:routine:": "DB_ROUTINE", "mybatis:mapper:": "MYBATIS_MAPPER", "mybatis:stmt:": "SQL_STATEMENT",
    "sql:stmt:": "SQL_STATEMENT", "mybatis:fragment:": "SQL_FRAGMENT", "http:endpoint:": "HTTP_ENDPOINT",
    "spring:bean:": "SPRING_BEAN", "java:type:": "JAVA_TYPE", "java:method:": "JAVA_METHOD",
}
SQL_START = re.compile(r"^(SELECT|INSERT|UPDATE|DELETE|WITH|EXECUTE)\b", re.I)

errors = []


def err(message):
    errors.append(message)


def load(name):
    return json.loads((EXPECTED / name).read_text(encoding="utf-8"))


def lines_of(rel):
    return (ROOT / rel).read_text(encoding="utf-8").split("\n")


def strip_comments(text):
    """SQL 주석(-- 줄, /* */)과 XML 주석을 지운다."""
    text = re.sub(r"<!--.*?-->", " ", text, flags=re.S)
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return "\n".join(re.sub(r"--.*$", "", line) for line in text.split("\n"))


# ---------------------------------------------------------------- DDL 읽기

def read_schema():
    raw = lines_of(SCHEMA)
    # COPY 데이터 줄은 SQL 이 아니다
    cleaned, in_copy = [], False
    for line in raw:
        if in_copy:
            in_copy = line != "\\."
            cleaned.append("")
            continue
        cleaned.append(line)
        in_copy = bool(re.match(r"^COPY .* FROM stdin;$", line))
    text = strip_comments("\n".join(cleaned))
    tables, views, sequences = {}, set(), set()
    pending = []
    for m in re.finditer(r"CREATE (?:UNLOGGED )?TABLE public\.(\w+) (PARTITION OF public\.(\w+)|\((.*?)\n\))([^;]*);", text, re.S):
        name = m.group(1).upper()
        if m.group(3):
            pending.append((name, "partition", m.group(3).upper(), []))
            continue
        own, like = [], None
        for item in m.group(4).split(",\n"):
            item = item.strip()
            if not item or item.upper().startswith("CONSTRAINT"):
                continue
            if item.upper().startswith("LIKE"):
                like = re.match(r"LIKE public\.(\w+)", item, re.I).group(1).upper()
                own.append(("__LIKE__", like, False))
                continue
            col, rest = item.split(None, 1)
            dtype = re.split(r"\s+(?:NOT NULL|DEFAULT|GENERATED)\b", rest, maxsplit=1, flags=re.I)[0]
            own.append((col.upper(), dtype, "NOT NULL" in rest.upper()))
        inherits = re.search(r"INHERITS \(public\.(\w+)\)", m.group(5))
        pending.append((name, "table", inherits.group(1).upper() if inherits else None, own))
    for name, kind, parent, own in pending:  # 정의 순서대로 (부모가 먼저 나온다)
        cols = []
        if kind == "partition" or parent:
            cols.extend((c, t, None) for c, t, _ in tables[parent])
        for col, dtype, notnull in own:
            if col == "__LIKE__":
                cols.extend((c, t, None) for c, t, _ in tables[dtype])
            else:
                cols.append((col, dtype, notnull))
        tables[name] = cols
    for m in re.finditer(r"CREATE (?:MATERIALIZED )?VIEW public\.(\w+)", text):
        views.add(m.group(1).upper())
    for m in re.finditer(r"CREATE SEQUENCE public\.(\w+)", text):
        sequences.add(m.group(1).upper())
    fks = set()
    for m in re.finditer(r"ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT \w+ FOREIGN KEY \((\w+)\) REFERENCES public\.(\w+)\((\w+)\)", text):
        fks.add(tuple(g.upper() for g in m.groups()))
    pks = {}
    for m in re.finditer(r"ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT \w+ PRIMARY KEY \((\w+)\)", text):
        pks[m.group(1).upper()] = m.group(2).upper()
    return raw, tables, views, sequences, fks, pks


def read_routines():
    raw = lines_of(ROUTINES)
    starts = []
    for i, line in enumerate(raw, 1):
        m = re.match(r"CREATE (?:OR REPLACE )?(FUNCTION|PROCEDURE|TRIGGER) (?:public\.)?(\w+)", line)
        if m:
            starts.append((i, m.group(2).upper(), m.group(1)))
    ranges = {}
    for idx, (line, name, kind) in enumerate(starts):
        end = starts[idx + 1][0] - 1 if idx + 1 < len(starts) else len(raw)
        ranges[name] = (line, end, kind)
    return raw, ranges


def read_mappers():
    statements, fragments = {}, {}
    for path in sorted(MAPPER_DIR.glob("*.xml")):
        text = path.read_text(encoding="utf-8")
        ns = re.search(r'<mapper namespace="([^"]+)"', text).group(1)
        text = re.sub(r"<!--.*?-->", " ", text, flags=re.S)
        for m in re.finditer(r'<sql id="(\w+)">(.*?)</sql>', text, re.S):
            fragments[ns + "." + m.group(1)] = m.group(2)
        for m in re.finditer(r'<(select|insert|update|delete) id="(\w+)"[^>]*>(.*?)</\1>', text, re.S):
            body = re.sub(r'<include refid="(\w+)"/>', lambda r: fragments.get(ns + "." + r.group(1), ""), m.group(3))
            statements[ns + "." + m.group(2)] = strip_comments(body)
    return statements, fragments


def java_methods():
    found = set()
    for path in JAVA_DIR.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        pkg = re.search(r"^package ([\w.]+);", text, re.M).group(1)
        type_name = pkg + "." + path.stem
        found.add(("type", type_name))
        text = re.sub(r"@\w+(\([^)]*\))?", "", text)  # 애너테이션(인자 포함)을 지우고 시그니처를 읽는다
        for m in re.finditer(r"^\s+(?:public\s+)?[\w<>\[\], .?]+\s+(\w+)\(([^)]*)\)\s*[{;]", text, re.M):
            params = [p for p in re.split(r",(?![^<]*>)", m.group(2)) if p.strip()]
            found.add((type_name, m.group(1), len(params)))
        for m in re.finditer(r"^\s+public\s+" + path.stem + r"\(([^)]*)\)\s*\{", text, re.M):
            found.add((type_name, "<init>", len([p for p in m.group(1).split(",") if p.strip()])))
    return found


def word_in(word, text):
    return re.search(r"(?<![\w.])" + re.escape(word) + r"(?!\w)|\." + re.escape(word) + r"(?!\w)", text, re.I) is not None


def norm_type(t):
    return re.sub(r"\s+", "", t).upper()


def id_matches(pattern, value):
    if pattern == "*":
        return True
    if pattern.endswith("*"):
        return value.startswith(pattern[:-1])
    return pattern == value


def main():
    nodes = load("must-have-nodes.json")
    edges = load("must-have-edges.json")
    must_not = load("must-not-have-edges.json")
    schema_lines, tables, views, sequences, fks, pks = read_schema()
    routine_lines, routines = read_routines()
    statements, fragments = read_mappers()
    java = java_methods()
    file_lines = {SCHEMA: schema_lines, ROUTINES: routine_lines}

    def kind_of(node_id):
        for prefix, kind in PREFIX_KIND.items():
            if node_id.startswith(prefix):
                return kind
        return None

    def check_statement_id(node_id, where):
        """sql:stmt:<루틴>:<줄> 또는 sql:stmt:<파일>:<줄>."""
        rest = node_id[len("sql:stmt:"):]
        owner, line = rest.rsplit(":", 1)
        line = int(line)
        if owner in file_lines:
            text = file_lines[owner][line - 1]
            if not re.match(r"^(CREATE (MATERIALIZED )?VIEW|COPY|DO)\b", text):
                err(f"{where}: {node_id} 의 줄이 구문 시작이 아니다: {text!r}")
            return
        if owner not in routines:
            err(f"{where}: 루틴 {owner} 이 {ROUTINES} 에 없다")
            return
        start, end, _ = routines[owner]
        text = routine_lines[line - 1].strip()
        if not (start < line <= end) or not SQL_START.match(text):
            err(f"{where}: {node_id} 의 줄이 {owner}({start}-{end}) 본문의 SQL 키워드 줄이 아니다: {text!r}")

    def exists(node_id, where):
        if node_id.startswith("db:table:"):
            name = node_id[9:]
            if name not in tables and name not in views and name != "PG_CLASS":
                err(f"{where}: 테이블 {name} 이 DDL 에 없다")
        elif node_id.startswith("db:view:"):
            if node_id[8:] not in views:
                err(f"{where}: 뷰 {node_id[8:]} 가 DDL 에 없다")
        elif node_id.startswith("db:sequence:"):
            if node_id[12:] not in sequences:
                err(f"{where}: 시퀀스 {node_id[12:]} 가 DDL 에 없다")
        elif node_id.startswith("db:column:"):
            table, column = node_id[10:].split(".")
            if column not in [c for c, _, _ in tables.get(table, [])]:
                err(f"{where}: 컬럼 {table}.{column} 이 DDL 에 없다")
        elif node_id.startswith("db:routine:"):
            if node_id[11:] not in routines:
                err(f"{where}: 루틴 {node_id[11:]} 이 {ROUTINES} 에 없다")
        elif node_id.startswith("sql:stmt:"):
            check_statement_id(node_id, where)
        elif node_id.startswith("mybatis:stmt:"):
            if node_id[13:] not in statements:
                err(f"{where}: 매퍼 구문 {node_id[13:]} 이 XML 에 없다")
        elif node_id.startswith("mybatis:fragment:"):
            if node_id[17:] not in fragments:
                err(f"{where}: 조각 {node_id[17:]} 이 XML 에 없다")
        elif node_id.startswith("java:type:"):
            if ("type", node_id[10:]) not in java:
                err(f"{where}: 타입 {node_id[10:]} 이 소스에 없다")
        elif node_id.startswith("java:method:"):
            type_name, rest = node_id[12:].split("#")
            name, params = rest[:-1].split("(")
            count = len([p for p in params.split(",") if p])
            if (type_name, name, count) not in java:
                err(f"{where}: 메서드 {type_name}#{name}/{count} 이 소스에 없다")

    # ---- 노드
    for i, node in enumerate(nodes):
        where = f"nodes[{i}] {node['id']}"
        if kind_of(node["id"]) != node["kind"]:
            err(f"{where}: ID 접두사와 kind {node['kind']} 가 맞지 않는다")
        exists(node["id"], where)
        attrs = node.get("attrs", {})
        if node["kind"] == "DB_COLUMN":
            table, column = node["id"][10:].split(".")
            cols = tables.get(table, [])
            names = [c for c, _, _ in cols]
            if column in names:
                ordinal = names.index(column) + 1
                _, dtype, notnull = cols[ordinal - 1]
                if "ordinal" in attrs and attrs["ordinal"] != ordinal:
                    err(f"{where}: ordinal 기대 {attrs['ordinal']}, DDL {ordinal}")
                if "dataType" in attrs and norm_type(attrs["dataType"]) != norm_type(dtype):
                    err(f"{where}: dataType 기대 {attrs['dataType']}, DDL {dtype}")
                if "nullable" in attrs and notnull is not None and attrs["nullable"] == notnull:
                    err(f"{where}: nullable 기대 {attrs['nullable']}, DDL NOT NULL={notnull}")
                if "primaryKey" in attrs and attrs["primaryKey"] != (pks.get(table) == column):
                    err(f"{where}: primaryKey 기대 {attrs['primaryKey']}, DDL {pks.get(table)}")
        if node["kind"] == "DB_TABLE" and "primaryKey" in attrs and attrs["primaryKey"] != [pks.get(node["id"][9:])]:
            err(f"{where}: primaryKey 기대 {attrs['primaryKey']}, DDL {pks.get(node['id'][9:])}")
        if node["kind"] == "DB_ROUTINE" and "routineType" in attrs:
            actual = routines.get(node["id"][11:], (0, 0, None))[2]
            if actual != attrs["routineType"]:
                err(f"{where}: routineType 기대 {attrs['routineType']}, 소스 {actual}")
    declared = {n["id"] for n in nodes}
    for table in tables:
        if "db:table:" + table not in declared:
            err(f"DDL 의 테이블 {table} 이 must-have-nodes 에 없다")
    for name in routines:
        if "db:routine:" + name not in declared:
            err(f"루틴 {name} 이 must-have-nodes 에 없다")
    for name in statements:
        if "mybatis:stmt:" + name not in declared:
            err(f"매퍼 구문 {name} 이 must-have-nodes 에 없다")

    # ---- 엣지
    for i, edge in enumerate(edges):
        where = f"edges[{i}] {edge['from']} -{edge['kind']}-> {edge['to']}"
        exists(edge["from"], where)
        exists(edge["to"], where)
        if "fromRoutine" in edge:
            owner = edge["from"][len("sql:stmt:"):].rsplit(":", 1)[0]
            if edge["fromRoutine"] != "db:routine:" + owner:
                err(f"{where}: fromRoutine 이 구문 ID 의 루틴과 다르다")
        if "fromFile" in edge and edge["from"] != f"sql:stmt:{edge['fromFile']}:{edge['line']}":
            err(f"{where}: fromFile/line 이 from 과 다르다")
        if edge["from"].startswith("mybatis:stmt:") and edge["kind"] in ("READS", "WRITES", "CALLS"):
            body = statements.get(edge["from"][13:], "")
            name = edge["to"].split(":", 2)[2].split(".")[-1]
            if not word_in(name, body):
                err(f"{where}: 구문 본문에 {name} 이 없다")
        if edge["kind"] == "JOINS":
            body = statements.get(edge.get("statement", "")[13:], None)
            for side, key in (("from", "leftColumns"), ("to", "rightColumns")):
                for column in edge["attrs"][key]:
                    exists(f"db:column:{edge[side][9:]}.{column}", where)
                    if body is not None and not word_in(column, body):
                        err(f"{where}: 근거 구문에 {column} 이 없다")
            if edge.get("statement", "").startswith("sql:stmt:"):
                check_statement_id(edge["statement"], where)
        if edge["kind"] == "REFERENCES":
            key = (edge["from"][9:], edge["attrs"]["columns"][0], edge["to"][9:], edge["attrs"]["refColumns"][0])
            if (edge["attrs"]["source"] == "DDL") != (key in fks):
                err(f"{where}: source={edge['attrs']['source']} 인데 DDL 외래 키 유무가 맞지 않는다")
            if edge["attrs"]["source"] == "INFERRED" and pks.get(key[2]) != key[3]:
                err(f"{where}: 추론 관계의 부모 컬럼이 단일 PK 가 아니다")

    # ---- 함정
    real_ids = {"db:table:" + t for t in tables} | {"db:view:" + v for v in views}
    real_ids |= {f"db:column:{t}.{c}" for t, cols in tables.items() for c, _, _ in cols}
    for i, rule in enumerate(must_not):
        where = f"must-not[{i}] {rule['from']} -{rule['kind']}-> {rule['to']}"
        if not rule.get("note", "").startswith("함정 "):
            err(f"{where}: note 가 '함정 <번호>' 로 시작하지 않는다")
        if rule["from"] == "*" and rule["kind"] == "*" and "fromRoutine" not in rule:
            hit = [r for r in real_ids if id_matches(rule["to"], r)]
            if hit:
                err(f"{where}: 실제 객체를 가리킨다: {hit[:3]}")
        for j, edge in enumerate(edges):
            if rule["kind"] not in ("*", edge["kind"]):
                continue
            froms = [edge["from"]] + edge.get("altFrom", [])
            tos = [edge["to"]] + edge.get("altTo", [])
            if not any(id_matches(rule["to"], t) for t in tos) or not any(id_matches(rule["from"], f) for f in froms):
                continue
            if "fromRoutine" in rule and edge.get("fromRoutine") != rule["fromRoutine"] and edge["from"] != rule["fromRoutine"]:
                continue
            if any(edge.get("attrs", {}).get(k) != v for k, v in rule.get("attrs", {}).items()):
                continue
            err(f"{where}: must-have edges[{j}] 와 모순된다")

    if errors:
        print(f"문제 {len(errors)}건")
        for message in errors:
            print(" -", message)
        return 1
    print(f"정답 점검 통과: 노드 {len(nodes)}, 엣지 {len(edges)}, 함정 {len(must_not)} "
          f"(테이블 {len(tables)}, 뷰 {len(views)}, 시퀀스 {len(sequences)}, 루틴 {len(routines)}, 매퍼 구문 {len(statements)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
