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

  // 로그인 세대. 로그인 화면으로 바꾸는 자리만 올린다. 그 전에 보낸 요청의 응답은 앱 상태를 바꾸지 못하고,
  // 가구 상태가 바뀌어도 그대로라 같은 로그인 세션의 늦은 401은 언제 와도 로그인 화면으로 보낸다.
  const generation = useRef(0)
  const snapshot = () => {
    const started = generation.current
    return () => started === generation.current
  }

  const goAnonymous = () => {
    generation.current++
    setSession('anonymous')
  }

  // 가구 상태는 목록 조회의 응답으로만 정하고, 가장 나중에 보낸 목록 요청의 응답만 반영한다. 다른 요청의 204, 403, 404는
  // 목록을 다시 읽으라는 신호일 뿐이라, 늦게 온 옛 응답이 새 가구 화면이나 로그인 화면을 덮지 못한다.
  // 첫 조회의 403은 경로를 그대로 둔다(가구 없는 사람이 /withdraw를 새로 열 수 있게). 그 뒤의 403만 /로 보낸다(FR-019).
  const listSeq = useRef(0)
  const loadHousehold = (first = false) => {
    const mine = ++listSeq.current
    const same = snapshot()
    const current = () => mine === listSeq.current && same()
    return getHousehold().then(
      (list) => {
        if (!current()) return
        setMembers(list.members)
        setSession('household')
      },
      (error) => {
        // 401은 순번과 관계없이 같은 로그인 세션이면 로그인 화면으로 보낸다. 순번은 가구 상태를 정하는 응답에만 쓴다.
        if (isUnauthenticated(error)) same() && goAnonymous()
        else if (!current()) return
        else if (isNoHousehold(error)) {
          setSession('noHousehold')
          if (!first) navigate('/')
        } else setSession('error')
      },
    )
  }
  const reload = () => loadHousehold()

  // 가구 삭제, 나가기, 만들기, 화면을 떠난 뒤 온 들어가기 성공 뒤에는 어느 경로에 있었든 첫 화면에서 목록을 다시 읽는다(US1-2).
  // 다시 읽는 동안은 확인 중으로 두어, 지운 가구의 옛 목록과 버튼이 잠깐 다시 그려지지 않게 한다.
  const goHome = () => {
    // 이미 첫 화면이면 확인 중으로 두지 않는다. 첫 화면에서 받은 늦은 신호가 방금 만든 링크와 입력값을 지우지 않게.
    // 늦은 콜백에서도 불리므로 렌더 때 값이 아니라 부르는 순간의 주소를 읽는다.
    if (window.location.pathname !== '/') setSession('loading')
    navigate('/')
    loadHousehold()
  }

  useEffect(() => {
    let active = true
    getMe().then(
      () => {
        if (active) loadHousehold(true)
      },
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

  // 409 HAS_HOUSEHOLD는 다른 탭에서 이미 만든 것이므로 만든 것과 같이 첫 화면에서 목록을 다시 읽는다.
  const onCreate = () => {
    const same = snapshot()
    createHousehold().then(
      () => same() && goHome(),
      (error) => {
        if (!same()) return
        if (isHasHousehold(error)) goHome()
        else if (isUnauthenticated(error)) goAnonymous()
        else setSession('error')
      },
    )
  }

  const home =
    session === 'household' ? (
      <HomePage
        members={members}
        snapshot={snapshot}
        onLogout={onLogout}
        onReload={reload}
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
          element={
            loggedIn ? (
              <InvitePage
                key={inviteKey}
                snapshot={snapshot}
                onJoined={onJoined}
                onJoinedAway={goHome}
                onUnauthenticated={onUnauthenticated}
              />
            ) : (
              home
            )
          }
        />
        <Route
          path="/household/leave"
          element={
            session === 'household' && members.some((m) => m.me && m.role === 'member') ? (
              <LeaveHouseholdPage snapshot={snapshot} onLeft={goHome} onUnauthenticated={onUnauthenticated} />
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
                onDeleted={goHome}
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
