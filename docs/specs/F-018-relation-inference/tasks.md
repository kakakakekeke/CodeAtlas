---
type: tasks
id: F-018
updated: 2026-10-11
---

# F-018 테이블 관계 추론 작업 목록

## 구현 (작업마다 테스트 먼저)
- [x] T1. `RelationInferenceLinker`(order 300) 묶기와 방향 판정: AC1~AC4 테스트 → 실패 확인 → 구현 → 통과 (F-018-R1, R2, R3)
- [x] T2. DDL 우선: AC5 테스트 → 실패 확인 → 구현 → 통과 (F-018-R4)
- [x] T3. 자기 조인, 뷰, 미선언 테이블, 근거 없는 쌍: AC6, AC7 테스트 → 실패 확인 → 구현 → 통과 (F-018-R5, R6)
- [x] T4. 최소 근거 수 옵션: AC8 테스트 → 실패 확인 → 구현 → 통과 (F-018-R7)
- [x] T5. 자료형 불일치: AC9 테스트 → 실패 확인 → 구현 → 통과 (F-018-R8)
- [x] T6. 정규화 링커와 함께 실행(시노님 경유 조인이 대상 테이블의 관계가 되는지) 통합 테스트 (F-018-R5)
- [x] T7. (감사 후속 U3-b) 상수로 채운 복합 키: AC10 테스트 → 실패 확인 → 근거 엣지별 고정 컬럼 판정, `fixedColumns`·`fixedValues` → 통과 (F-018-R9)
- [x] T8. (감사 후속 U3-a) 형제 조인: AC11 테스트 → 실패 확인 → 2단계(판정 → 거르고 기록) → 통과 (F-018-R10)

- [x] T9. (공개 프로젝트 검증 B13) 뷰가 낀 조인: AC12 테스트 → 실패 확인 → 기본으로 만들지 않고 `link.inferViewRelations=true`일 때만 방향 미상으로 만든다 → 통과 (F-018-R11, R5 고쳐 씀). 기대값을 바꾼 기존 테스트: `viewsAndUndeclaredTables`(AC6: 뷰 관계가 기본으로 없다), `runsAfterNormalization`(옵션을 켜고 확인)
- [x] T10. (실전 재검증 2차 후속, N14) F-018-AC13~AC15 테스트 → 실패 확인 → `RelationInferenceLinker`(리터럴 고정만, DDL과 반대 방향 낮추기, 사실상 키) → 통과 (F-018-R12~R14). F-018-AC10 테스트의 근거 수·`fixedValues` 단언을 새 기준으로 고쳤다
- [x] T11. spec 8절 "실전 재검증 2차 후속"의 계약 변경을 design.md에 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
- [x] T12. (실전 재검증 3차 후속, M9) F-018-AC16 방향 미상 추론이 DDL FK의 방향을 따르기 → 실패 확인 → `RelationInferenceLinker.followDeclared`, `Decision.follow`·`directionFrom` → 통과 (F-018-R15). 공통컴포넌트(mysql 기본 실행)에서 6건이 `directionFrom=DDL`(그중 사전순으로 DDL과 반대로 놓였던 COMTNCOMMENT–COMTNBBS 등 4건이 뒤집힘)
- [x] T13. spec 8절 "실전 재검증 3차 후속"의 계약 변경을 design.md에 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
- [x] T14. (실전 재검증 4차 후속, 검토만) "한쪽만 PK" 방향과 UNION 분기 형제의 일관성 규칙 검토: `JOINS` 엣지로는 분기에서 온 것인지 알 수 없어(F-006-R38의 엣지에 표시가 없다) 넣지 않았다. 코드 변경 없음. spec 5절과 design.md 7절에 한계로 적음. 추론 `REFERENCES` 수는 그대로(공통컴포넌트 149건·방향 미상 52건, enterprise 15건·4건)

## 마무리
- [x] 모든 수용 기준 통과 확인
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
- [x] 계약 추가 제안(spec 8절)을 최종 보고에 올림
- [x] 옵션 키 `link.inferViewRelations`를 design.md 3.8에 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
