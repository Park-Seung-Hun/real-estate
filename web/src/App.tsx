import { useEffect, useState } from 'react'
import { BrowserRouter, Route, Routes } from 'react-router'
import { getMe, isUnauthenticated, logout } from './api/client'
import { LoginFailedPage } from './pages/LoginFailedPage'
import { LoginPage } from './pages/LoginPage'
import { NoHouseholdPage } from './pages/NoHouseholdPage'
import { ReauthFailedPage } from './pages/ReauthFailedPage'
import { RetryPage } from './pages/RetryPage'
import { WithdrawPage } from './pages/WithdrawPage'
import './styles/app.css'

type Session = 'loading' | 'anonymous' | 'user' | 'error'

export function App() {
  const [session, setSession] = useState<Session>('loading')

  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    getMe().then(
      () => active && setSession('user'),
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

  // 401이면 로그인 화면, 그 밖의 오류는 가구 없음 화면을 그대로 둔다.
  const onLogout = () =>
    logout().then(
      () => setSession('anonymous'),
      (error) => isUnauthenticated(error) && setSession('anonymous'),
    )

  const home =
    session === 'user' ? (
      <NoHouseholdPage onLogout={onLogout} />
    ) : session === 'anonymous' ? (
      <LoginPage />
    ) : session === 'error' ? (
      <RetryPage onRetry={retry} />
    ) : null

  return (
    <BrowserRouter>
      <main className="page">
        <Routes>
          <Route path="/login-failed" element={<LoginFailedPage />} />
          <Route path="/reauth-failed" element={<ReauthFailedPage />} />
          {/* 가구 화면이 생기기 전까지 로그인한 모든 경로는 가구 없음 화면이다. */}
          <Route path="/withdraw" element={session === 'user' ? <WithdrawPage onGone={() => setSession('anonymous')} /> : home} />
          <Route path="*" element={home} />
        </Routes>
      </main>
    </BrowserRouter>
  )
}
