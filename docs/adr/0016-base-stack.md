# 0016. 기반 스택과 데이터 접근 방식

서버는 Java 25와 Spring Boot 4.1, Gradle, Flyway로 만들고, 데이터는 Spring Data JPA를 기본으로 접근하며 잠금과 대량 반영과 집계처럼 JPA가 어색한 곳은 SQL을 직접 사용합니다. QueryDSL은 첫 동적 조회가 필요한 기능에서 도입합니다.

## 배경

- [constitution](../../.specify/memory/constitution.md)의 기술 제약은 서버를 Java와 Spring Boot, 데이터베이스를 PostgreSQL로만 정하고, 버전과 빌드 도구와 데이터 접근 방식은 정하지 않았습니다. 첫 기능(001 로그인)의 plan 전에 정해야 합니다.
- 설계 문서가 이미 데이터 접근에 요구하는 것이 있습니다.
  - 개인정보 열은 `테이블 이름.열 이름`과 그 행의 기본 키를 추가 인증 데이터(AAD)로 넣어 암호화합니다([결정 기록 0012](0012-personal-data-storage.md)). 그래서 기본 키를 삽입 전에 알 수 있어야 합니다.
  - 삭제 연쇄는 `SELECT ... FOR UPDATE`의 잠금 순서까지 정해져 있고, 알림 발송기는 전용 연결 하나에서 `pg_try_advisory_lock`을 잡습니다([C4 컴포넌트](../architecture/c4-component.md), [핵심 흐름](../architecture/flows.md)).
  - 실거래 수집은 범위마다 거래를 식별 키로 직전 결과와 비교해 반영하고 이력을 기록합니다([결정 기록 0007](0007-change-history.md)).
