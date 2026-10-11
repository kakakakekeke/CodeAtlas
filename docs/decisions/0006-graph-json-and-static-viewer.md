---
status: accepted
date: 2026-10-11
decision-makers: 프로젝트 소유자 위임, 에이전트 제안 (소유자 검토 필요)
---

# 분석 결과는 하나의 그래프 모델에 모아 JSON 파일로 저장하고, 조회는 정적 파일로 한다

## Context and Problem Statement
여러 언어의 분석 결과를 화면에서 테이블까지 이어서 추적하려면 한 모델에 모여야 한다. 그 모델을 어디에 저장하고 사용자가 어떻게 볼지 정해야 한다.

## Decision Drivers
- 분석기를 서로 독립적으로 개발한다.
- 폐쇄망에서 설치 없이 결과를 본다.
- 결과를 다른 도구가 다시 읽을 수 있다.

## Considered Options
- 메모리 그래프 + JSON 파일 + 정적 출력물(Mermaid, PlantUML, DBML, CSV, 단일 HTML)
- 내장 DB(SQLite 등)에 저장하고 웹 서버로 조회
- 그래프 DB(Neo4j 등)에 적재

## Decision Outcome
Chosen option: "메모리 그래프 + JSON 파일 + 정적 출력물", because 설치할 것이 없고 출력 폴더만 옮기면 어디서든 볼 수 있기 때문이다.

### Consequences
- Good, because 분석기는 노드 ID 규칙만 지키면 서로를 몰라도 된다.
- Bad, because 그래프 전체를 메모리에 올린다. 매우 큰 프로젝트에서 메모리와 HTML 크기가 문제가 될 수 있다. 규모 목표는 PRD의 미확정 사항이다.

### Confirmation
`analyze` 실행 후 출력 폴더의 `graph.json`을 다시 읽어 같은 그래프가 복원되는지 테스트한다.

## More Information
- 그래프 계약은 `docs/specs/F-001-analysis-pipeline/design.md`에 있다.
