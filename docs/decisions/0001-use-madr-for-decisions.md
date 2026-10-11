---
status: accepted
date: 2026-10-11
decision-makers: 프로젝트 소유자
---

# 결정 기록은 MADR 양식의 ADR 하나로 관리한다

## Context and Problem Statement
프로젝트를 진행하면서 내린 결정의 이유가 남지 않으면 나중에 같은 논의를 반복하게 된다. 코딩 에이전트도 과거 결정을 알아야 그에 어긋나는 코드를 쓰지 않는다. 결정을 어떤 형식으로 어디에 남길지 정해야 한다.

## Decision Drivers
- 혼자 유지할 수 있을 만큼 가벼워야 한다.
- 에이전트가 파일로 읽을 수 있어야 한다.
- 대안과 선택 이유가 남아야 한다.

## Considered Options
- MADR 4.0.0 양식의 ADR을 저장소에 둔다
- Nygard 양식의 ADR을 저장소에 둔다
- ADR과 별도로 프로젝트 결정 로그 표를 둔다

## Decision Outcome
Chosen option: "MADR 4.0.0 양식의 ADR을 저장소에 둔다", because 검토한 대안과 장단점을 적는 칸이 양식에 들어 있고, 개인 프로젝트에서는 결정 기록을 두 군데로 나눌 이유가 없기 때문이다.

### Consequences
- Good, because 아키텍처 결정과 그 밖의 프로젝트 결정을 한 곳에서 찾을 수 있다.
- Bad, because Nygard 양식보다 채울 칸이 많다. 작은 결정은 Pros and Cons 절을 생략한다.

### Confirmation
`docs/decisions/`에 순번 파일로 쌓이는지, 승인된 ADR이 수정되지 않았는지 커밋 이력으로 확인한다.

## More Information
- 승인된 ADR의 본문은 고치지 않는다. 바꿀 때는 새 ADR을 만들고 이전 ADR의 status를 `superseded by ADR-NNNN`으로 바꾼다.
- 새 ADR은 `docs/_templates/adr.md`를 복사해 `NNNN-짧은-제목.md`로 만든다.