- constitution 원칙 V는 데이터베이스가 걸린 테스트를 실제 PostgreSQL로 실행하라고 정했고, 원칙 VI는 Spring 기능을 감싸기만 하는 계층을 두지 말라고 정했습니다.
- 2026-11-04에 최신 버전과 지원 기간을 조사했습니다. 출처는 [endoflife.date의 Spring Boot](https://endoflife.date/api/spring-boot.json)와 [Oracle JDK](https://endoflife.date/api/oracle-jdk.json), [Spring Boot 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html), [Spring Data JPA 문서](https://docs.spring.io/spring-data/jpa/reference/)입니다.

## 선택지

Java 버전은 아래 둘을 비교했습니다.

| 선택지 | 내용 | 선택 |
|---|---|---|
| Java 25(LTS) | 2025-09에 출시되었고 기본 지원이 2030-09까지입니다. Spring Boot 4.1은 Java 17 이상 27까지 호환 범위로 둡니다 | 채택 |
| Java 21(LTS) | 개발 PC에 이미 설치되어 있고 기본 지원이 2028-09까지입니다 | 제외. 3, 4단계 개발 도중에 지원이 끝나 올려야 합니다 |

Spring Boot 버전은 아래 둘을 비교했습니다.

| 선택지 | 내용 | 선택 |
|---|---|---|
| 4.1.x | 최신은 4.1.2(2026-06-30)이고 오픈소스 지원은 2027-07까지로 보입니다(조사한 표의 열 이름이 모호해 확정은 아닙니다) | 채택 |
| 4.0.x | 오픈소스 지원이 2026-12-31에 끝납니다 | 제외. 시작하기에 지원 기간이 너무 짧습니다 |

빌드 도구와 스키마 마이그레이션은 아래처럼 비교했습니다.

| 선택지 | 내용 | 선택 |
|---|---|---|
| Gradle(Kotlin DSL)과 래퍼 | Spring Boot 4.1이 Gradle 8.14 이상과 9.x를 지원합니다 | 채택 |
| Maven | Spring Boot 4.1이 3.6.3 이상을 지원합니다 | 제외. 둘 다 지원하고 선택할 근거 차이가 없어 Gradle로 정했습니다 |
| Flyway(SQL 파일) | 마이그레이션을 SQL로 직접 적습니다. 데이터 모델의 PostgreSQL 기능(유니크 제약이 빈 값을 다루는 방식 등)과 `spring_session` 열 변경을 그대로 적을 수 있습니다 | 채택 |
| Liquibase | 변경을 XML이나 YAML로 적습니다 | 제외. SQL을 그대로 쓰는 쪽이 이 프로젝트의 직접 SQL 방식과 맞습니다 |

데이터 접근 방식은 아래 다섯 가지를 비교했습니다. 평가의 상당 부분은 [2026-05 기준 비교 글](https://note.com/glad_briony3465/n/n2a5ff74994a5?hl=en)과 Spring Data JPA 문서에 기댔습니다.

| 선택지 | 내용 | 선택 |
|---|---|---|
| JPA를 기본으로 하고 SQL 직접 병행 | 일반 저장과 조회는 Spring Data JPA(Hibernate 7)로 하고, 잠금과 권고 잠금과 수집 대량 반영과 집계 조회는 `JdbcClient`로 SQL을 직접 씁니다 | 채택 |
| JPA와 QueryDSL을 처음부터 사용 | 동적 조회를 QueryDSL(OpenFeign 포크 `io.github.openfeign.querydsl`)로 씁니다. 원본 `com.querydsl`은 사실상 중단되었고, Spring Data JPA 문서는 포크를 최선 노력으로만 지원한다고 적습니다 | 제외. 001과 002의 조회는 단순해 필요가 없고, Hibernate 7과의 호환은 직접 확인한 근거가 없습니다. 첫 동적 조회가 필요한 기능에서 도입합니다 |
| JPA와 Specification | Spring Data JPA 4의 `PredicateSpecification`과 Hibernate Processor가 만드는 메타모델로 조건을 조합합니다 | QueryDSL 도입이 막힐 때의 대체 |
| jOOQ | 코드 생성으로 SQL을 타입 안전하게 씁니다. PostgreSQL은 오픈소스 판으로 사용할 수 있습니다 | 제외. 저장소 소유자가 JPA를 기본으로 하기로 했습니다 |
| Spring Data JDBC | 연관관계가 단순한 도메인용입니다 | 제외. 지연 로딩과 다대다가 없고, 열 암호화의 기본 키 문제는 JPA와 같아 이점이 없습니다 |

테스트 데이터베이스는 constitution 원칙 V가 실제 PostgreSQL을 요구하므로 H2 같은 다른 데이터베이스는 선택지에서 제외했습니다. 실제 PostgreSQL을 컨테이너(Testcontainers)로 띄우는 방식을 채택합니다. 개발 PC에서 Docker Desktop이 실행 중임을 확인했습니다.

## 결정

- 서버는 Java 25, Spring Boot 4.1.x(최신 패치), Gradle(Kotlin DSL과 래퍼)로 만듭니다. 스키마 마이그레이션은 Flyway의 SQL 파일로 합니다.
- 데이터는 Spring Data JPA를 기본으로 접근합니다. 아래는 `JdbcClient`로 SQL을 직접 씁니다.
  - 삭제 연쇄의 `SELECT ... FOR UPDATE`와 권고 잠금
  - 수집이 범위마다 거래를 반영하는 대량 삽입과 갱신
  - 윈도 함수나 집계가 필요한 조회
- 개인정보 열 AAD에 행 기본 키를 넣기 위해 기본 키를 삽입 전에 시퀀스로 받아 엔티티에 넣습니다. Hibernate가 저장 시점에 부여하는 기본 키는 값 변환기가 볼 수 없어 쓰지 않습니다(이 판단은 구현 때 확인합니다). 구체적인 암호화 방식은 001의 plan이 정합니다.
- QueryDSL(OpenFeign 포크)은 첫 동적 조회가 필요한 기능(004 관심 단지)에서 도입합니다. 도입 때 실제 PostgreSQL에서 쿼리가 도는 스모크 테스트로 호환을 확인하고, 되지 않으면 Specification으로 대체합니다.
- 데이터베이스가 걸린 테스트는 Testcontainers로 띄운 실제 PostgreSQL에서 실행합니다. 컨테이너는 테스트 실행 하나에서 재사용합니다. 이미지는 현재 안정 메이저 버전으로 고정하고, 골격 단계에서 버전을 확인합니다.
- 웹 화면 기술은 이 기록에서 정하지 않고, 화면이 처음 나오는 001의 plan에서 정합니다(constitution 기술 제약).

## 결과

- constitution의 기술 제약에 이 스택을 추가합니다(1.2.0).
- 앱 골격(`chore/app-skeleton`)은 빈 Spring Boot 애플리케이션과 Flyway와 Testcontainers 기반을 만듭니다. 완료 조건은 실제 PostgreSQL에서 Flyway가 적용되고, 시퀀스로 먼저 받은 기본 키로 엔티티를 저장하고 조회하는 테스트가 통과하는 것입니다. QueryDSL은 골격에 넣지 않습니다.
- 개발 PC에 Java 25를 설치해야 합니다.
- 001의 plan이 정할 것: 웹 화면 기술, 개인정보 열 암호화의 구현 방식, 로그인 흐름을 검증할 구글 대체 서버(가짜 OIDC 서버) 방식, 시간 제어 방식(7일, 30일, 5분 검증), 보안 경계 테스트 작성 방식, 실제 구글의 `max_age`와 `auth_time` 동작을 확인하는 수동 점검 절차입니다.
- 재검토 조건: 004에서 QueryDSL 포크가 Hibernate 7에서 동작하지 않으면 Specification으로 바꿉니다. 수집 대량 반영이 JPA 경로에서 느리면 해당 부분을 `JdbcClient`로 옮기는 것은 이 결정 안에서 허용됩니다. JPA에서 열 암호화가 복잡해지면 그 열의 저장만 SQL 직접으로 바꿀 수 있습니다.
- 확인하지 못한 것: Hibernate 7과 QueryDSL 포크의 호환, Spring Boot 4.1에서 Spring Session JDBC에 해시 저장 래퍼를 얹는 구현은 해당 기능의 구현 때 확인합니다.

## 상태

확정, 2026-11-04
