/**
 * T035 초대 토큰 보안 경계 화면 테스트(spec US2-8, US2-10, US2-12, FR-012, FR-022, 결정 기록 0013, research R7, R14).
 * 구현과 다른 쪽이 요구사항에서 썼다. 공개 표면은 login.test.tsx 맨 위 주석과 invite.test.tsx 맨 위 주석을 따른다.
 * 토큰을 담는 sessionStorage 키 이름은 구현이 정하므로, 이 파일은 저장소의 모든 값에 토큰이 있는지로 본다.
 *
 * tasks T035 문장과 테스트 이름의 대응:
 * 1. "`/invite#토큰`을 열면 화면을 그리기 전에 주소에서 `#` 뒤가 사라지고"
 *    -> "R7: 로그인한 사람이 /invite#토큰을 열면 첫 렌더에서 이미 # 뒤가 없다",
 *       "R7, FR-012: 로그인하지 않은 사람이 /invite#토큰을 열면 ..." (같은 첫 렌더 확인을 함께 한다)
 * 2. "로그인 화면의 로그인 버튼 주소의 `returnTo`가 `/invite`이며 토큰이 없음"
 *    -> "R7, FR-012: 로그인하지 않은 사람이 /invite#토큰을 열면 ..."
 * 3. "토큰은 `sessionStorage`에만 있고 `localStorage`, 요청 주소, 질의 값에 없으며 초대 확인과 수락 요청의 본문으로만 나감"
 *    -> "R7: 초대 확인부터 들어가기 성공까지 토큰은 sessionStorage와 초대 확인, 수락 요청의 본문에만 있다",
 *       "R7: owner가 자기 초대 링크를 연 뒤 발급 재인증 안내의 구글 계정 확인 링크에도 토큰이 없다",
 *       그리고 아래 모든 테스트의 expectNoTokenLeak()
 * 4. "들어가기의 성공과 실패, 취소, 확인 단계의 실패 안내 뒤 `sessionStorage`에서 토큰이 삭제됨"
 *    -> "R7: 들어가기 성공 뒤 ...", "R7: 들어가기가 %s 로 실패하면 ...", "R7, US2-10: 취소를 누르면 ...",
 *       "R7, US2-12: 초대 확인이 %s 로 실패하면 ..."
 * 5. "로그인하지 않았으면 확인 화면 대신 로그인 화면"
 *    -> "R7, FR-012: 로그인하지 않은 사람이 /invite#토큰을 열면 ..."
 * 6. "초대 발급의 재인증에서 돌아와도 발급을 자동으로 다시 보내지 않고 고른 만료 기간을 저장하지 않음"
 *    -> "FR-022, 결정 기록 0013: 발급 재인증에서 돌아와도 ..."
 * 7. (T054) "구성원 제거의 재인증에서 돌아와도 제거 요청을 자동으로 다시 보내지 않고, 재인증 전후로 저장소, 화면 주소,
 *    `returnTo`에 밀린 동작과 대상 번호가 없음"
 *    -> "FR-022, 결정 기록 0013: 구성원 제거 재인증에서 돌아와도 ..."
 *    공개 표면: owner 화면에서 다른 구성원 행의 "제거" 버튼을 누르면 확인 없이 DELETE /api/v1/household/members/{userId}
 *    하나를 보내고, 401 REAUTH_REQUIRED면 안내 "구성원을 제거하려면 구글 계정을 한 번 더 확인해야 합니다."와
 *    href가 정확히 /oauth2/authorization/google?purpose=reauth&returnTo=/ 인 "구글 계정 확인" 링크가 보인다.
 *    대상 번호와 하려던 동작은 localStorage, sessionStorage, 주소(경로, 쿼리, #), returnTo, history.state 어디에도 남지 않는다.
 * 8. (T068) "가구 삭제의 재인증에서 `/household/delete`로 돌아와도 삭제 요청을 자동으로 다시 보내지 않고, 브라우저 저장소와
 *    주소에 밀린 동작이 없음"
 *    -> "US4-3, FR-022, 결정 기록 0013: 가구 삭제 재인증에서 /household/delete로 돌아와도 ..."
 *    공개 표면: owner 첫 화면의 "가구 삭제" 버튼은 요청 없이 /household/delete 확인 화면으로 간다. 확인 화면은 안내
 *    "가구를 삭제하면 가구, 구성원 {N}명의 연결, 보낸 초대가 삭제되며 되돌릴 수 없습니다."와 "가구 삭제", "취소" 버튼을 보인다.
 *    "가구 삭제"는 본문 없는 DELETE /api/v1/household 하나를 보내고, 401 REAUTH_REQUIRED면 확인 화면에 머물며 안내
 *    "가구를 삭제하려면 구글 계정을 한 번 더 확인해야 합니다."와 href가 정확히
 *    /oauth2/authorization/google?purpose=reauth&returnTo=/household/delete 인 "구글 계정 확인" 링크가 보인다.
 *    재인증에서 돌아오면(문서를 새로 열어 /household/delete) 확인 화면이 다시 보이고 삭제를 자동으로 보내지 않는다.
 *    하려던 동작은 localStorage, sessionStorage, 주소(경로, 쿼리, #), returnTo 외의 값, history.state의 usr, 쿠키 어디에도 없다.
 * 게이트 반영으로 더한 테스트는 이름이 "게이트"로 시작한다(같은 문서에서 다시 연 링크, 끝 슬래시, 로그인 끊김, 그 밖의 오류 뒤 삭제).
 * 재리뷰 반영 테스트는 이름이 "재리뷰"로 시작한다(이전 링크의 늦은 들어가기 응답이 새 링크를 건드리지 않음).
 * expectNoTokenLeak()은 콘솔(log, info, warn, error, debug)로 나간 인자와 history.state에도 토큰이 없는지 본다.
 *
 * 첫 렌더 확인의 방법과 한계: RTL render는 act 안에서 effect까지 마친 뒤 돌아오므로, render 직후 동기 확인만으로는
 * "그리기 전"과 "그린 직후 effect에서 지움"을 가르지 못한다. 그래서 render 동안 document.createElement를 감싸
 * React가 DOM 요소를 만드는 매 순간의 location.hash와 sessionStorage를 기록한다. 이 방법은 요소를 만드는 시점만 보므로,
 * 기존 요소를 고치기만 하는 다시 그리기 사이에 주소가 바뀌는 경우는 잡지 못한다(첫 마운트에는 해당하지 않는다).
 * 재인증에서 돌아오는 것은 서버가 returnTo=/ 로 문서를 새로 여는 것이므로, 같은 탭(sessionStorage 유지)에서 /에 App을 새로 마운트해 흉내 낸다.
 */
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../src/App'
import {
  type ApiCall,
  hasHousehold,
  me,
  members,
  mockApi,
  networkError,
  noContent,
  noHousehold,
  type Reply,
  serverError,
  settle,
  unauthenticated,
  watchNavigation,
} from './api-mock'

