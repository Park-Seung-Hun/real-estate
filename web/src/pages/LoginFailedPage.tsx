export function LoginFailedPage() {
  return (
    <>
      <p>로그인하지 못했습니다. 다시 시도해 주세요.</p>
      <a className="button" href="/oauth2/authorization/google">
        Google 계정으로 로그인
      </a>
    </>
  )
}
