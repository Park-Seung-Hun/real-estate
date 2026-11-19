import { useEffect, useRef, useState } from 'react'
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router'
import {
  createHousehold,
  getHousehold,
  getMe,
  INVITE_TOKEN_KEY,
  isHasHousehold,
  isNoHousehold,
  isUnauthenticated,
  logout,
} from './api/client'
import type { Member } from './api/client'
import { DeleteHouseholdPage } from './pages/DeleteHouseholdPage'
import { LeaveHouseholdPage } from './pages/LeaveHouseholdPage'
import { LoginFailedPage } from './pages/LoginFailedPage'
import { HomePage } from './pages/HomePage'
import { InvitePage } from './pages/InvitePage'
import { LoginPage } from './pages/LoginPage'
import { NoHouseholdPage } from './pages/NoHouseholdPage'
import { ReauthFailedPage } from './pages/ReauthFailedPage'
import { RetryPage } from './pages/RetryPage'
import { WithdrawPage } from './pages/WithdrawPage'
import './styles/app.css'

type Session = 'loading' | 'anonymous' | 'noHousehold' | 'household' | 'error'

// /invite#토큰이면 토큰을 탭 저장소로 옮기고 주소에서 # 뒤를 지운다(research R7). 옮겼으면 true.
function stashInviteToken() {
  const { pathname, hash, search } = window.location
  const path = pathname.replace(/(.)\/$/, '$1')
  if (path !== '/invite' || hash.length <= 1) return false
  sessionStorage.setItem(INVITE_TOKEN_KEY, hash.slice(1))
  window.history.replaceState(window.history.state, '', path + search)
  return true
}

export function App() {
  // 라우터가 그리기 전에 한 번, 로그인 상태와 관계없이 처리한다.
  useState(stashInviteToken)
  return (
    <BrowserRouter>
      <Shell />
    </BrowserRouter>
  )
}

function Shell() {
  const navigate = useNavigate()
  const [session, setSession] = useState<Session>('loading')
  const [members, setMembers] = useState<Member[]>([])

  const [attempt, setAttempt] = useState(0)
  // 같은 문서에서 /invite#새토큰을 다시 열면(hashchange) 초대 확인 화면을 새 토큰으로 다시 시작한다.
  const [inviteKey, setInviteKey] = useState(0)

  useEffect(() => {
    const onHashChange = () => stashInviteToken() && setInviteKey((n) => n + 1)
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  // 로그인 없음이나 가구 없음으로 바꾸는 자리는 모두 세대를 올린다. 그 전에 보낸 요청의 늦은 응답은 앱 상태를 바꾸지 못한다.
  const generation = useRef(0)
  const snapshot = () => {
    const started = generation.current
    return () => started === generation.current
  }

  // 가구 요청이 403 NO_HOUSEHOLD를 받으면 가구 없음 화면으로 간다(FR-019). 이후 가구 화면이 같은 함수를 쓴다.
  const onNoHousehold = () => {
    generation.current++
    setSession('noHousehold')
    navigate('/')
  }

  const goAnonymous = () => {
    generation.current++
    setSession('anonymous')
  }

  // 목록을 읽어 가구 있음이나 가구 없음으로 정한다. 호출한 쪽이 이미 사라졌으면 active로 건너뛴다.
  // 첫 조회의 403은 경로를 그대로 둔다(가구 없는 사람이 /withdraw를 새로 열 수 있게). 화면을 그린 뒤의 403만 /로 보낸다(FR-019).
  const loadHousehold = (
    active: () => boolean = () => true,
    onMissing = () => {
      generation.current++
      setSession('noHousehold')
    },
  ) => {
    const same = snapshot()
    const current = () => active() && same()
    return getHousehold().then(
      (list) => {
        if (!current()) return
        setMembers(list.members)
        setSession('household')
      },
      (error) => {
        if (!current()) return
        if (isUnauthenticated(error)) goAnonymous()
        else if (isNoHousehold(error)) onMissing()
        else setSession('error')
      },
    )
  }

  useEffect(() => {
    let active = true
    getMe().then(
      () => loadHousehold(() => active),
      (error) => active && (isUnauthenticated(error) ? goAnonymous() : setSession('error')),
    )
    return () => {
      active = false
    }
  }, [attempt])

  const retry = () => {
    setSession('loading')
    setAttempt((n) => n + 1)
  }

  const onUnauthenticated = goAnonymous

  // 401이면 로그인 화면, 그 밖의 오류는 지금 화면을 그대로 둔다.
  const onLogout = () =>
    logout().then(
      goAnonymous,
      (error) => isUnauthenticated(error) && goAnonymous(),
    )

  // 409 HAS_HOUSEHOLD는 다른 탭에서 이미 만든 것이므로 목록을 다시 읽는다.
  const onCreate = () =>
    createHousehold().then(
      () => loadHousehold(undefined, onNoHousehold),
      (error) =>
        isHasHousehold(error)
          ? loadHousehold(undefined, onNoHousehold)
          : isUnauthenticated(error)
            ? goAnonymous()
            : setSession('error'),
    )

  const home =
    session === 'household' ? (
      <HomePage
        members={members}
        snapshot={snapshot}
        onLogout={onLogout}
        onReload={() => loadHousehold(undefined, onNoHousehold)}
        onNoHousehold={onNoHousehold}
        onUnauthenticated={onUnauthenticated}
      />
    ) : session === 'noHousehold' ? (
      <NoHouseholdPage onCreate={onCreate} onLogout={onLogout} />
    ) : session === 'anonymous' ? (
      <LoginPage />
    ) : session === 'error' ? (
      <RetryPage onRetry={retry} />
    ) : null

  // 들어가기가 성공하면 /로 가서 내 정보와 목록을 다시 읽는다.
  const onJoined = () => {
    retry()
    navigate('/')
  }

  const loggedIn = session === 'household' || session === 'noHousehold'

  return (
    <main className="page">
      <Routes>
        <Route path="/login-failed" element={<LoginFailedPage />} />
        <Route path="/reauth-failed" element={<ReauthFailedPage />} />
        <Route path="/withdraw" element={loggedIn ? <WithdrawPage onGone={goAnonymous} /> : home} />
        <Route
          path="/invite"
          element={loggedIn ? <InvitePage key={inviteKey} onJoined={onJoined} onUnauthenticated={onUnauthenticated} /> : home}
        />
        <Route
          path="/household/leave"
          element={
            session === 'household' && members.some((m) => m.me && m.role === 'member') ? (
              <LeaveHouseholdPage snapshot={snapshot} onLeft={onNoHousehold} onUnauthenticated={onUnauthenticated} />
            ) : (
              home
            )
          }
        />
        <Route
          path="/household/delete"
          element={
            session === 'household' && members.some((m) => m.me && m.role === 'owner') ? (
              <DeleteHouseholdPage
                memberCount={members.length}
                snapshot={snapshot}
                onDeleted={onNoHousehold}
                onUnauthenticated={onUnauthenticated}
              />
            ) : (
              home
            )
          }
        />
        <Route path="*" element={home} />
      </Routes>
    </main>
  )
}
