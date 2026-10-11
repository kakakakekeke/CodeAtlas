# 변경 기록

사용자가 알아챌 수 있는 변경(기능 추가, 동작·출력·옵션의 변경, 버그 수정)을 적는다. 작성 규칙은 [AGENTS.md](AGENTS.md)의 "변경 기록"에 있다.

- 새 항목은 `[Unreleased]`의 맞는 분류(추가, 변경, 수정, 제거) 맨 위에 넣는다.
- 한 항목은 `기능 ID: 사용자 관점에서 무엇이 달라졌는지 (날짜)` 꼴로 적는다.
- 이 파일을 만들기 전(2026-10-11 이전)의 이력은 `git log`와 [docs/features.md](docs/features.md)에 있다.

## [Unreleased]

### 변경
- F-015: `impact`가 forward로 넘어간 다른 엔드포인트의 핸들러를 거쳐서만 닿은 항목(호출 사슬, 엔드포인트, 화면)을 핸들러 자신의 호출 사슬로 닿은 항목과 구분해 낸다. 그런 항목은 이름 앞에 `[forward]`가 붙고, 경로의 넘어간 대상 핸들러 앞에 `[forward]`와 끝에 `(forward: <넘어간 경로>)`가 붙으며, 머리의 개수 요약과 계층 제목이 `엔드포인트 43개(직접 39, forward 경유 4)`, `## 엔드포인트 (43: 직접 39, forward 경유 4)`처럼 나뉜다. `--format json`의 항목에는 `viaForward`와 `forward`(`from`, `to`, `target`) 필드가 더해졌다. forward 경유 항목이 있는 리포트의 Markdown이 달라지므로(이름 칸, 경로 칸, 머리 요약 줄, `## 계층 (n)` 제목) 그 글자를 그대로 읽던 스크립트는 고쳐야 한다. forward로도 넘어가고 자기 호출 사슬로도 닿는 엔드포인트의 경로는 이제 forward를 거치지 않는 쪽으로 적힌다. 항목의 수와 forward 경유 항목이 없는 리포트의 Markdown은 그대로다. (2026-10-11)

### 추가
- F-019: PostgreSQL을 Oracle·DB2와 같은 대상 방언으로 읽는다. `--dialect postgres`(별칭 `postgresql`, `pgsql`)를 받고, 경로의 방언 이름(`postgres/`, `_pgsql`, `.pgsql`)이나 내용(`$$` 본문, `ALTER TABLE ONLY`, `COPY … FROM stdin` 등)으로 PostgreSQL을 알아본다. pg_dump 평문 DDL, PostgreSQL DML(`ON CONFLICT`, `DELETE … USING`, `WITH` 안의 변경 구문, `::`·`->>`·`#>>`, `nextval('seq')`), PL/pgSQL 함수·프로시저·트리거·`DO` 블록이 테이블·컬럼·호출 관계로 들어간다. 전에는 PostgreSQL 프로젝트에서 `ONLY`라는 테이블과 매개변수 이름의 컬럼이 생기고 루틴 본문의 SQL이 빠졌다. PostgreSQL로 읽을 때 `"orders"`처럼 대문자가 없는 따옴표 이름은 따옴표 없는 `orders`와 같은 테이블로 본다. 노드의 `dialect`에 `POSTGRES`, 루틴의 `language`에 `PLPGSQL`·`SQL`, 파티션 테이블에 `partitionOf`가 새로 나온다. (2026-10-11)
- F-016: 뷰어의 노드 상세에 "← 뒤로"·"앞으로 →" 단추를 더했다. 탐색 화면에서 상세 안의 엣지나 속성 값 링크로 A → B → C로 옮겨 간 뒤 지나온 노드로 돌아갈 수 있고, 브라우저의 뒤로·앞으로 가기로도 같은 노드로 돌아온다. 다른 화면(콜트리, 진단 등)의 상세 서랍에도 같은 단추가 있다. 목록에서 연달아 고른 노드는 한 칸으로 친다. 이미 만든 `viewer.html`에는 `codeatlas export <출력 폴더> --out <출력 폴더>`로 다시 만들어야 반영된다. (2026-10-11)

### 변경
- F-019: 방언별로 여러 벌인 매퍼·DDL에서 한 벌을 고르는 순위에 PostgreSQL이 들어갔다(Oracle → DB2 → PostgreSQL → Tibero·Altibase → 그 밖). Oracle·DB2 파일이 있는 프로젝트의 결과는 그대로이고, 그 둘이 없고 postgres 파일이 있는 프로젝트는 이제 postgres 파일을 쓴다(전에는 Tibero·Altibase나 사전순으로 앞선 mysql 등을 썼다). 방언 진단의 문구와 방언 나열 순서도 달라졌다. (2026-10-11)
- F-019: PostgreSQL 문법 가운데 다른 방언과 겹치지 않는 것은 방언과 무관하게 읽으므로 Oracle·DB2·MySQL 프로젝트에서도 다음이 달라진다: `id SERIAL` 컬럼이 자동 증가(`identity`)로 표시되고, `CREATE TABLE a (LIKE b)`가 b의 컬럼을 가져오며, 이름 없는 `CREATE INDEX ON t (a)`가 인덱스로 기록되고, 관계 추론에서 `TEXT`와 `VARCHAR` 컬럼의 조인에 타입 불일치 표시가 붙지 않는다. (2026-10-11)

### 수정
- F-019: PostgreSQL의 `COPY (쿼리) TO …`에서 괄호 안 쿼리가 읽는 테이블·컬럼과 조인을 기록한다. 전에는 스크립트와 매퍼에서 이 구문을 진단 없이 넘겨 읽기가 빠졌다. 이런 구문이 있는 프로젝트는 구문 노드와 읽기·조인 엣지가 늘어난다. PL/pgSQL 본문에서는 읽기는 기록됐지만 "지원하지 않는 구문" 진단이 함께 남았는데, 이제 진단 없이 구문 하나로 읽는다. `COPY 테이블 TO …`와 `COPY 테이블 FROM '경로'`는 전과 같이 넘긴다. (2026-10-11)
- F-006: `.properties` 파일의 값에 든 SQL(보안 프레임워크의 권한 조회 SQL처럼 설정 파일에 적어 두고 라이브러리가 실행하는 구문)을 구문으로 읽는다. 전에는 빠져 있던 이 구문들이 영향도(`impact`), CRUD 매트릭스, ERD의 추론 관계에 나오고, 그 구문만 읽던 테이블이 리포트의 "어떤 구문에서도 참조되지 않는 테이블"에서 빠진다. 구문 수와 `READS`·`JOINS` 수가 늘어 기존 결과가 달라진다. 화면 문구(메시지 번들)와 `SELECT 1 FROM DUAL` 같은 연결 확인 쿼리는 읽지 않는다. (2026-10-11)