/** 계약 패턴 ^[A-Za-z0-9_-]{43}$ 를 따르는 가짜 토큰. */
const TOKEN = 'fake_token-'.repeat(3) + 'fake_token'
const ISSUED_TOKEN = 'issued-tok_'.repeat(3) + 'issued-tok'
/** 같은 문서에서 나중에 연 다른 초대 링크의 토큰. */
const OTHER_TOKEN = 'other-tok_'.repeat(4) + 'oth'

const LOGIN = 'Google 계정으로 로그인'
const RETRY_GUIDE = '잠시 후 다시 시도해 주세요.'
const ENTER = '들어가기'
const CANCEL = '취소'
const TO_HOME = '첫 화면으로'
const NO_HOUSEHOLD = '아직 가구가 없습니다.'
const MEMBERS_TITLE = '함께 보는 사람'
const EXPIRES = '만료 기간'
const CREATE_INVITE = '초대 링크 만들기'
const REAUTH = '구글 계정 확인'
const REAUTH_NOTICE = '초대 링크를 만들려면 구글 계정을 한 번 더 확인해야 합니다.'
const NOT_FOUND_GUIDE = '존재하지 않는 초대 링크입니다. 가족에게 새 초대 링크를 받아 주세요.'
const REVOKED_GUIDE = '취소된 초대 링크입니다. 가족에게 새 초대 링크를 받아 주세요.'
const USED_GUIDE = '이미 사용된 초대 링크입니다. 가족에게 새 초대 링크를 받아 주세요.'
const EXPIRED_GUIDE = '만료된 초대 링크입니다. 가족에게 새 초대 링크를 받아 주세요.'
const ALREADY_GUIDE = '이미 이 가구의 구성원입니다. 링크는 가족에게 보내세요.'
const HAS_HOUSEHOLD_GUIDE = '이미 다른 가구에 속해 있습니다. 가구를 나가거나 삭제한 뒤 이 링크를 다시 여세요.'

/** contracts/openapi.yaml 초대 확인 200 예시 모양. */
const preview: Reply = { status: 200, body: { inviterDisplayName: '민지' } }

/** contracts/openapi.yaml InvitationNotFound 예시. */
const notFound: Reply = {
  status: 404,
  body: {
    type: 'https://example.com/problems/not-found',
    title: '찾을 수 없습니다',
    status: 404,
    detail: '주소를 확인해 주세요.',
    code: 'NOT_FOUND',
  },
}

/** contracts/openapi.yaml InvitationRejected 예시들. */
const rejected = (code: string, slug: string, title: string, detail: string): Reply => ({
  status: 409,
  body: { type: `https://example.com/problems/${slug}`, title, status: 409, detail, code },
})
const NEW_LINK = '가족에게 새 초대 링크를 받아 주세요.'
const revoked = rejected('INVITATION_REVOKED', 'invitation-revoked', '취소된 초대 링크입니다', NEW_LINK)
const used = rejected('INVITATION_USED', 'invitation-used', '이미 사용된 초대 링크입니다', NEW_LINK)
const expired = rejected('INVITATION_EXPIRED', 'invitation-expired', '초대 링크가 만료되었습니다', NEW_LINK)
const alreadyIn = rejected(
  'ALREADY_IN_THIS_HOUSEHOLD',
  'already-in-this-household',
  '이미 이 가구의 구성원입니다',
  '링크는 가족에게 보내세요.',
)

/** contracts/openapi.yaml UnauthenticatedOrReauth 예시 reauthRequired. */
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

/** 수락 뒤 함께 보는 사람 목록. 나는 member다. */
const joinedMembers: Reply = {
  status: 200,
  body: {
    members: [
      { userId: 2, displayName: '민지', role: 'owner', loginEmail: 'user2@example.com', emailVerified: true, me: false },
      { userId: 1, displayName: '엄마', role: 'member', loginEmail: 'user1@example.com', emailVerified: true, me: true },
    ],
  },
}

const pendingInvitation = {
  id: 7,
  createdAt: '2026-11-18T10:00:00+09:00',
  expiresAt: '2026-11-21T10:00:00+09:00',
  remainingDays: 3,
}

let navigation: ReturnType<typeof watchNavigation>
let api: ReturnType<typeof mockApi>
/** 테스트 동안 콘솔로 나간 인자를 모은다. 원래 출력은 그대로 둔다. afterEach의 restoreAllMocks가 되돌린다. */
let consoleSpies: { mock: { calls: unknown[][] } }[] = []

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  navigation = watchNavigation()
  consoleSpies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) => vi.spyOn(console, method))
})

afterEach(async () => {
  cleanup()
  await navigation.stop()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  sessionStorage.clear()
  localStorage.clear()
  expect(navigation.attempts).toEqual([])
  expect(api.unmatched).toEqual([])
  for (const call of api.calls) {
    expect(call.sameOrigin).toBe(true)
    expect([undefined, 'same-origin']).toContain(call.credentials)
  }
})

type Options = { session?: boolean; owner?: boolean; preview?: Reply | typeof networkError; accept?: Reply }

