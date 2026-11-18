/**
 * T042 로그인, 가구 없음, 로그인 실패 화면 테스트(spec US1, FR-002, FR-005, FR-007, FR-010, research R7, R13, 결정 기록 0013).
 *
 * 구현이 따라야 할 공개 표면(뒤 단계의 withdraw, reauth 화면 테스트도 같은 방식을 쓴다):
 * - 진입 컴포넌트는 `web/src/App.tsx`의 이름 있는 내보내기 `App`(props 없음)이다. 라우터를 안에 둔다.
 *   `web/src/main.tsx`는 `<App />`을 #root에 그리기만 한다.
 * - 라우터는 `App`이 마운트될 때 그때의 `window.location`에서 시작한다(BrowserRouter 방식).
 *   모듈을 불러올 때 라우터를 만들거나(모듈 수준 createBrowserRouter) 로그인 상태를 모듈 변수에 남겨,
 *   다음 마운트에 이어지면 안 된다. 테스트는 `history.replaceState`로 경로를 정한 뒤 `render(<App />)`한다.
 * - 자료는 전역 `fetch`로 같은 출처 상대 주소(`/api/v1/...`)에서 받고, credentials는 기본값이나 'same-origin'이다.
 * - 로그인 버튼은 `<a href="/oauth2/authorization/google[?returnTo=...]">` 링크다. 그 밖의 화면 이동(로그아웃 뒤 등)은
 *   문서 이동 없이 화면 안에서 한다. 화면을 여는 것만으로 문서 이동(location 대입, 링크 자동 클릭 등)을 하지 않는다.
 * - 문구는 research R13 확정 문구 그대로다.
 */
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../src/App'
import {
  type ApiCall,
  me,
  mockApi,
  networkError,
  noContent,
  noHousehold,
  originRejected,
  type Reply,
  serverError,
  settle,
  unauthenticated,
  watchNavigation,
} from './api-mock'

const SERVICE_NAME = '서초댁 임장 기록'
const LOGIN = 'Google 계정으로 로그인'
const INVITE_GUIDE = '가족에게 받은 초대 링크로 들어왔다면 로그인하면 바로 수락됩니다.'
const NO_HOUSEHOLD = '아직 가구가 없습니다.'
const CREATE_GUIDE = '가족과 함께 볼 기록을 모으려면 가구를 만드세요.'
const INVITE_LINK_GUIDE = '가족에게 받은 초대 링크가 있으면 그 링크를 여세요.'
const CREATE = '가구 만들기'
const LOGOUT = '로그아웃'
const WITHDRAW = '탈퇴'
const LOGIN_FAILED = '로그인하지 못했습니다. 다시 시도해 주세요.'
const LOGIN_START = '/oauth2/authorization/google'
const REAUTH_FAILED = '구글 계정 확인에 실패했습니다. 다시 시도해 주세요.'
const RETRY_GUIDE = '잠시 후 다시 시도해 주세요.'
const RETRY = '다시 시도'

let navigation: ReturnType<typeof watchNavigation>
let api: ReturnType<typeof mockApi>

beforeEach(() => {
  navigation = watchNavigation()
})

afterEach(async () => {
  cleanup()
  await navigation.stop()
  vi.unstubAllGlobals()
  // 모든 테스트 공통: 화면은 문서 이동을 하지 않고, 흉내 낸 요청만 같은 출처로 보낸다.
  expect(navigation.attempts).toEqual([])
  expect(api.unmatched).toEqual([])
  for (const call of api.calls) {
    expect(call.sameOrigin).toBe(true)
    expect([undefined, 'same-origin']).toContain(call.credentials)
  }
})

const loggedOut = (call: ApiCall) =>
  call.method === 'GET' && call.path === '/api/v1/me' ? unauthenticated : undefined

const loggedIn = (call: ApiCall) => {
  if (call.method === 'GET' && call.path === '/api/v1/me') return me
  if (call.method === 'GET' && call.path === '/api/v1/household') return noHousehold
  return undefined
}

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  render(<App />)
}

