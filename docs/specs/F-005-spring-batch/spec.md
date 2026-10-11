---
type: spec
id: F-005
title: Spring Batch 해석 (Java 설정)
status: implemented     # draft | approved | implemented
updated: 2026-10-11
---

# F-005 Spring Batch 해석 (Java 설정)

## 1. 배경
배치 프로그램의 처리 흐름은 Job → Step → Reader/Processor/Writer·Tasklet → SQL로 이어진다(PRD 6). Java 설정에서는 이 구성이 빌더 호출 체인 안에 있어 일반 콜 그래프로는 드러나지 않는다. 이 기능은 빌더 체인을 읽어 잡, 스텝, 스텝 흐름, 구성 요소를 그래프에 올린다. Spring Batch jar가 없어도 구문 구조만으로 해석한다.

## 2. 범위
- 포함: `JobBuilder`/`JobBuilderFactory`, `StepBuilder`/`StepBuilderFactory` 체인의 `BATCH_JOB`, `BATCH_STEP`, `CONTAINS`, `NEXT`, `USES`, MyBatis 리더·라이터의 `EXECUTES`.
- 제외: XML 잡 설정(F-017), `Flow`·`split`·파티셔닝·`JobExecutionDecider`의 세부 흐름, JDBC 리더·라이터의 인라인 SQL, 잡 파라미터 값.

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-005-R1 | 시스템은 `new JobBuilder("name", ..)`와 `jobBuilderFactory.get("name")` 체인마다 `BATCH_JOB` 노드를 만들어야 한다. | 사용자 요청, design 3.3 |
| F-005-R2 | 시스템은 잡 체인의 `start`, `next`, `flow`, `from`, `on(..).to(..)`에서 `CONTAINS`(JOB→STEP)와 `NEXT`(STEP→STEP, `attrs.on`)를 만들어야 한다. | 사용자 요청, design 3.4 |
| F-005-R3 | 시스템은 `new StepBuilder("name", ..)`와 `stepBuilderFactory.get("name")` 체인마다 `BATCH_STEP` 노드를 만들어야 한다. 잡을 알 수 없는 스텝은 `batch:step:?/<이름>`이다. | 사용자 요청, design 3.3 |
| F-005-R4 | 시스템은 스텝 체인의 `reader`, `processor`, `writer`, `tasklet`, `listener` 인자마다 `USES`(STEP→대상, `attrs.role`)를 만들어야 한다. 대상은 `@Bean` 메서드 호출이면 그 METHOD, 매개변수·필드면 그 빈의 METHOD 또는 TYPE, `new`면 TYPE, 람다면 감싼 METHOD다. | 사용자 요청, design 3.4 |
| F-005-R5 | 잡 체인의 스텝 인자가 메서드 호출이거나 주입된 `Step` 매개변수·필드이면, 시스템은 그 `@Bean` 메서드가 만드는 스텝 이름으로 연결해야 한다. | 사용자 요청 |
| F-005-R6 | 스텝이 쓰는 리더·라이터가 `MyBatisPagingItemReader`/`MyBatisCursorItemReader`/`MyBatisBatchItemWriter`(빌더 포함)이고 `queryId`/`statementId`가 문자열 상수이면, 시스템은 `EXECUTES`(STEP→`mybatis:stmt:..`)를 만들어야 한다. 스텝과 연결되지 않으면 그 빈 메서드에서 `EXECUTES`를 만든다. | 사용자 요청, design 3.4 |
| F-005-R7 | 만약 스텝 인자나 구성 요소 인자를 해석하지 못하면, 시스템은 미해결 엣지(분류 `batch-step`, `batch-component`)를 남겨야 한다. 잡·스텝 이름이 상수가 아니면 `WARN` 진단을 남긴다. | AGENTS.md |

## 4. 수용 기준

### F-005-AC1 (대상: F-005-R1, R2, R3, R5)
- Given `new JobBuilder("dailySalesJob", repo).start(salesSumStep).next(closeOrderStep).build()`(스텝은 `Step` 매개변수)와 각 스텝을 `new StepBuilder("salesSumStep", repo)`로 만드는 `@Bean` 메서드 (jar 없음)
- When 분석하면
- Then `batch:job:dailySalesJob`, `batch:step:dailySalesJob/salesSumStep`, `batch:step:dailySalesJob/closeOrderStep`이 있고 `CONTAINS`(JOB→STEP) 둘과 `NEXT`(salesSumStep→closeOrderStep)가 있다.

### F-005-AC2 (대상: F-005-R1, R2, R5)
- Given `jobBuilderFactory.get("settleJob").start(step1()).on("FAILED").to(errorStep()).from(step1()).on("*").to(step2()).end().build()`와 스텝 이름이 메서드명과 다른 `stepBuilderFactory.get("readStep")` 등
- When 분석하면
- Then 스텝 노드 ID는 빌더에 준 이름을 쓰고 `NEXT`가 `on=FAILED`, `on=*`로 있다.

