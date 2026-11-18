import { useEffect, useState } from 'react'
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router'
import { createHousehold, getHousehold, getMe, isHasHousehold, isNoHousehold, isUnauthenticated, logout } from './api/client'
import type { Member } from './api/client'
import { LoginFailedPage } from './pages/LoginFailedPage'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'
import { NoHouseholdPage } from './pages/NoHouseholdPage'
import { ReauthFailedPage } from './pages/ReauthFailedPage'
import { RetryPage } from './pages/RetryPage'
import { WithdrawPage } from './pages/WithdrawPage'
import './styles/app.css'

type Session = 'loading' | 'anonymous' | 'noHousehold' | 'household' | 'error'

export function App() {
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

  // 가구 요청이 403 NO_HOUSEHOLD를 받으면 가구 없음 화면으로 간다(FR-019). 이후 가구 화면이 같은 함수를 쓴다.
  const onNoHousehold = () => {
    setSession('noHousehold')
    navigate('/')
  }

  // 목록을 읽어 가구 있음이나 가구 없음으로 정한다. 호출한 쪽이 이미 사라졌으면 active로 건너뛴다.
  // 첫 조회의 403은 경로를 그대로 둔다(가구 없는 사람이 /withdraw를 새로 열 수 있게). 화면을 그린 뒤의 403만 /로 보낸다(FR-019).
  const loadHousehold = (active: () => boolean = () => true, onMissing = () => setSession('noHousehold')) =>
    getHousehold().then(
      (list) => {
        if (!active()) return
        setMembers(list.members)
        setSession('household')
      },
      (error) => active() && (isNoHousehold(error) ? onMissing() : setSession('error')),
    )

  useEffect(() => {
    let active = true
    getMe().then(
      () => loadHousehold(() => active),
      (error) => active && setSession(isUnauthenticated(error) ? 'anonymous' : 'error'),
    )
    return () => {
      active = false
    }
  }, [attempt])

  const retry = () => {
    setSession('loading')
    setAttempt((n) => n + 1)
  }

  // 401이면 로그인 화면, 그 밖의 오류는 지금 화면을 그대로 둔다.
  const onLogout = () =>
    logout().then(
      () => setSession('anonymous'),
      (error) => isUnauthenticated(error) && setSession('anonymous'),
    )

  // 409 HAS_HOUSEHOLD는 다른 탭에서 이미 만든 것이므로 목록을 다시 읽는다.
  const onCreate = () =>
    createHousehold().then(
      () => loadHousehold(undefined, onNoHousehold),
      (error) =>
        isHasHousehold(error)
          ? loadHousehold(undefined, onNoHousehold)
          : setSession(isUnauthenticated(error) ? 'anonymous' : 'error'),
    )

  const home =
    session === 'household' ? (
      <HomePage members={members} onLogout={onLogout} />
    ) : session === 'noHousehold' ? (
      <NoHouseholdPage onCreate={onCreate} onLogout={onLogout} />
    ) : session === 'anonymous' ? (
      <LoginPage />
    ) : session === 'error' ? (
      <RetryPage onRetry={retry} />
    ) : null

  const loggedIn = session === 'household' || session === 'noHousehold'

  return (
    <main className="page">
      <Routes>
        <Route path="/login-failed" element={<LoginFailedPage />} />
        <Route path="/reauth-failed" element={<ReauthFailedPage />} />
        <Route path="/withdraw" element={loggedIn ? <WithdrawPage onGone={() => setSession('anonymous')} /> : home} />
        <Route path="*" element={home} />
      </Routes>
    </main>
  )
}
