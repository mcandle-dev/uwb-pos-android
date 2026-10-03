---
name: wrap-up
description: 작업 세션 마무리 루틴. tasks 체크 갱신, CHANGELOG 기록, docs/ARCHITECTURE.md 동기화, 작업 일지 작성을 일괄 수행한다. 사용자가 "마무리해", "정리해", "/wrap-up"이라고 하거나 기능 구현이 끝났을 때 사용.
---

# 세션 마무리 루틴

아래 순서로 문서를 동기화한다. 변경이 없는 항목은 건너뛴다.

1. **tasks.md**: 이번 세션에서 완료한 항목을 `[x]`로 바꾸고 커밋 해시를 병기한다.
   spec의 Acceptance 체크리스트도 갱신한다.
2. **CHANGELOG.md**: `## [YYYY-MM-DD]` 섹션에 Added / Changed / Fixed 로 기록한다
   (기존 형식 유지, 최신 날짜가 위).
3. **docs/ARCHITECTURE.md**: 구성 요소·흐름·상태·수명 규칙이 바뀌었으면 해당 섹션을 갱신한다.
   구현과 문서가 다른 상태로 커밋하지 않는다.
4. **constitution.md**: 원칙 수준의 결정이 새로 내려졌다면 사용자에게 승격 여부를 물어본 뒤 추가한다
   (임의로 수정하지 않는다).
5. **logs/work_log_YYYY-MM-DD.md**: 기존 일지 형식으로 작성 —
   `## 1. Accomplishments`, `## 2. Technical Challenges & Fixes`, `## 3. Current State`,
   `## 4. Tomorrow's Goals`.
6. 위 문서 변경을 코드 커밋과 함께(또는 별도 `docs:` 커밋으로) 커밋한다.
