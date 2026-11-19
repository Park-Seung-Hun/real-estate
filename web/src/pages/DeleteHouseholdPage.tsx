import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { deleteHousehold, isNoHousehold, isReauthRequired, isUnauthenticated } from '../api/client'

export function DeleteHouseholdPage({
  memberCount,
  snapshot,
  onDeleted,
  onUnauthenticated,
}: {
  memberCount: number
  snapshot: () => () => boolean
  onDeleted: () => void
  onUnauthenticated: () => void
}) {
  const navigate = useNavigate()
  const [notice, setNotice] = useState<'reauth' | 'retry' | null>(null)
  const sending = useRef(false)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const onDelete = () => {
    if (sending.current) return
    sending.current = true
    setNotice(null)
    const same = snapshot()
    deleteHousehold().then(
      () => same() && onDeleted(),
      (error) => {
        sending.current = false
        if (isUnauthenticated(error)) same() && onUnauthenticated()
        else if (isNoHousehold(error)) same() && onDeleted()
        else if (mounted.current) setNotice(isReauthRequired(error) ? 'reauth' : 'retry')
      },
    )
  }

  return (
    <>
      <p>{`가구를 삭제하면 가구, 구성원 ${memberCount}명의 연결, 보낸 초대가 삭제되며 되돌릴 수 없습니다.`}</p>
      {notice === 'retry' && <p>잠시 후 다시 시도해 주세요.</p>}
      {notice === 'reauth' && (
        <>
          <p>가구를 삭제하려면 구글 계정을 한 번 더 확인해야 합니다.</p>
          <a className="button" href="/oauth2/authorization/google?purpose=reauth&returnTo=/household/delete">
            구글 계정 확인
          </a>
        </>
      )}
      <button className="button" type="button" onClick={onDelete}>
        가구 삭제
      </button>
      <button className="button" type="button" onClick={() => navigate('/')}>
        취소
      </button>
    </>
  )
}