/** 상태를 갖는 흉내 서버. 수락 204 뒤에는 member로 목록을 돌려주고, 재인증 뒤 발급 201 뒤에는 보낸 초대 목록에 그 초대를 돌려준다. */
function server({ session = true, owner = false, preview: previewReply = preview, accept = noContent }: Options = {}) {
  const state = { joined: false, reauthed: false, issued: false }
  const handler = (call: ApiCall): Reply | typeof networkError | undefined => {
    const is = (method: string, path: string) => call.method === method && call.path === path
    if (is('GET', '/api/v1/me')) return session ? me : unauthenticated
    if (is('GET', '/api/v1/household')) return owner ? members : state.joined ? joinedMembers : noHousehold
    if (is('GET', '/api/v1/household/invitations'))
      return { status: 200, body: { invitations: state.issued ? [pendingInvitation] : [] } }
    if (is('POST', '/api/v1/household/invitations')) {
      if (!state.reauthed) return reauthRequired
      state.issued = true
      return {
        status: 201,
        body: { id: 7, token: ISSUED_TOKEN, createdAt: pendingInvitation.createdAt, expiresAt: pendingInvitation.expiresAt },
      }
    }
    if (is('POST', '/api/v1/invitations/preview')) return previewReply
    if (is('POST', '/api/v1/invitations/accept')) {
      if (accept.status === 204) state.joined = true
      return accept
    }
    return undefined
  }
  return { state, handler }
}

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  render(<App />)
}

/** render 동안 React가 DOM 요소를 만들 때마다 그 순간의 주소와 sessionStorage를 기록한다. */
function openWatchingFirstRender(path: string) {
  window.history.replaceState(null, '', path)
  const container = document.body.appendChild(document.createElement('div'))
  const original = document.createElement.bind(document)
  const seen: { hash: string; pathname: string; stored: boolean }[] = []
  const spy = vi.spyOn(document, 'createElement').mockImplementation(((tag: string, options?: ElementCreationOptions) => {
    seen.push({ hash: window.location.hash, pathname: window.location.pathname, stored: hasToken(sessionStorage) })
    return original(tag, options)
  }) as typeof document.createElement)
  try {
    render(<App />, { container })
  } finally {
    spy.mockRestore()
  }
  return seen
}

function entries(storage: Storage) {
  return Array.from({ length: storage.length }, (_, i) => {
    const key = storage.key(i) ?? ''
    return [key, storage.getItem(key) ?? ''] as const
  })
}

const hasToken = (storage: Storage, token = TOKEN) => entries(storage).some(([, value]) => value.includes(token))

const tokenCall = (call: ApiCall) =>
  call.method === 'POST' && (call.path === '/api/v1/invitations/preview' || call.path === '/api/v1/invitations/accept')
const previewCalls = () => api.calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/invitations/preview')
const acceptCalls = () => api.calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/invitations/accept')
const issueCalls = () => api.calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/household/invitations')
const meCalls = () => api.calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/me')

/** 콘솔 인자 하나를 문자열로 바꾼다. 오류는 메시지와 스택, 객체는 JSON(실패하면 String)으로. */
function printed(arg: unknown) {
  if (typeof arg === 'string') return arg
  if (arg instanceof Error) return `${arg.message} ${arg.stack ?? ''}`
  try {
    return JSON.stringify(arg) ?? String(arg)
  } catch {
    return String(arg)
  }
}

/**
 * 토큰이 sessionStorage와 초대 확인, 수락 요청의 본문 밖으로 나가지 않았는지 본다.
 * tokens는 이 테스트에서 연 링크의 토큰들이고, 초대 확인과 수락 요청의 본문은 그중 하나의 {"token": 토큰}과 같아야 한다.
 */
function expectNoTokenLeak(tokens: string[] = [TOKEN]) {
  for (const call of api.calls) {
    if (tokenCall(call)) expect(tokens.map((token) => ({ token }))).toContainEqual(JSON.parse(call.body ?? 'null'))
  }
  for (const token of tokens) {
    expect(window.location.href).not.toContain(token)
    expect(document.cookie).not.toContain(token)
    expect(JSON.stringify(window.history.state) ?? '').not.toContain(token)
    for (const call of api.calls) {
      expect(call.path).not.toContain(token)
      expect(call.search).not.toContain(token)
      if (!tokenCall(call)) expect(call.body ?? '').not.toContain(token)
    }
    for (const link of document.querySelectorAll('a')) expect(link.getAttribute('href') ?? '').not.toContain(token)
    for (const [key, value] of entries(localStorage)) {
      expect(key).not.toContain(token)
      expect(value).not.toContain(token)
    }
    for (const attempt of navigation.attempts) expect(attempt).not.toContain(token)
    for (const spy of consoleSpies) for (const args of spy.mock.calls) expect(args.map(printed).join(' ')).not.toContain(token)
  }
}

function expectAddressClean(pathname: string) {
  expect(window.location.pathname).toBe(pathname)
  expect(window.location.search).toBe('')
  expect(window.location.hash).toBe('')
}

