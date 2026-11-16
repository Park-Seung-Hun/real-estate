# Quickstart: 001 로그인

001을 로컬에서 띄워 브라우저로 직접 확인하는 방법입니다. 자동 테스트 실행은 끝에 있습니다. 값(키, 비밀번호)은 이 문서에 적지 않습니다. 명령은 따로 적지 않으면 저장소 루트에서 실행합니다.

## 준비물

| 항목 | 기준 | 확인 명령 |
|---|---|---|
| JDK | 25 | `java -version` |
| Docker | 실행 중 | `docker version` |
| Node | 24 LTS 이상 | `node -v` |
| 브라우저 | Chrome(`http://localhost`에서 `__Host-` 쿠키를 받는 것을 확인함). Safari는 사용하지 않습니다 | |
| 구글 OAuth 클라이언트 | 웹 애플리케이션, 리디렉션 주소 `http://localhost:8080/login/oauth2/code/google`, 확인할 구글 계정이 테스트 사용자에 있음 | 구글 클라우드 콘솔 |

다른 계정으로 한 재인증을 확인하려면 테스트 사용자에 구글 계정이 둘 있어야 합니다.

## 처음 한 번

1. 저장소 루트의 `.env`에 아래 항목을 추가합니다. `.env`가 없으면 `.env.example`을 복사해 만듭니다. 이미 있는 항목(공공데이터 키 등)은 그대로 둡니다. `.env`는 저장소에 올라가지 않고(gitignore), 앱은 `spring.config.import`로 이 파일을 읽습니다.

   | 항목 | 넣을 값 |
   |---|---|
   | `GOOGLE_CLIENT_ID` | 구글 콘솔의 클라이언트 ID |
   | `GOOGLE_CLIENT_SECRET` | 구글 콘솔의 클라이언트 보안 비밀번호 |
   | `ESTATE_COLUMN_KEYS` | `1:` 다음에 아래 명령으로 만든 값 |
   | `ESTATE_COLUMN_KEY_ACTIVE` | `1` |
   | `ESTATE_LOGIN_COOKIE_KEY` | 아래 명령으로 한 번 더 만든 값(열 키와 다른 값) |

   ```bash
   openssl rand -base64 32
   ```

   데이터베이스 접속 값은 `application-local.yml`에 `compose.yaml`과 같은 기본값이 있어 넣지 않아도 됩니다. 키를 바꾸면 이미 저장한 로그인 이메일을 읽지 못하므로, 로컬에서 키를 바꿨다면 `docker compose down -v`로 데이터베이스를 삭제하고 다시 시작합니다.

2. 로컬 데이터베이스를 띄웁니다. 데이터는 Docker 볼륨에 남습니다.

   ```bash
   docker compose up -d postgres
   ```

## 앱 띄우기

웹 화면을 빌드해 서버와 같이 띄웁니다. 첫 실행은 npm 설치 때문에 몇 분 걸릴 수 있습니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Chrome에서 `http://localhost:8080`을 열고 개발자 도구의 기기 화면(Toggle device toolbar)을 활성화해 너비를 360px로 둡니다.

웹 화면을 수정하며 볼 때만 Vite 개발 서버를 사용합니다. 구글 콘솔에 리디렉션 주소 `http://localhost:5173/login/oauth2/code/google`을 추가로 등록한 뒤, 터미널 둘에서 각각 실행하고 `http://localhost:5173`을 엽니다.

```bash
./gradlew bootRun -PskipWeb --args='--spring.profiles.active=local,local-vite'
```

```bash
cd web && npm ci && npm run dev
```

## 화면에서 확인하기

각 단계의 기대 결과가 다르면 그 단계와 화면을 알려 주세요.

### 로그인과 로그아웃 (사용자 스토리 1)

