---
name: sdd-new-spec
description: 새 기능 작업을 SDD 방식으로 시작한다. specs/NNN-이름/ 디렉토리에 spec.md, plan.md, tasks.md 3종을 스캐폴딩한다. 사용자가 새 기능을 요청하거나 "/sdd-new-spec"을 호출하면 사용.
---

# 새 spec 스캐폴딩

1. `specs/` 아래 기존 디렉토리의 최대 번호를 확인하고 다음 번호(NNN, 3자리 0패딩)를 정한다.
2. 기능 이름을 kebab-case 영문으로 정해 `specs/NNN-이름/`을 만든다.
3. 아래 3개 파일을 작성한다. **spec.md를 먼저 사용자와 합의한 뒤** plan/tasks를 채운다.
4. 작성 전 반드시 `constitution.md`, `docs/REQUIREMENTS.md`, `docs/ARCHITECTURE.md`를 읽고, 원칙과 충돌하는 요구는
   구현 전에 사용자에게 보고한다.

## spec.md 템플릿

```markdown
# Spec NNN — <제목>

- **상태**: 초안 | 승인됨 | 구현 중 | 완료
- **관련**: (선행 spec, 이슈 링크)

## 목적 (Why)
## 사용자 스토리
## 기능 요구사항 (FR-1, FR-2, …)
## 비기능 요구사항
## 완료 기준 (Acceptance) — 체크리스트
```

## plan.md 템플릿

```markdown
# Plan NNN — <제목>

spec: [spec.md](spec.md)

## 기술 선택 (선택 | 근거 표)
## 구현 접근 (단계별)
## 리스크와 대응
## 검증 방법 (JVM 단위 테스트는 순수 함수만 — 실기기 시나리오는 docs/REQUIREMENTS.md §7 번호로)
```

## tasks.md 템플릿

```markdown
# Tasks NNN — <제목>

- [ ] T1. …
- [ ] T2. …

완료 시 커밋 해시를 항목 끝에 병기한다: `- [x] T1. … (abc1234)`
```