describe('주소에서 토큰 지우기와 로그인 화면 (R7, FR-012, US2-8)', () => {
  it('토큰 값은 계약 패턴을 따른다', () => {
    api = mockApi(() => undefined)
    expect(TOKEN).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(ISSUED_TOKEN).toMatch(/^[A-Za-z0-9_-]{43}$/)
  })

  it('R7: 로그인한 사람이 /invite#토큰을 열면 첫 렌더에서 이미 # 뒤가 없다', async () => {
    api = mockApi(server().handler)
    const seen = openWatchingFirstRender(`/invite#${TOKEN}`)
    // 기다리기 전, render 직후 동기적으로 본다.
    expectAddressClean('/invite')
    expect(hasToken(sessionStorage)).toBe(true)
    // render 동안 요소를 만든 모든 순간에 이미 주소에 # 뒤가 없고 토큰은 sessionStorage에 있었다.
    expect(seen.length).toBeGreaterThan(0)
    expect(seen.filter((s) => s.hash !== '' || s.pathname !== '/invite')).toEqual([])
    expect(seen.every((s) => s.stored)).toBe(true)

    await screen.findByRole('button', { name: ENTER })
    expectAddressClean('/invite')
    expectNoTokenLeak()
  })

  it('R7, FR-012: 로그인하지 않은 사람이 /invite#토큰을 열면 확인 화면 대신 로그인 화면이고, 로그인 버튼의 returnTo는 토큰 없는 /invite이며 토큰은 sessionStorage에 남는다', async () => {
    api = mockApi(server({ session: false }).handler)
    const seen = openWatchingFirstRender(`/invite#${TOKEN}`)
    expectAddressClean('/invite')
    expect(seen.length).toBeGreaterThan(0)
    expect(seen.filter((s) => s.hash !== '' || s.pathname !== '/invite')).toEqual([])

    const login = await screen.findByRole('link', { name: LOGIN })
    await settle()
    const href = login.getAttribute('href') ?? ''
    expect(href).toBe('/oauth2/authorization/google?returnTo=%2Finvite')
    expect(new URL(href, window.location.origin).searchParams.get('returnTo')).toBe('/invite')
    expect(previewCalls()).toEqual([])
    expect(acceptCalls()).toEqual([])
    expect(screen.queryByRole('button', { name: ENTER })).toBeNull()
    // 로그인 뒤 /invite로 돌아와 확인 화면을 열도록 토큰은 탭 저장소에 남는다.
    expect(hasToken(sessionStorage)).toBe(true)
    expectAddressClean('/invite')
    expectNoTokenLeak()
  })
})

describe('토큰이 나가는 곳 (R7, FR-012)', () => {
  it('R7: 초대 확인부터 들어가기 성공까지 토큰은 sessionStorage와 초대 확인, 수락 요청의 본문에만 있다', async () => {
    api = mockApi(server().handler)
    openAt(`/invite#${TOKEN}`)
    const enter = await screen.findByRole('button', { name: ENTER })
    await settle()
    expect(hasToken(sessionStorage)).toBe(true)
    expect(previewCalls().length).toBeGreaterThan(0)
    expect(acceptCalls()).toEqual([])
    expectNoTokenLeak()

    fireEvent.click(enter)
    await screen.findByText(MEMBERS_TITLE)
    await settle()
    expect(acceptCalls()).toHaveLength(1)
    for (const call of [...previewCalls(), ...acceptCalls()]) expect(JSON.parse(call.body ?? 'null')).toEqual({ token: TOKEN })
    expectNoTokenLeak()
  })

  it('R7: owner가 자기 초대 링크를 연 뒤 발급 재인증 안내의 구글 계정 확인 링크에도 토큰이 없다', async () => {
    api = mockApi(server({ owner: true, preview: alreadyIn }).handler)
    openAt(`/invite#${TOKEN}`)
    expect(hasToken(sessionStorage)).toBe(true)
    fireEvent.click(await screen.findByRole('button', { name: TO_HOME }))
    expect(screen.getByLabelText(EXPIRES)).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: CREATE_INVITE }))
    await screen.findByText(REAUTH_NOTICE)
    await settle()
    const href = screen.getByRole('link', { name: REAUTH }).getAttribute('href') ?? ''
    expect(href).not.toContain(TOKEN)
    expect(new URL(href, window.location.origin).searchParams.get('returnTo')).toBe('/')
    expect(hasToken(sessionStorage)).toBe(false)
    expectNoTokenLeak()
  })
})

describe('토큰을 지우는 때 (R7, US2-10, US2-12)', () => {
  it('R7: 들어가기 성공 뒤 sessionStorage에서 토큰이 지워진다', async () => {
    api = mockApi(server().handler)
    openAt(`/invite#${TOKEN}`)
    const enter = await screen.findByRole('button', { name: ENTER })
    await settle()
    expect(hasToken(sessionStorage)).toBe(true)
    fireEvent.click(enter)
    await screen.findByText(MEMBERS_TITLE)
    await settle()
    expect(acceptCalls()).toHaveLength(1)
    expect(hasToken(sessionStorage)).toBe(false)
    expectAddressClean('/')
    expectNoTokenLeak()
  })

  it.each([
    ['409 INVITATION_REVOKED', revoked, REVOKED_GUIDE],
    ['409 INVITATION_USED', used, USED_GUIDE],
    ['404 NOT_FOUND', notFound, NOT_FOUND_GUIDE],
  ])('R7: 들어가기가 %s 로 실패하면 안내 뒤 sessionStorage에서 토큰이 지워진다', async (_, reply, guide) => {
    api = mockApi(server({ accept: reply }).handler)
    openAt(`/invite#${TOKEN}`)
    const enter = await screen.findByRole('button', { name: ENTER })
    await settle()
    expect(hasToken(sessionStorage)).toBe(true)
    fireEvent.click(enter)
    await screen.findByText(guide)
    await settle()
    expect(acceptCalls()).toHaveLength(1)
    expect(hasToken(sessionStorage)).toBe(false)
    expectNoTokenLeak()
  })

  it('R7, US2-10: 취소를 누르면 수락을 보내지 않고 가구 없음 화면으로 가며 sessionStorage에서 토큰이 지워진다', async () => {
    api = mockApi(server().handler)
    openAt(`/invite#${TOKEN}`)
    await screen.findByRole('button', { name: ENTER })
    await settle()
    expect(hasToken(sessionStorage)).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: CANCEL }))
    await screen.findByText(NO_HOUSEHOLD)
    await settle()
    expect(acceptCalls()).toEqual([])
    expect(hasToken(sessionStorage)).toBe(false)
    expectAddressClean('/')
    expectNoTokenLeak()
  })

  it.each([
    ['409 INVITATION_EXPIRED', expired, EXPIRED_GUIDE],
    ['409 HAS_HOUSEHOLD', hasHousehold, HAS_HOUSEHOLD_GUIDE],
    ['409 ALREADY_IN_THIS_HOUSEHOLD', alreadyIn, ALREADY_GUIDE],
    ['404 NOT_FOUND', notFound, NOT_FOUND_GUIDE],
  ])('R7, US2-12: 초대 확인이 %s 로 실패하면 확인 화면 대신 안내를 보이고 sessionStorage에서 토큰이 지워진다', async (_, reply, guide) => {
    api = mockApi(server({ owner: reply === alreadyIn, preview: reply }).handler)
    openAt(`/invite#${TOKEN}`)
    // 초대 확인 응답을 받기 전(기다리기 전)에는 토큰이 탭 저장소에 있다.
    expect(hasToken(sessionStorage)).toBe(true)
    await screen.findByText(guide)
    await settle()
    expect(previewCalls().length).toBeGreaterThan(0)
    expect(acceptCalls()).toEqual([])
    expect(screen.queryByRole('button', { name: ENTER })).toBeNull()
    expect(hasToken(sessionStorage)).toBe(false)
    expectNoTokenLeak()
  })
})

