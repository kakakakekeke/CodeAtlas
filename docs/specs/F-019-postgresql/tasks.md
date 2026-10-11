---
type: tasks
id: F-019
updated: 2026-10-11
---

# F-019 PostgreSQL 지원 작업 목록

테스트: `mvn -q -pl codeatlas-sql test` (모듈별), 전체는 루트에서 `mvn -q install`

## 구현 (작업마다 테스트 먼저)

### 1단계: 통로 (동작을 바꾸지 않는다)
- [x] T1. core `Dialect.POSTGRES`와 `parse` 별칭, `FileClassifier`의 `pgsql` — F-019-AC1의 core 부분 테스트 → 구현 → `mvn -q -pl codeatlas-core install` (F-019-R1, R2)
- [x] T2. sql `lexer/Syntax`를 만들고 `Lexer`·`ScriptSplitter`·`SqlParser`·`DdlParser`의 `boolean mysql`을 `Syntax`로 바꾼다. `parser/RoutineLanguage`를 만들고 `RoutineParser`의 `boolean sqlPl`을 바꾼다. F-019-AC6 테스트(기본 읽기 방식에서 `#`·`$`가 이름) → 기존 테스트 전부 통과 확인 (F-019-R6, R8)

### 2단계: 판정과 선택
- [x] T3. F-019-AC2, AC3 테스트 → `DialectDetector`의 PostgreSQL 내용 특징·토큰 점수, `fromName`·`fromPath`, 파일 구문의 `dialect=POSTGRES` (F-019-R2, R3)
- [x] T4. F-019-AC7 테스트 → `DialectDetector.syntaxOfFile`·`syntaxOfEmbedded`, `SqlAnalyzer`가 파일과 내장 소스에 읽기 방식을 정해 넘김 (F-019-R7)
- [x] T5. F-019-AC4, AC5 테스트(sql 쪽) → `DialectTokens` 순서, `DialectSelection`의 대상 방언과 순위, `DIALECT_NOT_TARGET` (F-019-R4, R5). 순위가 바뀌어 기대가 달라지는 기존 테스트는 새 수용 기준 ID를 적어 고친다
- [x] T6. F-019-AC4(mybatis 쪽), AC30 테스트 → `DialectVariants`의 대상 방언과 순위, 내장 소스 `attrs.dialect`, 양쪽 방언 표 테스트 맞춤 (F-019-R4, R32)
- [x] T7. F-019-AC31 테스트 → springxml `BatchGraphWriter` (F-019-R33)
- [x] T8. F-019-AC1(cli), AC32 테스트 → `AnalyzeCommand`, `Hints`, 계약 4.5·README 대조 (F-019-R1, R34)

### 3단계: 렉서, 분할기, DML
- [x] T9. F-019-AC8, AC9 테스트 → 달러 인용, `E''`, `$n`, 연산자 (F-019-R9, R10)
- [x] T10. F-019-AC10 테스트 → `COPY … FROM stdin` 구간과 데이터 적재, psql 메타 명령 (F-019-R11, R23)
- [x] T11. F-019-AC11, AC12, AC13 테스트 → `ON CONFLICT`, `DELETE … USING`, 데이터 변경 CTE (F-019-R12, R13, R14)
- [x] T12. F-019-AC14 테스트 → 그 밖의 식과 절 (F-019-R15)
- [x] T13. F-019-AC15, AC16 테스트 → 시퀀스 함수, 시스템 카탈로그 (F-019-R16, R17)

### 4단계: DDL
- [x] T14. F-019-AC17, AC18 테스트 → `ALTER TABLE ONLY`, `CREATE TABLE` 수식어와 serial (F-019-R18, R19)
- [x] T15. F-019-AC19 테스트 → `LIKE`, `INHERITS`, `PARTITION OF` (F-019-R20)
- [x] T16. F-019-AC20, AC21 테스트 → 인덱스, pg_dump 부속 구문 (F-019-R21, R22)

### 5단계: 루틴
- [x] T17. F-019-AC22 테스트 → 루틴 헤더와 달러 인용 본문, `language` (F-019-R24)
- [x] T18. F-019-AC23, AC24 테스트 → PL/pgSQL 본문 문장, 본문의 SQL 구문과 변수 스코프 (F-019-R25, R26)
- [x] T19. F-019-AC25, AC26 테스트 → 호출, 동적 SQL (F-019-R27, R28)
- [x] T20. F-019-AC27, AC28, AC29 테스트 → 트리거, `DO`, `LANGUAGE sql` (F-019-R29, R30, R31)

### 6단계: 그 밖의 모듈과 검증
- [x] T21. F-019-AC33 테스트 → link 관계 추론의 타입 별칭 (F-019-R35)
- [x] T22. `samples/sample-pg`와 `expected/`, F-019-AC34 통합 테스트(`SamplePgIT`) (F-019-R36)
- [x] T23. F-019-AC35: 루트 `mvn -q install`과 `mvn -q -pl codeatlas-cli verify`로 sample-shop 통과율 100% 확인, 뷰어 JS 테스트 (F-019-R37, R8)

### 리뷰 뒤 고침
- [x] T24. F-019-AC36 테스트 → `COPY (쿼리) TO`의 쿼리를 스크립트·매퍼·루틴 본문에서 읽음. `COPY 테이블 TO|FROM '경로'`는 그대로 (F-019-R38)

## 마무리
- [x] 모든 수용 기준 통과 확인
- [x] 그래프 계약(F-001 design.md 3.5·3.7·3.8·4.5), README, CHANGELOG 반영
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경
- [x] architecture.md 갱신(4절 해결 전략, 5절 sql 모듈, 11절 검증 범위. 9절은 decisions 폴더를 가리킬 뿐이라 고칠 것이 없다)
