/**
 * T074 가구 삭제 확인 화면 테스트(spec US4-1~US4-4, US4-6, FR-019~FR-022, research R14).
 * 공개 표면은 login.test.tsx, invite.test.tsx, members.test.tsx 맨 위 주석에 더해 다음을 따른다.
 * - owner의 임시 첫 화면(`/`)에는 목록 아래, "로그아웃" 버튼 앞에 이름이 "가구 삭제"인 버튼이 있다. member 화면에는 없다.
 *   누르면 요청 없이 `/household/delete`(확인 화면)로 이동한다.
 * - 확인 화면에는 "가구를 삭제하면 가구, 구성원 {N}명의 연결, 보낸 초대가 삭제되며 되돌릴 수 없습니다."(한 요소의 글자 전체,
 *   N은 `GET /api/v1/household`의 members 사람 수)와 "가구 삭제", "취소" 버튼이 있다. 처음에는 재인증 안내와 링크가 없다.
 *   이 화면을 그릴 때는 보낸 초대 목록(`GET /api/v1/household/invitations`)을 요청하지 않는다.
 * - "취소"는 요청 없이 `/`의 임시 첫 화면으로 돌아간다. "가구 삭제"는 `DELETE /api/v1/household`(본문 없음)를 보낸다.
 *   204와 403 NO_HOUSEHOLD는 가구 없음 화면(주소 `/`), 401 UNAUTHENTICATED는 로그인 화면이다.
 *   401 REAUTH_REQUIRED는 확인 화면에 머물며 "가구를 삭제하려면 구글 계정을 한 번 더 확인해야 합니다."와 이름이 "구글 계정 확인"인
 *   링크(href `/oauth2/authorization/google?purpose=reauth&returnTo=/household/delete`)를 보인다.
 *   그 밖의 실패(500, 네트워크 오류, 403 PERMISSION_DENIED)는 확인 화면에 머물고 "잠시 후 다시 시도해 주세요."를 보인다.
 *   안내는 한 번에 하나만 보인다(마지막 응답의 안내).
 * - 재인증에서 돌아와 `/household/delete`를 새로 열어도 삭제 요청을 자동으로 보내지 않고, 하려던 동작을 localStorage,
 *   sessionStorage, 쿠키, 주소, history.state의 앱 자리(usr) 어디에도 남기지 않는다.
 * - member가 `/household/delete`를 열면 임시 첫 화면, 가구 없는 사람이면 가구 없음 화면, 로그인 안 했으면 로그인 화면이다.
 * - 늦은 응답: 요청 뒤 로그아웃이나 다른 401이 먼저 일어났으면 늦은 응답은 가구 없음 화면이나 안내를 만들지 않는다.
 *   같은 로그인 상태에서 "취소"로 첫 화면에 간 뒤 온 204와 403 NO_HOUSEHOLD는 가구 없음 화면으로 보내고,
 *   401 REAUTH_REQUIRED와 500은 아무것도 바꾸지 않는다.
 * 흉내 응답은 상태를 갖는다(삭제 뒤 가구 없음, 로그아웃이나 401 뒤 모든 요청이 401).
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

const LOGIN = 'Google 계정으로 로그인'
const NO_HOUSEHOLD = '아직 가구가 없습니다.'
const TITLE = '함께 보는 사람'
const DELETE_OPEN = '가구 삭제'
const CANCEL = '취소'
const LOGOUT = '로그아웃'
const REMOVE = '제거'
const LEAVE_OPEN = '가구 나가기'
const LEAVE = '나가기'
const LEAVE_GUIDE = '가구를 나가면 이 가구의 기록을 볼 수 없습니다. 다시 들어오려면 새 초대 링크가 필요합니다.'
const REAUTH_GUIDE = '가구를 삭제하려면 구글 계정을 한 번 더 확인해야 합니다.'
const REAUTH_LINK = '구글 계정 확인'
const REAUTH_HREF = '/oauth2/authorization/google?purpose=reauth&returnTo=/household/delete'
const RETRY = '잠시 후 다시 시도해 주세요.'
const DELETE_PATH = '/api/v1/household'
const INVITATIONS_PATH = '/api/v1/household/invitations'
const MEMBER_PATH = '/api/v1/household/members/2'
const MEMBERSHIP_PATH = '/api/v1/household/membership'
const EMAILS = ['user1@example.com', 'user2@example.com', 'owner@example.com']

const guideFor = (n: number) => `가구를 삭제하면 가구, 구성원 ${n}명의 연결, 보낸 초대가 삭제되며 되돌릴 수 없습니다.`

type Outcome = ReturnType<Parameters<typeof mockApi>[0]>

let navigation: ReturnType<typeof watchNavigation>
let api: ReturnType<typeof mockApi>

beforeEach(() => {
  navigation = watchNavigation()
  sessionStorage.clear()
  localStorage.clear()
})

afterEach(async () => {
  cleanup()
  await navigation.stop()
  vi.unstubAllGlobals()
  sessionStorage.clear()
  localStorage.clear()
  expect(navigation.attempts).toEqual([])
  expect(api.unmatched).toEqual([])
})

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  render(<App />)
}

// 계약(specs/002-household-sharing/contracts/openapi.yaml)의 reauthRequired 예시
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

const permissionDenied: Reply = {
  status: 403,
  body: {
    type: 'https://example.com/problems/permission-denied',
    title: '권한이 없습니다',
    status: 403,
    detail: '이 가구에서 할 수 없는 동작입니다.',
    code: 'PERMISSION_DENIED',
  },
}

const owner2: Reply = {
  status: 200,
  body: {
    members: [
      { userId: 1, displayName: '엄마', role: 'owner', loginEmail: 'user1@example.com', emailVerified: true, me: true },
      { userId: 2, displayName: '아빠', role: 'member', loginEmail: 'user2@example.com', emailVerified: false, me: false },
    ],
  },
}

const asMember: Reply = {
  status: 200,
  body: {
    members: [
      { userId: 1, displayName: '민지', role: 'owner', loginEmail: 'owner@example.com', emailVerified: true, me: false },
      { userId: 2, displayName: '엄마', role: 'member', loginEmail: 'user1@example.com', emailVerified: true, me: true },
    ],
  },
}

const is = (call: ApiCall, method: string, path: string) => call.method === method && call.path === path
const callsOf = (method: string, path: string) => api.calls.filter((c) => is(c, method, path))
const deleteCalls = () => callsOf('DELETE', DELETE_PATH)
const bodyText = () => document.body.textContent ?? ''

/**
 * 가구 상태를 갖는 서버 흉내. 삭제 응답은 차례로 쓰고 마지막 것을 되풀이한다.
 * 삭제가 204나 403 NO_HOUSEHOLD면 이후 가구 없음이 된다(keep이면 목록을 그대로 둔다. 늦은 응답 테스트용).
 * 로그아웃 204나 401 UNAUTHENTICATED 응답 뒤에는 모든 요청이 401이다.
 */