describe('게이트: 다시 연 링크와 끝 슬래시 (R7, FR-012)', () => {
  it('게이트, R7, FR-012: 실패 안내가 떠 있는 /invite에서 주소의 해시만 새 토큰으로 바뀌면 # 뒤를 지우고 새 토큰으로 확인 화면을 연다', async () => {
    const base = server({ preview: expired }).handler
    api = mockApi((call) =>
      call.path === '/api/v1/invitations/preview' && (call.body ?? '').includes(OTHER_TOKEN) ? preview : base(call),
    )
    openAt(`/invite#${TOKEN}`)
    await screen.findByText(EXPIRED_GUIDE)
    await settle()
    expect(hasToken(sessionStorage)).toBe(false)

    // 문서를 다시 불러오지 않고 해시만 바뀐다(hashchange).
    act(() => {
      window.location.hash = `#${OTHER_TOKEN}`
    })
    await screen.findByRole('button', { name: ENTER })
    await settle()
    expectAddressClean('/invite')
    expect(hasToken(sessionStorage, OTHER_TOKEN)).toBe(true)
    expect(screen.queryByText(EXPIRED_GUIDE)).toBeNull()
    const last = previewCalls().at(-1)
    expect(JSON.parse(last?.body ?? 'null')).toEqual({ token: OTHER_TOKEN })
    expectNoTokenLeak([TOKEN, OTHER_TOKEN])
  })

  it('게이트, R7: 끝 슬래시 /invite/#토큰으로 열어도 토큰을 저장하고 # 뒤를 지우며 확인 화면이 나온다', async () => {
    api = mockApi(server().handler)
    openAt(`/invite/#${TOKEN}`)
    expect(window.location.hash).toBe('')
    expect(window.location.search).toBe('')
    expect(['/invite', '/invite/']).toContain(window.location.pathname)
    expect(hasToken(sessionStorage)).toBe(true)
    await screen.findByRole('button', { name: ENTER })
    await settle()
    expect(window.location.hash).toBe('')
    expect(previewCalls().length).toBeGreaterThan(0)
    expectNoTokenLeak()
  })
})

describe('재리뷰: 이전 링크의 늦은 들어가기 응답 (R7, FR-012)', () => {
  it('재리뷰, R7, FR-012: 토큰 A의 들어가기 응답이 B 링크를 연 뒤 500으로 와도 B의 토큰과 확인 화면이 그대로이고 B로 수락을 보낸다', async () => {
    api = mockApi(server({ accept: serverError }).handler)
    const mocked = globalThis.fetch
    const hold = { held: false, release: () => {} }
    // 이 테스트만 A의 첫 수락 응답을 붙잡아 두었다가 나중에 푼다(요청은 mockApi에 기록된다).
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : undefined
      const url = new URL(request ? request.url : String(input), window.location.href)
      const body = typeof init?.body === 'string' ? init.body : ''
      if (hold.held || url.pathname !== '/api/v1/invitations/accept' || !body.includes(TOKEN)) return mocked(input, init)
      hold.held = true
      const answer = await mocked(input, init)
      await new Promise<void>((resolve) => {
        hold.release = resolve
      })
      return answer
    })
    openAt(`/invite#${TOKEN}`)
    fireEvent.click(await screen.findByRole('button', { name: ENTER }))
    await waitFor(() => expect(hold.held).toBe(true))

    act(() => {
      window.location.hash = `#${OTHER_TOKEN}`
    })
    await waitFor(() => expect(previewCalls().some((c) => (c.body ?? '').includes(OTHER_TOKEN))).toBe(true))
    await screen.findByRole('button', { name: ENTER })
    expect(hasToken(sessionStorage, OTHER_TOKEN)).toBe(true)

    act(() => hold.release())
    await settle()
    expect(hasToken(sessionStorage, OTHER_TOKEN)).toBe(true)
    expect(screen.queryByText(RETRY_GUIDE)).toBeNull()
    expect(screen.queryByRole('button', { name: TO_HOME })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: ENTER }))
    await waitFor(() => expect(acceptCalls()).toHaveLength(2))
    expect(JSON.parse(acceptCalls()[1].body ?? 'null')).toEqual({ token: OTHER_TOKEN })
    await settle()
    expectNoTokenLeak([TOKEN, OTHER_TOKEN])
  })
})

