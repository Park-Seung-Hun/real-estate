import { useLocation } from 'react-router'

const NO_RETURN = ['/', '/login-failed', '/reauth-failed']

export function LoginPage() {
  const { pathname, search } = useLocation()
  const href =
    '/oauth2/authorization/google' +
    (NO_RETURN.includes(pathname.replace(/(.)\/$/, '$1')) ? '' : `?returnTo=${encodeURIComponent(pathname + search)}`)
  return (
    <>
      <h1>서초댁 임장 기록</h1>
      <a className="button" href={href}>
        Google 계정으로 로그인
      </a>
      <p>가족에게 받은 초대 링크로 들어왔다면 로그인하면 바로 수락됩니다.</p>
    </>
  )
}
