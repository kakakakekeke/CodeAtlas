---
status: accepted
date: 2026-10-11
decision-makers: 프로젝트 소유자 위임, 에이전트 제안 (소유자 검토 필요)
---

# 다른 언어 안에 박힌 코드는 내장 소스로 넘기고 분석기끼리 직접 호출하지 않는다

## Context and Problem Statement
분석 대상에는 한 언어의 파일 안에 다른 언어의 코드가 들어 있는 곳이 많다. 매퍼 XML 안의 SQL, `@Select` 같은 애너테이션의 SQL, Spring XML 빈의 인라인 SQL, JSP 안의 인라인 스크립트와 이벤트 핸들러다. 호스트 파일을 읽는 분석기(mybatis, java, springxml, jsp)와 그 언어를 분석하는 분석기(sql, web)가 다르므로, 박힌 코드를 누가 어떻게 분석할지 정해야 한다.

## Decision Drivers
- 모듈을 서로 독립적으로(병렬로) 개발한다.
- core가 아닌 모듈끼리는 의존하지 않는다(ADR-0006의 "분석기는 서로를 몰라도 된다").
- 같은 언어의 코드는 출처가 어디든 한 분석기가 같은 규칙으로 분석한다.

## Considered Options
- 내장 소스(`EmbeddedSource`)로 넘기고 실행 순서로 잇는다: 생산자 order 100, 소비자 order 200
- 호스트 분석기가 대상 언어의 파서를 라이브러리로 직접 호출한다(예: mybatis가 sql 파서를 호출)
- 호스트 분석기마다 필요한 만큼의 간이 추출을 자체 구현한다

## Decision Outcome
Chosen option: "내장 소스로 넘기고 실행 순서로 잇는다", because 생산자는 조각을 등록만 하고 소비자는 꺼내서 분석만 하면 되므로 두 쪽이 core의 SPI만 알면 되고, SQL과 JS 분석이 한 곳에만 있기 때문이다.

생산자는 주인 노드(매퍼 구문의 `SQL_STATEMENT`, JSP의 `JS_MODULE` 등)를 만들고 본문을 `AnalysisContext.addEmbedded`로 등록한다. 소비자는 `embedded("sql")`·`embedded("js")`로 꺼내 분석하고 주인 노드에 엣지와 속성을 덧붙인다. 규칙은 그래프 계약 4.1, 자리표시자와 attrs 키는 3.9에 있다.

### Consequences
- Good, because sql, mybatis, springxml, jsp 모듈의 의존은 core뿐이다. mybatis는 SQL 문법을, jsp는 JS 문법을 모른다.
- Good, because 매퍼 XML, 애너테이션, XML 빈, SQL 파일의 SQL이 모두 같은 파서와 같은 추출 규칙을 거친다.
- Bad, because 위치가 근사치가 되는 경우가 있다. 본문이 호스트 파일의 연속 구간이 아니면(`exactPositions=false`: 애너테이션 SQL, XML 빈의 인라인 SQL, 동적 태그나 include가 있는 매퍼 구문의 전개본) 거기서 나온 엣지의 위치는 조각의 시작 위치(`origin`) 하나다.
- Bad, because 생산자는 소비자의 결과를 볼 수 없다. 생산자가 먼저 돌기 때문에, 예를 들어 mybatis는 구문이 어떤 테이블을 읽는지 모른 채 끝난다. 양쪽 결과가 함께 필요한 일은 링커로 미뤄야 하고, 생산자가 미리 채운 값(`statementType`)을 소비자가 덮어쓰는 규칙이 필요했다.
- Bad, because 자리표시자 약속(`__DYNAMIC__`, `__JSP_EXPR__`)이 모듈 사이의 암묵 계약이 된다. 생산자가 바꿔 넣은 문자열을 소비자가 알아봐야 하는데 컴파일러가 이 약속을 검사해 주지 않는다. 문서(계약 3.9)와 양쪽 테스트로만 지킨다.

### Confirmation
각 모듈 pom의 의존이 core뿐인지(아키텍처 5절의 그림과 대조), 생산자 테스트가 등록한 내장 소스의 본문·attrs를 확인하는지, 소비자 테스트가 자리표시자가 든 본문을 넣어 기대한 엣지를 확인하는지로 본다. 전체 연결은 `samples/sample-shop` 대조(`SampleShopIT`)가 확인한다.

## Pros and Cons of the Options

### 호스트 분석기가 대상 언어의 파서를 직접 호출
- Good, because 생산자가 분석 결과를 바로 받아 쓸 수 있고 실행 순서 약속이 필요 없다.
- Bad, because mybatis·java·springxml이 sql 모듈에, jsp가 web 모듈에 의존하게 되어 모듈을 따로 개발하고 따로 테스트하기 어렵다. SQL 파서의 API가 바뀌면 호출하는 모듈을 전부 고쳐야 한다.

### 호스트 분석기마다 간이 추출을 자체 구현
- Good, because 모듈 사이에 약속이 전혀 필요 없다.
- Bad, because 같은 SQL이 출처에 따라 다른 규칙으로 분석된다. 서브쿼리, 별칭, 방언 구문 처리를 모듈마다 다시 만들어야 한다(ADR-0004가 정규식 추출을 버린 이유와 같다).

## More Information
- 관련 기능: F-001(SPI), F-004(애너테이션 SQL), F-006(SQL 소비), F-009(매퍼), F-010(JS 소비), F-011(JSP), F-017(XML 빈의 SQL)
- 계약: `docs/specs/F-001-analysis-pipeline/design.md` 1절, 3.9, 4.1
- 다시 볼 조건: 생산자가 소비자의 결과를 써야 하는 요구가 링커로 풀리지 않을 때, 자리표시자가 더 늘어날 때.