describe('게이트: 로그인이 끊긴 경우 (FR-012, R7)', () => {
  /** 처음 내 정보는 200이고, at 단계의 요청이 401을 받는 순간 로그인 상태가 끊긴다. */
  function expiringServer(at: 'preview' | 'accept') {
    const state = { session: true }
    return (call: ApiCall): Reply | undefined => {
      const is = (method: string, path: string) => call.method === method && call.path === path
      if (is('GET', '/api/v1/me')) return state.session ? me : unauthenticated
      if (!state.session) return undefined
      if (is('GET', '/api/v1/household')) return noHousehold
      if (is('POST', '/api/v1/invitations/preview') && at === 'accept') return preview
      if (is('POST', `/api/v1/invitations/${at}`)) {
        state.session = false
        return unauthenticated
      }
      return undefined
    }
  }

  it.each(['accept', 'preview'] as const)(
    '게이트, FR-012: %s 가 401 UNAUTHENTICATED면 로그인 화면(returnTo=/invite)이 보이고 토큰이 남아, 로그인 뒤 /invite에서 확인 화면이 다시 나온다',
    async (at) => {
      api = mockApi(expiringServer(at))
      openAt(`/invite#${TOKEN}`)
      if (at === 'accept') fireEvent.click(await screen.findByRole('button', { name: ENTER }))
      const login = await screen.findByRole('link', { name: LOGIN })
      await settle()
      const url = new URL(login.getAttribute('href') ?? '', window.location.origin)
      expect(url.pathname).toBe('/oauth2/authorization/google')
      expect([...url.searchParams]).toEqual([['returnTo', '/invite']])
      expect(screen.queryByRole('button', { name: ENTER })).toBeNull()
      expect(hasToken(sessionStorage)).toBe(true)
      expectNoTokenLeak()

      // 로그인을 마치고 같은 탭의 /invite(해시 없음)로 돌아온다.
      cleanup()
      api = mockApi(server().handler)
      openAt('/invite')
      await screen.findByRole('button', { name: ENTER })
      await settle()
      expect(acceptCalls()).toEqual([])
      expect(previewCalls().length).toBeGreaterThan(0)
      expect(hasToken(sessionStorage)).toBe(true)
      expectNoTokenLeak()
    },
  )
})

describe('게이트: 그 밖의 오류 뒤 토큰 삭제 (R14, R7)', () => {
  it.each([
    ['초대 확인이 500', { preview: serverError }],
    ['초대 확인이 네트워크 오류', { preview: networkError }],
    ['들어가기가 500', { accept: serverError }],
  ] as const)(
    '게이트, R14, R7: %s 면 "잠시 후 다시 시도해 주세요."와 첫 화면으로가 보이고 들어가기는 없으며 토큰이 지워진다',
    async (name, options) => {
      api = mockApi(server(options).handler)
      openAt(`/invite#${TOKEN}`)
      expect(hasToken(sessionStorage)).toBe(true)
      if (name === '들어가기가 500') {
        fireEvent.click(await screen.findByRole('button', { name: ENTER }))
        expect(hasToken(sessionStorage)).toBe(true)
      }
      await screen.findByText(RETRY_GUIDE)
      screen.getByRole('button', { name: TO_HOME })
      await settle()
      expect(screen.queryByRole('button', { name: ENTER })).toBeNull()
      expect(hasToken(sessionStorage)).toBe(false)
      expectNoTokenLeak()
    },
  )
})

describe('발급 재인증 뒤 (FR-022, 결정 기록 0013, R14)', () => {
  /** 고른 만료 기간 6이 탭 저장소와 브라우저 저장소의 어떤 키와 값에도 숫자로 담기지 않았는지 본다. */
  function expectChoiceNotStored() {
    const six = /(^|\D)6(\D|$)/
    for (const storage of [sessionStorage, localStorage]) {
      for (const [key, value] of entries(storage)) {
        expect(key).not.toMatch(six)
        expect(value).not.toMatch(six)
      }
    }
    expect(window.location.search).toBe('')
    expect(window.location.hash).toBe('')
  }

  it('FR-022, 결정 기록 0013: 발급 재인증에서 돌아와도 발급을 다시 보내지 않고, 만료 기간은 3일로 돌아가며 고른 값은 어디에도 담기지 않는다', async () => {
    const { state, handler } = server({ owner: true })
    api = mockApi(handler)
    openAt('/')
    const select = (await screen.findByLabelText(EXPIRES)) as HTMLSelectElement
    expect(select.value).toBe('3')
    fireEvent.change(select, { target: { value: '6' } })
    expect(select.value).toBe('6')
    fireEvent.click(screen.getByRole('button', { name: CREATE_INVITE }))
    await screen.findByText(REAUTH_NOTICE)
    const href = screen.getByRole('link', { name: REAUTH }).getAttribute('href') ?? ''
    const url = new URL(href, window.location.origin)
    expect(url.pathname).toBe('/oauth2/authorization/google')
    expect(url.searchParams.get('purpose')).toBe('reauth')
    expect(url.searchParams.get('returnTo')).toBe('/')
    expect([...url.searchParams.keys()].sort()).toEqual(['purpose', 'returnTo'])
    await settle()
    expect(issueCalls().map((c) => JSON.parse(c.body ?? 'null'))).toEqual([{ expiresInDays: 6 }])
    expectChoiceNotStored()

    // 재인증을 마치고 같은 탭에서 /로 돌아온다.
    cleanup()
    state.reauthed = true
    const mesBefore = meCalls().length
    openAt('/')
    const again = (await screen.findByLabelText(EXPIRES)) as HTMLSelectElement
    await settle()
    expect(meCalls().length).toBeGreaterThan(mesBefore)
    expect(issueCalls()).toHaveLength(1)
    expect(again.value).toBe('3')
    expect(screen.queryByText(REAUTH_NOTICE)).toBeNull()
    expectChoiceNotStored()

    // 사용자가 다시 누를 때만 보내고, 그때의 값은 처음 값 3이다.
    fireEvent.click(screen.getByRole('button', { name: CREATE_INVITE }))
    await waitFor(() => expect(issueCalls()).toHaveLength(2))
    expect(JSON.parse(issueCalls()[1].body ?? 'null')).toEqual({ expiresInDays: 3 })
    await settle()
  })
})

