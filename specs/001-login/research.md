# Research: 001 로그인

이 문서는 001 plan이 정한 기술 판단과 그 근거를 적습니다. 설계 문서가 이미 정한 것(결정 기록 0010, 0012, 0013, 0016, 0017, API 공통 규칙, 데이터 모델)은 다시 정하지 않고, 설계 문서가 구현으로 넘긴 것만 다룹니다. 버전은 2026-11-16에 npm registry와 Maven Central에서, Spring 동작은 Spring Security 7.1.2, Spring Session 4.1.1, Spring Boot 4.1.2 소스에서 확인했습니다.

## R1. 의존성과 웹 화면 빌드 연결

- Decision:
  - 서버 의존성(Boot 4.1.2 BOM): `spring-boot-starter-webmvc`, `spring-boot-starter-security-oauth2-client`, `spring-boot-starter-session-jdbc`. 입력 검증은 표시 이름 하나라 Bean Validation 스타터를 넣지 않습니다. 알 수 없는 JSON 필드는 `spring.jackson.deserialization.fail-on-unknown-properties: true`로 400이 되게 합니다. 테스트는 `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`, `no.nav.security:mock-oauth2-server:7.0.0`. 앱 골격의 JPA, Flyway, PostgreSQL, Testcontainers는 그대로입니다. Spring Session 4.1.1, Spring Security 7.1.2는 BOM이 정합니다.
  - 웹 화면(`web/`): React 19.3.1, React DOM 19.3.1, Vite 8.3.8, `@vitejs/plugin-react` 6.3.0, TypeScript 7.0.2, React Router 8.5.1(라이브러리 방식, 서버 렌더링 없음), Vitest 5.1.0, Testing Library 16.3.3, Playwright 1.65.1. Node는 24 LTS 이상(React Router 8이 Node 22.22 이상을 요구)입니다.
  - Gradle은 `Exec` 태스크로 `npm ci`와 `npm run build`를 호출하고, `processResources`가 `web/dist`를 jar의 `static/`으로 복사합니다. 입력(`web/src`, `web/index.html`, `web/package-lock.json`, 설정 파일)과 출력(`web/dist`)을 선언해 바뀌지 않으면 다시 빌드하지 않습니다. `-PskipWeb`을 주면 npm 태스크를 건너뜁니다.
  - `.gitignore`에 `web/dist/`, `web/test-results/`, `web/playwright-report/`를 추가합니다(`node_modules/`는 이미 있음).
