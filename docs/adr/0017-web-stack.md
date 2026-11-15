# 0017. 웹 화면 기술

웹 화면은 React, Vite, TypeScript로 만든 SPA(Single Page Application, 화면을 브라우저에서 그리고 자료는 API로 받는 웹 앱)이고, API 서버와 같은 주소에서 서비스합니다.

## 배경

- [constitution](../../.specify/memory/constitution.md)의 기술 제약은 웹 화면 기술을 화면이 처음 나오는 기능의 plan에서 정하고 결정 기록으로 남기라고 정했습니다. 첫 화면은 001(로그인)에 나옵니다. 001 plan을 준비하며 선택지를 비교했고, constitution은 `docs/*` 브랜치에서만 고치므로 결정 기록도 그 브랜치에서 씁니다.
- [API 공통 규칙](../architecture/api-conventions.md)은 화면과 서버 사이를 JSON API로 정했습니다. 필드 이름, RFC 9457 오류 본문, 기능마다 OpenAPI 3.1 계약을 둡니다.
- [결정 기록 0013](0013-session.md)은 웹 화면이 처음 받는 HTML에 개인 자료를 담지 않고 자료를 API 요청으로 받게 정했습니다. 세션 쿠키는 `SameSite=Strict`와 `__Host-` 접두사를 쓰고, 상태를 바꾸는 요청의 Origin을 검사합니다. 이 쿠키 설정은 웹 화면과 API가 같은 주소일 때만 맞습니다.
- 1단계 화면은 열 개 남짓이고 휴대폰 화면 폭을 기준으로 합니다([와이어프레임](../design/wireframes.md)). 005는 지도, 008은 사진 업로드를 화면에 둡니다.
- 2026-11-16에 버전과 연결 방식을 조사했습니다. 출처는 npm registry의 각 패키지 최신 버전, [Vite 서버 옵션](https://vite.dev/config/server-options.html), [Spring Boot 정적 콘텐츠](https://docs.spring.io/spring-boot/reference/web/servlet.html)입니다.

## 선택지

| 선택지 | 내용 | 선택 |
|---|---|---|
| 서버가 HTML을 그리는 템플릿(Thymeleaf) | 화면 수가 적으면 가장 단순합니다 | 제외. 컨트롤러가 HTML을 돌려주면 API 공통 규칙의 JSON 계약이 쓰이지 않거나 HTML용과 JSON용을 둘 다 만들어야 합니다. 나중에 SPA로 바꾸면 만든 화면을 모두 다시 써야 합니다 |
| 템플릿으로 시작해 화면이 복잡해지면 바꿈 | 처음은 빠릅니다 | 제외. 바꾸는 비용이 위와 같고, 그 사이에는 화면 기술이 두 벌이 됩니다 |
| React, Vite, TypeScript SPA | 지도, 사진, 폼 라이브러리의 선택지가 가장 많습니다 | 채택 |
| Vue나 Svelte SPA | 문법이 가볍고 번들이 작습니다 | 제외. 같은 구조에서 React보다 생태계가 작고, 이 화면 규모에서는 번들 크기 차이가 문제가 되지 않습니다 |
| Next.js(서버 렌더링) | 서버에서 화면을 그릴 수 있습니다 | 제외. Node 서버가 하나 더 생겨 [C4 컨테이너](../architecture/c4-container.md)의 구성과 세션을 두는 곳이 바뀝니다 |

## 결정

- 웹 화면은 저장소 루트의 `web/`에 React, Vite, TypeScript로 만듭니다. 버전은 001 plan에서 그때의 최신 안정 버전으로 고정합니다.
- 배포할 때는 Vite 빌드 결과를 API 서버 jar의 정적 파일로 넣어 같은 주소에서 서비스합니다. 화면 경로를 직접 열면 `index.html`을 돌려주고, API 경로와 로그인 시작, 콜백, 로그아웃 경로는 `index.html`로 돌리지 않습니다.
- 개발할 때는 Vite 개발 서버를 띄우고, Vite 프록시가 API와 로그인 경로를 API 서버로 넘깁니다. 브라우저 주소가 `localhost` 하나라 운영과 같은 쿠키 설정을 씁니다. 브라우저마다 `http://localhost`에서 `__Host-` 쿠키를 받는지가 달라, 개발에 쓰는 브라우저와 구글 리디렉션 주소를 맞추는 방법은 001의 quickstart에 적습니다. 개발 서버 주소는 개발 설정의 Origin 허용 목록에만 넣습니다.
- 화면은 사용자 화면 문구, 화면 이동, API 호출만 맡고 권한 판정을 하지 않습니다. 권한은 [API 공통 규칙](../architecture/api-conventions.md#인증과-권한-확인)대로 서버가 확인합니다.

## 결과

- [C4 컨테이너](../architecture/c4-container.md)의 웹 화면 기술이 "정하지 않음"에서 이 결정으로 바뀝니다.
- constitution 기술 제약의 웹 화면 기술 줄을 이 결정을 따르게 고칩니다.
- Gradle 빌드와 npm 빌드를 잇는 방식, 라우터, 화면 테스트 도구는 001 plan에서 정합니다.
- 휴대폰 실기기에서 개발 서버에 접속하려면 HTTPS 주소가 필요합니다. 사설 IP 주소는 Secure 쿠키를 받지 못하고 구글 리디렉션 주소로 등록할 수 없기 때문입니다. 방법은 001의 quickstart에 적습니다.
- 웹 화면과 API를 다른 주소에 두게 되면 이 결정과 결정 기록 0013의 쿠키 설정을 함께 재검토합니다.

## 상태

확정, 2026-11-16