describe('구성원 제거 재인증 뒤 (FR-022, 결정 기록 0013, R14)', () => {
  /** 흔하지 않은 대상 번호. 저장소, 주소, history.state, 링크, 콘솔 전체에서 이 숫자를 찾는다. */
  const TARGET = 4242
  const REMOVE = '제거'
  const REMOVE_REAUTH_NOTICE = '구성원을 제거하려면 구글 계정을 한 번 더 확인해야 합니다.'
  const REAUTH_HREF = '/oauth2/authorization/google?purpose=reauth&returnTo=/'
  const OTHER_EMAIL = 'user2@example.com'

  /** contracts/openapi.yaml Members 스키마 모양. 나는 owner이고 제거할 member의 userId가 TARGET이다. */
  const twoMembers: Reply = {
    status: 200,
    body: {
      members: [
        { userId: 1, displayName: '엄마', role: 'owner', loginEmail: 'user1@example.com', emailVerified: true, me: true },
        { userId: TARGET, displayName: '민지', role: 'member', loginEmail: OTHER_EMAIL, emailVerified: true, me: false },
      ],
    },
  }

  /** 재인증 전에는 제거가 401 REAUTH_REQUIRED, 재인증 뒤에는 204다(자동으로 다시 보내면 기록에 남는다). */
  function removeServer() {
    const state = { reauthed: false }
    const handler = (call: ApiCall): Reply | undefined => {
      const is = (method: string, path: string) => call.method === method && call.path === path
      if (is('GET', '/api/v1/me')) return me
      if (is('GET', '/api/v1/household')) return twoMembers
      if (is('GET', '/api/v1/household/invitations')) return { status: 200, body: { invitations: [] } }
      if (is('DELETE', `/api/v1/household/members/${TARGET}`)) return state.reauthed ? noContent : reauthRequired
      return undefined
    }
    return { state, handler }
  }

  const removeCalls = () => api.calls.filter((c) => c.method === 'DELETE' && c.path.startsWith('/api/v1/household/members'))

  /** 로그인 이메일 글자를 포함하는 구성원 행. */
  const rowOf = (email: string) =>
    waitFor(() => {
      const row = screen.getAllByRole('listitem').find((item) => (item.textContent ?? '').includes(email))
      if (!row) throw new Error(`${email} 행이 없다`)
      return row
    })

  /**
   * 제거 흐름이 브라우저에 아무것도 남기지 않았는지 본다. 저장소는 제거를 누르기 전 모습 그대로여야 하고(밀린 동작 없음),
   * 대상 번호는 저장소, 주소, 쿠키, history.state, 링크, 콘솔 어디에도 없어야 한다.
   */
  function expectNoRemoveTrace(stored: { local: (readonly [string, string])[]; session: (readonly [string, string])[] }) {
    const target = String(TARGET)
    expect(entries(localStorage)).toEqual(stored.local)
    expect(entries(sessionStorage)).toEqual(stored.session)
    for (const storage of [localStorage, sessionStorage]) {
      for (const [key, value] of entries(storage)) {
        expect(key).not.toContain(target)
        expect(value).not.toContain(target)
      }
    }
    expectAddressClean('/')
    expect(window.location.href).not.toContain(target)
    expect(document.cookie).not.toContain(target)
    expect(JSON.stringify(window.history.state) ?? '').not.toContain(target)
    for (const link of document.querySelectorAll('a')) expect(link.getAttribute('href') ?? '').not.toContain(target)
    for (const spy of consoleSpies) for (const args of spy.mock.calls) expect(args.map(printed).join(' ')).not.toContain(target)
  }

  it('FR-022, 결정 기록 0013: 구성원 제거 재인증에서 돌아와도 제거를 다시 보내지 않고, 대상 번호와 밀린 동작은 저장소, 주소, returnTo, history.state 어디에도 없다', async () => {
    const { state, handler } = removeServer()
    api = mockApi(handler)
    openAt('/')
    const row = await rowOf(OTHER_EMAIL)
    await settle()
    const stored = { local: entries(localStorage), session: entries(sessionStorage) }
    const historyBefore = window.history.state
    expect(removeCalls()).toEqual([])

    fireEvent.click(within(row).getByRole('button', { name: REMOVE }))
    await screen.findByText(REMOVE_REAUTH_NOTICE)
    await settle()
    // 확인 화면 없이 그 행의 userId로 본문 없는 요청 하나만 보냈다.
    expect(removeCalls().map((c) => [c.path, c.search, c.body])).toEqual([
      [`/api/v1/household/members/${TARGET}`, '', undefined],
    ])
    const href = screen.getByRole('link', { name: REAUTH }).getAttribute('href') ?? ''
    expect(href).toBe(REAUTH_HREF)
    const url = new URL(href, window.location.origin)
    expect([...url.searchParams]).toEqual([
      ['purpose', 'reauth'],
      ['returnTo', '/'],
    ])
    // 재인증 링크를 누르기 전: 주소도 history.state도 제거를 누르기 전 그대로다.
    expect(window.history.state).toEqual(historyBefore)
    expectNoRemoveTrace(stored)

    // 재인증을 마치고 같은 탭에서 문서를 새로 열어 /로 돌아온다.
    cleanup()
    state.reauthed = true
    const removesBefore = removeCalls().length
    const callsBefore = api.calls.length
    openAt('/')
    const again = await rowOf(OTHER_EMAIL)
    await settle()
    expect(api.calls.slice(callsBefore).some((c) => c.method === 'GET' && c.path === '/api/v1/me')).toBe(true)
    expect(removeCalls()).toHaveLength(removesBefore)
    expect(api.calls.slice(callsBefore).filter((c) => c.method === 'DELETE')).toEqual([])
    expect(screen.queryByText(REMOVE_REAUTH_NOTICE)).toBeNull()
    // 사용자가 같은 버튼을 다시 누를 수 있게 버튼은 그대로 있다.
    within(again).getByRole('button', { name: REMOVE })
    expectNoRemoveTrace(stored)
  })
})

