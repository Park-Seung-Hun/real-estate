# 설계 문서 지도

설계 문서의 목록과 작성 상태입니다. PRD에서 서비스 범위를 정하고, 그 범위에 맞춰 큰 구조와 결정 기록을 작성한 뒤 1단계 상세 설계를 씁니다.

| 문서 | 경로 | 상태 |
|---|---|---|
| 문서 지도. 설계 문서의 목록과 작성 상태 | [`docs/README.md`](README.md) | 작성 완료 |
| PRD. 서비스가 누구를 위해 무엇을 어디까지 하는지 | [`docs/prd.md`](prd.md) | 작성 완료 |
| C4 시스템 컨텍스트. 사용자와 외부 시스템 사이에서 이 서비스의 경계 | [`docs/architecture/c4-context.md`](architecture/c4-context.md) | 작성 완료 |
| C4 컨테이너. 1단계 실행 단위와 최종 목표 구성 | [`docs/architecture/c4-container.md`](architecture/c4-container.md) | 작성 완료 |
| 데이터 모델. 1단계 테이블과 키, 이력 저장 방식 | [`docs/architecture/data-model.md`](architecture/data-model.md) | 작성 완료 |
| C4 컴포넌트. API 서버, collector, 알림 발송기의 1단계 모듈 경계 | [`docs/architecture/c4-component.md`](architecture/c4-component.md) | 작성 완료 |
| 핵심 흐름. 수집과 변경 감지, 알림 발송, 임장 기록과 사진 업로드, 단지 등록에서 매물 등록까지의 순서도 | [`docs/architecture/flows.md`](architecture/flows.md) | 작성 완료 |
| 결정 기록 목록. 결정 기록의 형식과 목록 | [`docs/adr/README.md`](adr/README.md) | 작성 완료 |
| 결정 기록 0001. 기관이 공개하는 공식 API만 사용하는 결정 | [`docs/adr/0001-official-api-only.md`](adr/0001-official-api-only.md) | 작성 완료 |
| 결정 기록 0002. 기록은 서비스 자체 화면에서 입력하는 결정 | [`docs/adr/0002-own-input-screen.md`](adr/0002-own-input-screen.md) | 작성 완료 |
| 결정 기록 0003. 알림 채널 | [`docs/adr/0003-notification-channel.md`](adr/0003-notification-channel.md) | 작성 완료 |
| 결정 기록 0004. 단계별 기술 도입과 도입 조건 | [`docs/adr/0004-staged-adoption.md`](adr/0004-staged-adoption.md) | 작성 완료 |
| 결정 기록 0005. 하나의 애플리케이션으로 두는 부분과 따로 실행하는 부분 | [`docs/adr/0005-modular-monolith.md`](adr/0005-modular-monolith.md) | 작성 완료 |
| 결정 기록 0006. 같은 실거래를 구분하는 키 | [`docs/adr/0006-transaction-identity.md`](adr/0006-transaction-identity.md) | 작성 완료 |
| 결정 기록 0007. 해제, 정정, 등기로 바뀐 거래를 남기는 방식 | [`docs/adr/0007-change-history.md`](adr/0007-change-history.md) | 작성 완료 |
| 결정 기록 0008. 기관마다 다른 단지 키를 잇는 방식 | [`docs/adr/0008-complex-matching.md`](adr/0008-complex-matching.md) | 작성 완료 |
| 결정 기록 0009. 실거래를 매일 다시 받는 기간 | [`docs/adr/0009-refetch-window.md`](adr/0009-refetch-window.md) | 작성 완료 |
| 결정 기록 0010. 로그인 방식 | [`docs/adr/0010-login-method.md`](adr/0010-login-method.md) | 작성 완료 |
| 결정 기록 0011. 가족 공유와 권한 | [`docs/adr/0011-household-sharing.md`](adr/0011-household-sharing.md) | 작성 완료 |
| 결정 기록 0012. 개인정보 저장 방식 | [`docs/adr/0012-personal-data-storage.md`](adr/0012-personal-data-storage.md) | 작성 완료 |
| 결정 기록 0013. 세션 방식 | [`docs/adr/0013-session.md`](adr/0013-session.md) | 작성 완료 |
| 결정 기록 0014. 사진과 원본 응답 파일 저장소 | [`docs/adr/0014-file-storage.md`](adr/0014-file-storage.md) | 작성 완료 |
| 결정 기록 0015. 주소를 좌표로 바꾸는 곳과 좌표 저장 | [`docs/adr/0015-geocoder.md`](adr/0015-geocoder.md) | 작성 완료 |
| 화면 와이어프레임. 1단계 화면과 화면 사이 이동 | [`docs/design/wireframes.md`](design/wireframes.md) | 작성 완료 |
| API 공통 규칙. 기능마다 만드는 계약이 따를 경로와 형식, 오류와 목록 형식, 권한 확인 순서, 응답에 담는 개인정보 | [`docs/architecture/api-conventions.md`](architecture/api-conventions.md) | 작성 완료 |

- 엔드포인트 단위 계약은 설계 문서로 쓰지 않습니다. SpecKit 계획 단계(`/speckit-plan`)가 기능마다 `specs/NNN-이름/contracts/`에 만듭니다.
- 작성 예정 문서를 작성하면 경로를 링크로 바꾸고 상태를 작성 완료로 고칩니다.
