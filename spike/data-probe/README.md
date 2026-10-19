# 실거래 데이터 확인 스크립트

공공데이터포털의 아파트 매매 실거래가 API를 받아 날짜별 스냅샷으로 저장하고, 한 스냅샷을 표로 집계하는 확인용(spike) 코드다. 제품 코드가 아니고, 설계를 정하기 전에 실제 데이터를 보려고 만들었다. 14일 동안 매일 받은 결과를 비교해 매일 다시 받을 기간과 거래를 알아보는 키를 정하는 데 쓴다.

Python 3.9 이상과 표준 라이브러리만 쓴다. macOS 기본 `/usr/bin/python3`로 돈다.

## 준비

저장소 루트의 `.env.example`을 복사해 `.env`를 만들고 값을 채운다. `.env`는 `.gitignore`로 제외된다.

```
DATA_GO_KR_SERVICE_KEY=your-decoding-key
LAWD_CD=12345
```

- `DATA_GO_KR_SERVICE_KEY`는 Encoding 키가 아니라 Decoding 키다. 스크립트가 URL 인코딩을 한다.
- `LAWD_CD`는 시군구 법정동 코드 5자리다. 위 값은 가짜 예시다.
- 스크립트는 기본으로 `~/Desktop/real-estate/.env`를 읽는다. 다른 파일은 `--env`로 준다.

## 실행

| 목적 | 명령 | 산출물 |
|---|---|---|
| 첫 적재 | `python3 spike/data-probe/probe.py fetch --months 60` | `<out>/<오늘 날짜>/<YYYYMM>.xml`, `fetch.json` |
| 매일 수집 | `python3 spike/data-probe/probe.py fetch --months 6` | 같은 모양 |
| 집계 | `python3 spike/data-probe/probe.py analyze` | `<스냅샷 폴더>/summary.md` |

- `--out`의 기본값은 `~/Desktop/real-estate/.private/data/snapshots`다.
- 대상 계약년월은 `--date`(기본값은 오늘)가 속한 달부터 거슬러 `--months`개월이다.
- 한 달 응답이 1,000행을 넘으면 `<YYYYMM>-p1.xml`, `<YYYYMM>-p2.xml`처럼 페이지마다 파일 하나로 저장한다.
- 같은 날 다시 실행하면 같은 폴더의 같은 달 파일을 덮어쓴다.
- `analyze`는 폴더를 생략하면 `--out` 아래 가장 최근 날짜 폴더를 쓴다. 월별 건수 표에 보일 개월 수는 `--months`(기본값 12)로 정한다.
- `summary.md`에는 월별 건수와 해제 건수, 항목별 빈 비율, 식별 키 겹침, 해제와 등기 건수 표 네 개를 쓴다. 지역 코드와 단지 이름은 쓰지 않는다.

## 호출 수

한 달에 페이지 수만큼 호출한다. `numOfRows=1000`이므로 보통 한 달에 1회다.

| 작업 | 호출 수 |
|---|---|
| 첫 적재 60개월 | 약 60회 |
| 매일 수집 6개월 | 약 6회 |
| 개발계정 하루 한도 | 10,000회 |

재시도한 요청도 호출 수에 들어간다. 총 호출 수는 `fetch.json`의 `total_calls`에 남는다.

## 예약 실행

launchd 사용자 에이전트로 매일 09:00에 `fetch --months 6`을 돌린다.

```
spike/data-probe/launchd/install.sh [저장소 루트]
spike/data-probe/launchd/uninstall.sh
```

- `install.sh`는 템플릿에서 `~/Library/LaunchAgents/com.real-estate.data-probe.plist`를 만들고 `launchctl bootstrap`으로 올린다. 이미 올라가 있으면 먼저 내린다. 저장소 루트의 기본값은 `~/Desktop/real-estate`(워크트리가 아니라 원래 체크아웃)다.
- `uninstall.sh`는 에이전트를 내리고 plist를 지운다.
- 로그는 `<저장소 루트>/.private/data/logs/data-probe.out.log`와 `data-probe.err.log`에 쌓인다.

확인과 즉시 실행 명령은 아래와 같다.

```
launchctl print gui/$(id -u)/com.real-estate.data-probe | head
launchctl kickstart -k gui/$(id -u)/com.real-estate.data-probe
```

09:00에 PC가 잠자기 상태였다면 launchd가 깨어난 뒤에 한 번 실행한다. 완전히 꺼져 있던 날은 실행되지 않으므로 그날 스냅샷은 비게 된다.

### macOS 폴더 접근 권한

launchd가 띄운 `python3`는 터미널과 달리 데스크톱, 문서, 다운로드 폴더를 읽지 못한다. 저장소가 그 아래에 있으면 `data-probe.err.log`에 `can't open file ... Operation not permitted`가 남고 스냅샷이 생기지 않는다. 한 번만 아래처럼 권한을 준다.

1. 시스템 설정에서 개인정보 보호 및 보안, 전체 디스크 접근 권한을 연다.
2. `install.sh`가 출력한 "실행 파일" 경로가 속한 `Python.app`을 목록에 더하고 켠다(예: `/Library/Developer/CommandLineTools/Library/Frameworks/Python3.framework/Versions/3.9/Resources/Python.app`. 버전 폴더 이름은 설치된 Python에 따라 다르다). Finder에서 Command+Shift+G로 그 경로를 입력하면 고를 수 있다. `/usr/bin/python3`는 껍데기라서 그 경로나 심링크에 권한을 줘도 효과가 없고, launchd는 처음 띄운 실행 파일을 기준으로 권한을 보기 때문에 `install.sh`는 껍데기가 아니라 실제 실행 파일을 plist에 등록한다.
3. `launchctl kickstart -k gui/$(id -u)/com.real-estate.data-probe`로 즉시 실행해 `data-probe.out.log`에 달별 건수가 찍히는지 본다.

## 실패 처리

- 한 달이 실패해도 나머지 달은 계속 받는다.
- HTTP 오류와 네트워크 오류는 같은 요청을 최대 3번 시도한다. 첫 실패 뒤 2초, 두 번째 실패 뒤 4초를 쉰다.
- `resultCode`가 `000`이 아닌 응답은 다시 시도하지 않고 그 달을 실패로 처리한다. 코드 `22`는 일일 호출 한도 초과다.
- 요청 사이에 0.2초를 쉰다.
- 종료 코드는 모두 성공하면 0, 실패한 달이 하나라도 있으면 1, `.env`를 읽지 못하거나 키가 빠졌으면 2다.
- 달마다 결과(`resultCode`, HTTP 상태, 페이지 수, 받은 건수, `totalCount`, 소요 시간)는 `fetch.json`의 `results`에, 실패한 달과 오류 내용은 `failed`에 남는다. `analyze`는 `failed`를 읽어 summary 첫머리에 실패한 달을 적는다.

## 공개 저장소 주의

이 저장소는 공개로 전환할 예정이다. 실제 지역 코드와 인증키는 `.env`에만 둔다. `spike/`와 `.env.example`에는 가짜 값만 쓴다. 스냅샷은 `.private/` 아래에 두므로 저장소에 올라가지 않는다.
