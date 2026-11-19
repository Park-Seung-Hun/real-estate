import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { acceptInvitation, errorCode, INVITE_TOKEN_KEY, isUnauthenticated, previewInvitation } from '../api/client'

// research R14 초대 수락 실패 안내
const NEW_LINK = '가족에게 새 초대 링크를 받아 주세요.'
const FAILURE: Record<string, string> = {
  NOT_FOUND: `존재하지 않는 초대 링크입니다. ${NEW_LINK}`,
  INVITATION_REVOKED: `취소된 초대 링크입니다. ${NEW_LINK}`,
  INVITATION_USED: `이미 사용된 초대 링크입니다. ${NEW_LINK}`,
  INVITATION_EXPIRED: `만료된 초대 링크입니다. ${NEW_LINK}`,
  HOUSEHOLD_FULL: '이 가구는 구성원이 이미 둘이라 들어갈 수 없습니다.',
  ALREADY_IN_THIS_HOUSEHOLD: '이미 이 가구의 구성원입니다. 링크는 가족에게 보내세요.',
  HAS_HOUSEHOLD: '이미 다른 가구에 속해 있습니다. 가구를 나가거나 삭제한 뒤 이 링크를 다시 여세요.',
}
const RETRY = '잠시 후 다시 시도해 주세요.'

type View = { kind: 'checking' } | { kind: 'confirm'; inviter: string | null } | { kind: 'failure'; message: string }

// onJoinedAway: 확인 화면이 내려간 뒤에 들어가기가 성공하면 첫 화면에서 목록을 다시 읽는다(research R14 흐름도 아래 문단).
export function InvitePage({
  snapshot,
  onJoined,
  onJoinedAway,
  onUnauthenticated,
}: {
  snapshot: () => () => boolean
  onJoined: () => void
  onJoinedAway: () => void
  onUnauthenticated: () => void
}) {
  const navigate = useNavigate()
  const [view, setView] = useState<View>({ kind: 'checking' })
  const sending = useRef(false)
  // 다시 마운트된 뒤(새 토큰으로 다시 열림) 이전 화면의 수락 응답은 아무것도 하지 않는다.
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  // 결과를 보인 뒤에는 토큰을 지운다(research R7). 같은 링크를 다시 열면 앱 시작 때 다시 저장된다.
  const fail = (error: unknown) => {
    sessionStorage.removeItem(INVITE_TOKEN_KEY)
    setView({ kind: 'failure', message: FAILURE[errorCode(error) ?? ''] ?? RETRY })
  }

  useEffect(() => {
    let active = true
    const token = sessionStorage.getItem(INVITE_TOKEN_KEY)
    if (token === null) setView({ kind: 'failure', message: FAILURE.NOT_FOUND })
    else {
      const same = snapshot()
      previewInvitation(token).then(
        (preview) => active && setView({ kind: 'confirm', inviter: preview.inviterDisplayName }),
        // 401은 화면이 내려간 뒤에 와도 같은 로그인 세션이면 로그인 화면으로 보낸다(토큰은 남긴다, research R7).
        (error) => (isUnauthenticated(error) ? same() && onUnauthenticated() : active && fail(error)),
      )
    }
    return () => {
      active = false
    }
  }, [])

  const onJoin = () => {
    const token = sessionStorage.getItem(INVITE_TOKEN_KEY)
    if (sending.current || token === null) return
    sending.current = true
    // 결과가 오면 화면이 내려간 뒤라도 보낸 토큰을 지운다. 새 토큰으로 다시 열린 경우를 지키려고 저장된 값이 같을 때만(research R7).
    const forget = () => sessionStorage.getItem(INVITE_TOKEN_KEY) === token && sessionStorage.removeItem(INVITE_TOKEN_KEY)
    const same = snapshot()
    acceptInvitation(token).then(
      () => {
        forget()
        if (mounted.current) onJoined()
        else if (same()) onJoinedAway()
      },
      (error) => {
        // 401은 토큰을 남기고(research R7), 확인 화면이 내려간 뒤에 와도 같은 로그인 세션이면 로그인 화면으로 보낸다
        if (isUnauthenticated(error)) {
          if (mounted.current) sending.current = false
          if (same()) onUnauthenticated()
          return
        }
        forget()
        if (!mounted.current) return
        sending.current = false
        fail(error)
      },
    )
  }

  const onCancel = () => {
    sessionStorage.removeItem(INVITE_TOKEN_KEY)
    navigate('/')
  }

  if (view.kind === 'checking') return null
  if (view.kind === 'failure')
    return (
      <>
        <p>{view.message}</p>
        <button className="button" type="button" onClick={() => navigate('/')}>
          첫 화면으로
        </button>
      </>
    )
  return (
    <>
      <p>
        {view.inviter ? `${view.inviter}님의 ` : ''}가구에 들어갑니다. 가족에게 받은 링크가 맞을 때만 들어가세요.
      </p>
      <button className="button" type="button" onClick={onJoin}>
        들어가기
      </button>
      <button className="button" type="button" onClick={onCancel}>
        취소
      </button>
    </>
  )
}
