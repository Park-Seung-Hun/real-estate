import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { isNoHousehold, isUnauthenticated, leaveHousehold } from '../api/client'

export function LeaveHouseholdPage({
  snapshot,
  onLeft,
  onUnauthenticated,
}: {
  snapshot: () => () => boolean
  onLeft: () => void
  onUnauthenticated: () => void
}) {
  const navigate = useNavigate()
  const [retry, setRetry] = useState(false)
  const sending = useRef(false)
  // 화면 안 안내는 화면이 남아 있을 때만 띄운다. 앱 상태 전환은 snapshot의 로그인 세대로 거른다.
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const onLeave = () => {
    if (sending.current) return
    sending.current = true
    setRetry(false)
    const same = snapshot()
    leaveHousehold().then(
      () => same() && onLeft(),
      (error) => {
        sending.current = false
        if (isUnauthenticated(error)) same() && onUnauthenticated()
        else if (isNoHousehold(error)) same() && onLeft()
        else if (mounted.current) setRetry(true)
      },
    )
  }

  return (
    <>
      <p>가구를 나가면 이 가구의 기록을 볼 수 없습니다. 다시 들어오려면 새 초대 링크가 필요합니다.</p>
      {retry && <p>잠시 후 다시 시도해 주세요.</p>}
      <button className="button" type="button" onClick={onLeave}>
        나가기
      </button>
      <button className="button" type="button" onClick={() => navigate('/')}>
        취소
      </button>
    </>
  )
}
