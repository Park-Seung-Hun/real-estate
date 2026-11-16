import { useSearchParams } from 'react-router'

export function ReauthFailedPage() {
  const [params] = useSearchParams()
  return (
    <>
      <p>
        {params.get('reason') === 'account-mismatch'
          ? '다른 구글 계정을 골라 로그인이 해제되었습니다. 다시 로그인해 주세요.'
          : '구글 계정 확인에 실패했습니다. 다시 시도해 주세요.'}
      </p>
      <a className="button" href="/oauth2/authorization/google">
        Google 계정으로 로그인
      </a>
    </>
  )
}
