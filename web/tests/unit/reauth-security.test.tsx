/**
 * T054 재인증 보안 경계 테스트(spec US2-3, FR-012, 결정 기록 0013). 구현과 다른 쪽이 요구사항에서 썼다.
 *
 * 공개 표면은 login.test.tsx 맨 위 주석과 reauth.test.tsx 맨 위 주석과 같다.
 * 재인증에서 돌아오는 것은 서버가 returnTo=/withdraw 로 보내 문서를 새로 여는 것이므로,
 * 테스트는 `/withdraw`에서 `App`을 새로 마운트해 흉내 낸다.
 * 001은 브라우저 저장소에 둘 것이 없으므로(세션은 HttpOnly 쿠키) localStorage, sessionStorage,
 * JS로 읽는 쿠키가 비어 있어야 한다.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../src/App'
import { type ApiCall, me, mockApi, noContent, noHousehold, type Reply, settle, unauthenticated, watchNavigation } from './api-mock'

const WITHDRAW = '탈퇴'
const WITHDRAW_NOTICE = '탈퇴하면 계정, 로그인 이메일, 표시 이름과 모든 기기의 로그인 상태가 삭제되며 되돌릴 수 없습니다.'
const REAUTH_NOTICE = '탈퇴하려면 구글 계정을 한 번 더 확인해야 합니다.'
const REAUTH = '구글 계정 확인'
const LOGIN = 'Google 계정으로 로그인'

/** contracts/openapi.yaml `DELETE /api/v1/me` 401 예시 reauthRequired. */
const reauthRequired: Reply = {
  status: 401,
  body: {
    type: 'https://example.com/problems/reauth-required',
    title: '구글 계정 확인이 필요합니다',
    status: 401,
    detail: '구글 계정을 한 번 더 확인한 뒤 다시 시도해 주세요.',
    code: 'REAUTH_REQUIRED',
  },
}

let navigation: ReturnType<typeof watchNavigation>
let api: ReturnType<typeof mockApi>

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  navigation = watchNavigation()
})

afterEach(async () => {
  cleanup()
  await navigation.stop()
  vi.unstubAllGlobals()
  expect(navigation.attempts).toEqual([])
  expect(api.unmatched).toEqual([])
  for (const call of api.calls) {
    expect(call.sameOrigin).toBe(true)
    expect([undefined, 'same-origin']).toContain(call.credentials)
  }
})

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  render(<App />)
}

function deleteCalls() {
  return api.calls.filter((call) => call.method === 'DELETE' && call.path === '/api/v1/me')
}

/** 처음 DELETE 는 REAUTH_REQUIRED, 재인증 뒤(reauthed=true)의 DELETE 는 204 와 로그인 상태 없음. */
function server() {
  const state = { reauthed: false, session: true }
  const handler = (call: ApiCall) => {
    if (call.method === 'GET' && call.path === '/api/v1/me') return state.session ? me : unauthenticated
    if (call.method === 'GET' && call.path === '/api/v1/household') return noHousehold
    if (call.method === 'DELETE' && call.path === '/api/v1/me') {
      if (!state.reauthed) return reauthRequired
      state.session = false
      return noContent
    }
    return undefined
  }
  return { state, handler }
}

/** 브라우저 저장소, 화면 주소, 재인증 링크의 returnTo 에 밀린 동작이 없는지 본다. */
function expectNothingPending() {
  expect(localStorage.length).toBe(0)
  expect(sessionStorage.length).toBe(0)
  expect(document.cookie).toBe('')
  expect(window.location.pathname).toBe('/withdraw')
  expect(window.location.search).toBe('')
  expect(window.location.hash).toBe('')
}

describe('재인증 전후의 밀린 동작 (US2-3, FR-012)', () => {
  it('US2-3: 재인증 안내를 받은 뒤 구글 계정 확인을 누르기 전, 저장소와 주소와 returnTo 에 탈퇴가 남지 않는다', async () => {
    const { handler } = server()
    api = mockApi(handler)
    openAt('/withdraw')
    await screen.findByText(WITHDRAW_NOTICE)
    fireEvent.click(screen.getByRole('button', { name: WITHDRAW }))
    await screen.findByText(REAUTH_NOTICE)
    const link = screen.getByRole('link', { name: REAUTH })
    await settle()
    expectNothingPending()
    const url = new URL(link.getAttribute('href') ?? '', window.location.origin)
    expect(url.searchParams.get('returnTo')).toBe('/withdraw')
    expect([...url.searchParams.keys()].sort()).toEqual(['purpose', 'returnTo'])
    expect(deleteCalls()).toHaveLength(1)
  })

  it('US2-3: 재인증에서 돌아온 탈퇴 확인 화면은 탈퇴를 자동으로 보내지 않고, 다시 누를 때만 보낸다', async () => {
    const { state, handler } = server()
    api = mockApi(handler)
    openAt('/withdraw')
    await screen.findByText(WITHDRAW_NOTICE)
    fireEvent.click(screen.getByRole('button', { name: WITHDRAW }))
    await screen.findByText(REAUTH_NOTICE)
    await settle()
    expect(deleteCalls()).toHaveLength(1)

    // 구글에서 돌아옴: 서버가 /withdraw 로 보내 문서가 새로 열린다
    cleanup()
    state.reauthed = true
    const mesBefore = api.calls.filter((call) => call.method === 'GET' && call.path === '/api/v1/me').length
    openAt('/withdraw')
    await screen.findByText(WITHDRAW_NOTICE)
    await settle()
    expect(api.calls.filter((call) => call.method === 'GET' && call.path === '/api/v1/me').length).toBeGreaterThan(
      mesBefore,
    )
    expect(deleteCalls()).toHaveLength(1)
    expect(screen.queryByText(REAUTH_NOTICE)).toBeNull()
    expectNothingPending()

    // 사용자가 같은 버튼을 다시 누르면 그때 한 번 보낸다
    fireEvent.click(screen.getByRole('button', { name: WITHDRAW }))
    await screen.findByRole('link', { name: LOGIN })
    await settle()
    expect(deleteCalls()).toHaveLength(2)
  })

  it.each([
    '/withdraw?confirm=1',
    '/withdraw?action=withdraw&confirm=true',
    '/withdraw?purpose=reauth',
    '/withdraw#confirm',
  ])('FR-012, 결정 기록 0013: 다른 사이트의 링크로 %s 를 열어도 탈퇴를 보내지 않는다', async (path) => {
    const { state, handler } = server()
    state.reauthed = true
    api = mockApi(handler)
    openAt(path)
    await screen.findByText(WITHDRAW_NOTICE)
    await settle()
    expect(deleteCalls()).toEqual([])
    screen.getByRole('button', { name: WITHDRAW })
  })
})
