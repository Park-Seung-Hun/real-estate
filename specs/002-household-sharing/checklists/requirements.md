# Specification Quality Checklist: 가구와 가족 공유

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-17
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- 이 spec의 재료는 기능 목록을 나누기 전의 "로그인과 가족 공유" spec(사용자 스토리 7개)에서 001로 가지 않은 몫입니다. 나눈 경계는 [백로그](../../../docs/backlog.md)에 있습니다. 사용자 스토리는 5개이고, 요청이 막히는 순서는 별도 스토리가 아니라 FR-023과 각 스토리의 acceptance에 있습니다.
- 상태 코드(400, 401, 403, 404, 409)와 코드 REAUTH_REQUIRED는 구현 방식이 아니라 API 공통 규칙이 정한 사용자 관점의 응답 약속이라 spec에 남겼습니다(001과 같음). Key Entities의 "링크 값의 해시"는 데이터 모델과 결정 기록 0011이 정한 저장 약속입니다.
- 초대 수락 실패 사유 중 "구성원이 이미 둘임"은 수락 대기 초대가 가구마다 하나라 보통의 흐름으로는 생기지 않습니다. 결정 기록 0011과 분할 전 spec이 사유로 두었고, 테스트는 자료를 직접 만들어 확인합니다.
- 사용자 결정(2026-11-17): 초대 만료 기간은 owner가 1일부터 7일까지 하루 단위로 고름, 이미 가구가 있는 사람의 수락은 409로 거절하고 안내. spec 작성자가 정한 기본값(만료 기간 처음 값 3일, 남은 기간 표시, 가구 삭제 확인 방식, owner 자기 제거 403, 역할 이전 없음)은 Assumptions와 해당 FR에 있습니다.
- 설계 문서와 대조한 리뷰(2026-11-17)를 반영했습니다. 확인 순서 예 세 개와 다른 가구 404를 acceptance로 옮김(US3 10, 11), 재인증 조건을 Given에 명시, 입력 검증과 상태 충돌이 확인 여섯 단계 뒤라는 문장(FR-023), 수락 실패 사유의 안내 순서와 owner가 자기 링크를 연 경우(FR-013, FR-014, US2 13, 14), 보낸 초대 목록의 범위와 "방금 만듦"(FR-010), 표시 이름 입력란을 와이어프레임대로(US5 1), 만료 시각 정각 수락(FR-007), 들어온 시각 기록(FR-001), 대기 중이 아닌 초대 취소 409(FR-011), 탈퇴 확인 화면의 409 안내(FR-021), 용어 표의 "추가하다".
- 와이어프레임에 만료 기간 선택이 없어 구현 전에 `docs/*` 브랜치에서 와이어프레임을 맞춥니다(Assumptions).
- 오류 코드, 링크 모양, 로그인을 거치는 동안 초대를 유지하는 방법, 동시 요청을 막는 방법은 의도적으로 plan에 넘겼습니다.
