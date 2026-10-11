---
type: features
updated: 2026-10-11
---

# 기능정의서

기능 ID는 여기서 한 번만 부여한다. spec, tasks, 커밋은 이 ID를 참조한다.

- **우선순위**: P0(없으면 출시 불가) · P1(중요) · P2(있으면 좋음)
- **상태**: 제안 · 명세중 · 구현중 · 완료 · 보류 · 폐기
- 폐기한 기능의 행은 지우지 않고 상태만 바꾼다.
- 우선순위는 에이전트가 붙인 초안이다. PRD의 세 목표에 직접 필요한 것을 P0으로 두었다.

| ID | 분류 | 기능명 | 설명 | 우선순위 | 상태 | Spec |
|---|---|---|---|---|---|---|
| F-001 | 기반 | 분석 파이프라인과 그래프 모델 | 프로젝트 파일을 분류하고, 분석기를 실행하고, 결과를 공통 그래프 모델과 JSON으로 저장하는 뼈대와 CLI | P0 | 완료 | [spec](specs/F-001-analysis-pipeline/spec.md) |
| F-002 | Java | Java 구조 분석 | 타입, 필드, 메서드, 상속, 구현, 연관, 의존 관계 추출 | P0 | 완료 | [spec](specs/F-002-java-structure/spec.md) |
| F-003 | Java | Java 콜 그래프 | 메서드 호출, 생성자 호출, 오버라이드, 필드 접근을 타입 해석 기반으로 추출 | P0 | 완료 | [spec](specs/F-003-java-call-graph/spec.md) |
| F-004 | Spring | Spring 해석 (애너테이션) | 스테레오타입 빈, `@Bean`, 의존성 주입, 요청 매핑(엔드포인트), 뷰 이름, Java 코드 안의 MyBatis 호출 해석 | P0 | 완료 | [spec](specs/F-004-spring/spec.md) |
| F-005 | Spring | Spring Batch 해석 (Java 설정) | Java 설정으로 만든 Job, Step, Reader/Processor/Writer 구성 해석 | P1 | 완료 | [spec](specs/F-005-spring-batch/spec.md) |
| F-006 | SQL | SQL 파서 | Oracle, DB2 DML을 오류 허용 방식으로 파싱해 테이블, 컬럼, 조인 조건 추출 | P0 | 완료 | [spec](specs/F-006-sql-parser/spec.md) |
| F-007 | SQL | DDL 파서 | DDL 파일에서 테이블, 컬럼, 키, 제약, 뷰, 시퀀스, 시노님 추출 | P0 | 완료 | [spec](specs/F-007-ddl-parser/spec.md) |
| F-008 | SQL | 프로시저 분석 | PL/SQL과 DB2 SQL PL의 프로시저·함수·패키지·트리거 본문 파싱, 내부 SQL과 호출 추출 | P1 | 완료 | [spec](specs/F-008-routines/spec.md) |
| F-009 | MyBatis | MyBatis 매퍼 분석 | 매퍼 XML의 구문, 동적 SQL, include, resultMap 해석과 SQL 분석 연결 | P0 | 완료 | [spec](specs/F-009-mybatis/spec.md) |
| F-010 | 화면 | JavaScript/TypeScript 분석 | JS, JSX, TS, TSX의 함수, 호출, import, HTTP 요청 추출 | P1 | 완료 | [spec](specs/F-010-javascript/spec.md) |
| F-011 | 화면 | JSP 분석 | JSP의 include, 폼, 링크, 인라인 스크립트, 요청 URL 추출 | P1 | 완료 | [spec](specs/F-011-jsp/spec.md) |
| F-012 | 연결 | 교차 연결 | Mapper 메서드↔SQL 구문, 화면 요청↔엔드포인트, 뷰 이름↔JSP, SQL↔뷰·시노님·프로시저 연결 | P0 | 완료 | [spec](specs/F-012-cross-linking/spec.md) |
| F-013 | 출력 | ERD 출력 | 테이블, 컬럼, 키, 관계(DDL과 추론 구분)를 ERD로 출력 (Mermaid, PlantUML, DBML) | P0 | 완료 | [spec](specs/F-013-erd/spec.md) |
| F-014 | 출력 | 클래스 다이어그램 출력 | 패키지나 타입 범위를 골라 클래스 다이어그램으로 출력 | P0 | 완료 | [spec](specs/F-014-class-diagram/spec.md) |
| F-015 | 출력 | 콜트리와 영향도 조회 | 메서드 기준 콜트리(양방향), 테이블 기준 역추적, CRUD 매트릭스 출력 | P0 | 완료 | [spec](specs/F-015-call-tree/spec.md) |
| F-016 | 출력 | HTML 뷰어 | 분석 결과를 검색하고 다이어그램과 콜트리를 보는 단일 HTML 파일 | P2 | 완료 | [spec](specs/F-016-html-viewer/spec.md) |
| F-017 | Spring | Spring XML 설정 해석 | XML 빈 설정(빈, 주입, 뷰 리졸버)과 Spring Batch XML 잡 설정(Job, Step, 흐름) 해석 | P0 | 완료 | [spec](specs/F-017-spring-xml/spec.md) |
| F-018 | 연결 | 테이블 관계 추론 | SQL 조인 조건과 키 정보에서 FK 제약이 없는 테이블 관계를 추론 | P0 | 완료 | [spec](specs/F-018-relation-inference/spec.md) |
