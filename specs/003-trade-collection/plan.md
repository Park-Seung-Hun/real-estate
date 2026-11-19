# Implementation Plan: 003 실거래 수집

**Branch**: `003-trade-collection` | **Date**: 2026-11-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-trade-collection/spec.md`

## Summary

collector가 관심 지역의 아파트 매매 실거래를 범위(지역 하나와 달 하나)마다 수집해 원본 응답 파일로 보관하고, 직전 결과와 비교해 단지, 거래, 거래 이력, 범위 결과를 기록하는 예약 작업입니다. 새 지역은 같은 예약 실행에서 매일 수집 뒤 60개월 초기 적재(또는 범위 확장)로 적재하고, 그 뒤로는 매일 재수집 기간 6개월과 실패한 범위를 다시 수집합니다. 화면, API 엔드포인트, 알림은 없습니다.

기술 방식은 Spring의 `RestClient`와 Spring Framework 7 내장 재시도로 외부 API를 호출하고, AWS SDK for Java v2로 S3 호환 저장소(로컬과 테스트는 Garage)에 gzip 원본을 쓰며, 범위 반영은 `JdbcClient` SQL 한 트랜잭션으로 합니다. 기준 적재 판정은 실행이 시작될 때 읽어 둔 `fetch_range` 값으로 합니다(research R6). 설계 문서와 달라지는 것(7일 조건 둘, 판정 스냅숏, 초기 적재를 시작하는 방법과 선기록, 멈출 때 기록하는 범위, `등기` 규칙)은 구현 전에 `docs/*` 브랜치에서 먼저 수정합니다([research R12](research.md#r12-설계-문서와-달라진-것)).

## Technical Context

**Language/Version**: Java 25. 001, 002와 같습니다

**Primary Dependencies**: Spring Boot 4.1.2(Spring Framework 7.0.x의 `RestClient`, `RetryTemplate`, `@Scheduled`, `JdbcClient`). 새 의존성은 AWS SDK for Java v2 `s3`와 `url-connection-client`(BOM 2.59.4) 둘입니다([research R4](research.md#r4-파일-저장소)). XML 해석은 JDK의 StAX를 씁니다([research R1](research.md#r1-외부-api-응답-형식))

**Storage**: PostgreSQL 18(`complex`, `trade`, `trade_history`, `fetch_range`, 마이그레이션 `V3__trade_collection.sql`, [data-model.md](data-model.md)). 원본 응답 파일은 S3 호환 오브젝트 저장소의 원본 응답 버킷([contracts/raw-response-object-key.md](contracts/raw-response-object-key.md))

**Testing**: JUnit 5, Spring Boot Test, Testcontainers 2.0.5(PostgreSQL 18, Garage `dxflrs/garage:v2.5.0`), 가짜 외부 API는 JDK `com.sun.net.httpserver.HttpServer`(의존성 추가 없음, [research R10](research.md#r10-테스트-방식))

**Target Platform**: 001, 002와 같은 JVM 하나. collector는 API 서버와 한 애플리케이션 안의 모듈입니다(결정 기록 0005)

**Project Type**: 웹 서비스 안의 예약 작업(인바운드 어댑터는 스케줄러뿐)

**Performance Goals**: 한 자치구 매일 수집은 호출 6회, 초기 적재는 60회(결정 기록 0009). 처리량 목표는 없고, 초기 적재 약 1만 2천 건을 한 번의 예약 실행 안에 반영하면 됩니다

**Constraints**: 인증키를 로그, 예외 메시지, 실패 이유, 원본 파일에 남기지 않음(FR-022). 원본 저장 뒤 비교, 범위 반영 한 트랜잭션(FR-010, FR-012). 외부 호출 중에는 데이터베이스 트랜잭션을 열지 않음([research R7](research.md#r7-범위-결과-기록과-트랜잭션)). 실제 관심 지역과 실제 응답 원문을 저장소에 두지 않음(constitution III)

**Scale/Scope**: 한 자치구, 범위 60개(초기 적재)와 매일 6개, 테이블 4개, 외부 API 하나, 설정값 여섯 남짓

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 점검 질문에 대한 답 | 판정 |
|---|---|---|
| I. 공식 API만 사용 | 외부 자료는 공공데이터포털의 아파트 매매 실거래가 상세 API 하나에서만 수집합니다. 화면 수집, 호가, KB 시세, 후기 저장이 없습니다 | 통과 |
| II. 자체 화면 입력 | 이 기능은 사용자 입력이 없는 예약 작업이고 외부 서비스에서 기록을 옮기지 않습니다. 화면이 없어 휴대폰 화면 폭 점검 대상이 없습니다 | 통과(해당 화면 없음) |
| III. 실제 데이터를 저장소에 두지 않음 | fixture의 단지 번호, 단지 이름, 시군구 코드, 주소는 가짜 값(`99999`, "가상단지")입니다. 관심 지역과 인증키는 `.env`와 환경 변수에만 있고 `.env.example`에는 자리표시 값만 둡니다. 재생 확인은 저장소 밖의 원본을 경로 환경 변수로 읽고 결과도 저장소 밖에 씁니다([quickstart](quickstart.md#2-데이터-확인-기간-재생)). spec, plan, contracts에 실제 지역 이름과 코드가 없습니다. tasks에 커밋 전 공개 점검을 넣습니다 | 통과 |
| IV. 설계 문서를 따름 | contracts는 외부 API를 소비하는 쪽의 계약이라 API 공통 규칙(이 서비스가 내보내는 `/api/v1` 규칙)이 적용되지 않습니다. 이 기능이 내보내는 엔드포인트가 없습니다. `data-model.md`는 네 테이블만 적고 설계 데이터 모델을 참조합니다. 용어 표를 따릅니다(수집, 재수집 기간, 범위, 초기 적재, 범위 확장, 기준 적재, 실패한 범위). 설계와 다른 결정 셋은 구현 전에 `docs/*` 브랜치에서 먼저 수정합니다([research R12](research.md#r12-설계-문서와-달라진-것)). 백로그 003의 경계(화면과 알림 없음, 단지는 수집한 실거래에서 만듦)를 지킵니다 | 통과(R12 선행 조건) |
| V. 기능마다 테스트 | spec의 시나리오마다 테스트를 둡니다(tasks에서 짝지음). 변경 감지와 실패 격리 테스트는 구현과 다른 쪽이 spec과 research에서 먼저 씁니다. 데이터베이스가 걸린 테스트는 Testcontainers의 실제 PostgreSQL입니다. 파일 저장소는 대부분 테스트에서 기록하는 가짜 구현을 쓰고, S3 어댑터는 실제 Garage 컨테이너로 확인합니다(constitution V는 데이터베이스만 정함) | 통과 |
| VI. 결정 없이 미리 만들지 않음 | 새로 만드는 지점(실거래가 API 어댑터, 원본 응답 파일 어댑터, 스케줄러, 변경 감지, 단지 등록)은 C4 컴포넌트의 collector 그림에 있습니다. 인터페이스는 원본 응답 파일 포트 하나이고, 결정 기록 0014의 저장소와 테스트의 가짜 구현 둘이 있어 둡니다. 범위 반영(`RangeApplier`)은 구현이 하나라 인터페이스 없이 클래스로 둡니다. 조회용 JPA 엔티티, 009용 인덱스, 수동 실행 엔드포인트를 만들지 않습니다. 재시도와 예약은 Spring 기능을 직접 씁니다 | 통과 |
| 기술 제약 | Java 25, Spring Boot 4.1, PostgreSQL, Gradle, Flyway(결정 기록 0016). 데이터 접근은 대량 반영이라 `JdbcClient` SQL(0016이 허용). collector는 한 애플리케이션 안의 모듈입니다. Kafka, Redis, Kubernetes를 쓰지 않습니다. 웹 화면이 없습니다 | 통과 |

Phase 1 설계 뒤 다시 확인했습니다. data-model은 네 테이블만 적었고, contracts 둘은 외부 API 응답과 원본 응답 파일의 오브젝트 키라 API 공통 규칙 대상이 아닙니다. 새 의존성 둘은 S3 호환 저장소 접근에 필요한 최소 구성입니다.

## Project Structure

### Documentation (this feature)

```text
specs/003-trade-collection/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # 기술 판단과 근거
├── data-model.md        # 003이 만드는 테이블
├── quickstart.md        # 실행 확인 세 단계(화면 확인 대신)
├── contracts/
│   ├── trade-api-response.md        # 소비하는 외부 API의 요청과 응답
│   └── raw-response-object-key.md   # 원본 응답 파일의 위치와 형식
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks가 만듦
```

### Source Code (repository root)

```text
src/main/java/estate/
├── trade/                           # 거래 모듈(새로 만듦)
│   ├── collect/                     # 예약 실행(스케줄러), 실행 계획(매일 수집 범위, 초기 적재 대상), 범위마다 수집, 호출 수 세기
│   ├── detect/                      # 변경 감지와 범위 반영(트랜잭션), 범위 결과 기록
│   ├── api/                         # 실거래가 API 어댑터(RestClient, 재시도, 응답 해석, 인증키 가리기)
│   ├── raw/                         # 원본 응답 파일 포트와 S3 어댑터
│   └── CollectorProperties.java     # 설정값과 시작 시 검사
└── complex/                         # 단지 모듈(새로 만듦)
    └── ComplexRegistry.java         # 처음 보는 단지 번호의 행 만들기(공개, 거래 모듈이 범위 반영 트랜잭션 안에서 호출)

src/main/resources/
├── application.yml                  # collector 설정 기본값(예약 시각, 한도, 버킷, 타임아웃)
└── db/migration/V3__trade_collection.sql

src/test/java/estate/
├── support/                         # 가짜 실거래 API 서버(JDK HttpServer), 기록하는 가짜 원본 저장소, Garage 컨테이너, 거래 fixture 도우미
├── trade/                           # 응답 해석, 변경 감지 21경우, 실패 여덟 가지, 순서, 인증키 노출, 설정 검사
│   └── replay/TradeReplayTest.java  # 데이터 확인 기간 재생(ESTATE_REPLAY_DIR가 있을 때만)
└── complex/

compose.yaml                         # garage 서비스 추가
scripts/garage-init.sh               # 버킷 둘과 collector 키(원본 응답 버킷에만 권한)
.env.example                         # collector 변수 추가(자리표시 값)
```

**Structure Decision**: 001, 002와 같은 루트 Gradle 프로젝트에 모듈별 패키지를 추가합니다. 거래 모듈과 단지 모듈은 [C4 컴포넌트의 collector](../../docs/architecture/c4-component.md#collector) 그림을 따르고, 모듈마다 자기 테이블만 씁니다(단지 모듈은 `complex`, 거래 모듈은 `trade`, `trade_history`, `fetch_range`). 거래 모듈의 범위 반영은 단지 모듈의 공개 서비스를 같은 트랜잭션 안에서 호출합니다(C4 컴포넌트의 모듈 사이 규칙: 같은 실행 단위의 공개 인터페이스 호출). `spike/data-probe/`는 그대로 둡니다.

## 구현 전에 할 것

- `docs/*` 브랜치에서 [research R12](research.md#r12-설계-문서와-달라진-것)의 설계 문서 수정을 먼저 머지합니다(constitution IV). 머지와 이 브랜치의 rebase는 저장소 소유자 허락을 받습니다.
- S3 어댑터 첫 작업에서 Garage 컨테이너에 gzip 파일 하나를 업로드하고 다운로드하는 테스트로 SDK 체크섬 설정(`WHEN_REQUIRED`)이 맞는지 먼저 확인합니다([research R4](research.md#r4-파일-저장소)).

## Complexity Tracking

위반이 없습니다.