- Rationale: `com.github.node-gradle.node` 플러그인의 마지막 릴리스(7.1.0, 2024-09)는 Gradle 9 호환을 밝히지 않았고 Gradle 9 이슈(#350, #351)가 열려 있습니다. 명령 두 개에 플러그인을 들이지 않습니다(constitution VI).
- Alternatives considered: node-gradle 플러그인(위 이유), 빌드 분리와 배포 때 복사(jar 하나로 같은 주소에서 서비스한다는 결정 기록 0017과 맞지 않음).

## R2. 화면 경로를 `index.html`로 돌리는 방식

- Decision: `WebMvcConfigurer.addResourceHandlers`에서 `/**`에 `PathResourceResolver`를 상속한 해석기를 둡니다. 정적 파일이 있으면 그 파일을, 없으면 `index.html`을 반환합니다. 경로가 `api/`, `oauth2/`, `login/oauth2/`로 시작하면 `null`을 반환해 `index.html`로 새지 않게 합니다. 통합 테스트로 `/api/v1/없는경로`가 404 Problem Details이고 `/no-household`가 `index.html`인지 확인합니다.
- 화면 경로와 정적 파일은 로그인 없이 받습니다. `index.html`과 번들에는 개인 자료가 없고(결정 기록 0013), 자료는 모두 `/api/**`로 받습니다. spec FR-002와 US1 시나리오 1은 이 구조에 맞춰 "화면 주소를 열면 로그인 화면이 보이고, 자료 요청은 401"로 수정했습니다.
- Rationale: Spring Boot 4 참조 문서에는 SPA 대체 기능이 없습니다. 해석기 방식은 정적 파일 처리 안에서 끝나 컨트롤러 경로와 충돌하지 않습니다.
- Alternatives considered: `addViewControllers`의 경로 패턴 forward(Framework 7 경로 매칭 변경에 걸릴 수 있음), 404 오류 페이지를 `index.html`로(API 404까지 덮음).

## R3. 세션 ID 해시 저장과 세션 쿠키

- Decision: `spring.session.jdbc.initialize-schema=never`로 두고 스키마는 Flyway가 만듭니다. 결정 기록 0013대로 두 곳만 바꿉니다. 둘 다 Spring Session이 빈으로 받는 지점입니다(`JdbcHttpSessionConfiguration.setSessionIdGenerator`, `SpringHttpSessionConfiguration`의 `HttpSessionIdResolver`).
  - 세션 ID 생성기(`SessionIdGenerator` 빈)는 32바이트 무작위 값을 패딩 없는 base64url(43자)로 만든 원문 토큰의 SHA-256 소문자 16진수(64자)를 반환하고, 원문을 현재 요청의 속성에 둡니다(`RequestContextHolder`). 요청 밖에서 호출되면 예외입니다.
  - 쿠키 해석기(`HttpSessionIdResolver` 빈)는 요청의 `__Host-Http-session` 쿠키 값을 해시해 세션 ID로 반환하고, 쿠키를 기록할 때는 현재 요청 속성의 원문을 사용합니다. 원문이 없으면 쿠키를 기록하지 않습니다. 쿠키를 삭제할 때는 빈 값과 `Max-Age=0`을 사용합니다.
  - 쿠키 속성: 이름 `__Host-Http-session`, `HttpOnly`, `Secure`(프록시 뒤에서도 활성화되게 명시), `SameSite=Strict`, `Path=/`, Domain 없음, `Max-Age`는 30일입니다. 쿠키는 로그인과 재인증 때만 기록하므로 `Max-Age` 30일이 곧 절대 만료와 같고, 유휴 7일은 서버가 판단합니다. 브라우저를 닫아도 로그인이 남게 하려는 것입니다.
  - 해시를 다루는 곳은 이 둘뿐입니다. 로그인 시작이 옛 세션 참조를 얻을 때도 쿠키를 직접 해시하지 않고 `request.getSession(false).getId()`(해석기가 이미 해시한 값)를 사용합니다. 이때 다른 요청처럼 세션의 마지막 접근 시각이 갱신됩니다. 세션 ID와 내용은 바뀌지 않습니다.
- Rationale: 저장소 전체를 감싸면 `getId`, `changeSessionId`, `flush`까지 바꿔야 하고 해시가 쿠키로 나갈 수 있습니다. SQL 안에서 해시하면 원문이 쿼리 인자로 데이터베이스까지 갑니다.
- Alternatives considered: 저장소 래퍼, SQL 함수로 해시(위 이유), 세션 쿠키(Max-Age 없음, 브라우저를 닫으면 날마다 다시 로그인해야 해 제외).

## R4. 콜백에서 로그인 정보와 세션 만들기

Spring Security의 `AbstractAuthenticationProcessingFilter`는 성공 처리기를 호출하기 전에 인증 결과를 보안 컨텍스트 저장소(기본은 세션)에 저장하고, Spring Session은 요청이 끝날 때 세션을 저장합니다. 그대로 두면 기본 `DefaultOidcUser`(ID 토큰, 이메일)를 담은 세션이 생기고, 재인증 실패나 롤백 때도 세션이 남습니다. 그래서 콜백은 아래처럼 구성합니다.

- Decision:
  - 콜백 필터(`OAuth2LoginAuthenticationFilter`)의 보안 컨텍스트 저장소를 아무것도 저장하지 않는 저장소로, 세션 인증 전략을 아무것도 하지 않는 전략으로 둡니다. `oauth2Login().securityContextRepository(...)`로 지정하면 설정 마지막 단계에서 전역 저장소로 덮어써지므로(Spring Security 7.1.2 `AbstractAuthenticationFilterConfigurer.configure`, 세션 인증 전략도 같음), 덮어쓰기 뒤에 도는 `withObjectPostProcessor`로 콜백 필터에만 적용합니다. 다른 요청이 세션에서 로그인 정보를 읽는 전역 저장소는 그대로 둡니다. 콜백 요청은 Strict 세션 쿠키가 없으므로 이 요청에서 세션이 만들어지는 길은 성공 처리기뿐입니다.
  - 인가된 클라이언트 저장소(`OAuth2AuthorizedClientRepository`)는 아무것도 저장하지 않는 구현으로 바꿉니다. 기본 구현은 액세스 토큰을 메모리에 둡니다(결정 기록 0012 "구글 토큰은 저장하지 않는다").
  - 성공 처리기가 한 트랜잭션(`TransactionTemplate`, 아래 R5)에서 사용자 처리와 세션 처리를 하고, 커밋한 뒤에만 쿠키 해석기로 새 세션 쿠키를 씁니다. 세션은 `JdbcIndexedSessionRepository.createSession()`으로 만들고, 사용자 번호와 마지막 구글 인증 시각(서버가 콜백을 처리한 시각)만 담은 로그인 정보를 보안 컨텍스트 속성에 넣어 `save`합니다. 로그인 정보의 이름은 사용자 번호 문자열이라 `principal_name`에 그 값이 들어갑니다. 권한 목록은 비웁니다.
  - 실패하거나 롤백하면 쿠키를 쓰지 않고, 현재 스레드의 보안 컨텍스트를 비운 뒤 실패 화면으로 보냅니다.
  - Spring Session 저장소의 트랜잭션은 기본이 `REQUIRES_NEW`이므로 `springSessionTransactionOperations` 빈으로 `PROPAGATION_REQUIRED` 트랜잭션 연산을 줘 바깥 트랜잭션에 참여하게 합니다.
- Rationale: 결정 기록 0013(최소 로그인 정보, 새 세션 저장과 옛 세션 삭제를 한 트랜잭션, 실패하면 새 세션 없음)과 0012입니다.
- Alternatives considered: 인증 결과 변환기로 최소 로그인 정보만 바꾸기(컨텍스트 자동 저장과 요청 끝 저장이 남아 한 트랜잭션을 보장하지 못해 제외).
- 테스트: 콜백 응답이 끝난 뒤를 기준으로 본 롤백 테스트(옛 세션 삭제를 실패시키면 새 세션 행도 없고 쿠키도 없음), 계정 불일치 재인증 뒤 새 세션 행과 쿠키 없음, 세션 속성 직렬화 결과(`attribute_bytes`)에 이메일, 토큰, `sub`가 없음, 콜백 한 번에 세션 행이 정확히 하나 생김.

## R5. 콜백 처리 순서와 잠금

- Decision: 성공 처리기의 트랜잭션 안 순서입니다.
  - 일반 로그인:
    1. `(provider, provider_subject)`의 사용자 행을 `SELECT ... FOR UPDATE`로 잠급니다. 없으면 시퀀스에서 번호를 먼저 받아 이메일을 암호화해 넣습니다. 처음 로그인이 동시에 둘이면 유니크 제약 위반이 나고 PostgreSQL은 그 트랜잭션을 중단하므로, 세션 저장을 포함한 트랜잭션 전체를 새 트랜잭션으로 한 번 다시 실행합니다.
    2. 있으면 이메일과 검증 여부가 다를 때 수정합니다. 표시 이름은 바꾸지 않습니다.
    3. 새 세션을 저장하고, 임시 값 쿠키에 옛 세션 참조가 있으면 그 행을 삭제합니다(삭제한 행이 없어도 성공).
  - 재인증:
    1. 옛 세션 참조의 행을 읽고 그 `principal_name`(사용자 번호)의 사용자 행을 `SELECT ... FOR UPDATE`로 잠급니다.
    2. ID 토큰의 `sub`가 그 사용자의 `provider_subject`와 다르면 이 트랜잭션을 끝내고(아무것도 바꾸지 않음), 다른 트랜잭션에서 옛 세션 행을 삭제한 뒤 `/reauth-failed?reason=account-mismatch`로 보냅니다. 다른 계정의 사용자 행은 만들지도 수정하지도 않습니다.
    3. 옛 세션이 없거나, 유휴 만료(`expiry_time`이 지남)이거나, 절대 만료(`creation_time`부터 30일)면 `/reauth-failed`로 보냅니다.
    4. 같으면 이메일과 검증 여부를 수정하고, 새 세션을 저장하고, 옛 세션 행을 삭제합니다. 삭제한 행이 정확히 하나가 아니면 롤백하고 `/reauth-failed`로 보냅니다.
  - 탈퇴(R9)도 사용자 행을 먼저 `FOR UPDATE`로 잠급니다. 그래서 같은 사용자의 콜백과 탈퇴는 한 줄로 섭니다. 탈퇴가 먼저 끝나면 일반 로그인 콜백은 사용자 행을 찾지 못해 새 사용자로 만들고(탈퇴 뒤 다시 로그인한 것과 같음), 재인증 콜백은 옛 세션이 없어 실패합니다. 콜백이 먼저 끝나면 탈퇴가 그 새 세션까지 삭제합니다.
- Rationale: 결정 기록 0013의 재인증 조건 순서(`sub` 먼저), [C4 컴포넌트의 삭제 연쇄](../../docs/architecture/c4-component.md#삭제-연쇄)(시작 모듈이 사용자 행을 먼저 잠금)입니다.
- 테스트: 다른 계정 재인증 뒤 다른 계정의 사용자 행이 없음, 옛 세션의 유휴 만료와 절대 만료 각각에서 재인증 실패, 탈퇴와 콜백을 동시에 실행했을 때 남는 세션이 없거나 새 사용자 행의 세션뿐임.

## R6. 로그인 시작, 임시 값 쿠키, ID 토큰 검증

- Decision:
  - 로그인 시작 요청은 `GET /oauth2/authorization/google`이고 질의 값 `returnTo`(돌아갈 경로)와 `purpose=reauth`를 받습니다. 인가 요청 해석기(`DefaultOAuth2AuthorizationRequestResolver`)의 사용자 지정으로 모든 요청에 `prompt=select_account`를 붙이고, PKCE(S256)는 해석기에 `OAuth2AuthorizationRequestCustomizers.withPkce()`를 더해 켭니다(Boot 4.1.2의 클라이언트 등록 속성에는 PKCE 항목이 없어 설정 파일로 켤 수 없음). 요청 캐시는 끕니다.
  - `AuthorizationRequestRepository`를 직접 구현해 인가 요청(state, nonce, PKCE 검증값, 리디렉션 주소)과 돌아갈 경로, 시작 시각, 옛 세션 참조, 목적을 JSON으로 묶어 `__Host-Http-login` 쿠키(10분, `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`)에 AES-GCM으로 감쌉니다(키는 R10의 쿠키 키, AAD는 쿠키 이름). 옛 세션 참조와 목적은 세션 쿠키가 있을 때만 담습니다. 콜백은 state를 비교하고 시작 시각이 10분 안인지 확인한 뒤 쿠키를 삭제합니다.
  - 돌아갈 경로 검사: 서블릿이 질의 값을 한 번 푼 값(`getParameter`)이 결정 기록 0013의 세 조건을 지키고, 그 값에 `%`가 없고, 출력 가능한 ASCII만 있을 때만 받습니다. 그 밖은 `/`입니다. 그래서 퍼센트 인코딩을 다시 푼 값과 Location에 쓰는 값이 늘 같습니다. 001의 화면 경로는 ASCII뿐입니다.
  - ID 토큰 검증: 서명 알고리즘은 RS256만 받도록 `OidcIdTokenDecoderFactory`의 알고리즘 해석기를 고정합니다. `iss`는 설정값 `estate.oidc.allowed-issuers`(운영은 `https://accounts.google.com`과 `accounts.google.com`, 테스트는 가짜 서버 주소)에 있는 값만 받도록 검증기를 바꿉니다. Spring의 기본 검증은 등록된 issuer 하나만 비교하기 때문입니다. `aud`, `exp`, `nonce`는 Spring 기본 검증을 사용합니다. `auth_time`은 확인하지 않습니다(결정 기록 0013).
  - 실패 처리기는 직접 구현해 세션을 만들거나 건드리지 않습니다. 기본 `SimpleUrlAuthenticationFailureHandler`는 세션을 만들어 오류를 담으므로(그러면 세션 행과 쿠키가 생김) 사용하지 않습니다. 구글 오류(`error` 질의 값), state 불일치, 임시 값 쿠키 없음, 10분 지남, ID 토큰 검증 실패를 모두 `/login-failed`로 보내고, 목적이 재인증이었으면 `/reauth-failed`로 보냅니다.
- Rationale: Spring Security에는 쿠키 기반 인가 요청 저장소가 없고, Strict 세션 쿠키는 구글에서 돌아오는 콜백에 실리지 않습니다(결정 기록 0013). 인가 요청을 데이터베이스에 두지 않는다는 것도 0013의 결정입니다.
- 테스트: RS256 밖의 알고리즘과 허용 목록 밖의 `iss`로 서명한 ID 토큰 거절, state 위조, 10분 지난 임시 값, 돌아갈 경로 네 값(`/` 다음 탭, `/%09/x`, `//x`, `/\x`). 로그인 실패, 재인증 실패, 다른 계정 재인증 뒤에는 새 세션 행이 없고 응답에 `__Host-Http-session`을 만드는 Set-Cookie가 없습니다.

## R7. 화면이 돌아갈 경로를 넘기는 방식

- Decision: 로그인 상태가 없으면 화면은 어느 경로에서든 로그인 화면을 그립니다. 로그인 버튼은 `/oauth2/authorization/google?returnTo=<현재 경로와 질의>`로 갑니다. 현재 경로가 `/`, `/login-failed`, `/reauth-failed`면 `returnTo`를 붙이지 않습니다. 재인증 버튼은 `purpose=reauth&returnTo=/withdraw`입니다. 로그인 뒤 서버가 그 경로로 보내고 화면은 그 경로를 그립니다.
- Rationale: FR-005와 US1 시나리오 7(열려던 경로로 돌아감), FR-012(밀린 동작은 담지 않고 경로만 담음)입니다.

## R8. 만료와 재인증 판정

- Decision:
  - 유휴 7일은 `spring.session.timeout=7d`(`max_inactive_interval`)입니다. Spring Session이 판단합니다.
  - 절대 30일은 Spring Session 필터 뒤에서 도는 필터가 세션의 `creationTime`과 주입한 `Clock`의 현재 시각을 비교해, 30일이 지났으면 `HttpSession.invalidate()`로 세션을 삭제하고 쿠키를 삭제합니다. 그 뒤 `/api/**`는 401로 응답하고, 화면 경로와 로그인 시작은 로그인 상태 없이 그대로 처리합니다(화면은 로그인 화면을 그리고, 로그인 시작은 새 로그인을 시작함). 로그인할 때마다 새 세션(새 행)을 만들고 로그인 전에는 세션을 만들지 않으므로 `creationTime`이 로그인 시각입니다.
  - 재인증 판정은 공통 라이브러리의 판정 객체 하나가 로그인 정보의 마지막 구글 인증 시각이 없거나, 현재 시각과 차이가 5분보다 크거나, 현재 시각보다 60초를 넘게 미래이면 401 `REAUTH_REQUIRED`를 반환합니다. 탈퇴 서비스가 호출합니다.
  - 우리 코드의 시각은 모두 주입한 `Clock`으로 읽습니다. Spring Session은 유휴 만료와 `creationTime`에 시스템 시각(`Instant.now()`)을 직접 사용합니다(`MapSession` 4.1.1 소스).
- Rationale: Spring Session에는 절대 만료가 없습니다. 열을 추가하지 않고 `creation_time`으로 계산한다는 데이터 모델의 방침을 따릅니다.
- 테스트: 절대 30일이 지난 쿠키로 `/api/v1/me`는 401, 화면 경로(`/withdraw`)는 `index.html`이고 세션 행이 삭제되며, 로그인 시작은 정상으로 구글에 보냅니다. 7일과 30일은 테스트가 `spring_session` 행의 `creation_time`, `last_access_time`, `expiry_time`을 과거로 옮겨 확인합니다(시스템 시각을 사용하는 Spring Session과 `Clock`을 사용하는 우리 필터가 같은 기준을 보게 하려면 행의 값을 옮기는 쪽이 정확함). 5분과 60초는 시각을 옮길 수 있는 `Clock`으로 확인합니다.
- Alternatives considered: 세션 속성에 로그인 시각을 따로 저장(같은 값이 둘이 되어 제외).

## R9. 로그아웃, 탈퇴, 요청 위조 방어, 401

- Decision:
  - Spring Security의 기본 로그아웃은 끕니다. CSRF 토큰을 끄면 기본 로그아웃이 GET도 받고, 로그인 상태가 없어도 성공으로 응답하기 때문입니다. 로그아웃은 `POST /api/v1/logout` 컨트롤러가 로그인 상태를 확인한 뒤 `HttpSession.invalidate()`로 그 세션 행을 삭제하고, 세션 쿠키를 삭제하고, 204를 반환합니다.
  - 탈퇴는 `DELETE /api/v1/me`입니다. 재인증 판정 뒤 한 트랜잭션에서 사용자 행을 `FOR UPDATE`로 잠그고, `DELETE FROM spring_session WHERE principal_name = ?`로 그 사용자의 세션을 모두 삭제하고(속성 행은 외래 키 연쇄), 사용자 행을 삭제합니다. 커밋한 뒤 현재 요청의 세션을 `invalidate()`하고(요청 끝에 Spring Session이 다시 저장하지 않게) 쿠키를 삭제한 뒤 204를 반환합니다.
  - Spring Security의 CSRF 토큰은 끄고, 상태를 바꾸는 메서드(POST, PUT, PATCH, DELETE)에 Origin 검사 필터를 둡니다. Origin이 없으면 Referer의 출처로, 둘 다 없으면 403 `ORIGIN_REJECTED`입니다. 허용 출처는 설정값 `estate.web.allowed-origins`입니다(운영은 서비스 주소 하나, `local` 프로필은 `http://localhost:8080`, `local-vite` 프로필은 `http://localhost:5173`). 이 필터는 로그인 상태 확인(권한 확인 1단계) 뒤에 둡니다. 그래서 로그인 상태가 없으면 출처와 관계없이 401이고, 로그인 상태가 있는데 허용하지 않은 출처면 403입니다. [API 공통 규칙](../../docs/architecture/api-conventions.md#인증과-권한-확인)의 순서(401이 먼저, 403은 로그인한 뒤의 거절)와 맞추려는 것입니다. 001의 상태를 바꾸는 요청은 모두 로그인 상태가 필요해 이 순서로 빠지는 요청이 없습니다.
  - `/api/**`에서 로그인 상태가 없거나 만료되면 401 Problem Details(`UNAUTHENTICATED`)입니다. 화면 경로와 정적 파일은 R2대로 로그인 없이 받습니다.
- Rationale: 결정 기록 0013의 세 가지 방어(Strict 쿠키, Origin 검사, GET은 상태를 바꾸지 않음)와 API 공통 규칙의 안전한 메서드 규칙입니다. CSRF 토큰을 함께 두면 화면이 토큰을 읽어 요청에 담아 보내야 해 계층이 하나 늘어납니다.
- Alternatives considered: Spring Security 로그아웃의 매처와 처리기를 바꿔 사용하기(로그인 상태 확인보다 앞에서 돌아 401을 반환할 수 없어 제외), CSRF 토큰(0013이 정하지 않은 방어).

## R10. 개인정보 열 암호화와 키

- Decision: 공통 라이브러리의 암호화 객체 하나가 AES-256-GCM으로 암호화하고 결과를 `키 버전(1바이트) + nonce(12바이트) + 암호문 + 태그(16바이트)`로 이어 반환합니다. 열의 추가 인증 데이터는 `user.login_email:{user.id}`입니다. 엔티티의 `login_email` 필드는 `byte[]`이고, 서비스가 시퀀스에서 번호를 먼저 받아(`select nextval('user_id_seq')`) 암호화한 뒤 저장합니다. JPA 변환기(`AttributeConverter`)는 행의 기본 키를 받지 못해 사용하지 않습니다.
- 키는 설정값으로 받고 `.env`(로컬)나 환경 변수(운영)에 둡니다. `ESTATE_COLUMN_KEYS`(`버전:base64 32바이트`를 쉼표로 이음), `ESTATE_COLUMN_KEY_ACTIVE`(새 값에 사용할 버전), `ESTATE_LOGIN_COOKIE_KEY`(임시 값 쿠키 전용)입니다. 테스트는 실행마다 무작위 키를 만듭니다. 키가 없거나 32바이트가 아니면 앱이 시작하지 않습니다.
- Rationale: 결정 기록 0012(형식, AAD, 키는 데이터베이스 밖)와 앱 골격의 "시퀀스로 먼저 받은 기본 키" 방식입니다. 키를 용도별로 나누면 한 키가 유출되어도 다른 용도로 번지지 않습니다.
- Alternatives considered: 키 하나에 용도별 AAD만 다르게, JPA 변환기(위 이유로 제외).

## R11. 표시 이름 규칙

- Decision: 앞뒤 공백(유니코드 공백)을 제거한 값이 1자 이상 20자 이하(코드 포인트)이고, 제어 문자(Cc), 줄 구분 문자(Zl, Zp), 방향 제어 문자(U+202A~U+202E, U+2066~U+2069)가 없어야 저장합니다. 어기면 400 `VALIDATION_FAILED`, `errors[0].pointer`는 `#/displayName`입니다. `displayName`이 없거나 null이거나 문자열이 아니어도(숫자, 참거짓, 배열, 객체) 같은 응답입니다. 본문에 알 수 없는 필드가 있으면 400 `VALIDATION_FAILED`, `errors[0].pointer`는 `#`(본문 전체)이고, 필드 이름은 응답에 넣지 않습니다. 깨진 JSON은 그 밖의 읽기 오류와 같은 400(code 없음)입니다.
- 구글이 보낸 이름은 앞뒤 공백과 위 금지 문자를 제거하고 20자로 자른 값을 사용합니다. 그 결과가 비면(구글이 이름을 보내지 않았거나 금지 문자뿐) 빈 값입니다. 그래서 빈 값은 "첫 로그인에서 다듬은 뒤 사용할 수 있는 이름이 없는 경우"에만 생기고, spec 가정 절도 같은 말로 수정했습니다.
- Rationale: 가족끼리 부르는 이름이라 20자면 충분하고 휴대폰 목록 한 줄에 들어갑니다. 코드 포인트로 세야 한글과 영문이 같은 기준입니다. 방향 제어 문자는 다른 이름처럼 보이게 꾸미는 데 사용될 수 있습니다. 이모지 결합 문자(ZWJ)는 막지 않습니다.
- Alternatives considered: 바이트 수 기준(한글이 영문의 세 배로 세어짐), 30자(목록 화면에서 줄이 넘침).

## R12. API와 경로

| 동작 | 메서드와 경로 | 계약 표기 |
|---|---|---|
| 로그인 시작 | `GET /oauth2/authorization/google?returnTo=&purpose=` | `security: []` |
| 콜백 | `GET /login/oauth2/code/google` | `security: []` |
| 내 정보 조회 | `GET /api/v1/me` | `x-permission: none`, `x-household-scope: false`, `x-owner-only: false`, `x-reauth: false` |
| 표시 이름 수정 | `PATCH /api/v1/me` | `x-permission: profile:write`, `x-household-scope: false`, `x-owner-only: true`, `x-reauth: false` |
| 로그아웃 | `POST /api/v1/logout` | `x-permission: none`, `x-household-scope: false`, `x-owner-only: false`, `x-reauth: false` |
| 탈퇴 | `DELETE /api/v1/me` | `x-permission: none`, `x-household-scope: false`, `x-owner-only: false`, `x-reauth: true` |

- 001에는 가구가 없으므로 권한 확인 2단계의 "멤버십이 있으면 탈퇴 409"와 3단계의 `profile:write` 확인은 002가 붙입니다. 001은 1단계(401)와 6단계(401 `REAUTH_REQUIRED`)를 확인합니다.
- 표시 이름 수정은 경로에 사용자 번호가 없고 세션의 사용자만 수정하므로 5단계의 본인 확인이 경로 설계로 보장됩니다.

## R13. 화면과 문구

화면은 다섯이고 모두 휴대폰 폭(360px 기준)에서 가로 스크롤이 없게 만듭니다. 아래 문구와 R11의 표시 이름 규칙은 저장소 소유자가 2026-11-16에 확정했습니다.

| 경로 | 화면 | 문구 |
|---|---|---|
| 모든 경로(로그인 상태 없음) | 로그인 | 서비스 이름 "서초댁 임장 기록", 버튼 "Google 계정으로 로그인", 안내 "가족에게 받은 초대 링크로 들어왔다면 로그인하면 바로 수락됩니다." |
| `/`, `/no-household` | 가구 없음 | "아직 가구가 없습니다.", 버튼 "로그아웃", "탈퇴" |
| `/withdraw` | 탈퇴 확인 | "탈퇴하면 계정, 로그인 이메일, 표시 이름과 모든 기기의 로그인 상태가 삭제되며 되돌릴 수 없습니다.", 버튼 "탈퇴", "취소". 401 `REAUTH_REQUIRED`를 받으면 안내 "탈퇴하려면 구글 계정을 한 번 더 확인해야 합니다."와 버튼 "구글 계정 확인" |
| `/login-failed` | 로그인 실패 | "로그인하지 못했습니다. 다시 시도해 주세요.", 버튼 "Google 계정으로 로그인" |
| `/reauth-failed` | 재인증 실패 | 기본 "구글 계정 확인에 실패했습니다. 다시 시도해 주세요.", `?reason=account-mismatch`면 "다른 구글 계정을 골라 로그인이 해제되었습니다. 다시 로그인해 주세요." |

- 내 정보 조회가 401도 성공도 아닌 오류(서버 오류, 네트워크 끊김)로 끝나면 `/login-failed`와 `/reauth-failed`를 뺀 어느 경로든 안내 "잠시 후 다시 시도해 주세요."와 버튼 "다시 시도"를 보이고, 버튼을 누르면 내 정보를 다시 조회합니다(저장소 소유자가 2026-11-16에 확정). 로그아웃 요청이 401이 아닌 오류로 끝나면 가구 없음 화면을 그대로 둡니다.
- 탈퇴 요청이 401도 `REAUTH_REQUIRED`도 아닌 오류(403, 서버 오류, 네트워크 끊김)로 끝나면 탈퇴 확인 화면에 머물고 안내 "잠시 후 다시 시도해 주세요."를 보입니다. 사용자는 "탈퇴"를 다시 누를 수 있고, 오류 안내와 재인증 안내 중 마지막 응답의 안내만 보입니다(저장소 소유자가 2026-11-16에 확정).
- 재인증에서 돌아오면 화면은 탈퇴 확인 화면으로 가고 사용자가 "탈퇴"를 다시 누릅니다. 밀린 동작은 어디에도 저장하지 않습니다(FR-012).
- 질의 값 `reason`에는 개인 자료가 없습니다.

## R14. 테스트 방식

- Decision:
  - 서버 통합 테스트는 `@SpringBootTest`와 MockMvc(`spring-boot-starter-webmvc-test`), Testcontainers PostgreSQL 18(앱 골격의 `@IntegrationTest`)로 합니다. 데이터베이스를 목으로 바꾸지 않습니다(constitution V).
  - 가짜 구글 서버는 `mock-oauth2-server` 7.0.0을 테스트 안에서 라이브러리로 띄우고, 테스트마다 `enqueueCallback`으로 `sub`, `email`, `email_verified`, `name`을 정합니다. 7.0.0은 토큰 교환 때 `client_id`와 `redirect_uri`가 인가 요청과 같아야 합니다. 로그인 흐름은 MockMvc로 로그인 시작, 가짜 서버의 인가 요청(코드 발급), 콜백을 차례로 보냅니다.
  - 시각: 우리 코드는 시각을 옮길 수 있는 `Clock` 빈, Spring Session의 만료는 세션 행의 시각 열을 옮겨서 테스트합니다(R8).
  - 개인정보 누출(FR-020, SC-006): 로그 출력을 캡처(`OutputCaptureExtension`)한 채 로그인 성공, 로그인 실패, 재인증 실패, 롤백, 표시 이름 400, 탈퇴를 실행하고, 출력과 모든 응답 본문에 가짜 이메일, 가짜 표시 이름, 가짜 `sub`, 쿠키 원문 토큰, ID 토큰 문자열이 없는지 확인합니다(표시 이름은 내 정보 응답 본문에만 있어야 함).
  - 웹 화면은 Vitest와 Testing Library로 화면 문구와 버튼, 401과 `REAUTH_REQUIRED` 처리, `returnTo`를 테스트합니다. 휴대폰 폭의 가로 스크롤(FR-021, SC-007)과 로그인부터 로그아웃까지 한 바퀴는 Playwright로 테스트합니다. Playwright는 Testcontainers 대신 `compose.yaml`의 PostgreSQL과 가짜 구글 서버 컨테이너(`ghcr.io/navikt/mock-oauth2-server:7.0.0`), `e2e` 프로필로 띄운 앱 jar(테스트 키는 실행 스크립트가 만듦)를 사용합니다.
  - 보안 경계 테스트(세션 행 삭제, 절대 30일, 돌아갈 경로, 401과 `REAUTH_REQUIRED`, 다른 계정 재인증, ID 토큰 검증, 개인정보 누출)는 constitution V대로 구현과 다른 쪽이 spec과 결정 기록에서 직접 작성합니다. tasks에서 별도 작업으로 나눕니다.
- Alternatives considered: 가짜 구글 서버를 컨테이너로만 띄우기(테스트마다 `sub`를 바꾸기 어려워 서버 통합 테스트에서는 제외), 직접 만든 가짜 서버(mock-oauth2-server가 필요한 것을 모두 지원해 제외).

## R15. 응답 보안 헤더

- Decision: CSP는 `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'`입니다. `frame-ancestors 'none'`은 결정 기록 0013이 구현으로 넘긴 값입니다. HSTS는 `max-age` 1년이고 Spring Security가 HTTPS 요청에만 붙입니다. 운영처럼 프록시 뒤에서 HTTPS를 받으면 `server.forward-headers-strategy=framework`가 있어야 HSTS와 Secure 판단이 맞습니다(배포 환경을 정할 때 설정). 지도(005)와 사진(008)이 오면 그 기능이 CSP를 넓힙니다.
- Rationale: 001 화면은 같은 출처의 스크립트와 스타일만 사용합니다. Vite 빌드는 인라인 스크립트를 만들지 않습니다. Vite 개발 서버(5173)의 응답에는 이 CSP가 붙지 않습니다(API 서버 응답에만 붙음).

## R16. 로컬 실행과 화면 확인

- Decision: 화면 확인은 앱 하나(`http://localhost:8080`)로 합니다. `docker compose`로 PostgreSQL 18을 띄우고, `local` 프로필로 `bootRun`하면 웹 화면을 빌드해 같이 서비스합니다. 구글 콘솔에 이미 등록한 리디렉션 주소(`http://localhost:8080/login/oauth2/code/google`)를 그대로 사용합니다. 앱은 `spring.config.import=optional:file:.env[.properties]`로 저장소 루트의 `.env`를 읽고, 데이터베이스 접속 값은 `application-local.yml`에 `compose.yaml`과 같은 기본값으로 둡니다(값을 바꾸려면 `.env`에 `SPRING_DATASOURCE_*`를 넣음).
- 웹 화면을 수정하며 볼 때만 Vite 개발 서버(5173)와 `local-vite` 프로필(`application-local-vite.yml`)을 `local`과 함께 활성화합니다(`local,local-vite`, 뒤 프로필이 허용 출처와 리디렉션 주소를 덮어씀). 휴대폰 터널도 `local,tunnel`입니다. `local-vite` 프로필은 허용 출처와 리디렉션 주소가 `http://localhost:5173`이고, 구글 콘솔에 그 리디렉션 주소를 추가로 등록해야 합니다.
- 휴대폰 폭 확인은 PC Chrome 개발자 도구의 기기 화면으로 합니다. 휴대폰 실기기는 선택 절차로 cloudflared 임시 터널을 사용합니다(quickstart). 터널 주소는 실행할 때마다 바뀌어 구글 콘솔에 매번 등록해야 합니다.
- Rationale: 내장 브라우저(Chrome 156)에서 `http://localhost`가 `__Host-` 쿠키를 받는 것을 2026-11-16에 직접 확인했습니다. 앱 하나로 띄우면 리디렉션 주소와 Origin이 하나라 운영과 같습니다. Safari는 `http://localhost`에서 `__Host-` 쿠키를 거부한다는 보고가 있어 확인 브라우저는 Chrome으로 둡니다.
- Alternatives considered: 로컬 HTTPS(mkcert, 설치와 인증서 관리가 늘어 제외), 개발 프로필에서 쿠키 접두사 빼기(운영과 쿠키 설정이 달라져 제외).