describe('가구 삭제 재인증 뒤 (US4-3, FR-022, 결정 기록 0013, R14)', () => {
  const DELETE_HOUSEHOLD = '가구 삭제'
  const CONFIRM_NOTICE = '가구를 삭제하면 가구, 구성원 2명의 연결, 보낸 초대가 삭제되며 되돌릴 수 없습니다.'
  const DELETE_REAUTH_NOTICE = '가구를 삭제하려면 구글 계정을 한 번 더 확인해야 합니다.'
  const REAUTH_HREF = '/oauth2/authorization/google?purpose=reauth&returnTo=/household/delete'
  /** 밀린 동작의 이름이나 표시. 저장소, 쿠키, history.state의 usr, 링크(재인증 링크 밖), 콘솔에서 찾는다. */
  const PENDING = /delete|household/i

  /** contracts/openapi.yaml Members 스키마 모양. 나는 owner이고 구성원은 두 명이다. */
  const twoMembers: Reply = {
    status: 200,
    body: {
      members: [
        { userId: 1, displayName: '엄마', role: 'owner', loginEmail: 'user1@example.com', emailVerified: true, me: true },
        { userId: 2, displayName: '민지', role: 'member', loginEmail: 'user2@example.com', emailVerified: true, me: false },
      ],
    },
  }

  /** 재인증 전에는 가구 삭제가 401 REAUTH_REQUIRED, 재인증 뒤에는 204다(자동으로 다시 보내면 기록에 남는다). */
  function deleteServer() {
    const state = { reauthed: false }
    const handler = (call: ApiCall): Reply | undefined => {
      const is = (method: string, path: string) => call.method === method && call.path === path
      if (is('GET', '/api/v1/me')) return me
      if (is('GET', '/api/v1/household')) return twoMembers
      if (is('GET', '/api/v1/household/invitations')) return { status: 200, body: { invitations: [] } }
      if (is('DELETE', '/api/v1/household')) return state.reauthed ? noContent : reauthRequired
      return undefined
    }
    return { state, handler }
  }

  const deleteCalls = () => api.calls.filter((c) => c.method === 'DELETE')
  const usr = () => (window.history.state as { usr?: unknown } | null)?.usr ?? null

  /**
   * 삭제 흐름이 브라우저에 아무것도 남기지 않았는지 본다. 저장소, 쿠키, history.state의 usr는 삭제를 누르기 전 모습 그대로여야
   * 하고(밀린 동작 없음), 저장소, 쿠키, usr, 재인증 링크 밖의 링크, 콘솔에 밀린 동작의 이름이 없어야 한다.
   */
  function expectNoDeleteTrace(before: {
    local: (readonly [string, string])[]
    session: (readonly [string, string])[]
    cookie: string
    usr: unknown
  }) {
    expect(entries(localStorage)).toEqual(before.local)
    expect(entries(sessionStorage)).toEqual(before.session)
    for (const storage of [localStorage, sessionStorage]) {
      for (const [key, value] of entries(storage)) {
        expect(key).not.toMatch(PENDING)
        expect(value).not.toMatch(PENDING)
      }
    }
    expectAddressClean('/household/delete')
    expect(document.cookie).toBe(before.cookie)
    expect(document.cookie).not.toMatch(PENDING)
    expect(usr()).toEqual(before.usr)
    expect(JSON.stringify(usr())).not.toMatch(PENDING)
    for (const link of document.querySelectorAll('a')) {
      const href = link.getAttribute('href') ?? ''
      if (href !== REAUTH_HREF) expect(href).not.toMatch(PENDING)
    }
    for (const spy of consoleSpies) for (const args of spy.mock.calls) expect(args.map(printed).join(' ')).not.toMatch(PENDING)
  }

  it('US4-3, FR-022, 결정 기록 0013: 가구 삭제 재인증에서 /household/delete로 돌아와도 삭제를 다시 보내지 않고 확인 화면이 다시 보이며, 밀린 동작은 저장소, 주소, 쿠키, history.state, 링크, 콘솔 어디에도 없다', async () => {
    const { state, handler } = deleteServer()
    api = mockApi(handler)
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: DELETE_HOUSEHOLD }))
    await screen.findByText(CONFIRM_NOTICE)
    await settle()
    expectAddressClean('/household/delete')
    const before = { local: entries(localStorage), session: entries(sessionStorage), cookie: document.cookie, usr: usr() }
    expect(deleteCalls()).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: DELETE_HOUSEHOLD }))
    await screen.findByText(DELETE_REAUTH_NOTICE)
    await settle()
    // 본문 없는 가구 삭제 요청 하나만 보냈다.
    expect(deleteCalls().map((c) => [c.path, c.search, c.body])).toEqual([['/api/v1/household', '', undefined]])
    const href = screen.getByRole('link', { name: REAUTH }).getAttribute('href') ?? ''
    expect(href).toBe(REAUTH_HREF)
    const url = new URL(href, window.location.origin)
    expect([...url.searchParams]).toEqual([
      ['purpose', 'reauth'],
      ['returnTo', '/household/delete'],
    ])
    expect(url.hash).toBe('')
    // 재인증 링크를 누르기 전: 주소, 저장소, 쿠키, history.state의 usr가 삭제를 누르기 전 그대로다.
    expectNoDeleteTrace(before)

    // 재인증을 마치고 같은 탭에서 문서를 새로 열어 /household/delete로 돌아온다. 이제 삭제를 보내면 204다.
    cleanup()
    state.reauthed = true
    const callsBefore = api.calls.length
    openAt('/household/delete')
    await screen.findByText(CONFIRM_NOTICE)
    await settle()
    expect(api.calls.slice(callsBefore).some((c) => c.method === 'GET' && c.path === '/api/v1/me')).toBe(true)
    expect(api.calls.slice(callsBefore).filter((c) => c.method === 'DELETE')).toEqual([])
    expect(deleteCalls()).toHaveLength(1)
    expect(screen.queryByText(DELETE_REAUTH_NOTICE)).toBeNull()
    expect(screen.queryByRole('link', { name: REAUTH })).toBeNull()
    // 사용자가 다시 누를 수 있게 확인 화면의 두 버튼이 그대로 있다.
    screen.getByRole('button', { name: DELETE_HOUSEHOLD })
    screen.getByRole('button', { name: CANCEL })
    // 새 문서의 history.state는 비어 있으므로 usr는 null이어야 한다.
    expectNoDeleteTrace({ ...before, usr: null })
  })
})
