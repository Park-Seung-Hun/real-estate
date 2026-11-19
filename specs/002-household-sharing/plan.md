# Implementation Plan: 002 가구와 가족 공유

**Branch**: `002-household-sharing` | **Date**: 2026-11-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-household-sharing/spec.md`

## Summary

가구가 없는 사람이 가구를 만들어 owner가 되고, 초대 링크로 가족을 member로 들이며, 구성원 목록을 보고 제거하거나 나가고, owner가 가구를 삭제하는 기능입니다. 001이 만든 로그인 위에 가구 모듈(`estate.household`)과 세 테이블(`household`, `household_member`, `invitation`)을 추가하고, 권한 확인 2단계(멤버십)와 3단계(권한 키)를 처음 만듭니다. 이 장치는 컨트롤러 메서드의 표시(`@Access`)와 Spring MVC 인터셉터 하나라, 004부터 010까지의 기능은 표시만 추가합니다. 초대 토큰은 링크의 `#` 뒤에 두고 서버에는 해시만 저장하며, 로그인을 거치는 동안 화면이 탭 저장소에 보관합니다. 구성원과 초대를 바꾸는 동작은 가구 행을 잠가 한 줄로 세워 "구성원 둘까지"와 "수락 대기 중인 초대 하나"를 동시 요청에서도 지킵니다. 웹 화면은 임시 첫 화면, 초대 확인, 가구 삭제 확인, 가구 나가기 확인을 추가하고 가구 없음, 로그인, 탈퇴 확인을 수정합니다. 기술 판단과 근거는 [research.md](research.md)에 있습니다.

## Technical Context

**Language/Version**: Java 25(서버), TypeScript 7.0.2(웹 화면), Node 24 LTS 이상(웹 빌드). 001과 같습니다

**Primary Dependencies**: 001과 같습니다(Spring Boot 4.1.2, Spring Security 7.1.2, Spring Session JDBC 4.1.1, Spring Data JPA, Flyway, React 19.3.1, React Router 8.5.1, Vite 8.3.8). 새 의존성은 없습니다. 계약과 표시를 대조하는 테스트는 Spring Boot에 이미 있는 SnakeYAML을 사용합니다

**Storage**: PostgreSQL 18. 002의 테이블은 `household`, `household_member`, `invitation`([data-model.md](data-model.md)), 마이그레이션 `V2__household.sql`

**Testing**: 001과 같습니다(JUnit 5, Spring Boot Test와 MockMvc, Testcontainers PostgreSQL 18, mock-oauth2-server 7.0.0, Vitest, Testing Library, Playwright). 오류 본문이 걸린 요구는 실제 서버(`RANDOM_PORT`)로도 확인합니다

**Target Platform**: 001과 같습니다(서버는 JVM 하나, 화면은 휴대폰 웹 브라우저)

**Project Type**: 웹 서비스(API 서버와 같은 주소에서 서비스하는 SPA)

**Performance Goals**: 사용자 두 명이라 처리량 목표는 없습니다. 권한 확인기가 `/api/**` 요청마다 멤버십 조회 한 번을 추가합니다(개선 후보는 011)

