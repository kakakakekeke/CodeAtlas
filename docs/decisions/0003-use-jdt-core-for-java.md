---
status: accepted
date: 2026-10-11
decision-makers: 프로젝트 소유자 위임, 에이전트 제안 (소유자 검토 필요)
---

# Java 소스는 Eclipse JDT Core로 파싱하고 바인딩을 해석한다

## Context and Problem Statement
IDE 수준의 콜트리를 만들려면 메서드 호출이 어느 타입의 어느 오버로드를 가리키는지 해석해야 한다. 대상 프로젝트는 의존 jar가 빠져 있거나 컴파일되지 않는 상태로 들어올 수 있다.

## Decision Drivers
- 오버로드, 제네릭, 람다, 상속을 고려한 호출 대상 해석
- classpath가 불완전해도 가능한 만큼 해석하고 나머지는 실패로 구분
- 폐쇄망에서 쓸 수 있는 라이브러리 형태

## Considered Options
- Eclipse JDT Core (ASTParser, 바인딩 복구 사용)
- JavaParser + JavaSymbolSolver
- javac Tree API (com.sun.source)
- Java 파서 직접 구현

## Decision Outcome
Chosen option: "Eclipse JDT Core", because Eclipse IDE가 쓰는 엔진 그대로라 호출 해석 결과가 IDE의 Call Hierarchy와 같은 기준이 되고, 구문·바인딩 복구 옵션이 있어 불완전한 프로젝트를 다룰 수 있다.

### Consequences
- Good, because 타입 해석을 직접 구현하지 않는다.
- Bad, because API가 크고 IDE 밖에서 쓰는 문서가 적다. 의존 jar 크기가 크다.

### Confirmation
샘플 프로젝트의 정답 호출 목록과 분석 결과를 통합 테스트로 대조한다.

## Pros and Cons of the Options

### JavaParser + JavaSymbolSolver
- Good, because API가 단순하다.
- Bad, because 심볼 해석을 별도 구현에 의존해 IDE와 결과가 다를 수 있다.

### javac Tree API
- Good, because JDK에 들어 있어 추가 의존이 없다.
- Bad, because 컴파일 오류가 많은 입력에서 해석 결과를 얻기 어렵다.

### Java 파서 직접 구현
- Bad, because 타입 해석까지 만들어야 해서 비용이 가장 크다.