| 순서 | 할 일 | 기대 결과 |
|---|---|---|
| 1 | `http://localhost:8080` 열기 | 서비스 이름 "서초댁 임장 기록", "Google 계정으로 로그인" 버튼, 초대 링크 안내 문구만 보입니다. 가로 스크롤이 없습니다 |
| 2 | `http://localhost:8080/withdraw` 열기 | 탈퇴 화면이 아니라 로그인 화면이 보입니다 |
| 3 | `http://localhost:8080/api/v1/me` 열기 | 401과 `UNAUTHENTICATED` 오류 본문이 보입니다 |
| 4 | 로그인 화면에서 "Google 계정으로 로그인" 누르기 | 구글 계정 선택 화면이 뜹니다. 구글에 이미 로그인해 있어도 뜹니다 |
| 5 | 계정을 고르고 로그인 마치기 | "아직 가구가 없습니다." 안내와 "로그아웃", "탈퇴" 버튼이 있는 가구 없음 화면이 열립니다 |
| 6 | 개발자 도구의 Application 탭에서 쿠키 보기 | `__Host-Http-session` 쿠키가 HttpOnly, Secure, SameSite=Strict, 만료 30일 뒤로 있습니다. `__Host-Http-login` 쿠키는 없습니다 |
| 7 | `http://localhost:8080/api/v1/me` 열기 | 내 표시 이름, 로그인 이메일, 이메일 검증 여부가 보입니다 |
| 8 | 가구 없음 화면에서 "로그아웃" 누르기 | 로그인 화면으로 돌아갑니다. 7번 주소를 다시 열면 401입니다 |
| 9 | 로그인 실패: 로그아웃 상태에서 `http://localhost:8080/login/oauth2/code/google?error=access_denied&state=x` 열기 | "로그인하지 못했습니다. 다시 시도해 주세요." 화면과 로그인 버튼이 보이고, 7번 주소는 401입니다. 쿠키에 `__Host-Http-session`이 없습니다 |
| 10 | 두 기기: 일반 창과 시크릿 창에서 각각 로그인한 뒤 시크릿 창에서 로그아웃 | 시크릿 창만 로그인 화면으로 가고, 일반 창은 계속 7번 주소가 보입니다 |
| 11 | 돌아갈 경로: 로그아웃 상태에서 `http://localhost:8080/withdraw`를 열고 로그인 | 로그인 뒤 탈퇴 확인 화면으로 갑니다 |
| 12 | 서비스 밖 경로: 로그아웃 상태에서 `http://localhost:8080/oauth2/authorization/google?returnTo=//example.com` 열고 로그인 | 로그인 뒤 `example.com`이 아니라 서비스의 `/`로 갑니다 |

### 재인증과 탈퇴 (사용자 스토리 2, 3)

| 순서 | 할 일 | 기대 결과 |
|---|---|---|
| 1 | 로그인한 직후(5분 안) 가구 없음 화면에서 "탈퇴" 누르기 | 삭제되는 것과 되돌릴 수 없다는 안내가 있는 탈퇴 확인 화면이 보입니다 |
| 2 | 탈퇴 확인 화면에서 "취소" 누르기 | 가구 없음 화면으로 돌아오고 `/api/v1/me`가 그대로 보입니다 |
| 3 | 로그인하고 5분 넘게 기다린 뒤 "탈퇴", 확인 화면에서 "탈퇴" 누르기 | "탈퇴하려면 구글 계정을 한 번 더 확인해야 합니다." 안내와 "구글 계정 확인" 버튼이 보입니다. 탈퇴는 처리되지 않았습니다 |
| 4 | "구글 계정 확인"을 누르고 같은 계정 고르기 | 구글 계정 선택 화면이 뜨고, 돌아오면 탈퇴 확인 화면입니다. 탈퇴는 아직 처리되지 않았습니다 |
| 5 | 다시 "탈퇴" 누르기 | 로그인 화면으로 가고 `/api/v1/me`는 401입니다 |
| 6 | 같은 계정으로 다시 로그인한 뒤 `/api/v1/me` 열기 | 처음 로그인한 계정처럼 구글 이름이 표시 이름으로 들어 있습니다 |
| 7 | 다른 계정 재인증: 5분이 지난 로그인 상태에서 3번처럼 "구글 계정 확인"을 누르고 다른 테스트 계정 고르기 | "다른 구글 계정을 골라 로그인이 해제되었습니다. 다시 로그인해 주세요." 화면이 보이고 `/api/v1/me`는 401입니다 |
| 8 | 두 기기 탈퇴: 일반 창과 시크릿 창에서 같은 계정으로 로그인한 뒤 한쪽에서 탈퇴 | 다른 창에서도 `/api/v1/me`가 401입니다 |

### 표시 이름 (사용자 스토리 4)