/** 로그인 화면이면 로그인 링크를 돌려준다. 서비스 이름, 로그인 링크, 초대 안내 외의 버튼과 링크가 없어야 한다. */
async function expectLoginScreen() {
  const login = await screen.findByRole('link', { name: LOGIN })
  screen.getByText(SERVICE_NAME)
  screen.getByText(INVITE_GUIDE)
  expect([...screen.queryAllByRole('link'), ...screen.queryAllByRole('button')]).toEqual([login])
  expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
  expect(screen.queryByText(/탈퇴/)).toBeNull()
  return login
}

/** 로그인 시작 주소이고 질의가 returnTo 하나(또는 없음)인지 본다. 값은 URL 해석 뒤(퍼센트 인코딩을 푼 뒤) 비교한다. */
function expectLoginHref(link: HTMLElement, returnTo: string | null) {
  const url = new URL(link.getAttribute('href') ?? '', window.location.origin)
  expect(url.origin).toBe(window.location.origin)
  expect(url.pathname).toBe(LOGIN_START)
  if (returnTo === null) expect(url.search).toBe('')
  else expect([...url.searchParams]).toEqual([['returnTo', returnTo]])
}

/** 버튼 또는 화면 이동 링크(탈퇴 확인 화면으로 가는 컨트롤)를 찾는다. */
function getControl(name: string) {
  return screen.queryByRole('button', { name }) ?? screen.getByRole('link', { name })
}

describe('로그인 상태가 없을 때', () => {
  it.each(['/', '/?x=1', '/withdraw', '/withdraw?x=1&y=2', '/no-household', '/not-a-screen'])(
    '%s 에서 로그인 화면의 세 요소만 보인다 (US1-1, FR-002)',
    async (path) => {
      api = mockApi(loggedOut)
      openAt(path)
      await expectLoginScreen()
    },
  )

  it.each([
    ['/', null],
    ['/?x=1', null], // R7의 "현재 경로가 /"는 경로만 보고 질의는 보지 않는다
    ['/withdraw', '/withdraw'],
    ['/withdraw?x=1&y=2', '/withdraw?x=1&y=2'],
    ['/no-household', '/no-household'],
  ])('%s 의 로그인 링크는 returnTo=%s (US1-7, FR-005, R7)', async (path, returnTo) => {
    api = mockApi(loggedOut)
    openAt(path)
    expectLoginHref(await expectLoginScreen(), returnTo)
  })

  it.each(['/', '/withdraw', '/login-failed'])(
    '%s 를 열기만 해서는 로그인 시작 주소로 가지 않는다 (결정 기록 0013)',
    async (path) => {
      api = mockApi(loggedOut)
      const before = new URL(path, window.location.origin).href
      openAt(path)
      await screen.findByRole('link', { name: LOGIN })
      await settle()
      expect(window.location.href).toBe(before)
      expect(api.calls.filter((call) => !call.path.startsWith('/api/'))).toEqual([])
      expect(navigation.attempts).toEqual([])
    },
  )
})

describe('로그인 실패 화면', () => {
  it.each(['/login-failed', '/login-failed/'])(
    '/me 가 401 이어도 %s 는 실패 문구와 returnTo 없는 로그인 링크를 보인다 (US1-4, FR-007)',
    async (path) => {
      api = mockApi(loggedOut)
      openAt(path)
      await screen.findByText(LOGIN_FAILED)
      expectLoginHref(screen.getByRole('link', { name: LOGIN }), null)
    },
  )
})

describe('재인증 실패 화면의 로그인 링크', () => {
  // 화면 문구와 버튼은 T056이 본다. 여기서는 R7의 returnTo 규칙만 본다. 버튼 이름이 R13에 없어 주소로 찾는다.
  it.each(['/reauth-failed', '/reauth-failed/'])('/me 가 401 일 때 %s 의 로그인 링크에는 질의가 없다 (R7)', async (path) => {
    api = mockApi(loggedOut)
    openAt(path)
    await screen.findByText(REAUTH_FAILED)
    const links = screen
      .queryAllByRole('link')
      .map((link) => new URL(link.getAttribute('href') ?? '', window.location.origin))
      .filter((url) => url.pathname === LOGIN_START)
    expect(links.length).toBeGreaterThan(0)
    for (const url of links) expect(url.search).toBe('')
  })
})