function server(o: { household: Reply; del?: Outcome[]; remove?: Reply; keep?: boolean }) {
  const state = { household: o.household, session: true, deletes: 0 }
  const answer = (reply: Reply) => {
    if (reply === unauthenticated) state.session = false
    return reply
  }
  return (call: ApiCall): Outcome => {
    if (is(call, 'POST', '/api/v1/logout')) {
      state.session = false
      return noContent
    }
    if (!state.session) return unauthenticated
    if (is(call, 'GET', '/api/v1/me')) return me
    if (is(call, 'GET', DELETE_PATH)) return state.household
    if (is(call, 'GET', INVITATIONS_PATH)) return { status: 200, body: { invitations: [] } }
    if (is(call, 'DELETE', MEMBER_PATH)) return answer(o.remove ?? noContent)
    if (is(call, 'DELETE', DELETE_PATH)) {
      const replies = o.del ?? [noContent]
      const reply = replies[Math.min(state.deletes++, replies.length - 1)]
      if (reply === networkError || !reply) return reply
      if (!o.keep && (reply.status === 204 || reply === noHousehold)) state.household = noHousehold
      return answer(reply)
    }
    return undefined
  }
}

/** 그 로그인 이메일 글자를 포함하는 listitem */
function rowOf(email: string) {
  const row = screen.getAllByRole('listitem').find((li) => (li.textContent ?? '').includes(email))
  if (!row) throw new Error(`행이 없습니다: ${email}`)
  return row
}