**Constraints**: 초대 토큰 원문은 발급 응답에만 담고, 주소와 로그와 오류에 남기지 않음(FR-008, FR-028). 토큰은 해시만 저장. 구성원 둘까지와 수락 대기 중인 초대 하나를 동시 요청에서도 지킴(FR-004, FR-009, FR-015). 응답 순서는 [research R3](research.md#r3-46단계와-응답-순서)의 표(FR-023). 휴대폰 폭에서 가로 스크롤 없음(FR-029)

**Scale/Scope**: 사용자 2명, API 10개 추가와 1개 수정(탈퇴 409), 화면 4개 추가(임시 첫 화면, 초대 확인과 수락 실패 안내, 가구 삭제 확인, 가구 나가기 확인)와 3개 수정(가구 없음, 로그인 안내, 탈퇴 확인)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 점검 질문에 대한 답 | 판정 |
|---|---|---|
| I. 공식 API만 사용 | 외부 호출은 001의 구글 OIDC뿐이고 이 기능은 외부 자료를 가져오지 않습니다. 후기를 저장하지 않습니다 | 통과 |
| II. 자체 화면 입력 | 가구, 초대, 표시 이름은 서비스 화면에서 입력합니다. 노션 연동이 없습니다. 새 화면과 바뀐 화면을 Playwright로 360px에서 가로 스크롤 없이 확인합니다 | 통과 |
| III. 실제 데이터를 저장소에 두지 않음 | 테스트와 예시는 가짜 계정(`user1@example.com` 등)과 가짜 표시 이름만 사용합니다. quickstart에는 실제 이메일, 키, 토큰을 적지 않고 두 계정을 "계정 가", "계정 나"로 부릅니다. 새 키나 설정 값이 없어 `.env`와 `.env.example`은 바뀌지 않습니다. 커밋 전 실제 데이터와 인증키 확인은 tasks에 둡니다 | 통과 |
| IV. 설계 문서를 따름 | 계약은 API 공통 규칙(경로 `/api/v1`, 단수 `/household`, Problem Details, 권한 표기 넷, 생성 201)을 따릅니다. `data-model.md`는 세 테이블만 적고 설계 데이터 모델을 참조합니다. 용어는 용어 표를 따릅니다(초대 발급, 수락 대기 중인 초대, 구성원, 민감 동작). 백로그 경계대로 체크리스트 기본 항목(007)과 알림 설정(009)을 만들지 않습니다. 설계 문서와 다른 것(초대 확인 화면, 만료 기간 선택, 가구 나가기 확인 화면)은 [research R17](research.md#r17-설계-문서와-달라진-것)에 적었고, 구현 전에 `docs/*` 브랜치에서 결정 기록 0011, 와이어프레임, API 공통 규칙, 데이터 모델, C4 컴포넌트를 먼저 수정합니다 | 조건부 통과(구현 전 `docs/*` 브랜치 머지가 조건) |
| V. 기능마다 테스트 | acceptance마다 테스트를 둡니다(tasks에서 짝지음). 보안 경계 테스트 열두 묶음([research R16](research.md#r16-테스트-방식과-보안-경계-테스트-대상))은 구현과 다른 쪽이 spec과 설계 문서에서 직접 씁니다. 데이터베이스 테스트는 실제 PostgreSQL이고, 동시성 테스트는 다른 연결의 잠금으로 요청을 실제로 겹치게 합니다 | 통과 |
| VI. 결정 없이 미리 만들지 않음 | 새로 만드는 지점은 모두 설계 문서나 spec에서 나옵니다. 권한 확인기와 표시(C4 컴포넌트의 가구 모듈 권한 확인기, "계약 확장 필드를 코드에 표시하는 방법은 구현"), 사용자 모듈의 공개 조회(C4 컴포넌트의 가족 초대 화면 합치기), 가구 모듈의 멤버십 확인(C4 컴포넌트의 탈퇴 전 멤버십 확인)입니다. 권한 키 열거형에는 002가 사용하는 키만 둡니다. 삭제 연쇄에 뒤 기능이 추가할 자리는 인터페이스나 이벤트 없이 서비스 메서드 안의 순서로 둡니다. 권한 확인은 Spring MVC 인터셉터를 직접 사용하고 감싸는 계층을 두지 않습니다 | 통과 |
| 기술 제약 | Java 25, Spring Boot 4.1, PostgreSQL, Gradle, Flyway, JPA 기본에 잠금만 SQL(결정 기록 0016). 웹 화면은 결정 기록 0017. 모듈은 한 애플리케이션 안입니다. Kafka, Redis, Kubernetes를 사용하지 않습니다 | 통과 |

Phase 1 설계 뒤 다시 확인했습니다. 계약의 경로 열 개와 표기 넷은 API 공통 규칙과 [research R4](research.md#r4-api와-경로)의 표와 같고, 001 경로는 탈퇴의 409만 다시 적었습니다. data-model은 세 테이블만 다루며 설계 데이터 모델의 열과 유니크 제약을 그대로 사용하고, 추가한 것은 체크 제약 둘(`octet_length(token_hash) = 32`, `expires_at > created_at`)뿐입니다. quickstart에는 실제 데이터가 없습니다. 원칙 IV의 조건(구현 전 `docs/*` 브랜치)은 위반이 아니라 순서 조건이라 Complexity Tracking은 비워 둡니다.

## Project Structure

### Documentation (this feature)

```text
specs/002-household-sharing/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # 기술 판단과 근거
├── data-model.md        # 002가 만드는 테이블
├── quickstart.md        # 로컬 실행과 두 계정 화면 확인 순서
├── contracts/
│   └── openapi.yaml     # 002의 API 계약(001 탈퇴의 409 포함)
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks가 만듦
```

### Source Code (repository root)

```text
src/main/java/estate/
├── common/web/
│   └── ProblemCode.java             # 오류 코드 열한 개 추가(research R5)
├── household/                       # 가구 모듈(새로 만듦)
│   ├── domain/                      # Household, HouseholdMember, Invitation 엔티티, 역할과 초대 상태, 저장소
│   ├── application/                 # 가구 만들기와 삭제, 구성원 제거와 나가기, 초대 발급과 취소와 확인과 수락, 탈퇴용 멤버십 확인(공개)
│   ├── authz/                       # @Access, Permission, MembershipRule, 권한 확인 인터셉터, 멤버십 인자 해석기
│   └── web/                         # 가구와 초대 컨트롤러(화면 API, 사용자 모듈 조회와 합침)
└── user/
    ├── application/
    │   ├── UserProfiles.java        # 공개 조회: 표시 이름, 로그인 이메일, 검증 여부(새로 만듦)
    │   └── WithdrawService.java     # 사용자 행 잠금 뒤 멤버십 확인 추가
    └── web/
        ├── MeController.java        # @Access 표시 추가
        └── LogoutController.java    # @Access 표시 추가

src/main/resources/db/migration/
└── V2__household.sql                # 시퀀스 둘, 세 테이블, 제약

src/test/java/estate/
├── support/                         # 가구 둘과 사용자 넷을 만드는 도우미, 가구 테이블 정리 추가
├── household/                       # 기능 테스트(가구, 초대, 구성원, 만료, 동시성)
│   └── security/                    # 보안 경계 테스트 B1~B11(B10 탈퇴 409 포함, 구현과 다른 쪽이 작성). B12는 web/tests/unit
└── user/                            # 001 테스트 중 바뀌는 것(표시 이름 수정 잠금 등)

web/
├── src/
│   ├── App.tsx                      # 앱 상태에 가구 여부 추가, 경로 추가, 시작 때 초대 토큰 보관
│   ├── api/client.ts                # JSON 본문 전송, 가구와 초대 호출 추가
│   └── pages/                       # 임시 첫 화면, 초대 확인, 가구 삭제 확인, 가구 나가기 확인 추가. 가구 없음, 로그인, 탈퇴 확인 수정
└── tests/
    ├── unit/                        # 화면 문구와 역할별 버튼, 토큰 처리(B12)
    └── e2e/                         # 브라우저 둘로 가구 만들기부터 삭제까지, 360px 가로 스크롤. 001 문구 기대값 수정
```

**Structure Decision**: 서버는 001의 루트 Gradle 프로젝트에 모듈별 패키지를 추가합니다. 가구 모듈은 [C4 컴포넌트](../../docs/architecture/c4-component.md)의 가구 모듈(권한 확인기, 가구와 구성원과 초대)을 `estate.household` 패키지로 옮긴 것이고, 001의 `estate.user`와 같은 나눔(`domain`, `application`, `web`)에 권한 확인기(`authz`)를 더했습니다. 사용자 모듈에는 공개 조회 하나와 탈퇴의 멤버십 확인만 추가합니다. 웹 화면은 001의 `web/`에 화면을 추가합니다.

## 구현 전에 할 것

- `docs/*` 브랜치에서 [research R17](research.md#r17-설계-문서와-달라진-것)의 설계 문서 수정을 먼저 머지합니다(constitution IV).
- [research R18](research.md#r18-002를-둘로-나눌지)의 나눌지 판단(나누지 않음)과 [research R14](research.md#r14-화면과-문구)의 "새로 정함" 문구(그대로 사용)는 2026-11-17 저장소 소유자가 정했습니다.

## Complexity Tracking

위반이 없습니다.
