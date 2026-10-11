---
status: accepted
date: 2026-10-11
decision-makers: 프로젝트 소유자 위임, 에이전트 제안 (소유자 검토 필요)
---

# 분석 도구는 Java 21과 Maven 멀티모듈로 구현한다

## Context and Problem Statement
분석 대상은 Java 프로젝트이고, Java 타입 해석에는 JVM 위에서 도는 컴파일러 프런트엔드가 필요하다. 도구를 어떤 언어와 빌드 도구로 만들지 정해야 한다.

## Decision Drivers
- Java 바인딩 해석 라이브러리를 프로세스 안에서 직접 쓸 수 있어야 한다.
- 폐쇄망에 jar 하나로 반입할 수 있어야 한다. (PRD 가정)
- 언어별 분석기를 독립 모듈로 나눠 병렬로 개발할 수 있어야 한다.

## Considered Options
- Java 21 + Maven 멀티모듈
- Kotlin + Gradle
- TypeScript(Node.js)
- Python

## Decision Outcome
Chosen option: "Java 21 + Maven 멀티모듈", because JDT Core를 그대로 쓸 수 있고, 실행 jar 하나로 묶을 수 있으며, 개발 환경에 JDK와 Maven이 이미 있다.

### Consequences
- Good, because 분석 대상과 같은 생태계라 대상 프로젝트의 jar를 그대로 classpath로 쓴다.
- Bad, because JavaScript 파서는 JVM 밖 생태계가 더 풍부하다. ADR-0005로 따로 다룬다.

### Confirmation
`mvn verify`가 루트에서 통과하고 `codeatlas-cli`가 실행 가능한 jar 하나를 만든다.

## More Information
- 컴파일 대상은 `--release 21`. 개발 환경의 JDK는 24다.
