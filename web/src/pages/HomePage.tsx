import { useEffect, useRef, useState } from 'react'
import {
  cancelInvitation,
  createInvitation,
  errorCode,
  errorStatus,
  isNoHousehold,
  isUnauthenticated,
  listInvitations,
} from '../api/client'
import type { Invitation, Member } from '../api/client'

type Handlers = { onReload: () => void; onNoHousehold: () => void; onUnauthenticated: () => void }

export function HomePage({ members, onLogout, ...handlers }: { members: Member[]; onLogout: () => void } & Handlers) {
  return (
    <>
      <h2>함께 보는 사람</h2>
      <ul>
        {members.map((m) => (
          <li key={m.userId}>
            {m.displayName && (
              <>
                {m.displayName}
                {m.me && ' (나)'} {m.role}{' '}
              </>
            )}
            {!m.displayName && (
              <>
                {m.me && '(나) '}
                {m.role}{' '}
              </>
            )}
            {m.loginEmail}
            {!m.emailVerified && ' 검증되지 않음'}
          </li>
        ))}
      </ul>
      {members.some((m) => m.me && m.role === 'owner') && <InvitationArea members={members} {...handlers} />}
      <button className="button" type="button" onClick={onLogout}>
        로그아웃
      </button>
    </>
  )
}

// "M월 D일"은 실행 환경 시간대에 기대지 않고 Asia/Seoul로 얻는다.
const seoulDay = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric' })
const monthDay = (iso: string) => {
  const part = (type: string) => seoulDay.formatToParts(new Date(iso)).find((p) => p.type === type)?.value
  return `${part('month')}월 ${part('day')}일`
}

function InvitationArea({ members, onReload, onNoHousehold, onUnauthenticated }: { members: Member[] } & Handlers) {
  const [invitations, setInvitations] = useState<Invitation[]>([])
  // 만든 직후의 링크. 이 컴포넌트의 상태에만 두고 어디에도 저장하지 않는다.
  const [link, setLink] = useState<{ id: number; url: string } | null>(null)
  const [days, setDays] = useState('3')
  // 마지막 응답의 안내만 보인다(research R13)
  const [notice, setNotice] = useState<'reauth' | 'retry' | null>(null)
  const sending = useRef(false)
  // 늦게 온 앞선 목록 응답이 발급이나 취소 결과, 더 나중 요청의 결과를 덮지 않게 요청마다 순번을 둔다.
  const latest = useRef(0)

  const fail = (error: unknown) => {
    if (isUnauthenticated(error)) onUnauthenticated()
    else if (isNoHousehold(error)) onNoHousehold()
    else setNotice('retry')
  }

  const reloadInvitations = () => {
    const mine = ++latest.current
    return listInvitations().then(
      (list) => mine === latest.current && setInvitations(list.invitations),
      (error) => mine === latest.current && fail(error),
    )
  }

  useEffect(() => {
    reloadInvitations()
  }, [])

  // 409(와 사라진 초대의 404)는 다른 탭에서 바뀐 것이므로 두 목록을 다시 읽는다.
  const reload = () => {
    onReload()
    reloadInvitations()
  }

  const onCreate = () => {
    if (sending.current) return
    sending.current = true
    setNotice(null)
    createInvitation(Number(days)).then(
      (created) => {
        sending.current = false
        setLink({ id: created.id, url: `${window.location.origin}/invite#${created.token}` })
        latest.current++
        setInvitations([
          { id: created.id, createdAt: created.createdAt, expiresAt: created.expiresAt, remainingDays: Number(days) },
        ])
      },
      (error) => {
        sending.current = false
        if (errorStatus(error) === 409) reload()
        else if (errorCode(error) === 'REAUTH_REQUIRED') setNotice('reauth')
        else fail(error)
      },
    )
  }

  const onCancel = (id: number) => {
    setNotice(null)
    cancelInvitation(id).then(
      () => {
        latest.current++
        setLink(null)
        setInvitations((list) => list.filter((i) => i.id !== id))
      },
      (error) => (errorStatus(error) === 409 || errorStatus(error) === 404 ? reload() : fail(error)),
    )
  }

  return (
    <>
      {invitations.length > 0 && (
        <>
          <h2>보낸 초대</h2>
          <ul>
            {invitations.map((i) => (
              <li key={i.id}>
                {link?.id === i.id ? (
                  <>
                    <p>{link.url}</p>
                    <button className="button" type="button" onClick={() => navigator.clipboard.writeText(link.url)}>
                      복사
                    </button>
                    <p>이 화면을 떠나면 링크를 다시 볼 수 없습니다. 가족에게만 보내세요.</p>
                    <p>방금 만듦</p>
                  </>
                ) : (
                  <p>{`${monthDay(i.createdAt)} 만듦, ${i.remainingDays}일 남음`}</p>
                )}
                <button className="button" type="button" onClick={() => onCancel(i.id)}>
                  취소
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      {members.length >= 2 ? (
        <p>구성원이 둘이라 새 초대를 만들 수 없습니다.</p>
      ) : invitations.length > 0 ? (
        <p>기다리는 초대가 있어 새 초대를 만들 수 없습니다.</p>
      ) : (
        <>
          <label>
            만료 기간
            <select value={days} onChange={(e) => setDays(e.target.value)}>
              {[1, 2, 3, 4, 5, 6, 7].map((n) => (
                <option key={n} value={n}>
                  {n}일
                </option>
              ))}
            </select>
          </label>
          <button className="button" type="button" onClick={onCreate}>
            초대 링크 만들기
          </button>
          <p>만든 링크를 복사해 메신저나 메일로 보냅니다.</p>
        </>
      )}
      {notice === 'retry' && <p>잠시 후 다시 시도해 주세요.</p>}
      {notice === 'reauth' && (
        <>
          <p>초대 링크를 만들려면 구글 계정을 한 번 더 확인해야 합니다.</p>
          <a className="button" href="/oauth2/authorization/google?purpose=reauth&returnTo=/">
            구글 계정 확인
          </a>
        </>
      )}
    </>
  )
}
