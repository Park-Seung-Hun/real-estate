# 외부 API 계약: 아파트 매매 실거래가 상세

003은 엔드포인트를 내보내지 않고 이 외부 API를 소비합니다. 이 문서는 collector가 기대하는 요청과 응답이고, 응답 해석 테스트(가짜 XML)의 기준입니다. 근거와 확인 날짜는 [research R1](../research.md#r1-외부-api-응답-형식)에 있습니다. 예시 값은 모두 가짜입니다.

## 요청

`GET https://apis.data.go.kr/1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev`

| 인자 | 값 | 비고 |
|---|---|---|
| `serviceKey` | 환경 변수의 Decoding 키를 엄격 인코딩한 값(`+`, `/`, `=` 포함, 한 번만) | 로그와 예외 메시지에 남기지 않습니다(spec FR-022) |
| `LAWD_CD` | 시군구 코드 다섯 자리 | |
| `DEAL_YMD` | 계약년월 `YYYYMM` | |
| `pageNo` | 1부터 | |
| `numOfRows` | 1000 | 더 큰 값은 확인하지 않았습니다 |

## 정상 응답

HTTP 200, `Content-Type`은 확인하지 않았고 본문은 UTF-8 XML입니다.

```xml
<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<response>
  <header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header>
  <body>
    <items>
      <item>... 항목 32개 ...</item>
    </items>
    <numOfRows>1000</numOfRows><pageNo>1</pageNo><totalCount>1</totalCount>
  </body>
</response>
```

- 정상 결과 코드는 `000`입니다.
- 거래가 없는 달은 `<items/>`와 `totalCount` 0인 정상 응답입니다.
- 마지막 페이지는 `ceil(totalCount / numOfRows)`입니다. 수집한 건수는 모든 페이지의 `item` 수 합이고 `totalCount`와 비교합니다. 페이지마다 `totalCount`가 다르면 건수 확인 실패입니다(spec FR-011).
- 응답의 `pageNo`가 요청한 값과 다르면 그 범위를 `PARSE_ERROR`로 기록합니다.
- 행 순서는 정해져 있지 않고 날마다 바뀔 수 있습니다. 순서나 위치로 거래를 구분하지 않습니다.

## 항목

모든 `item`에 32개 요소가 늘 있고, 값이 없으면 공백 한 칸(`" "`)입니다. collector는 값의 앞뒤 공백을 뺀 뒤 빈 문자열이면 값이 없는 것으로 봅니다. 아래는 collector가 읽는 항목만 적었습니다.

| 요소 | 형식(가짜 예시) | collector가 쓰는 곳 |
|---|---|---|
| `aptSeq` | `99999-123` | 단지 번호, 식별 키 |
| `aptNm` | `가상단지` | 단지 이름 |
| `sggCd` | `99999` | 단지의 시군구 코드 |
| `umdNm` | `가상동` | 단지의 법정동 이름 |
| `jibun` | `123`, `123-4` | 단지의 지번 |
| `dealYear`, `dealMonth`, `dealDay` | `2026`, `9`, `15`(앞 0 없음) | 계약일(정수로 읽음), 식별 키 |
| `floor` | `7`, `12` | 층(정수), 식별 키. 음수는 관측되지 않았지만 정수로 받습니다 |
| `excluUseAr` | `84.97`, `59.9934`, `114` | 전용면적(소수 자릿수가 일정하지 않음), 식별 키 |
| `dealAmount` | `98,500` | 거래 금액(쉼표를 뺀 만원), 식별 키 |
| `cdealType` | `O` 또는 빈 값 | 해제 여부(비어 있지 않으면 해제), 식별 키 |
| `cdealDay` | `26.10.03`(`YY.MM.DD`) 또는 빈 값 | 해제 사유 발생일(20YY로 읽음) |
| `rgstDate` | `26.10.03`(`YY.MM.DD`) 또는 빈 값 | 등기일(20YY로 읽음) |
| `aptDong` | `101`, `101동`, 빈 값 등 형식이 정해져 있지 않음 | 동(앞뒤 공백을 뺀 문자열 그대로) |

필수 항목(`aptSeq`, `aptNm`, `sggCd`, `umdNm`, 계약일 셋, `floor`, `excluUseAr`, `dealAmount`)이 비었거나 형식이 맞지 않거나, `sggCd`가 요청한 `LAWD_CD`와 다르거나 계약년월이 `DEAL_YMD`와 다르거나 응답의 `pageNo`가 요청과 다르면 그 범위를 실패(`RESULT_CODE`가 아니라 `PARSE_ERROR`)로 기록하고 비교하지 않습니다([research R7](../research.md#r7-범위-결과-기록과-트랜잭션)).

## 오류 응답

데이터 확인 기간(23일, 191회 응답)에 오류 응답은 한 번도 관측되지 않았습니다. 오류 코드표는 첨부 기술문서와 2026-11-19에 대조했고, 응답 모양은 공공데이터포털 공통 형식에 대한 가정입니다. 구현은 두 모양을 모두 처리합니다.

| 모양 | 판별 | collector의 처리 |
|---|---|---|
| `response/header/resultCode`가 `000`이 아님 | 결과 코드 값 | 인증키 오류 코드와 `22`는 실행을 멈춤, 그 밖은 그 범위만 실패 |
| 루트가 `OpenAPI_ServiceResponse`(`cmmMsgHeader/returnReasonCode`) | 이유 코드 값 | 위와 같음 |
| 어느 쪽도 아님, XML이 아님 | | 그 범위만 실패(`PARSE_ERROR`), 다시 시도하지 않음 |

- 코드표(첨부 기술문서)의 코드는 두 자리(`22`)이고 정상 코드는 세 자리(`000`)입니다. 코드는 숫자로 바꿔 비교해 `22`와 `022`를 같은 코드로 봅니다. 숫자가 아닌 코드는 그 범위만 실패입니다.
- 실행을 멈추는 코드는 `20`, `22`, `30`, `31`, `32`입니다. 그 밖의 코드(`01`~`05`, `10`~`12` 포함)는 그 범위만 실패입니다. 이유는 [research R1](../research.md#r1-외부-api-응답-형식)에 있습니다.
- 기술문서에는 오류 때의 HTTP 상태에 대한 설명이 없습니다. HTTP 상태가 200이 아니면 본문을 해석하지 않고 HTTP 오류로 봅니다.

실제 오류 응답을 처음 받으면 그 원본 파일로 이 표를 확인하고, 다르면 이 문서와 research를 수정합니다.
