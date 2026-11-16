# Implementation Plan: 001 로그인

**Branch**: `001-login` | **Date**: 2026-11-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-login/spec.md`

## Summary

구글 계정으로 로그인하고 로그아웃하며, 로그인 상태(세션)를 유휴 7일과 절대 30일로 만료시키고, 탈퇴 앞에서 구글 계정 다시 고르기로 재인증하는 기능입니다. 서버는 이미 있는 앱 골격 위에 Spring Security의 OIDC 로그인과 Spring Session JDBC를 얹고, 세션 ID는 쿠키 토큰의 SHA-256 해시로만 저장합니다. 웹 화면은 `web/`의 React SPA이고 API 서버 jar에 들어가 같은 주소에서 서비스합니다. 화면은 로그인, 가구 없음, 탈퇴 확인, 로그인 실패, 재인증 실패 다섯입니다. 기술 판단과 근거는 [research.md](research.md)에 있습니다.

## Technical Context

**Language/Version**: Java 25(서버), TypeScript 7.0.2(웹 화면), Node 24 LTS 이상(웹 빌드)

**Primary Dependencies**: Spring Boot 4.1.2(Spring Security 7.1.2, Spring Session JDBC 4.1.1, Spring Data JPA, Flyway), React 19.3.1, React Router 8.5.1, Vite 8.3.8

**Storage**: PostgreSQL 18. 001의 테이블은 `user`, `spring_session`, `spring_session_attributes`([data-model.md](data-model.md))

**Testing**: JUnit 5, Spring Boot Test와 MockMvc, Testcontainers PostgreSQL 18, mock-oauth2-server 7.0.0(라이브러리), Vitest 5.1.0, Testing Library 16.3.3, Playwright 1.65.1

**Target Platform**: 서버는 JVM 하나(Linux 또는 macOS), 화면은 휴대폰 웹 브라우저(개발 확인은 PC Chrome의 휴대폰 화면)

**Project Type**: 웹 서비스(API 서버와 같은 주소에서 서비스하는 SPA)

**Performance Goals**: 사용자 두 명이라 처리량 목표는 없습니다. SC-001(로그인 화면에서 가구 없음 화면까지 서비스 쪽 처리)을 지키는 수준이면 됩니다.

**Constraints**: 토큰 원문과 로그인 이메일 원문을 로그와 오류에 남기지 않음(FR-020). 세션 ID 열에는 해시만. 웹 화면과 API는 같은 주소. 휴대폰 폭에서 가로 스크롤 없음(FR-021).

**Scale/Scope**: 사용자 2명, 화면 5개, API 4개와 로그인 경로 2개

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 점검 질문에 대한 답 | 판정 |
|---|---|---|
| I. 공식 API만 사용 | 외부 호출은 구글 OIDC(로그인)뿐이고 부동산 자료를 수집하지 않습니다 | 통과 |
| II. 자체 화면 입력 | 입력은 서비스 화면과 API(표시 이름)뿐입니다. 화면은 휴대폰 폭 기준이고 Playwright로 가로 스크롤을 확인합니다 | 통과 |
| III. 실제 데이터를 저장소에 두지 않음 | 테스트와 예시는 가짜 구글 계정(`user1@example.com` 등)만 사용합니다. 키와 구글 클라이언트 값은 `.env`(gitignore)에만 두고 `.env.example`에는 항목 이름만 둡니다. 커밋 전에 공통 점검 명령과 실제 이메일, 키 검색을 tasks에 둡니다 | 통과 |
| IV. 설계 문서를 따름 | 계약은 API 공통 규칙(경로 `/api/v1`, Problem Details, 권한 표기)을 따릅니다. `data-model.md`는 001이 만드는 세 테이블만 적고 설계 데이터 모델을 참조합니다. 재인증 방식과 세션 ID 해시 변환은 이 plan보다 먼저 결정 기록 0013에 반영했습니다(`develop/v1` 머지 `416e809`). 백로그 경계대로 `notification_setting`과 가구를 만들지 않습니다. 용어는 용어 표를 따릅니다(로그인 상태, 탈퇴, 가구 없음 화면, 테스트). spec FR-002와 US1 시나리오 1은 0013의 "화면 HTML에 개인 자료 없음"과 0017에 맞춰 이 plan에서 수정했고, API 공통 규칙의 "세션 없이 받는 요청" 문장에 웹 화면 파일을 추가하는 수정을 구현 전에 `docs/*` 브랜치로 반영했습니다 | 통과 |
| V. 기능마다 테스트 | acceptance마다 테스트를 둡니다(tasks에서 짝지음). 보안 경계 테스트는 구현과 다른 쪽이 씁니다(research R14). 데이터베이스 테스트는 실제 PostgreSQL입니다 | 통과 |
| VI. 결정 없이 미리 만들지 않음 | 바꾸거나 새로 만드는 Spring 지점은 모두 결정 기록이나 spec의 요구에서 나옵니다. 세션 ID 생성기와 쿠키 해석기(0013 해시 변환), 인가 요청 쿠키 저장소(0013 임시 값 쿠키), 저장하지 않는 보안 컨텍스트 저장소와 세션 인증 전략과 인가 클라이언트 저장소, 성공 처리기와 세션 트랜잭션 참여(0013 최소 로그인 정보와 한 트랜잭션, 0012 구글 토큰 저장 안 함), 실패 처리기(US1-4 실패하면 세션 없음), 알고리즘 해석기와 `iss` 검증기(0013 ID 토큰 확인 표), 절대 만료 필터(0013 절대 30일), Origin 검사 필터와 로그아웃 컨트롤러(0013 요청 위조 방어, API 공통 규칙 안전한 메서드), 화면 경로 해석기(0017). 사용하지 않을 인터페이스나 설정은 두지 않습니다. 권한 키 확인기(002), 알림 설정(009)은 만들지 않습니다. Gradle npm 연결에 플러그인을 사용하지 않습니다 | 통과 |
| 기술 제약 | Java 25, Spring Boot 4.1, PostgreSQL, Gradle, Flyway, JPA 기본에 잠금과 세션 삭제만 SQL(결정 기록 0016). 웹 화면은 결정 기록 0017. 모듈은 한 애플리케이션 안입니다. Kafka, Redis는 사용하지 않습니다 | 통과 |

Phase 1 설계 뒤 다시 확인했습니다. 계약의 경로와 표기, data-model의 테이블, quickstart의 실제 데이터 금지가 위 답과 같습니다. 위반이 없어 Complexity Tracking은 비워 둡니다.

## Project Structure

### Documentation (this feature)

```text
specs/001-login/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # 기술 판단과 근거
├── data-model.md        # 001이 만드는 테이블
├── quickstart.md        # 로컬 실행과 화면 확인 순서
├── contracts/
│   └── openapi.yaml     # 001의 API 계약
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks가 만듦
```

### Source Code (repository root)

```text
build.gradle.kts                 # 의존성, web 빌드 연결(Exec 태스크, processResources)
compose.yaml                     # 로컬 PostgreSQL 18, e2e용 가짜 구글 서버
.env.example                     # 항목 이름만(값 없음)
.gitignore                       # web/dist, Playwright 결과 폴더 추가

src/main/java/estate/
├── EstateApplication.java
├── common/
│   ├── crypto/                  # 개인정보 암호화(AES-GCM, 키 버전, AAD)
│   ├── reauth/                  # 재인증 판정(권한 확인 6단계)
│   └── web/                     # Problem Details, Origin 검사 필터, 화면 경로 대체, 보안 헤더
└── user/                        # 사용자와 인증 모듈
    ├── domain/                  # User 엔티티, 표시 이름 규칙
    ├── application/             # 로그인 처리(사용자 찾기와 만들기), 내 정보, 탈퇴
    ├── web/                     # 내 정보와 탈퇴 API, 로그인 성공과 실패 처리기
    ├── security/                # 보안 설정, 로그인 정보, 절대 만료 필터, 인가 요청 쿠키 저장소, 돌아갈 경로 검사
    └── session/                 # 세션 ID 생성기, 쿠키 해석기, 세션 삭제(사용자별)

src/main/resources/
├── application.yml
├── application-local.yml        # 로컬 개발 설정(.env 읽기, 데이터베이스 기본값, 허용 출처)
├── application-local-vite.yml   # Vite 개발 서버(5173)를 쓸 때의 허용 출처와 리디렉션 주소
├── application-tunnel.yml       # 휴대폰 실기기용 터널(forwarded 헤더, ESTATE_PUBLIC_ORIGIN)
├── application-e2e.yml          # Playwright용(가짜 구글 서버 주소, 테스트 키)
└── db/migration/
    └── V1__login.sql            # user, 시퀀스, spring_session 두 테이블

src/test/java/estate/
├── support/                     # 앱 골격의 IntegrationTest, PostgresTestConfiguration, 가짜 구글 서버, 시각 제어
├── common/                      # 암호화, 화면 경로 대체, 보안 헤더 테스트
└── user/                        # 로그인, 만료, 로그아웃, 탈퇴, 내 정보 테스트와 보안 경계 테스트

web/
├── package.json
├── vite.config.ts               # 개발 프록시(/api, /oauth2, /login/oauth2)
├── index.html
├── src/
│   ├── main.tsx
│   ├── api/                     # fetch 호출과 Problem Details 처리
│   ├── pages/                   # 로그인, 가구 없음, 탈퇴 확인, 로그인 실패, 재인증 실패
│   └── styles/
└── tests/
    ├── unit/                    # Vitest와 Testing Library
    └── e2e/                     # Playwright(휴대폰 폭, 로그인 한 바퀴)
```

**Structure Decision**: 서버는 앱 골격의 루트 Gradle 프로젝트에 모듈별 패키지(`estate.user`, `estate.common`)로 둡니다. [C4 컴포넌트](../../docs/architecture/c4-component.md)의 사용자와 인증 모듈과 공통 라이브러리(개인정보 암호화, 재인증 판정)를 패키지로 옮긴 것입니다. 웹 화면은 결정 기록 0017대로 `web/`입니다. 앱 골격의 확인용 테스트와 테스트 전용 마이그레이션 `V0_1`은 그대로 둡니다.

## Complexity Tracking

위반이 없습니다.