001에는 표시 이름 화면이 없습니다(002가 만듦). 로그인한 Chrome 탭의 개발자 도구 콘솔에서 요청을 보냅니다. 세션 쿠키와 Origin이 브라우저에서 자동으로 전송됩니다.

```js
const save = (name) => fetch('/api/v1/me', { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ displayName: name }) }).then(async r => [r.status, await r.json()])
```

| 순서 | 콘솔에 입력 | 기대 결과 |
|---|---|---|
| 1 | `await save('아빠')` | 200, `displayName`이 `아빠`입니다. `/api/v1/me`도 같습니다 |
| 2 | `await save('   ')` | 400 `VALIDATION_FAILED`, `pointer`가 `#/displayName`입니다 |
| 3 | `await save('가'.repeat(21))` | 400 `VALIDATION_FAILED`입니다 |
| 4 | `await save('가'.repeat(20))` | 200입니다 |

### 실제 구글에서만 확인할 것

자동 테스트는 가짜 구글 서버로 하므로 아래 둘은 실제 구글 계정으로만 확인할 수 있습니다. 결정 기록 0013의 재인증이 이 동작에 기댑니다.

- 구글에 계정 하나만 로그인된 브라우저에서도 로그인과 재인증 모두 계정 선택 화면이 뜨는가(로그인 4번, 재인증 4번).
- 다른 계정으로 한 재인증이 실패하고 로그인 상태가 삭제되는가(재인증 7번).

계정 선택 화면이 뜨지 않으면 결정 기록 0013을 재검토해야 하므로 바로 알려 주세요. 로그와 오류에 이메일과 토큰 원문이 없는지는 자동 테스트가 확인하고, 보완으로 위 확인을 마친 뒤 서버 출력에서 내 구글 이메일 주소를 검색해 봅니다.

## 휴대폰 실기기로 보기(선택)

같은 와이파이의 휴대폰에서 `http://192.168.x.x:8080`으로는 로그인할 수 없습니다. 브라우저가 사설 주소의 http에서 Secure 쿠키를 받지 않고, 구글이 사설 IP를 리디렉션 주소로 받지 않습니다. cloudflared 임시 터널로 HTTPS 주소를 받아 사용합니다. 터널 주소는 실행할 때마다 바뀝니다.

1. cloudflared를 설치하고 터널을 엽니다. 출력의 `https://<임의 이름>.trycloudflare.com` 주소를 적어 둡니다.

   ```bash
   brew install cloudflared
   ```

   ```bash
   cloudflared tunnel --url http://localhost:8080
   ```

2. 구글 콘솔의 리디렉션 주소에 `https://<임의 이름>.trycloudflare.com/login/oauth2/code/google`을 추가합니다.
3. 다른 터미널에서 터널 주소를 서비스 주소로 넘겨 앱을 띄웁니다. `tunnel` 프로필은 프록시가 보낸 HTTPS 정보(forwarded 헤더)를 믿고 허용 출처를 `ESTATE_PUBLIC_ORIGIN`으로 둡니다.

   ```bash
   ESTATE_PUBLIC_ORIGIN=https://<임의 이름>.trycloudflare.com ./gradlew bootRun --args='--spring.profiles.active=local,tunnel'
   ```

4. 휴대폰 브라우저에서 터널 주소를 열고 위 확인 순서를 따릅니다. 끝나면 터널을 닫고 구글 콘솔에서 그 리디렉션 주소를 삭제합니다.

## 자동 테스트

서버 테스트입니다. Docker로 PostgreSQL 18 컨테이너를 띄우고, 가짜 구글 서버는 테스트 안에서 뜹니다. 7일, 30일, 5분 만료도 여기서 확인합니다.

```bash
./gradlew test
```

웹 화면 테스트입니다. 첫 실행 전에 의존성과 Playwright 브라우저를 설치합니다.

```bash
cd web && npm ci && npx playwright install chromium
```

```bash
cd web && npm test
```

Playwright 테스트는 `compose.yaml`의 PostgreSQL과 가짜 구글 서버 컨테이너, `e2e` 프로필의 앱 jar를 띄우고(테스트용 키는 실행 스크립트가 만듦) 휴대폰 폭(360px)에서 로그인부터 로그아웃까지 한 바퀴를 돕니다. 앱 jar를 먼저 빌드합니다.

```bash
./gradlew bootJar
```

```bash
cd web && npm run e2e
```