describe('내 정보 조회가 401도 200도 아닌 오류일 때 (R13)', () => {
  it.each([
    { path: '/', label: '500', reply: serverError },
    { path: '/withdraw', label: '500', reply: serverError },
    { path: '/no-household', label: '500', reply: serverError },
    { path: '/', label: '네트워크 끊김', reply: networkError },
    { path: '/withdraw', label: '네트워크 끊김', reply: networkError },
  ])('$path 에서 $label 이면 다시 시도 안내와 버튼만 보이고 스스로 다시 부르지 않는다', async ({ path, reply }) => {
    api = mockApi((call) => (call.method === 'GET' && call.path === '/api/v1/me' ? reply : undefined))
    openAt(path)
    await screen.findByText(RETRY_GUIDE)
    screen.getByRole('button', { name: RETRY })
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    await settle()
    expect(meCalls()).toBe(1)
  })

  it('다시 시도를 누르면 /me 를 다시 부르고, 200 이면 가구 없음 화면이다', async () => {
    let first = true
    api = mockApi((call) => {
      if (call.method === 'GET' && call.path === '/api/v1/household') return noHousehold
      if (call.method !== 'GET' || call.path !== '/api/v1/me') return undefined
      const reply = first ? serverError : me
      first = false
      return reply
    })
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: RETRY }))
    await screen.findByText(NO_HOUSEHOLD)
    expect(meCalls()).toBe(2)
    expect(screen.queryByText(RETRY_GUIDE)).toBeNull()
  })
})

function meCalls() {
  return api.calls.filter((call) => call.method === 'GET' && call.path === '/api/v1/me').length
}

describe('가구 없음 화면', () => {
  it.each(['/', '/no-household'])('%s 에서 안내 셋과 가구 만들기, 로그아웃, 탈퇴 버튼이 보인다 (US1-8, FR-010)', async (path) => {
    api = mockApi(loggedIn)
    openAt(path)
    await screen.findByText(NO_HOUSEHOLD)
    screen.getByText(CREATE_GUIDE)
    screen.getByText(INVITE_LINK_GUIDE)
    screen.getByRole('button', { name: CREATE })
    screen.getByRole('button', { name: LOGOUT })
    getControl(WITHDRAW)
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
  })

  it('로그아웃을 누르면 POST /api/v1/logout 뒤 로그인 화면이 보인다 (US1-5)', async () => {
    let session = true
    api = mockApi((call) => {
      if (call.method === 'GET' && call.path === '/api/v1/me') return session ? me : unauthenticated
      if (call.method === 'GET' && call.path === '/api/v1/household') return noHousehold
      if (call.method === 'POST' && call.path === '/api/v1/logout') {
        session = false
        return noContent
      }
      return undefined
    })
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: LOGOUT }))
    await expectLoginScreen()
    const logouts = api.calls.filter((call) => call.path === '/api/v1/logout')
    expect(logouts.map((call) => call.method)).toEqual(['POST'])
    expect(logouts[0].sameOrigin).toBe(true)
    expect([undefined, 'same-origin']).toContain(logouts[0].credentials)
  })

  it('로그아웃 요청이 401 이면 로그인 화면이다 (R13)', async () => {
    let session = true
    api = mockApi((call) => {
      if (call.method === 'GET' && call.path === '/api/v1/me') return session ? me : unauthenticated
      if (call.method === 'GET' && call.path === '/api/v1/household') return noHousehold
      if (call.method === 'POST' && call.path === '/api/v1/logout') {
        session = false
        return unauthenticated
      }
      return undefined
    })
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: LOGOUT }))
    await expectLoginScreen()
  })

  it.each([
    { label: '403', reply: originRejected as Reply | typeof networkError },
    { label: '500', reply: serverError },
    { label: '네트워크 끊김', reply: networkError },
  ])('로그아웃 요청이 $label 로 끝나면 가구 없음 화면과 로그아웃 버튼이 남는다 (R13)', async ({ reply }) => {
    api = mockApi((call) => {
      if (call.method === 'GET' && call.path === '/api/v1/me') return me
      if (call.method === 'GET' && call.path === '/api/v1/household') return noHousehold
      if (call.method === 'POST' && call.path === '/api/v1/logout') return reply
      return undefined
    })
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: LOGOUT }))
    await waitFor(() => expect(api.calls.some((call) => call.path === '/api/v1/logout')).toBe(true))
    await settle()
    screen.getByText(NO_HOUSEHOLD)
    screen.getByRole('button', { name: LOGOUT })
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
  })
})
