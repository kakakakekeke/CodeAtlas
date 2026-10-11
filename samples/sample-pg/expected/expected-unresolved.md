# 미해결로 나와야 하는 지점

정적 분석만으로는 대상을 확정할 수 없는 지점이다. 분석 결과에서 조용히 빠지면 안 되고, 추측으로 채워져도 안 된다.
기대하는 모습은 그래프 계약(`docs/specs/F-001-analysis-pipeline/design.md` 3.2, 3.6)의 미해결 표현이다:
`unresolved:<분류>:<원문>` 노드와 `UNRESOLVED` 신뢰도의 엣지, 그리고 진단 `UNRESOLVED_REFERENCE` 또는 `DYNAMIC_SQL`.

경로는 `samples/sample-pg` 기준이다. 줄 번호는 파일의 1부터 센 줄이다. 표의 형식은 `samples/sample-shop/expected/expected-unresolved.md`와 같다.

## 1. 반드시 미해결이어야 하는 지점

| # | 위치 | 내용 | 이유 | 있으면 안 되는 것 |
|---|---|---|---|---|
| U1 | `db/02_routines.sql:173-174` | `EXECUTE format('INSERT INTO %I (run_at, moved) VALUES (now(), $1)', p_stats_table) USING p_moved;` (프로시저 `ARCHIVE_ORDERS`) | `%I` 자리에 오는 값이 매개변수다. 서식 문자열만으로는 테이블을 알 수 없다 | `db:table:P_STATS_TABLE`, `db:table:%I`, `db:table:RUN_AT` 노드. 기대: 구문 `sql:stmt:ARCHIVE_ORDERS:173`(`dynamic=true`)에서 `unresolved:sql-dynamic:sql:stmt:ARCHIVE_ORDERS:173`으로 가는 `UNRESOLVED` `WRITES`(`op=INSERT`)와 진단 `DYNAMIC_SQL` |

## 2. 동적이지만 대상은 정해지는 지점 (미해결 아님)

| # | 위치 | 내용 | 기대 |
|---|---|---|---|
| D1 | `db/02_routines.sql:106-107` | `EXECUTE format('DELETE FROM %I WHERE k = $1', 'session_cache') USING 'order:' \|\| p_order_id::text;` (함수 `CLOSE_ORDER`) | `%I` 자리의 인자가 문자열 리터럴이라 SQL 이 풀린다. `SESSION_CACHE` 로 가는 `RESOLVED` `WRITES`(`op=DELETE`), 구문에 `dynamic=true`. 진단 `DYNAMIC_SQL` 은 없다 |
| D2 | `src/main/resources/mapper/OrderMapper.xml:13` | `selectOrderPage` 의 `<where>`, `<if>`, `<foreach>`, `<include>` | 분기 안의 테이블·조건을 모두 합쳐서 본다. 미해결이 아니다. `${}` 치환은 이 샘플에 없다 |
| D3 | `src/main/resources/mapper/CustomerMapper.xml:91` | `selectOrderSummary` 의 `<if>` | D2 와 같다 |

## 3. 환경에 따라 달라지는 미해결

- 이 샘플의 Java 코드는 라이브러리 타입의 메서드를 부르지 않는다(Spring·MyBatis 는 애너테이션만 쓴다). JDK 타입의 메서드 호출(`HashMap.put`, `Map.get`)이 어떻게 남는지는 단정하지 않는다. 프로젝트 안의 타입끼리의 호출(컨트롤러 → 서비스 → 매퍼)은 jar 없이도 전부 해석돼야 한다.
- `CustomerMapper.selectTableSizes` 가 읽는 `pg_catalog.pg_class` 는 DDL 에 없다. 미해결이 아니라 `declared=false`, `systemCatalog=true` 인 `db:table:PG_CLASS` 노드로 남는다.
- 트리거 함수 `TRG_ORDERS_AUDIT` 의 `NEW.id`, `NEW.status`, `OLD.status`, `TG_OP` 는 변수다. 미해결로도 컬럼으로도 남지 않는다.
- `db/02_routines.sql:208` 의 `LANGUAGE c` 함수 `FAST_HASH` 는 본문을 분석하지 않는다. 루틴 노드만 있고 `language` 가 없으며 진단이 없어야 한다(F-019-AC22).

## 4. 일부러 넣은 미지원 구문

F-019 의 범위에 없지만 pg_dump 출력에 실제로 나오는 구문이다. 분석이 멈추지 않아야 하고, 이 줄에 한해 진단 `UNSUPPORTED_SYNTAX` 또는 `PARSE_FAILED` 가 남아도 된다(조용히 넘겨도 된다). **이 표에 없는 위치의 `PARSE_FAILED`·`UNSUPPORTED_SYNTAX` 는 실패다**(F-019-AC34).

| # | 위치 | 내용 | 있으면 안 되는 것 |
|---|---|---|---|
| N1 | `db/01_schema.sql:517` | `REFRESH MATERIALIZED VIEW public.mv_daily_sales;` | 이 줄에서 나온 테이블·구문 노드 |

이 샘플에는 깨진 입력(`broken/`)이 없다. 파일 단위 파싱 실패로 기록돼야 하는 파일은 없다.
