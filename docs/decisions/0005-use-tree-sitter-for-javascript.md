---
status: accepted
date: 2026-10-11
decision-makers: 프로젝트 소유자 위임, 에이전트 제안 (소유자 검토 필요)
---

# JavaScript 계열은 tree-sitter 문법으로 파싱한다

## Context and Problem Statement
화면 코드는 JS, JSX, TS, TSX와 JSP 안의 인라인 스크립트로 들어온다. JVM 프로세스 안에서, 네트워크 없이, 문법 오류가 있어도 트리를 얻을 수 있어야 한다.

## Decision Drivers
- JSX와 TypeScript 지원
- 오류 허용 파싱
- 실행 jar 안에 포함 가능 (Node.js 설치를 요구하지 않음)

## Considered Options
- tree-sitter (Java 바인딩 + javascript, typescript, tsx 문법)
- Google Closure Compiler 파서
- Node.js 하위 프로세스로 Babel/TypeScript 파서 실행
- JavaScript 파서 직접 구현

## Decision Outcome
Chosen option: "tree-sitter", because JSX와 TSX까지 한 방식으로 다루고 깨진 입력에서도 트리를 주기 때문이다.

### Consequences
- Good, because 문법을 직접 유지하지 않는다.
- Bad, because 네이티브 라이브러리를 jar에 넣어야 하므로 지원 OS·CPU가 바인딩이 제공하는 범위로 묶인다.
- Bad, because 타입 정보가 없어 JS 호출 해석은 이름과 import 기준에 머문다.

### Confirmation
구현 때 Maven Central의 바인딩(후보: tree-sitter-ng)이 이 개발 환경(macOS arm64)에서 JS, JSX, TS, TSX를 파싱하는지 테스트로 확인한다. 쓸 수 없으면 이 ADR을 대체하는 새 ADR을 쓴다.