async function expectConfirm(n: number) {
  await screen.findByText(guideFor(n))
  screen.getByRole('button', { name: DELETE_OPEN })
  screen.getByRole('button', { name: CANCEL })
  expect(window.location.pathname).toBe('/household/delete')
}

const press = (name: string) => fireEvent.click(screen.getByRole('button', { name }))

describe('가구 삭제 버튼과 확인 화면', () => {
  it('US4-1: owner 첫 화면의 가구 삭제 버튼은 목록 아래, 로그아웃 버튼 앞에 있다', async () => {
    api = mockApi(server({ household: owner2 }))
    openAt('/')
    await screen.findByText(TITLE)
    const button = screen.getByRole('button', { name: DELETE_OPEN })
    expect(rowOf('user2@example.com').compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const logout = screen.getByRole('button', { name: LOGOUT })
    expect(button.compareDocumentPosition(logout) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it.each([
    { n: 1, household: members },
    { n: 2, household: owner2 },
  ])('US4-1: 가구 삭제를 누르면 요청 없이 구성원 $n 명 안내의 확인 화면이 먼저 보인다', async ({ n, household }) => {
    api = mockApi(server({ household }))
    openAt('/')
    await screen.findByText(TITLE)
    press(DELETE_OPEN)
    await expectConfirm(n)
    expect(screen.queryByRole('link', { name: REAUTH_LINK })).toBeNull()
    expect(screen.queryByText(REAUTH_GUIDE)).toBeNull()
    expect(screen.queryByText(RETRY)).toBeNull()
    await settle()
    expect(api.calls.filter((c) => c.method !== 'GET')).toEqual([])
  })

  it('US4-1: /household/delete 를 열면 확인 화면이고 초대 목록을 요청하지 않으며 삭제 요청도 없다', async () => {
    api = mockApi(server({ household: owner2, del: [serverError] }))
    openAt('/household/delete')
    await expectConfirm(2)
    await settle()
    expect(callsOf('GET', '/api/v1/me').length).toBeGreaterThan(0)
    expect(callsOf('GET', DELETE_PATH).length).toBeGreaterThan(0)
    expect(callsOf('GET', INVITATIONS_PATH)).toEqual([])
    expect(deleteCalls()).toEqual([])
    // 삭제 요청이 실패한 뒤에도 이 화면은 초대 목록을 요청하지 않는다
    press(DELETE_OPEN)
    await screen.findByText(RETRY)
    expect(callsOf('GET', INVITATIONS_PATH)).toEqual([])
  })

  it('US4-2: 취소하면 요청 없이 /로 돌아가 임시 첫 화면(목록)이 보인다', async () => {
    api = mockApi(server({ household: owner2 }))
    openAt('/')
    await screen.findByText(TITLE)
    press(DELETE_OPEN)
    await expectConfirm(2)
    press(CANCEL)
    await screen.findByText(TITLE)
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(guideFor(2))).toBeNull()
    expect(rowOf('user2@example.com').textContent).toContain('아빠')
    screen.getByRole('button', { name: DELETE_OPEN })
    await settle()
    expect(api.calls.filter((c) => c.method !== 'GET')).toEqual([])
  })
})

describe('가구 삭제 요청 결과', () => {
  it('US4-4: 가구 삭제가 DELETE /api/v1/household 하나(본문 없음)를 보내고 204면 가구 없음 화면이고 주소는 /이다', async () => {
    api = mockApi(server({ household: owner2 }))
    openAt('/household/delete')
    await expectConfirm(2)
    expect(deleteCalls()).toEqual([])
    press(DELETE_OPEN)
    await screen.findByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(guideFor(2))).toBeNull()
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
    await settle()
    expect(deleteCalls()).toHaveLength(1)
    expect(deleteCalls()[0].body).toBeUndefined()
    expect(api.calls.filter((c) => c.method !== 'GET')).toHaveLength(1)
  })

  it('403 NO_HOUSEHOLD면 가구 없음 화면이고 주소는 /이다', async () => {
    api = mockApi(server({ household: owner2, del: [noHousehold] }))
    openAt('/household/delete')
    await expectConfirm(2)
    press(DELETE_OPEN)
    await screen.findByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(guideFor(2))).toBeNull()
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(deleteCalls()).toHaveLength(1)
  })

  it('401 UNAUTHENTICATED면 로그인 화면이고 안내는 없다', async () => {
    api = mockApi(server({ household: owner2, del: [unauthenticated] }))
    openAt('/household/delete')
    await expectConfirm(2)
    press(DELETE_OPEN)
    await screen.findByRole('link', { name: LOGIN })
    await settle()
    expect(screen.queryByText(guideFor(2))).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByText(RETRY)).toBeNull()
    for (const email of EMAILS) expect(bodyText()).not.toContain(email)
    expect(deleteCalls()).toHaveLength(1)
  })

  it.each([
    { label: '500', reply: serverError },
    { label: '네트워크 오류', reply: networkError },
    { label: '403 PERMISSION_DENIED', reply: permissionDenied },
    { label: '409 HAS_HOUSEHOLD', reply: hasHousehold },
  ])('실패 [$label]: 확인 화면에 머물고 "잠시 후 다시 시도해 주세요."가 보이며 다시 누를 수 있다', async ({ reply }) => {
    api = mockApi(server({ household: owner2, del: [reply, reply] }))
    openAt('/household/delete')
    await expectConfirm(2)
    press(DELETE_OPEN)
    await screen.findByText(RETRY)
    await expectConfirm(2)
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
    expect(screen.queryByRole('link', { name: REAUTH_LINK })).toBeNull()
    expect(deleteCalls()).toHaveLength(1)
    press(DELETE_OPEN)
    await waitFor(() => expect(deleteCalls()).toHaveLength(2))
    await screen.findByText(RETRY)
  })
})