### F-005-AC3 (대상: F-005-R4)
- Given `.reader(reader())`(같은 클래스의 `@Bean` 메서드), `.processor(salesProcessor)`(프로젝트 타입 매개변수), `.writer(items -> {})`(람다), `.tasklet(new CleanupTasklet())`, `.listener(stepListener)`(필드)
- When 분석하면
- Then `USES`가 `role=reader`→그 METHOD, `processor`→TYPE, `writer`→감싼 METHOD, `tasklet`→TYPE, `listener`→TYPE으로 있다.

### F-005-AC4 (대상: F-005-R3)
- Given 어떤 잡에도 쓰이지 않는 스텝 정의와 두 잡이 함께 쓰는 스텝 정의
- When 분석하면
- Then 앞의 것은 `batch:step:?/<이름>`이고, 뒤의 것은 잡마다 스텝 노드가 있으며 각각 `USES`를 갖는다.

### F-005-AC5 (대상: F-005-R6)
- Given `reader.setQueryId("a.b.BatchMapper.selectClosedOrders")`를 부르는 `MyBatisPagingItemReader` 빈 메서드를 `.reader(closedOrderReader(null))`로 쓰는 스텝, `new MyBatisBatchItemWriterBuilder<>().statementId("a.b.insertSales")`를 쓰는 스텝, 어느 스텝도 쓰지 않는 MyBatis 리더 빈 메서드
- When 분석하면
- Then 스텝에서 `mybatis:stmt:a.b.BatchMapper.selectClosedOrders`, `mybatis:stmt:a.b.insertSales`로 `EXECUTES`가 있고, 쓰이지 않는 리더는 그 메서드에서 `EXECUTES`가 있다.

### F-005-AC6 (대상: F-005-R7)
- Given `.next(steps.get(i))`처럼 풀 수 없는 스텝 인자, `.reader(factory.create())`처럼 풀 수 없는 리더 인자, 이름이 변수인 `new StepBuilder(name, repo)`
- When 분석하면
- Then `unresolved:batch-step:...`, `unresolved:batch-component:...`로 가는 `UNRESOLVED` 엣지가 있고 이름을 못 푼 빌더에는 `WARN` 진단이 있다.

### F-005-AC7 (대상: F-005-R1~R4)
- Given AC1과 같은 소스와 Spring Batch 스텁이 든 classpath
- When 분석하면
- Then AC1과 같은 잡·스텝·엣지가 나온다.

## 5. 가정
- 빌더 체인은 구문(타입의 단순 이름과 메서드 이름)으로 인식한다. 수신자의 타입을 알 수 없으면 변수 이름이 `jobBuilderFactory`/`stepBuilderFactory`인 경우만 받는다.
- 체인을 지역 변수에 담아 이어 가는 경우(`SimpleJobBuilder b = ...; b.next(..)`)도 같은 잡으로 본다.
- 스텝 인자 해석: 인라인 스텝 체인 → 그 스텝, 같은 클래스의 메서드 호출 → 그 메서드가 만드는 스텝, 스텝 체인으로 초기화한 지역 변수 → 그 스텝, 매개변수·필드 → `@Qualifier` 값 또는 이름과 같은 빈 이름의 `@Bean` 메서드가 만드는 스텝(없으면 이름이 같은 스텝). 메서드 하나가 스텝을 둘 이상 만들면 풀지 않는다.
- 구성 요소 인자가 매개변수·필드일 때: 선언 타입이 프로젝트의 구체 클래스면 그 TYPE, 아니면 이름(또는 qualifier)이 같은 `@Bean` 메서드 → 스테레오타입 빈 TYPE → 선언 타입 TYPE 순이다.
- `on(..)` 뒤의 `end()`, `fail()`, `stop()`은 엣지를 만들지 않는다.
- `BATCH_JOB`/`BATCH_STEP`에는 `attrs.definedBy`(체인이 있는 메서드 ID)를, 스텝에는 `attrs.stepType`(`chunk`/`tasklet`)과 상수면 `attrs.chunkSize`를 남긴다.
- MyBatis 리더·라이터의 ID가 상수가 아니면 그 메서드에서 미해결 `EXECUTES`(분류 `mybatis-statement`)를 남긴다.
- `USES`의 대상이 `@Bean` 메서드이면 METHOD로만 잇는다(그 메서드의 `DEFINES_BEAN`으로 빈을 찾을 수 있다). `spring:bean:`으로 가는 `USES`는 만들지 않는다.
- 잡·스텝 이름이 상수가 아니면 노드를 만들지 않는다. 그 체인의 스텝·구성 요소도 그래프에 올라가지 않고 진단만 남는다.

## 6. 미확정 사항
- 없음 (사용자 위임에 따라 5의 가정으로 진행).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 기능: [F-017 Spring XML](../F-017-spring-xml/spec.md)

## 8. 계약 추가 제안 (design 3.5에 반영 요청)
design.md에 반영됨(2026-10-11): 아래 항목은 모두 F-001 design.md 3.5·3.6에 있다. 이번 감사 오류 수정 라운드에서 이 기능이 더한 제안은 없다.

- BATCH_JOB / BATCH_STEP: `definedBy`(메서드 ID); BATCH_STEP: `stepType`, `chunkSize`
- USES attrs: `lambda`(boolean)
- 미해결 분류: `batch-step`, `batch-component`
