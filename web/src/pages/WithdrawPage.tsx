import { useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { isReauthRequired, isUnauthenticated, withdraw } from '../api/client'

export function WithdrawPage({ onGone }: { onGone: () => void }) {
  const navigate = useNavigate()
  // 마지막 응답의 안내만 보인다(research R13)
  const [notice, setNotice] = useState<'reauth' | 'retry' | null>(null)
  const sending = useRef(false)

  const onWithdraw = () => {
    if (sending.current) return
    sending.current = true
    withdraw().then(
      () => {
        onGone()
        navigate('/')
      },
      (error) => {
        sending.current = false
        if (isUnauthenticated(error)) {
          onGone()
          navigate('/')
        } else setNotice(isReauthRequired(error) ? 'reauth' : 'retry')
      },
    )
  }

  return (
    <>
      <p>탈퇴하면 계정, 로그인 이메일, 표시 이름과 모든 기기의 로그인 상태가 삭제되며 되돌릴 수 없습니다.</p>
      {notice === 'retry' && <p>잠시 후 다시 시도해 주세요.</p>}
      {notice === 'reauth' && (
        <>
          <p>탈퇴하려면 구글 계정을 한 번 더 확인해야 합니다.</p>
          <a className="button" href="/oauth2/authorization/google?purpose=reauth&returnTo=/withdraw">
            구글 계정 확인
          </a>
        </>
      )}
      <button className="button" type="button" onClick={onWithdraw}>
        탈퇴
      </button>
      <button className="button" type="button" onClick={() => navigate('/')}>
        취소
      </button>
    </>
  )
}
