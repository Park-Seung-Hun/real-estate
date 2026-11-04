# 핵심 흐름

이 문서는 1단계의 네 흐름(수집과 변경 감지, 알림 판정과 발송, 임장 기록과 사진, 단지 등록에서 관심 단지와 매물 등록까지)이 어떤 컴포넌트를 어떤 순서로 지나고, 실패하면 어디서 멈추고 무엇이 남는지를 정합니다. 컴포넌트는 [C4 컴포넌트](c4-component.md), 테이블과 열은 [데이터 모델](data-model.md)을 따릅니다.

- 그림은 Mermaid 시퀀스 다이어그램(sequenceDiagram)으로 그렸습니다. 참여자 이름은 C4 컴포넌트의 컴포넌트 이름 그대로이고, 한 그림 안에서 같은 이름이 두 실행 단위에 나오면 이름 뒤에 실행 단위를 괄호로 붙였습니다.
- 그림을 짧게 하려고 데이터베이스 읽기와 쓰기는 데이터베이스(PostgreSQL)로 바로 그렸습니다. 실제로는 그 실행 단위의 데이터베이스 저장소를 거치고, 다른 실행 단위의 테이블을 읽을 때는 데이터베이스 조회 어댑터를 거칩니다([C4 컴포넌트의 모듈 사이 규칙](c4-component.md#모듈-사이-규칙)). 알림 흐름에서는 이 구분이 규칙이라 두 어댑터를 따로 그렸습니다.
- 그림의 번호는 Mermaid가 붙인 화살표 순번이고, 실패 표의 "단계" 칸이 이 번호를 가리킵니다.
- 엔드포인트, 요청, 응답 본문, 상태 코드와 오류 코드는 이 문서에서 정하지 않습니다. 기능마다 `/speckit-plan`이 `specs/NNN-이름/contracts/`에 만듭니다([API 공통 규칙](api-conventions.md)).

## 수집과 변경 감지

collector가 매일 정한 시각에 관심 지역의 실거래를 지역과 월 단위 범위로 수집하고, 직전 수집 결과와 비교해 거래와 거래 이력을 갱신합니다. 식별 키는 [결정 기록 0006](../adr/0006-transaction-identity.md), 변경 종류 넷은 [결정 기록 0007](../adr/0007-change-history.md)을 따릅니다. 재수집 기간은 [결정 기록 0009](../adr/0009-refetch-window.md)가 정한 이번 달을 포함한 최근 6개월입니다.

매일 수집에서 수집할 범위는 아래 둘을 합친 것입니다.

- 재수집 기간의 달
- `fetch_range.status`가 `실패`인 범위. 재수집 기간 밖이어도 함께 수집합니다

초기 적재나 범위 확장 실행은 그때 수집하는 달만 수집합니다. 아래 그림은 스케줄러가 시작하는 매일 수집이고, 초기 적재나 범위 확장 실행도 범위마다 같은 단계를 거칩니다.

매일 수집은 재수집 기간의 달을 먼저 수집하고, 그다음 실패한 범위를 수집합니다. 초기 적재와 범위 확장은 매일 수집과 따로 실행하고, 같은 날이면 매일 수집이 끝난 뒤에 시작합니다. 이유와 한도에 도달했을 때 무엇을 하는지는 [결정 기록 0009](../adr/0009-refetch-window.md)에 있습니다.

- 재수집 기간의 달 수집이 밀리면 그 지역의 알림이 하루 늦습니다. 달이 바뀐 첫날 새 달의 매일 수집이 실패하면 그 달은 다음 성공 때 기준 적재가 되어, 그 사이 신고된 그 달 거래의 알림이 빠집니다([결정 기록 0007](../adr/0007-change-history.md)).
- 초기 적재나 범위 확장이 한도에 도달해 남긴 범위는 다음 날부터 매일 수집이 실패한 범위로 이어서 수집합니다. 이 범위는 `fetch_range` 행이 있으므로 기준 적재가 됩니다.
- 매일 수집이 새 지역이나 범위 확장으로 늘어난 기간의 달을 먼저 수집하면 빈 결과와 비교되어, 그 달 거래가 모두 새 거래 알림이 됩니다. 그래서 초기 적재를 시작하는 방법(아래 정하지 않은 것)은 이를 막도록 정합니다.

### 범위마다 수집

```mermaid
sequenceDiagram
  autonumber
  participant sched as 스케줄러
  participant fetch as 실거래 수집
  participant apiAd as 실거래가 API 어댑터
  participant tradeApi as 아파트 실거래가 API
  participant rawAd as 원본 응답 파일 어댑터
  participant s3 as S3 호환 오브젝트 저장소(원본 응답 버킷)
  participant detect as 변경 감지
  participant db as 데이터베이스(PostgreSQL)

  sched->>fetch: 수집 시작(매일 정한 시각)
  fetch->>db: fetch_range 읽기, 수집할 범위 정하기
  Note over fetch: API를 호출할 때마다 이 실행의 호출 수를 세고, 하루 한도에 도달하기 전에 멈추고 남은 범위를 실패로 기록
  loop 범위(시군구 코드와 계약년월)마다
    loop 페이지마다, 마지막 페이지까지
      fetch->>apiAd: 한 페이지 조회
      apiAd->>tradeApi: LAWD_CD, DEAL_YMD, 페이지 번호
      opt HTTP 오류나 네트워크 오류
        apiAd->>tradeApi: 2초 쉬고, 다시 실패하면 4초 쉬고 같은 요청(최대 3번 시도)
      end
      alt 3번 모두 실패
        fetch->>db: 이 범위를 실패로 기록하고 다음 범위로
      else 응답을 받음
        tradeApi-->>apiAd: 결과 코드, totalCount, 거래 목록
        apiAd-->>fetch: 응답
        alt 인증키 오류나 하루 한도 초과(코드 22)
          fetch->>db: 이 범위와 남은 범위를 모두 실패로 기록
          Note over fetch: 실행을 멈춤
        else 결과 코드가 정상이 아님
          fetch->>db: 이 범위를 실패로 기록하고 다음 범위로
        end
      end
    end
    fetch->>rawAd: 원본 응답 저장(모든 페이지를 수집한 뒤)
    rawAd->>s3: 원본 응답 버킷에 gzip 파일 저장
    alt 원본 저장 실패
      fetch->>db: 이 범위를 실패로 기록하고 다음 범위로
    else 모든 페이지의 원본 저장을 마침
      alt 수집한 건수가 totalCount와 다름, 직전에 거래가 있던 범위가 0건
        fetch->>db: 이 범위를 실패로 기록하고 비교하지 않음(원본은 남음)
      else 건수 확인을 통과
        fetch->>detect: 이 범위의 거래 목록(아래 범위 반영)
      end
    end
  end
```

- 원본 응답 파일의 오브젝트 키는 원본 응답 버킷 안의 `{수집 날짜 YYYY-MM-DD}/{실행 시작 시각 HHmmss}/{시군구 코드}-{계약년월}-p{페이지 번호}.xml.gz`입니다. 원본은 gzip으로 압축해 삭제하지 않고 보관합니다([결정 기록 0014](../adr/0014-file-storage.md)). 실행마다 따로 남고 덮어쓰지 않으므로, 같은 날 재수집해도 앞 실행의 파일과 섞이지 않습니다.
- 결과 코드는 페이지마다 응답을 받은 직후에 확인합니다. 모든 페이지를 수집하면 원본 응답을 먼저 저장하고, 그다음 건수를 확인하고 비교합니다. 건수가 다른 범위도 원본이 남아 원인을 조사할 수 있습니다.
- 결과 코드가 정상이 아니면 같은 요청을 다시 보내도 같은 결과일 가능성이 커서 다시 시도하지 않습니다. 인증키 오류와 하루 한도 초과는 남은 범위도 같은 결과이므로 실행을 멈춥니다.
- 하루 호출 수를 세는 테이블은 두지 않습니다. 페이지 수는 첫 응답을 받아야 알 수 있어 시작 전에 최대 호출 수를 계산할 수 없습니다. 그래서 실행 안에서 호출할 때마다 호출 수를 세고 하루 한도에 도달하기 전에 멈춥니다. 이때 남은 범위는 실패로 기록하고 다음 날 재수집합니다. 같은 날 여러 번 실행하면 뒤 실행은 앞 실행의 호출 수를 모릅니다. 1단계에서도 초기 적재나 범위 확장을 하는 날에는 두 번 실행하므로, 뒤 실행이 스스로 멈추기 전에 하루 한도 초과(코드 22)를 받을 수 있습니다. 이때도 남은 범위가 실패로 남을 뿐 데이터는 손상되지 않습니다. 호출 수를 합산하는 방법은 3단계에 재검토합니다([결정 기록 0004](../adr/0004-staged-adoption.md), [결정 기록 0009](../adr/0009-refetch-window.md)).
- "직전에 거래가 있던 범위"는 그 범위의 거래 행 가운데 `same_key_count`가 1 이상인 행이 있는 범위입니다.

### 범위 반영

한 범위의 반영(단지 만들기, 거래와 이력 갱신, `fetch_range` 성공 기록)은 한 트랜잭션에서 합니다.

```mermaid
sequenceDiagram
  autonumber
  participant fetch as 실거래 수집
  participant detect as 변경 감지
  participant reg as 단지 등록
  participant db as 데이터베이스(PostgreSQL)

  fetch->>detect: 이 범위의 거래 목록
  detect->>db: 트랜잭션 시작
  detect->>db: 이 범위의 수집 시각 하나를 정함
  detect->>db: 직전 결과 읽기(이 범위에서 same_key_count가 1 이상인 거래 행)
  loop 처음 보는 aptSeq마다
    detect->>reg: 단지 찾기와 만들기
    reg->>db: complex 행 만들기
  end
  alt fetched_at이 있음(재수집 기간을 벗어난 실패한 범위 포함)
    detect->>db: 식별 키로 직전 결과와 비교해 trade 갱신(새 행은 first_seen_at), 바뀐 것마다 trade_history 한 행(observed_at)
  else fetched_at이 비었고 fetch_range 행이 있음(앞서 실패만 한 범위)
    detect->>db: 기준 적재, trade 행 만들기(first_seen_at), 이력은 기록하지 않음
  else fetched_at이 비었고 fetch_range 행이 없음(처음 수집하는 범위)
    alt 초기 적재나 범위 확장 실행
      detect->>db: 기준 적재, trade 행 만들기(first_seen_at), 이력은 기록하지 않음
    else 매일 수집(달이 바뀌어 재수집 기간에 새로 들어온 달)
      detect->>db: 빈 직전 결과와 비교해 trade 행 만들기(first_seen_at), 거래마다 trade_history 한 행(observed_at)
    end
  end
  detect->>db: fetch_range 성공 기록(status 성공, fetched_at 지금)
  detect->>db: 커밋
  opt 커밋 전에 오류
    db-->>detect: complex, trade, trade_history, fetch_range 변경을 모두 롤백
    detect->>db: 새 트랜잭션으로 이 범위를 실패로 기록
  end
```

- 범위 하나를 반영할 때 수집 시각 하나를 정해, 그 반영에서 새로 만드는 `trade.first_seen_at`과 그 반영에서 기록하는 `trade_history.observed_at`에 같은 값을 씁니다.
- `fetch_range.fetched_at`은 그 범위를 마지막으로 성공한 시각입니다. 실패해도 삭제하지 않고, `status`만 마지막 시도의 결과(`성공`, `실패`)로 바꿉니다.
- `fetched_at`이 있는 범위는 재수집 기간을 벗어난 실패한 범위를 포함해 늘 직전 결과와 비교해 이력을 기록합니다.
- `fetched_at`이 빈 범위는 `fetch_range` 행이 있는지로 나눕니다. 행이 있는 범위(앞서 실패만 한 범위)는 어느 실행에서든 기준 적재입니다. 실패한 범위를 빈 결과와 비교하면 그 달 거래가 모두 새 거래 알림으로 나가기 때문입니다.
- 행이 없는 범위(처음 수집하는 범위)는 초기 적재나 범위 확장 실행이면 기준 적재이고, 매일 수집이면(달이 바뀌어 재수집 기간에 새로 들어온 달) 빈 직전 결과와 비교해 이력을 기록합니다. 그래서 그 달의 새 거래에도 알림이 나갑니다.
- 대가로 새 달의 첫 매일 수집이 실패하면 그 달은 다음 성공 때 기준 적재가 되어, 그 사이 신고된 그 달 거래의 알림이 빠집니다.
- 기관마다 다른 단지 키를 연결하는 일은 이 흐름에 없습니다. 2단계에 고른 방법에 맞춰 열을 추가합니다([결정 기록 0008](../adr/0008-complex-matching.md)).

### 수집이 실패하면

| 단계 | 실패 | 멈추는 것 | 데이터베이스에 남는 것 | 파일 저장소에 남는 것 |
|---|---|---|---|---|
| 범위마다 수집 4~6 | HTTP 오류나 네트워크 오류로 3번 모두 실패 | 그 범위만. 다음 범위는 계속 수집합니다 | `fetch_range`의 `status`가 `실패`, `failure_reason`에 이유. `fetched_at`과 거래, 이력은 그대로 | 바뀌는 것 없음(이 범위의 원본은 모든 페이지를 수집한 뒤에 저장합니다) |
| 범위마다 수집 7, 8, 10 | 결과 코드가 정상이 아님(인증키 오류와 코드 22 제외) | 그 범위만 | 위와 같음 | 위와 같음 |
| 범위마다 수집 7~9 | 인증키 오류나 하루 한도 초과(코드 22) | 그 실행 전체 | 그 범위와 아직 수집하지 않은 범위가 모두 `실패` | 위와 같음 |
| 범위마다 수집(호출 전) | 실행 안에서 센 호출 수가 하루 한도에 도달해 스스로 멈춤 | 그 실행 전체 | 페이지를 수집하던 범위와 남은 범위가 모두 `실패` | 페이지를 수집하던 범위의 원본은 저장하지 않습니다(모든 페이지를 수집해야 저장). 앞서 끝난 범위의 파일은 남습니다 |
| 범위마다 수집 11~13 | 원본 응답 저장 실패 | 그 범위만. 비교하지 않습니다 | 그 범위가 `실패` | 저장에 성공한 페이지의 원본 파일 |
| 범위마다 수집 14 | 수집한 건수가 `totalCount`와 다르거나, 직전에 거래가 있던 범위가 0건 | 그 범위만. 비교하지 않습니다 | 그 범위가 `실패`. 거래와 이력은 그대로라 수집하지 못한 거래가 `사라짐`으로 남지 않습니다 | 그 범위의 원본 파일. 원인을 조사할 수 있게 남습니다 |
| 범위 반영 | 트랜잭션 커밋 전 오류 | 그 범위만 | 그 범위의 단지, 거래, 이력, 성공 기록이 모두 롤백되고 `실패` 기록만 | 원본 파일 모두 |

실패한 범위는 다음 날 매일 수집에서 다시 수집합니다. 범위마다 한 트랜잭션으로 반영하므로 일부만 반영된 범위는 남지 않고, 재수집하면 처음부터 비교합니다.

## 알림 판정과 발송

알림 발송기는 데이터베이스의 거래 이력과 매물의 방문 일시를 읽어 알림 대상을 정하고, 발송 기록 행을 먼저 만든 뒤 메일을 보냅니다. collector와 서로 호출하지 않습니다([C4 컴포넌트의 collector에서 알림 발송기로 전달](c4-component.md#collector에서-알림-발송기로-전달)).

### 실행과 판정

```mermaid
sequenceDiagram
  autonumber
  participant sched as 스케줄러
  participant tradeAlert as 거래 알림 판정
  participant change as 거래 변경 수신 어댑터
  participant visitAlert as 방문 알림 판정
  participant query as 데이터베이스 조회 어댑터
  participant delivery as 발송 기록
  participant db as 데이터베이스(PostgreSQL)

  sched->>db: 전용 연결 하나에서 pg_try_advisory_lock
  alt 잠금을 획득하지 못함
    Note over sched: 이 실행을 건너뜀
  else 잠금을 획득함
    sched->>tradeAlert: 거래 알림 대상 찾기
    tradeAlert->>change: 거래 변경 수신 포트로 후보 이력 받기
    change->>db: 판정과 같은 조건의 신규, 해제 이력 읽기(수집 시각이 최근 3일 안)
    change-->>tradeAlert: 후보 이력
    tradeAlert->>query: 관심 단지와 added_at, 구성원과 joined_at, email_verified, 단지 이름
    tradeAlert->>db: 알림 설정의 trade_alert_on_at 읽기
    tradeAlert->>delivery: 수신자마다 알림 메시지
    sched->>visitAlert: 방문 알림 대상 찾기
    visitAlert->>query: 방문 일시가 있는 관심 단지 매물, 구성원, email_verified, 단지 이름
    visitAlert->>db: 알림 설정의 visit_alert_on, visit_alert_lead_hours 읽기
    visitAlert->>delivery: 수신자마다 알림 메시지
    loop 메시지마다, 행마다 따로 커밋
      delivery->>db: 발송 기록 행 만들기(sent_at 비움, created_at 지금)
      alt 유니크 제약 위반
        Note over delivery: 이미 있는 조합이므로 건너뜀
      else 외래 키 오류
        Note over delivery: 대상이 사라졌으므로 그 행만 건너뜀
      end
    end
    sched->>delivery: sent_at이 빈 행 보내기(아래 보내기와 재발송)
    sched->>db: 같은 연결에서 pg_advisory_unlock
  end
  Note over sched: 실행이 끝나고 10분 뒤 다음 실행
```

거래 알림 판정의 조건은 아래와 같습니다([데이터 모델의 알림 대상](data-model.md#알림-대상)).

- 후보는 `change_type`이 `신규`나 `해제`이고, 그 이력의 `observed_at`이 그 거래 행의 `first_seen_at`과 같은 이력입니다. 사라졌다 다시 보인 키와 건수가 늘어난 기존 키의 이력은 보내지 않습니다.
- 수신자는 그 단지를 관심 단지로 둔 가구의 현재 구성원 가운데 `trade_alert_on_at`이 비어 있지 않은 사람입니다.
- 이력의 `observed_at`이 `interest_complex.added_at`, 수신자의 `household_member.joined_at`, 수신자의 `trade_alert_on_at`보다 뒤여야 합니다. 그래서 거래 알림을 껐다 켜면 끈 동안의 거래는 보내지 않습니다.
- 후보는 수집 시각(`observed_at`)이 최근 3일 안인 이력으로 한정합니다. 3일은 재발송 기한과 같게 정한 값입니다. 이렇게 해서 이메일이 나중에 검증되거나 발송기가 오래 멈췄다 돌아올 때 지난 이력의 알림이 한꺼번에 만들어지지 않게 합니다.
- `trade_alert_on_at`은 꺼진 상태에서 켤 때만 현재 시각을 적습니다. 켜진 채로 다른 설정(방문 알림 시점 등)을 저장할 때는 바꾸지 않습니다. 알림 설정 화면의 저장 버튼이 하나라, 저장할 때마다 `trade_alert_on_at`을 현재 시각으로 덮어쓰면 그 직전에 수집된 거래의 알림이 빠지기 때문입니다.
- 첫 로그인 때 만드는 알림 설정 행의 기본값은 `trade_alert_on_at` 행을 만든 시각, `visit_alert_on` 참, `visit_alert_lead_hours` 24입니다.
- 발송 기록이 없는 (사람, 거래, 종류) 조합만 행을 만듭니다.
- 거래 변경 수신 어댑터는 같은 조건을 만족하는 수신자 가운데 한 사람이라도 발송 기록이 없는 이력만 후보로 읽습니다. 조건이 판정과 어댑터 두 곳에 있는 것은 3단계에 어댑터만 Kafka 컨슈머로 바꾸려는 것이고, 두 조건이 같은지는 아래 [테스트](#구현-때-고정할-테스트)로 확인합니다.

방문 알림 판정의 조건은 아래와 같습니다.

- 방문 일시가 있는 매물 가운데 그 단지가 가구의 관심 단지인 것이 대상입니다.
- 수신자는 현재 구성원 가운데 `visit_alert_on`이 참인 모든 사람입니다.
- 현재 시각이 `visit_at`에서 그 사람의 `visit_alert_lead_hours`(3, 24, 48 중 하나, 기본 24)를 뺀 시각 이후이고 `visit_at` 전이면 보냅니다. 그래서 같은 매물이라도 수신자마다 보내는 시각이 다를 수 있습니다.
- 발송 기록이 없는 (사람, 매물, 방문 일시) 조합만 행을 만듭니다.

두 판정 모두 `user.email_verified`가 거짓인 사람은 수신자에서 빼고 발송 기록 행을 만들지 않습니다. 알림 메일은 수신자의 로그인 이메일로만 보내기 때문입니다.

실행을 감싸는 권고 잠금은 아래와 같이 다룹니다.

- 실행 전체를 PostgreSQL 권고 잠금(`pg_try_advisory_lock`)으로 감쌉니다. 두 실행이 겹치면 한쪽이 보내는 중인 행을 다른 쪽이 실패한 행으로 보고 재발송할 수 있기 때문입니다.
- 권고 잠금은 그 잠금을 획득한 데이터베이스 연결에 묶입니다. 그래서 실행 동안 붙잡는 전용 연결 하나에서 획득하고 같은 연결에서 해제합니다. 연결 풀의 다른 연결에서 해제하면 잠금이 해제되지 않습니다.
- 실행 중에 예외가 나도 같은 연결에서 잠금을 해제합니다. 프로세스가 멈춰 연결이 끊기면 PostgreSQL이 잠금을 해제합니다.
- 발송 기록 행은 행마다 따로 커밋합니다. 한 행의 오류가 다른 행을 롤백하지 않습니다.
- 알림 발송기는 수집 직후에 따로 실행되지 않습니다. 앞 실행이 끝나고 10분 뒤 다음 실행을 시작합니다.

### 보내기와 재발송

```mermaid
sequenceDiagram
  autonumber
  participant delivery as 발송 기록
  participant query as 데이터베이스 조회 어댑터
  participant mailAd as 메일 어댑터
  participant addrQ as 받을 주소 조회
  participant crypto as 개인정보 암호화
  participant mail as 메일 발송 서비스
  participant db as 데이터베이스(PostgreSQL)

  delivery->>db: sent_at이 빈 행 읽기
  loop 행마다
    alt 거래 알림이고 created_at에서 3일이 지남, 또는 방문 알림이고 visit_at이 지남
      Note over delivery: 보내지 않음, sent_at이 빈 채 남음
    else 기한 안
      delivery->>db: 수신자의 알림 설정 읽기
      delivery->>query: 현재 구성원인지(joined_at), 관심 단지인지(added_at), email_verified, 거래 알림이면 trade.first_seen_at, 방문 알림이면 매물의 현재 visit_at 읽기
      alt 판정 조건 전체를 지금은 만족하지 않음(거래 알림은 first_seen_at이 켠 시각, joined_at, added_at보다 앞선 경우, 방문 알림은 visit_at이 행과 다른 경우 포함)
        Note over delivery: 보내지 않음, sent_at이 빈 채 남음
      else 지금도 만족함
        delivery->>query: 행의 참조로 거래, 단지 이름, 방문 일시 읽기
        delivery->>mailAd: 알림 채널 포트로 알림 메시지
        mailAd->>addrQ: 수신자의 주소
        addrQ->>query: user.login_email 읽기
        addrQ->>crypto: 로그인 이메일 복호화
        addrQ-->>mailAd: 메일 주소
        mailAd->>mail: 메일 발송 요청
        alt 발송 성공
          mail-->>mailAd: 접수
          mailAd-->>delivery: 보냄
          delivery->>db: sent_at 기록(행마다 커밋)
        else 발송 실패
          mailAd-->>delivery: 보내지 못함
          Note over delivery: sent_at이 빈 채 남고 다음 실행이 재발송함
        end
      end
    end
  end
```

- 판정이 이번 실행에 만든 행과 앞 실행에서 보내지 못한 행은 모두 `sent_at`이 빈 행이므로 같은 순서로 보냅니다.
- 보내기 전에 행마다 수신자와 대상이 지금도 아래 조건을 모두 만족하는지 다시 확인하고, 아니면 보내지 않습니다.
  - 현재 구성원입니다.
  - 그 알림을 켰습니다.
  - 관심 단지입니다.
  - `email_verified`가 참입니다.
  - 방문 알림이면 매물의 현재 `visit_at`이 행의 `visit_at`과 같습니다.
  - 거래 알림이면 조회 포트로 읽은 `trade.first_seen_at`이 현재의 `interest_complex.added_at`, `household_member.joined_at`, `trade_alert_on_at`보다 뒤입니다. 보낼 행은 이 값이 이력의 `observed_at`과 같습니다.
- 이 확인은 판정 조건 전체를 다시 확인하는 것이라, 메일이 실패한 사이 알림을 껐다 켜거나 단지를 뺐다 다시 넣으면 그 전 이력은 보내지 않습니다. 앞 실행에서 보내지 못한 사이에 가구를 나간 구성원이나 알림을 끈 사람에게 메일이 가지 않게 하려는 것입니다. 이번 실행에 만든 행은 방금 판정을 통과했으므로 같은 결과가 나옵니다.
- 발송 기록 행에는 메시지 내용이 없으므로, 행의 참조(거래 알림은 `trade_id`, 방문 알림은 `listing_id`와 `visit_at`)로 조회 포트를 호출해 메시지를 다시 만듭니다.
- 알림 메일은 수신자의 `user.login_email`(복호화)로만 보냅니다. `user.email_verified`가 거짓인 사람은 판정에서 빠지므로 행이 없습니다. 알림 발송기는 사용자와 인증 모듈의 `user`를 실행 단위를 넘어 읽으므로 알림 모듈의 조회 포트와 데이터베이스 조회 어댑터로 읽습니다.
- 거래 알림 메일에는 단지 이름, 알림 종류(새 거래, 해제), 계약일, 전용면적, 층, 금액, 서비스 링크를 넣습니다. 방문 알림 메일에는 단지 이름, 방문 일시, 서비스 링크를 넣습니다. 매물 주소, 동, 호는 어떤 메일에도 넣지 않습니다.
- 실행마다 `sent_at`이 빈 행을 재발송합니다. 거래 알림은 `created_at`에서 3일이 지나면, 방문 알림은 `visit_at`이 지나면 재발송하지 않고, 그 행은 `sent_at`이 빈 채 남습니다.

### 삭제 연쇄와 겹칠 때

API 서버의 삭제 연쇄(매물 삭제, 가구 삭제, 탈퇴)가 발송 기록을 삭제하는 동안 알림 발송기가 같은 매물이나 같은 사람의 발송 기록 행을 만들 수 있습니다. 삭제 연쇄는 삭제할 부모 행을 `SELECT ... FOR UPDATE`로 먼저 잠근 뒤 발송 기록부터 삭제합니다. 아래는 매물 삭제의 예입니다.

```mermaid
sequenceDiagram
  autonumber
  participant listing as 관심 단지와 매물
  participant notiSet as 알림 설정
  participant db as 데이터베이스(PostgreSQL)
  participant delivery as 발송 기록

  alt 발송기의 행이 먼저 커밋됨
    delivery->>db: 그 매물의 방문 알림 행 만들기, 커밋
    listing->>db: 트랜잭션 시작, 매물 행 SELECT ... FOR UPDATE
    listing->>notiSet: 그 매물의 방문 알림 발송 기록 삭제
    notiSet->>db: 방금 커밋된 행까지 notification_delivery 삭제
    listing->>db: 호가 이력, 임장 기록, 매물 삭제, 커밋
  else 삭제 연쇄가 먼저 매물 행을 잠금
    listing->>db: 트랜잭션 시작, 매물 행 SELECT ... FOR UPDATE
    delivery->>db: 그 매물의 방문 알림 행 만들기
    Note over db: 외래 키 확인이 매물 행 잠금이 해제되기를 기다림
    listing->>notiSet: 그 매물의 방문 알림 발송 기록 삭제
    notiSet->>db: notification_delivery 삭제
    listing->>db: 호가 이력, 임장 기록, 매물 삭제, 커밋
    db-->>delivery: 외래 키 오류
    Note over delivery: 대상이 사라졌으므로 그 행만 건너뜀
  end
```

PostgreSQL은 외래 키가 있는 행을 만들 때 참조하는 부모 행에 잠금을 걸고, 이 잠금은 `FOR UPDATE`와 충돌합니다. 그래서 발송기의 행이 먼저 커밋되면 삭제 연쇄가 그 행까지 삭제하고, 삭제 연쇄가 먼저 잠그면 발송기는 삭제가 커밋된 뒤 외래 키 오류를 받습니다. 어느 순서든 삭제한 매물을 가리키는 발송 기록은 남지 않습니다.

| 동작 | 먼저 잠그는 부모 행 | 잠그는 곳 |
|---|---|---|
| 매물 삭제 | 그 매물 | 매물과 기록 모듈의 관심 단지와 매물 |
| 가구 삭제 | 그 가구의 매물들 | 매물과 기록 모듈의 "그 가구의 매물 번호 받기" 공개 인터페이스. 잠근 뒤 매물 번호를 가구 모듈에 반환하고, 가구 모듈이 그 번호를 알림 모듈에 넘깁니다 |
| 탈퇴 | 사용자 행 | 사용자와 인증 모듈의 내 정보와 탈퇴 |

### 알림이 실패하면

| 단계 | 실패 | 멈추는 것 | 데이터베이스에 남는 것 | 파일 저장소에 남는 것 |
|---|---|---|---|---|
| 실행과 판정 1 | 권고 잠금을 획득하지 못함(앞 실행이 끝나지 않음) | 이 실행 전체 | 바뀌는 것 없음 | 바뀌는 것 없음 |
| 실행과 판정, 행 만들기 | 유니크 제약 위반 | 그 행만 | 이미 있는 행 하나 | 바뀌는 것 없음 |
| 실행과 판정, 행 만들기 | 외래 키 오류(삭제 연쇄와 겹침) | 그 행만 | 행이 없음 | 바뀌는 것 없음 |
| 실행과 판정, 판정 중 | 예외 | 이 실행의 남은 일. 같은 연결에서 잠금을 해제합니다 | 이미 커밋한 발송 기록 행. 남은 조합은 다음 실행이 다시 판정해 만듭니다 | 바뀌는 것 없음 |
| 실행과 판정 | 수신자의 `email_verified`가 거짓 | 그 사람만 | 행을 만들지 않습니다 | 바뀌는 것 없음 |
| 보내기와 재발송 | 보내기 전 다시 확인에서 판정 조건을 만족하지 않음(나간 구성원, 알림을 끔, 관심 단지에서 뺌, `email_verified`가 거짓으로 바뀜, 방문 일시가 바뀜, 알림을 껐다 켜거나 단지를 뺐다 다시 넣어 이력이 그 시각보다 앞섬) | 그 행만 | `sent_at`이 빈 행 | 바뀌는 것 없음 |
| 보내기와 재발송 | 메일 발송 서비스 오류 | 그 행만 | `sent_at`이 빈 행. 다음 실행이 기한 안에서 재발송합니다 | 바뀌는 것 없음 |
| 보내기와 재발송 | 메일을 보낸 뒤 `sent_at`을 기록하기 전에 프로세스가 멈춤 | 이 실행 전체 | `sent_at`이 빈 행. 다음 실행이 같은 메일을 한 번 더 보낼 수 있습니다([데이터 모델의 `notification_delivery`](data-model.md#notification_delivery)) | 바뀌는 것 없음 |
| 보내기와 재발송 | 메일을 보낸 뒤 삭제 연쇄가 그 행을 삭제함 | 없음 | 행이 없습니다. 메일은 이미 나갔고 `sent_at`을 기록할 행이 없습니다 | 바뀌는 것 없음 |

체크포인트를 따로 기록하지 않으므로, 발송기가 하루 멈춰도 다시 실행되면 멈춘 동안 생긴 이력의 발송 기록 행을 만듭니다. 다만 거래 알림 행을 만든 뒤 3일 안에 보내지 못하면 그 알림은 나가지 않습니다.

## 임장 기록과 사진

사진은 [결정 기록 0012](../adr/0012-personal-data-storage.md)와 같은 애플리케이션 계층 AES-GCM(키 버전, nonce, 암호문, 인증 태그)으로 암호화하고, 늘 API 서버를 거쳐 업로드하고 다운로드합니다. 저장소 주소를 화면에 노출하지 않습니다. 사진은 S3 호환 오브젝트 저장소의 사진 버킷에 두고, API 서버의 저장소 접근 키는 사진 버킷에만 권한이 있습니다([결정 기록 0014](../adr/0014-file-storage.md)). 권한 확인 단계는 [API 공통 규칙의 인증과 권한 확인](api-conventions.md#인증과-권한-확인)을 따릅니다.

### 기록 저장과 사진 업로드

```mermaid
sequenceDiagram
  autonumber
  participant web as 웹 화면
  participant api as 화면 API
  participant sec as 보안 필터와 로그인 처리
  participant authz as 권한 확인기
  participant visit as 임장 기록
  participant crypto as 개인정보 암호화
  participant photoAd as 사진 파일 저장소 어댑터
  participant s3 as S3 호환 오브젝트 저장소(사진 버킷)
  participant db as 데이터베이스(PostgreSQL)

  web->>api: 임장 기록 저장(사진 포함)
  api->>sec: 권한 확인 1단계, 세션 확인
  api->>authz: 권한 확인 2, 3단계, 멤버십과 visit_log:write
  api->>visit: 기록 저장
  visit->>db: 4단계, 세션 가구 번호를 조건에 넣어 매물 찾기
  visit->>db: 5단계, 수정하는 기록이면 작성자가 요청한 사람인지
  loop 사진마다
    visit->>visit: storage_key 만들기(가구 번호/임의 UUID)
    visit->>crypto: AES-GCM 암호화(storage_key를 추가 인증 데이터로)
    visit->>photoAd: 파일 저장
    photoAd->>s3: 사진 버킷에 저장
  end
  alt 파일 저장 실패
    visit-->>api: 오류, 행을 저장하지 않음
  else 모든 파일 저장
    visit->>db: visit_log, visit_log_check, visit_photo 저장, 커밋
    visit-->>api: 저장한 기록
  end
  api-->>web: 결과
```

- `visit_photo.storage_key`는 사진 버킷 안의 `{household_id}/{임의 UUID}`입니다. 오브젝트 키를 먼저 만들고, 그 키를 추가 인증 데이터(AAD)로 넣어 암호화한 뒤 저장합니다. 사진을 다운로드할 때 복호화에도 같은 키를 넣습니다. 저장소 접근 키를 탈취한 사람이 남의 암호문을 자기 오브젝트 키(`storage_key`) 위치로 옮겨도 복호화되지 않게 하려는 것입니다.
- 파일을 먼저 저장하고 `visit_photo` 행을 저장합니다. 행이 없는 파일은 아래 사진 파일 정리가 삭제할 수 있지만, 파일이 없는 행은 열리지 않는 사진으로 남기 때문입니다.
- GPS 등 메타데이터를 삭제할지와 어디서 삭제할지는 임장 기록 기능 명세에서 정합니다.

### 사진 보기

```mermaid
sequenceDiagram
  autonumber
  participant web as 웹 화면
  participant api as 화면 API
  participant sec as 보안 필터와 로그인 처리
  participant authz as 권한 확인기
  participant visit as 임장 기록
  participant photoAd as 사진 파일 저장소 어댑터
  participant s3 as S3 호환 오브젝트 저장소(사진 버킷)
  participant crypto as 개인정보 암호화
  participant db as 데이터베이스(PostgreSQL)

  web->>api: 사진 요청
  api->>sec: 권한 확인 1단계, 세션 확인
  api->>authz: 권한 확인 2, 3단계, 멤버십과 household:read
  api->>visit: 사진 읽기
  visit->>db: 4단계, 세션 가구 번호를 조건에 넣어 visit_photo와 storage_key 찾기
  alt 행이 없음(다른 가구의 사진 포함)
    visit-->>api: 없음
    api-->>web: 404
  else 행이 있음
    visit->>photoAd: 파일 읽기
    photoAd->>s3: 사진 버킷에서 읽기
    visit->>crypto: 복호화와 인증 태그 확인(storage_key를 추가 인증 데이터로)
    visit-->>api: 사진
    api-->>web: 사진(저장소 주소는 노출하지 않음)
  end
```

### 사진과 기록 삭제

```mermaid
sequenceDiagram
  autonumber
  participant web as 웹 화면
  participant api as 화면 API
  participant visit as 임장 기록
  participant db as 데이터베이스(PostgreSQL)
  participant photoAd as 사진 파일 저장소 어댑터
  participant s3 as S3 호환 오브젝트 저장소(사진 버킷)

  web->>api: 임장 기록 삭제, 또는 사진 삭제
  Note over api: 권한 확인 1~3단계, visit_log:write
  api->>visit: 삭제
  visit->>db: 4, 5단계, 내 가구의 기록이고 내가 작성한 기록인지
  visit->>db: visit_photo 행(기록 삭제면 visit_log_check, visit_log도) 삭제, 커밋
  alt 커밋 실패
    Note over visit: 행이 롤백되고 파일은 삭제하지 않음
  else 커밋
    visit->>photoAd: 삭제한 행의 storage_key 파일 삭제
    photoAd->>s3: 사진 버킷에서 삭제
    opt 파일 삭제 실패
      Note over visit: 파일이 남고 사진 파일 정리가 삭제함
    end
  end
```

### 매물 삭제

```mermaid
sequenceDiagram
  autonumber
  participant web as 웹 화면
  participant api as 화면 API
  participant listing as 관심 단지와 매물
  participant notiSet as 알림 설정
  participant db as 데이터베이스(PostgreSQL)
  participant visit as 임장 기록
  participant photoAd as 사진 파일 저장소 어댑터

  web->>api: 매물 삭제
  Note over api: 권한 확인 1~3단계, listing:write
  api->>listing: 매물 삭제
  listing->>db: 트랜잭션 시작, 4단계 조건을 넣어 매물 행 SELECT ... FOR UPDATE
  listing->>db: 요청한 사람이 작성하지 않은 임장 기록이 있는지(author_id가 빈 기록 포함)
  alt 있음
    listing->>db: 롤백
    listing-->>api: 거절
    api-->>web: 거절 문구
  else 없음
    listing->>notiSet: 그 매물의 방문 알림 발송 기록 삭제
    notiSet->>db: notification_delivery 삭제
    listing->>db: 호가 이력, 체크 결과, 사진 행, 임장 기록, 매물 삭제, 커밋
    listing->>visit: 삭제한 사진 행의 파일 삭제 요청(storage_key)
    visit->>photoAd: 파일 삭제
    listing-->>api: 삭제함
    api-->>web: 단지 상세로 이동
  end
```

- 그 매물에 요청한 사람이 작성하지 않은 임장 기록(작성자가 나가 `author_id`가 빈 기록 포함)이 있으면 매물 삭제를 거절하고 "다른 구성원의 기록이 있어 지울 수 없습니다"를 보입니다. 매물 상태를 바꾸라는 안내는 넣지 않습니다. 상태 코드와 오류 코드는 기능 명세에서 정합니다.
- 관심 단지와 매물은 사진 파일 저장소 어댑터를 직접 호출하지 않고, 커밋 뒤 임장 기록 컴포넌트에 삭제한 사진 행의 오브젝트 키를 넘겨 파일을 삭제하게 합니다. 사진 파일 삭제는 임장 기록이 맡습니다.
- 기록이 없거나 요청한 사람의 기록만 있는 매물은 삭제합니다. 남의 기록이 있는지는 매물 행을 잠근 뒤 확인하므로, 확인과 삭제 사이에 다른 구성원이 그 매물에 새 기록을 저장하지 못합니다.

### 사진 파일 정리

```mermaid
sequenceDiagram
  autonumber
  participant cleanup as 스케줄러(사진 파일 정리)
  participant visit as 임장 기록
  participant photoAd as 사진 파일 저장소 어댑터
  participant s3 as S3 호환 오브젝트 저장소(사진 버킷)
  participant db as 데이터베이스(PostgreSQL)

  cleanup->>visit: 정리 시작(하루 한 번)
  visit->>photoAd: 사진 버킷의 파일 목록
  photoAd->>s3: 사진 버킷만 목록 조회(키, 업로드 시각)
  visit->>db: 그 키의 visit_photo 행이 있는지
  loop 행이 없고 업로드한 지 하루가 지난 파일마다
    visit->>photoAd: 파일 삭제
    photoAd->>s3: 사진 버킷에서 삭제
  end
```

업로드한 지 하루가 지나지 않은 파일은 삭제하지 않습니다. 파일은 저장했지만 아직 행을 저장하지 않은 요청의 파일을 삭제하지 않기 위해서입니다.

### 임장 기록과 사진이 실패하면

| 흐름 | 실패 | 멈추는 것 | 데이터베이스에 남는 것 | 파일 저장소에 남는 것 |
|---|---|---|---|---|
| 기록 저장과 사진 업로드 | 권한 확인 1~5단계에 걸림 | 그 요청. 걸린 단계의 상태 코드로 응답합니다 | 바뀌는 것 없음 | 없음 |
| 기록 저장과 사진 업로드 | 파일 저장 실패 | 그 요청 | 바뀌는 것 없음 | 먼저 저장한 사진 파일. 사진 파일 정리가 삭제합니다 |
| 기록 저장과 사진 업로드 | 행 저장 실패 | 그 요청 | 롤백되어 바뀌는 것 없음 | 저장한 사진 파일 모두. 사진 파일 정리가 삭제합니다 |
| 사진 보기 | 다른 가구의 사진이거나 없는 사진 | 그 요청(404) | 바뀌는 것 없음 | 그대로 |
| 사진 보기 | 복호화나 인증 태그 확인 실패 | 그 요청 | 바뀌는 것 없음 | 그대로 |
| 사진과 기록 삭제, 매물 삭제 | 커밋 실패 | 그 요청 | 롤백되어 바뀌는 것 없음 | 그대로 |
| 사진과 기록 삭제, 매물 삭제 | 커밋 뒤 파일 삭제 실패 | 없음. 행 삭제는 커밋됐습니다 | 삭제한 행은 없음 | 행이 없는 파일. 사진 파일 정리가 삭제합니다 |
| 매물 삭제 | 요청한 사람이 작성하지 않은 기록이 있음 | 그 요청(거절) | 롤백되어 바뀌는 것 없음 | 그대로 |
| 사진 파일 정리 | 목록 조회나 파일 삭제 실패 | 그 회차 | 바뀌는 것 없음 | 삭제하지 못한 파일. 다음 날 다시 대조합니다 |

## 단지 등록에서 관심 단지와 매물 등록까지

단지 행은 collector만 만들고, 사용자는 그 단지 가운데서 관심 단지를 고르고 매물을 등록합니다. 각 요청은 [임장 기록과 사진](#기록-저장과-사진-업로드)처럼 권한 확인 1~4단계를 거치며, 그림에는 권한 키만 적었습니다. 실거래, 건축물대장, K-apt, 청약의 단지 키를 연결하는 흐름은 2단계에 고른 방법에 맞춰 추가합니다([결정 기록 0008](../adr/0008-complex-matching.md)).

### 단지 등록, 관심 단지 추가, 매물 등록

```mermaid
sequenceDiagram
  autonumber
  participant reg as 단지 등록
  participant web as 웹 화면
  participant api as 화면 API
  participant complexQ as 단지 조회
  participant listing as 관심 단지와 매물
  participant crypto as 개인정보 암호화
  participant db as 데이터베이스(PostgreSQL)

  Note over reg,db: collector, 범위 반영 트랜잭션 안
  reg->>db: aptSeq로 complex 찾기, 없으면 행 만들기(이름, 시군구 코드, 법정동, 지번)
  Note over web,db: API 서버
  web->>api: 단지 추가 시트 열기(complex:read, household:read)
  api->>complexQ: 관심 지역의 단지 목록
  complexQ->>db: complex 읽기
  api->>listing: 이 가구의 관심 단지와 예전 관심 단지의 남은 매물 수
  listing->>db: 세션 가구 번호를 조건에 넣어 읽기
  api-->>web: 관심 단지가 아닌 단지 목록(화면 API가 합침)
  web->>api: 단지 상세에서 임장 후보로 추가(interest_complex:write)
  api->>listing: 관심 단지 추가
  listing->>db: interest_complex 행 만들기(added_at 지금)
  web->>api: 매물 등록(listing:write)
  api->>listing: 매물 등록
  listing->>crypto: 주소, 동, 호 암호화
  listing->>db: listing(상태 검토 중)과 첫 asking_price 저장, 관심 단지가 아니면 interest_complex 행도, 커밋
  listing-->>api: 등록한 매물
  api-->>web: 결과
```

- `interest_complex.added_at`은 거래 알림 판정이 사용하는 시각입니다. 관심 단지로 둔 뒤에 수집한 거래 변경만 그 가구에 보냅니다. 뺐다가 다시 추가하면 새 시각으로 다시 만듭니다.
- 매물을 등록할 때 그 단지가 관심 단지가 아니면 같은 트랜잭션에서 `interest_complex` 행을 만듭니다.
- 매물 저장에 실패하면 웹 화면은 입력한 값을 그대로 두고 "저장하지 못했습니다. 다시 시도하세요."를 보여 줍니다([와이어프레임](../design/wireframes.md)).

### 지도를 그릴 때

매물 등록 화면과 매물 상세 화면이 지도를 그릴 때마다 API 서버가 브이월드 지오코더를 호출해 주소를 좌표로 변환하고, 좌표만 화면에 반환합니다. 좌표는 저장하지 않습니다([결정 기록 0015](../adr/0015-geocoder.md)).

```mermaid
sequenceDiagram
  autonumber
  participant web as 웹 화면
  participant api as 화면 API
  participant listing as 관심 단지와 매물
  participant crypto as 개인정보 암호화
  participant db as 데이터베이스(PostgreSQL)
  participant geo as 지오코더 어댑터
  participant vworld as 지도 API(브이월드)

  alt 매물 상세
    web->>api: 매물 위치(household:read)
    api->>listing: 매물 위치
    listing->>db: 세션 가구 번호를 조건에 넣어 매물 주소 읽기
    listing->>crypto: 주소 복호화
  else 매물 등록
    web->>api: 입력한 주소의 위치
    api->>listing: 주소의 위치
  end
  Note over listing: 지오코더를 호출하기 전에 사용자마다 하루 호출 상한 확인(값은 기능 명세)
  alt 상한을 넘음
    listing-->>api: 좌표 없음
    api-->>web: 좌표 없음
    Note over web: 지도 대신 문구, 저장은 막지 않음
  else 상한 안
  listing->>geo: 주소를 좌표로
  geo->>vworld: 지오코더 호출(주소 하나)
  Note over geo,vworld: 요청에 주소 평문이 포함되므로 요청 URL을 로그에 남기지 않음
  alt 위치를 찾음
    vworld-->>geo: 좌표
    geo-->>listing: 좌표
    listing-->>api: 좌표(저장하지 않음)
    api-->>web: 좌표
    web->>vworld: 좌표 주변 지도 그리기(웹 화면이 직접 호출)
  else 위치를 못 찾음
    vworld-->>geo: 결과 없음
    geo-->>listing: 결과 없음
    listing-->>api: 좌표 없음
    api-->>web: 좌표 없음
    Note over web: 지도 자리에 안내 문구, 저장은 막지 않음
  end
  end
```

- 변환할 주소는 화면이 보낸 주소(저장 전 입력)나 저장된 매물 주소입니다. 지오코더를 호출하기 전에 사용자마다 하루 호출 상한을 확인하고(값은 기능 명세), 넘으면 지도 대신 문구를 보입니다. 로그인 허용 목록이 없어 누구나 로그인할 수 있으므로, 한 사용자가 지오코더의 하루 한도를 다 사용하면 지오코더를 호출할 수 없어 다른 사용자도 지도에 위치를 표시하지 못하기 때문입니다.
- 지오코더 요청에는 주소 평문이 포함되므로, 평문을 로그에 남기지 않는 규칙([결정 기록 0012](../adr/0012-personal-data-storage.md))이 나가는 요청 URL에도 적용됩니다. 요청 URL을 로그에 남기지 않습니다.
- 주소로 위치를 못 찾으면 지도 자리에 "주소의 위치를 찾지 못했습니다. 주소를 확인하세요."를 보이고 매물 저장은 막지 않습니다. 지도에서 직접 위치를 찍는 기능은 없습니다.
- 좌표를 저장하지 않는 근거는 브이월드 지오코더 API 2.0 안내 페이지(https://www.vworld.kr/dev/v4dv_geocoderguide2_s001.do) 소개 문단의 "단, API 요청은 실시간으로 사용하셔야 하며 별도의 저장장치나 데이터베이스에 저장할 수 없습니다."입니다(2026-10-20 확인). 지오코더 하루 호출 한도는 40,000회입니다.
- 구현 전에 인증키로 서버에서 지오코더를 호출할 수 있는지 확인합니다. 안 되면 웹 화면이 JSONP로 호출하고 CSP 허용 목록에 지오코더 주소를 넣습니다([결정 기록 0015](../adr/0015-geocoder.md)). 이 대체 경로에서는 사용자별 상한과 요청 URL 로그 규칙을 서버가 지킬 수 없습니다.

### 단지와 매물 등록이 실패하면

| 흐름 | 실패 | 멈추는 것 | 데이터베이스에 남는 것 | 파일 저장소에 남는 것 |
|---|---|---|---|---|
| 단지 등록 | 범위 반영 트랜잭션 오류 | 그 범위의 반영 | 그 범위에서 만든 단지 행도 롤백됩니다. 다음 날 재수집할 때 다시 만듭니다 | 원본 응답 파일은 남습니다(범위 반영 전에 저장했습니다). 재수집하면 그 실행의 파일이 따로 새로 생깁니다 |
| 관심 단지 추가 | 권한 확인에 걸리거나 저장 실패 | 그 요청 | 바뀌는 것 없음 | 바뀌는 것 없음 |
| 매물 등록 | 저장 실패 | 그 요청 | 롤백되어 매물, 첫 호가, 관심 단지 행 모두 없음 | 바뀌는 것 없음 |
| 지도를 그릴 때 | 주소로 위치를 못 찾음 | 지도 표시만 | 바뀌는 것 없음. 좌표는 처음부터 저장하지 않습니다 | 바뀌는 것 없음 |

## 구현 때 고정할 테스트

### 변경 감지

한 가구가 단지 하나를 미리 관심 단지로 두었고, 구성원 한 명이 그 전에 가구에 들어와 거래 알림을 켜 둔 상태에서 시작합니다. 키 A, B는 같은 범위의 거래이고 건수는 괄호에 적었습니다. "오늘"은 그 범위를 수집하는 날짜입니다.

| 경우 | 어제 결과 | 오늘 결과 | 기대 이력 | 기대 발송 기록 |
|---|---|---|---|---|
| 신규 | 없음 | A(1) | A `신규` | (그 사람, A, 거래 신규) 한 행 |
| 해제 | A(1) | A(1), A의 해제 키 A'(1) | A' `해제` | (그 사람, A', 거래 해제) 한 행 |
| 등기 | A(1), 등기일 빔 | A(1), 등기일 있음 | A `등기` | 없음 |
| 사라짐 | A(1), B(1) | B(1) | A `사라짐`, A의 `same_key_count` 0 | 없음 |
| 사라졌다 다시 보인 키 | B(1), A는 전날 사라져 건수 0 | A(1), B(1) | A `신규` | 없음. 이력의 `observed_at`이 A의 `first_seen_at`과 다릅니다 |
| 건수가 늘어난 기존 키 | A(1), A는 앞선 수집에서 처음 봄 | A(2) | A `신규` | 없음. 이력의 `observed_at`이 A의 `first_seen_at`과 다릅니다 |
| 같은 날 기준 적재 뒤 같은 키 건수 증가 | 같은 날 앞 수집이 기준 적재로 A(1) 행을 만듦(이력 없음) | 같은 날 뒤이은 수집에서 A(2) | A `신규` | 없음. 기준 적재 행은 이력이 없어, 뒤이은 수집의 `observed_at`이 A의 `first_seen_at`과 다릅니다 |
| 빈 결과 | A(1) | 0건 | 없음. 범위를 `실패`로 두고 비교하지 않음 | 없음 |
| 건수 불일치 | A(1) | 수집한 건수가 `totalCount`와 다름 | 없음. 범위를 `실패`로 두고 비교하지 않음 | 없음 |
| 기준 적재 | 초기 적재나 범위 확장으로 수집하는 달, `fetched_at` 빔, `fetch_range` 행 없음 | A(1) | 없음. A 행만 만듦 | 없음 |
| 앞서 실패만 한 달(초기 적재 때 실패한 달, 새 달의 첫 매일 수집이 실패한 달) | `fetched_at` 빔, `fetch_range` 행 있음(`status` `실패`) | 매일 수집에서 수집한 A(1) | 없음. 기준 적재로 A 행만 만듦 | 없음 |
| 재수집 기간을 벗어난 실패한 범위 | `fetched_at` 있음, `status` `실패`, A(1) | A(1), B(1) | B `신규` | (그 사람, B, 거래 신규) 한 행 |
| 재수집 기간에 새로 들어온 달 | 빈 직전 결과, `fetched_at` 빔, `fetch_range` 행 없음, 매일 수집 | A(1) | A `신규` | (그 사람, A, 거래 신규) 한 행 |

### 알림 발송기

| 테스트 | 기대 결과 |
|---|---|
| 같은 fixture로 발송기를 두 번 실행 | 조합마다 발송 기록이 한 행이고 메일도 한 번 나갑니다. 두 번째 실행은 행을 만들지 않고 보내지 않습니다 |
| 같은 fixture로 거래 알림 판정과 거래 변경 수신 어댑터의 조건(`email_verified` 참 포함)을 비교. fixture에는 거래 알림을 끈 사람, 켠 시각이 이력보다 뒤인 사람, 이력 뒤에 들어온 구성원, 이력 뒤에 관심 단지로 둔 가구, 다시 보인 키, `등기`와 `사라짐` 이력, 이미 발송 기록이 있는 조합, `email_verified`가 거짓인 사람, 건수가 늘어난 기존 키, 수집된 지 3일이 지난 이력을 넣습니다 | 어댑터가 후보로 읽은 이력의 집합과, 판정이 행을 만들 수신자가 하나 이상인 이력의 집합이 같습니다 |
| 실행 중에 예외를 일으킨 뒤 다음 실행 | 다음 실행이 권고 잠금을 다시 획득하고 남은 조합의 행을 만듭니다 |
| 한 실행이 잠금을 획득한 동안 다른 실행을 시작 | 두 번째 실행은 잠금을 획득하지 못해 건너뜁니다 |
| 삭제 연쇄가 매물 행을 잠근 동안 그 매물의 방문 알림 행을 만듦 | 삭제가 커밋된 뒤 외래 키 오류로 그 행만 건너뛰고, 다른 행은 커밋됩니다 |
| `email_verified`가 거짓인 구성원이 있는 가구의 거래와 방문 알림 판정 | 그 사람의 발송 기록 행을 만들지 않습니다 |
| 행을 만든 뒤 보내지 못한 사이에 수신자가 가구를 나가거나 그 알림을 끔 | 다음 실행이 보내지 않고 `sent_at`이 빈 채 남습니다 |
| 행을 만든 뒤 보내지 못한 사이에 알림을 껐다 켜거나 관심 단지를 뺐다 다시 넣음 | 거래의 `first_seen_at`이 새 `trade_alert_on_at`이나 `added_at`보다 앞서므로 보내지 않고 `sent_at`이 빈 채 남습니다 |
| 거래 알림이 켜진 채로 방문 알림 시점만 바꿔 저장 | `trade_alert_on_at`이 바뀌지 않습니다 |
| 메일 발송 서비스가 실패를 반환함 | `sent_at`이 빈 채 남고 다음 실행이 재발송합니다. 거래 알림은 `created_at`에서 3일, 방문 알림은 `visit_at`이 지난 뒤에는 보내지 않습니다 |

## 이 문서에서 정한 것

| 정한 것 | 근거 |
|---|---|
| 수집한 건수가 `totalCount`와 다르거나 직전에 거래가 있던 범위가 0건이면 그 범위를 실패로 두고 비교하지 않음 | 덜 수집한 결과를 비교하면 수집하지 못한 거래가 `사라짐`으로 남고, 다음에 재수집하면 다시 보인 거래가 됩니다 |
| HTTP 오류와 네트워크 오류만 2초, 4초 쉬고 최대 3번 시도. 정상이 아닌 결과 코드는 다시 시도하지 않음. 인증키 오류와 코드 22는 실행을 멈춤 | 일시적인 오류만 다시 시도해 하루 한도를 아낍니다. 인증키 오류와 한도 초과는 남은 범위도 같은 결과입니다 |
| 실패한 범위는 다음 날 재수집 기간 밖이어도 함께 수집함 | 실패를 지역과 월 단위 범위로 격리하고 그 범위만 재수집합니다(PRD 비기능 요구사항의 수집 실패 격리) |
| 한 범위의 모든 페이지를 수집하면 원본을 먼저 저장하고 그다음 건수 확인과 비교. 반영은 트랜잭션 하나 | 일부만 반영된 범위가 남지 않아 재수집하면 처음부터 비교합니다. 건수가 다른 범위도 원본이 남아 원인을 조사할 수 있습니다 |
| 하루 호출 수 테이블을 두지 않고, 실행 안에서 호출마다 세어 한도에 도달하기 전에 멈추고 남은 범위를 실패로 기록 | 페이지 수는 첫 응답을 받아야 알아 미리 계산할 수 없습니다. 1단계에서 같은 날 두 번 실행하는 날(초기 적재나 범위 확장)에는 뒤 실행이 코드 22로 멈출 수 있지만 남은 범위가 실패로 남아 다음 날 이어서 수집합니다. 호출 수 합산은 3단계에 재검토합니다 |
| `fetched_at`은 마지막으로 성공한 시각. `fetched_at`이 있는 범위는 늘 비교하고, 빈 범위는 `fetch_range` 행이 없고 매일 수집인 달(새로 들어온 달)만 빈 직전 결과와 비교하며 나머지는 기준 적재 | 새로 들어온 달을 비교해야 그 달의 새 거래에 알림이 나갑니다. 앞서 실패만 한 달을 비교하면 그 달 거래가 모두 새 거래 알림으로 나갑니다. 대가로 새 달의 첫 매일 수집이 실패하면 그 사이 신고된 거래의 알림이 빠집니다 |
| 거래 알림은 그 거래 행을 만든 수집(`observed_at`이 `first_seen_at`과 같음)의 `신규`, `해제` 이력에만 | 사라졌다 다시 보인 거래와 건수가 늘어난 기존 키에 알림이 다시 나가지 않습니다 |
| 이력의 수집 시각이 거래 알림을 켠 시각(`trade_alert_on_at`)보다 뒤여야 하고, 그 시각은 꺼진 상태에서 켤 때만 적음. 첫 로그인 기본값은 `trade_alert_on_at` 행을 만든 시각, `visit_alert_on` 참, `visit_alert_lead_hours` 24 | 껐다 켤 때 끈 동안의 거래를 한꺼번에 보내지 않습니다. 켜진 채로 다른 설정을 저장할 때 시각을 덮으면 그 직전에 수집된 거래의 알림이 빠집니다 |
| 방문 알림은 켠 구성원 모두에게, 시점은 사람마다 3시간 전, 하루 전, 이틀 전 가운데 고름(기본 하루 전) | 방문 일정은 가구가 함께 보는 자료이고, 언제 알림을 받을지는 사람마다 다릅니다 |
| 알림 메일은 `user.login_email`로만 보내고, `email_verified`가 거짓인 사람은 판정에서 빼 행을 만들지 않음 | 주소를 따로 받지 않아 저장하는 개인정보와 민감 동작이 하나씩 줄고, 검증되지 않은 주소로 보내지 않습니다 |
| 보내기 전에 수신자와 대상이 지금도 판정 조건을 만족하는지 다시 확인 | 보내지 못한 사이에 가구를 나간 구성원이나 알림을 끈 사람에게 메일이 가지 않습니다 |
| 메일 본문에 매물 주소, 동, 호를 넣지 않음 | 메일은 서비스 밖의 메일함에 남습니다 |
| 발송기는 앞 실행이 끝나고 10분 뒤 실행하고, 실행 전체를 전용 연결의 권고 잠금으로 감쌈 | 두 실행이 겹치면 보내는 중인 행을 실패한 행으로 보고 재발송할 수 있습니다. 권고 잠금은 연결에 묶여 다른 연결에서는 해제되지 않습니다 |
| 거래 알림은 만든 지 3일, 방문 알림은 방문 일시가 지나면 재발송하지 않음 | 늦게 받은 거래 알림과 지난 방문 알림은 쓸모가 없습니다 |
| 발송 기록 행은 행마다 따로 커밋 | 한 행의 오류가 다른 수신자의 행을 롤백하지 않습니다 |
| 삭제 연쇄는 부모 행을 먼저 `SELECT ... FOR UPDATE`로 잠그고, 발송기는 외래 키 오류면 그 행만 건너뜀 | 외래 키 확인이 부모 행 잠금을 기다리므로, 어느 순서든 삭제한 대상을 가리키는 발송 기록이 남지 않습니다 |
| 요청한 사람이 작성하지 않은 임장 기록이 있으면 매물 삭제를 거절 | 다른 구성원의 기록을 그 사람이 모르게 삭제하지 않습니다 |
| 사진은 오브젝트 키를 추가 인증 데이터로 넣어 AES-GCM으로 암호화하고 늘 API 서버를 거침. 파일을 먼저 저장하고 행을 저장하며, 삭제할 때는 커밋 뒤 파일을 삭제함. 남은 파일은 하루 한 번 정리 | 저장소 주소를 화면에 노출하지 않고, 남의 암호문을 자기 오브젝트 키 위치로 옮겨도 복호화되지 않으며, 행이 없는 파일은 정리로 삭제할 수 있지만 파일이 없는 행은 열리지 않는 사진이 됩니다([결정 기록 0014](../adr/0014-file-storage.md)) |
| 지오코더 좌표를 저장하지 않고 지도를 그릴 때마다 API 서버가 호출하며, 호출하기 전에 사용자마다 하루 호출 상한을 확인하고 요청 URL을 로그에 남기지 않음 | 브이월드 안내 페이지가 결과 저장을 금지합니다([결정 기록 0015](../adr/0015-geocoder.md)). 허용 목록이 없어 한 사용자가 하루 한도를 다 사용하면 지오코더를 호출할 수 없어 다른 사용자도 지도에 위치를 표시하지 못하고, 요청에 주소 평문이 포함됩니다 |
| 거래 알림 조건을 판정과 거래 변경 수신 어댑터 두 곳에 두고, 같은 fixture로 두 결과가 같은지 테스트 | 3단계에 어댑터만 Kafka 컨슈머로 바꾸고 판정을 수정하지 않습니다([C4 컴포넌트](c4-component.md#collector에서-알림-발송기로-전달)) |

## 정하지 않은 것

| 항목 | 정하는 곳 |
|---|---|
| 메일 발송 서비스 | 009(거래 알림) plan |
| S3 호환 오브젝트 저장소의 업체 | 배포 환경을 정할 때([결정 기록 0014](../adr/0014-file-storage.md)) |
| 사진 형식, 크기 제한, GPS 등 메타데이터를 삭제할지와 어디서 삭제할지 | 임장 기록 기능 명세 |
| collector 중복 실행 잠금 | 3단계([결정 기록 0004](../adr/0004-staged-adoption.md)) |
| 재수집 기간 | [결정 기록 0009](../adr/0009-refetch-window.md)(정함) |
| 같은 날 여러 번 실행할 때 앞 실행의 호출 수를 아는 방법 | 3단계([결정 기록 0004](../adr/0004-staged-adoption.md)) |
| 초기 적재와 범위 확장을 시작하는 방법. 새 지역이나 범위 확장으로 늘어난 기간의 달을 매일 수집이 먼저 수집하지 않게 해야 합니다([결정 기록 0009](../adr/0009-refetch-window.md)) | 수집 기능 명세 |
| 지오코더 호출 오류와 하루 한도 초과 때 화면에 보일 것, 사용자별 하루 호출 상한의 값 | 매물 기능 명세 |
| 사용자별 하루 호출 상한을 세는 곳 | 1단계는 API 서버 메모리. 3단계에 API 서버가 두 대가 되면 다시 정함 |
| 2단계 단지 연결의 좌표 거리 방법에 사용할 좌표(지오코더 좌표는 저장할 수 없음) | 2단계 계획([결정 기록 0008](../adr/0008-complex-matching.md), [0015](../adr/0015-geocoder.md)) |
| 엔드포인트, 요청, 응답, 상태 코드와 오류 코드(사진을 기록과 한 요청으로 올릴지 포함) | 기능마다 `/speckit-plan`의 `contracts/` |