describe('가구 삭제의 재인증', () => {
  it('US4-3: 401 REAUTH_REQUIRED면 확인 화면에 머물고 안내와 구글 계정 확인 링크가 보인다', async () => {
    api = mockApi(server({ household: owner2, del: [reauthRequired] }))
    openAt('/household/delete')
    await expectConfirm(2)
    press(DELETE_OPEN)
    await screen.findByText(REAUTH_GUIDE)
    expect(screen.getByRole('link', { name: REAUTH_LINK }).getAttribute('href')).toBe(REAUTH_HREF)
    await expectConfirm(2)
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
    await settle()
    expect(deleteCalls()).toHaveLength(1)
  })

  it('US4-3, ADR-0013: 재인증에서 돌아와도 자동으로 보내지 않고, 같은 버튼을 다시 눌러야 보내며, 하려던 동작을 남기지 않는다', async () => {
    api = mockApi(server({ household: owner2, del: [reauthRequired, noContent] }))
    openAt('/household/delete')
    await expectConfirm(2)
    press(DELETE_OPEN)
    await screen.findByText(REAUTH_GUIDE)
    await settle()
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    expect(document.cookie).toBe('')
    expect(window.location.pathname).toBe('/household/delete')
    expect(window.location.search).toBe('')
    expect(window.location.hash).toBe('')
    // BrowserRouter가 자기 기록(idx, key)을 쓰므로 앱이 넣는 자리(usr)만 본다
    expect(window.history.state?.usr ?? null).toBeNull()

    // 재인증에서 돌아온 것을 같은 탭에서 앱을 다시 그리는 것으로 흉내 낸다
    cleanup()
    openAt('/household/delete')
    await expectConfirm(2)
    await settle()
    expect(deleteCalls()).toHaveLength(1)
    expect(screen.queryByText(REAUTH_GUIDE)).toBeNull()
    expect(screen.queryByRole('link', { name: REAUTH_LINK })).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()

    press(DELETE_OPEN)
    await screen.findByText(NO_HOUSEHOLD)
    expect(deleteCalls()).toHaveLength(2)
    expect(window.location.pathname).toBe('/')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
})

describe('안내는 한 번에 하나만 보인다', () => {
  it('재인증 안내 뒤 새 삭제가 500이면 다시 시도 안내만 남는다', async () => {
    api = mockApi(server({ household: owner2, del: [reauthRequired, serverError] }))
    openAt('/household/delete')
    await expectConfirm(2)
    press(DELETE_OPEN)
    await screen.findByText(REAUTH_GUIDE)
    press(DELETE_OPEN)
    await screen.findByText(RETRY)
    expect(screen.queryByText(REAUTH_GUIDE)).toBeNull()
    expect(screen.queryByRole('link', { name: REAUTH_LINK })).toBeNull()
    expect(deleteCalls()).toHaveLength(2)
  })

  it('다시 시도 안내 뒤 새 삭제가 재인증이면 재인증 안내만 남는다', async () => {
    api = mockApi(server({ household: owner2, del: [serverError, reauthRequired] }))
    openAt('/household/delete')
    await expectConfirm(2)
    press(DELETE_OPEN)
    await screen.findByText(RETRY)
    press(DELETE_OPEN)
    await screen.findByText(REAUTH_GUIDE)
    expect(screen.queryByText(RETRY)).toBeNull()
    screen.getByRole('link', { name: REAUTH_LINK })
    expect(deleteCalls()).toHaveLength(2)
  })

  it.each([
    { label: '재인증 안내', first: reauthRequired },
    { label: '다시 시도 안내', first: serverError },
  ])('$label 뒤 새 삭제가 성공하면 가구 없음 화면이고 앞 안내가 남지 않는다', async ({ first }) => {
    api = mockApi(server({ household: owner2, del: [first, noContent] }))
    openAt('/household/delete')
    await expectConfirm(2)
    press(DELETE_OPEN)
    await waitFor(() => expect(bodyText()).toMatch(/한 번 더 확인|잠시 후/))
    press(DELETE_OPEN)
    await screen.findByText(NO_HOUSEHOLD)
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(screen.queryByText(REAUTH_GUIDE)).toBeNull()
    expect(screen.queryByRole('link', { name: REAUTH_LINK })).toBeNull()
    expect(deleteCalls()).toHaveLength(2)
  })

  it('재인증 안내 뒤 새 삭제가 403 NO_HOUSEHOLD면 가구 없음 화면이고 재인증 안내가 남지 않는다', async () => {
    api = mockApi(server({ household: owner2, del: [reauthRequired, noHousehold] }))
    openAt('/household/delete')
    await expectConfirm(2)
    press(DELETE_OPEN)
    await screen.findByText(REAUTH_GUIDE)
    press(DELETE_OPEN)
    await screen.findByText(NO_HOUSEHOLD)
    expect(screen.queryByText(REAUTH_GUIDE)).toBeNull()
    expect(screen.queryByRole('link', { name: REAUTH_LINK })).toBeNull()
  })
})

describe('다른 구성원과 member', () => {
  it('US4-4: 다른 구성원은 삭제 뒤 다음 가구 요청의 403 NO_HOUSEHOLD로 가구 없음 화면이 된다', async () => {
    // owner가 가구를 지운 뒤라 member의 나가기 요청이 403 NO_HOUSEHOLD를 받는다
    api = mockApi((call) => {
      if (is(call, 'GET', '/api/v1/me')) return me
      if (is(call, 'GET', DELETE_PATH)) return asMember
      if (is(call, 'DELETE', MEMBERSHIP_PATH)) return noHousehold
      return undefined
    })
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(within(rowOf('user1@example.com')).getByRole('button', { name: LEAVE_OPEN }))
    await screen.findByText(LEAVE_GUIDE)
    press(LEAVE)
    await screen.findByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(screen.queryByText(RETRY)).toBeNull()
  })

  it('US4-6: member 화면에는 가구 삭제 버튼이 어디에도 없다', async () => {
    api = mockApi(server({ household: asMember }))
    openAt('/')
    await screen.findByText(TITLE)
    screen.getByRole('button', { name: LOGOUT })
    expect(screen.queryByRole('button', { name: DELETE_OPEN })).toBeNull()
    expect(screen.queryByRole('link', { name: DELETE_OPEN })).toBeNull()
    await settle()
    expect(deleteCalls()).toEqual([])
  })

  it('member가 /household/delete 를 직접 열면 확인 화면 대신 임시 첫 화면(목록)이 보이고 삭제 요청은 없다', async () => {
    api = mockApi(server({ household: asMember }))
    openAt('/household/delete')
    await screen.findByText(TITLE)
    expect(screen.queryByText(guideFor(2))).toBeNull()
    expect(screen.queryByText(/가구를 삭제하면/)).toBeNull()
    expect(screen.queryByRole('button', { name: DELETE_OPEN })).toBeNull()
    await settle()
    expect(deleteCalls()).toEqual([])
  })

  it('가구 없는 사람이 /household/delete 를 열면 가구 없음 화면이다', async () => {
    api = mockApi(server({ household: noHousehold }))
    openAt('/household/delete')
    await screen.findByText(NO_HOUSEHOLD)
    expect(screen.queryByText(/가구를 삭제하면/)).toBeNull()
    expect(screen.queryByRole('button', { name: DELETE_OPEN })).toBeNull()
    await settle()
    expect(deleteCalls()).toEqual([])
  })

  it('로그인하지 않고 /household/delete 를 열면 로그인 화면이다', async () => {
    api = mockApi((call) => (is(call, 'GET', '/api/v1/me') ? unauthenticated : undefined))
    openAt('/household/delete')
    await screen.findByRole('link', { name: LOGIN })
    expect(screen.queryByText(/가구를 삭제하면/)).toBeNull()
    expect(deleteCalls()).toEqual([])
  })
})

describe('늦게 온 응답', () => {
  /**
   * mockApi 위에 fetch를 한 겹 더 씌워, 이 함수를 부른 뒤 처음 오는 method path 요청의 응답을 붙잡아 두었다가 release()로 푼다.
   * 요청은 mockApi에 그대로 기록되고, 응답은 요청을 받은 순간의 서버 상태로 정해진다.
   * 브라우저처럼 그 요청의 signal이 풀기 전에 중단되었으면 AbortError로 거부한다.
   */
  function holdNext(method: string, path: string) {
    const mocked = globalThis.fetch
    const hold = { held: false, release: () => {} }
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : undefined
      const url = new URL(request ? request.url : String(input), window.location.href)
      const requestMethod = (init?.method ?? request?.method ?? 'GET').toUpperCase()
      if (hold.held || requestMethod !== method || url.pathname !== path) return mocked(input, init)
      hold.held = true
      const answer = await mocked(input, init)
      await new Promise<void>((resolve) => {
        hold.release = resolve
      })
      if ((init?.signal ?? request?.signal)?.aborted) throw new DOMException('The operation was aborted.', 'AbortError')
      return answer
    })
    return hold
  }

  /** settle 뒤 최종 화면이 로그인 화면뿐이다. 안내, 가구 없음 화면, 목록, 확인 화면, 구성원 이메일이 없다. */
  async function expectOnlyLoginScreen() {
    await settle()
    screen.getByRole('link', { name: LOGIN })
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(screen.queryByText(REAUTH_GUIDE)).toBeNull()
    expect(screen.queryByRole('link', { name: REAUTH_LINK })).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(screen.queryByText(guideFor(2))).toBeNull()
    expect(screen.queryByRole('button', { name: DELETE_OPEN })).toBeNull()
    for (const email of EMAILS) expect(bodyText()).not.toContain(email)
  }

  /** owner가 가구 삭제를 누르고 응답을 기다리는 동안 같은 세션에서 취소로 첫 화면(목록)에 돌아온다. 목록은 그대로다. */
  async function deleteThenCancel(del: Reply, remove?: Reply) {
    api = mockApi(server({ household: owner2, del: [del], remove, keep: true }))
    openAt('/household/delete')
    await expectConfirm(2)
    await settle()
    const hold = holdNext('DELETE', DELETE_PATH)
    press(DELETE_OPEN)
    await waitFor(() => expect(hold.held).toBe(true))
    press(CANCEL)
    await screen.findByText(TITLE)
    expect(window.location.pathname).toBe('/')
    expect(rowOf('user2@example.com').textContent).toContain('아빠')
    return hold
  }

  it.each([
    { label: '204', del: noContent },
    { label: '403 NO_HOUSEHOLD', del: noHousehold },
    { label: '401 REAUTH_REQUIRED', del: reauthRequired },
    { label: '500', del: serverError },
  ])('삭제 $label 이 취소와 로그아웃 뒤에 늦게 와도 로그인 화면 그대로이고 가구 없음 화면이나 안내가 나오지 않는다', async ({ del }) => {
    const hold = await deleteThenCancel(del)
    press(LOGOUT)
    await screen.findByRole('link', { name: LOGIN })
    // owner 화면이 다시 그려지면 보내는 초대 목록 요청의 401이 로그인 화면으로 되돌려 결함을 가리므로, 그 응답은 풀지 않는다
    holdNext('GET', INVITATIONS_PATH)
    act(() => hold.release())
    await expectOnlyLoginScreen()
    expect(deleteCalls()).toHaveLength(1)
    expect(callsOf('POST', '/api/v1/logout')).toHaveLength(1)
  })

  it('삭제 204가 취소 뒤 다른 요청의 401 UNAUTHENTICATED 뒤에 늦게 와도 로그인 화면 그대로이고 가구 없음 화면이 나오지 않는다', async () => {
    const hold = await deleteThenCancel(noContent, unauthenticated)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByRole('link', { name: LOGIN })
    holdNext('GET', INVITATIONS_PATH)
    act(() => hold.release())
    await expectOnlyLoginScreen()
    expect(deleteCalls()).toHaveLength(1)
  })

  it.each([
    { label: '204', del: noContent },
    { label: '403 NO_HOUSEHOLD', del: noHousehold },
  ])('R14: 같은 로그인 상태에서 취소 뒤 늦게 온 삭제 $label 이면 가구 없음 화면이고 주소는 /이다', async ({ del }) => {
    const hold = await deleteThenCancel(del)
    act(() => hold.release())
    await settle()
    screen.getByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
    expect(deleteCalls()).toHaveLength(1)
  })

  it.each([
    { label: '401 REAUTH_REQUIRED', del: reauthRequired },
    { label: '500', del: serverError },
  ])('취소 뒤 늦게 온 삭제 $label 이면 첫 화면 목록이 그대로이고 안내를 띄우지 않는다', async ({ del }) => {
    const hold = await deleteThenCancel(del)
    act(() => hold.release())
    await settle()
    screen.getByText(TITLE)
    expect(rowOf('user2@example.com').textContent).toContain('아빠')
    screen.getByRole('button', { name: DELETE_OPEN })
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(screen.queryByText(REAUTH_GUIDE)).toBeNull()
    expect(screen.queryByRole('link', { name: REAUTH_LINK })).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByText(guideFor(2))).toBeNull()
    expect(deleteCalls()).toHaveLength(1)
  })

  it('응답을 기다리는 동안 가구 삭제를 다시 눌러도 삭제 요청은 하나다', async () => {
    api = mockApi(server({ household: owner2 }))
    openAt('/household/delete')
    await expectConfirm(2)
    await settle()
    const hold = holdNext('DELETE', DELETE_PATH)
    press(DELETE_OPEN)
    await waitFor(() => expect(hold.held).toBe(true))
    press(DELETE_OPEN)
    press(DELETE_OPEN)
    await act(async () => {})
    expect(deleteCalls()).toHaveLength(1)
    act(() => hold.release())
    await screen.findByText(NO_HOUSEHOLD)
    expect(deleteCalls()).toHaveLength(1)
  })

  it('앞 안내는 가구 삭제를 다시 누르면 응답이 오기 전에 사라진다', async () => {
    api = mockApi(server({ household: owner2, del: [serverError, serverError] }))
    openAt('/household/delete')
    await expectConfirm(2)
    press(DELETE_OPEN)
    await screen.findByText(RETRY)
    const hold = holdNext('DELETE', DELETE_PATH)
    press(DELETE_OPEN)
    await waitFor(() => expect(hold.held).toBe(true))
    expect(screen.queryByText(RETRY)).toBeNull()
    act(() => hold.release())
    await screen.findByText(RETRY)
    expect(deleteCalls()).toHaveLength(2)
  })
})
