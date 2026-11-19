/**
 * T060 함께 보는 사람 화면 동작 테스트(spec US3-1~US3-10, FR-016~FR-019, FR-022, research R14).
 * 공개 표면은 login.test.tsx, invite.test.tsx 맨 위 주석에 더해 다음을 따른다.
 * - 사람마다 `listitem` 하나이고, 그 행은 로그인 이메일 글자를 포함하는 `listitem`으로 찾는다.
 *   행에는 표시 이름(있을 때만), 내 행이면 "(나)", 역할, 로그인 이메일, 검증되지 않았으면 "검증되지 않음"이 있다.
 * - owner에게만 다른 구성원 행의 "제거" 버튼이 있고, member에게만 내 행의 "가구 나가기" 버튼이 있다.
 * - "제거"는 확인 화면 없이 DELETE /api/v1/household/members/{userId}(본문 없음)를 보내고, 204와 404면 목록을 다시 읽는다.
 *   401 REAUTH_REQUIRED는 안내와 구글 계정 확인 링크(purpose=reauth&returnTo=/)이고 돌아와도 자동으로 다시 보내지 않으며
 *   하려던 동작을 어디에도 남기지 않는다. 그 밖의 실패는 "잠시 후 다시 시도해 주세요."다. 안내는 한 번에 하나만 보인다.
 * - "가구 나가기"는 요청 없이 /household/leave(확인 화면)로 이동한다. "나가기"가 DELETE /api/v1/household/membership(본문 없음)을
 *   보내고, 204와 403 NO_HOUSEHOLD는 가구 없음 화면(주소 /), 401 UNAUTHENTICATED는 로그인 화면, 그 밖의 실패는 확인 화면에 머문다.
 *   "취소"는 요청 없이 /로 돌아간다. owner가 /household/leave를 열면 첫 화면, 가구 없는 사람이면 가구 없음 화면이다.
 * 흉내 응답은 상태를 갖는다(제거 뒤 구성원이 한 명, 나간 뒤 가구 없음).
 */
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../src/App'
import {
  type ApiCall,
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
const EXPIRY_LABEL = '만료 기간'
const MAKE = '초대 링크 만들기'
const REMOVE = '제거'
const LEAVE_OPEN = '가구 나가기'
const LEAVE = '나가기'
const CANCEL = '취소'
const REAUTH_GUIDE = '구성원을 제거하려면 구글 계정을 한 번 더 확인해야 합니다.'
const REAUTH_LINK = '구글 계정 확인'
const RETRY = '잠시 후 다시 시도해 주세요.'
const LEAVE_GUIDE = '가구를 나가면 이 가구의 기록을 볼 수 없습니다. 다시 들어오려면 새 초대 링크가 필요합니다.'
const MEMBER_PATH = '/api/v1/household/members/2'
const MEMBERSHIP_PATH = '/api/v1/household/membership'

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

function problem(status: number, code: string): Reply {
  return {
    status,
    body: {
      type: `https://example.com/problems/${code.toLowerCase()}`,
      title: code,
      status,
      detail: '계약 예시',
      code,
    },
  }
}

const is = (call: ApiCall, method: string, path: string) => call.method === method && call.path === path
const callsOf = (method: string, path: string) => api.calls.filter((c) => is(c, method, path))
const bodyText = () => document.body.textContent ?? ''

const owner2: Reply = {
  status: 200,
  body: {
    members: [
      { userId: 1, displayName: '엄마', role: 'owner', loginEmail: 'user1@example.com', emailVerified: true, me: true },
      { userId: 2, displayName: '아빠', role: 'member', loginEmail: 'user2@example.com', emailVerified: false, me: false },
    ],
  },
}

// 두 번째 사람의 표시 이름이 없다
const owner2Anonymous: Reply = {
  status: 200,
  body: {
    members: [
      { userId: 1, displayName: '엄마', role: 'owner', loginEmail: 'user1@example.com', emailVerified: true, me: true },
      { userId: 2, displayName: null, role: 'member', loginEmail: 'user2@example.com', emailVerified: false, me: false },
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

/**
 * 가구 상태를 갖는 서버 흉내. 제거 응답과 나가기 응답은 차례로 쓰고 마지막 것을 되풀이한다.
 * 204면 제거 뒤 한 명(members), 나간 뒤 가구 없음이 되고, 그 밖에는 afterFail(없으면 그대로)이 된다.
 * 401 UNAUTHENTICATED를 돌려준 뒤에는 /me도 401이다.
 */
function server(o: { household: Reply; remove?: Outcome[]; leave?: Outcome[]; afterFail?: Reply }) {
  const state = { household: o.household, loggedOut: false, removed: 0, left: 0 }
  const answer = (replies: Outcome[], n: number, done: Reply): Outcome => {
    const reply = replies[Math.min(n, replies.length - 1)]
    if (reply !== networkError && reply?.status === 204) state.household = done
    else if (o.afterFail) state.household = o.afterFail
    if (reply === unauthenticated) state.loggedOut = true
    return reply
  }
  return (call: ApiCall): Outcome => {
    if (is(call, 'GET', '/api/v1/me')) return state.loggedOut ? unauthenticated : me
    if (is(call, 'GET', '/api/v1/household')) return state.household
    if (is(call, 'GET', '/api/v1/household/invitations')) return { status: 200, body: { invitations: [] } }
    if (is(call, 'DELETE', MEMBER_PATH)) return answer(o.remove ?? [noContent], state.removed++, members)
    if (is(call, 'DELETE', MEMBERSHIP_PATH)) return answer(o.leave ?? [noContent], state.left++, noHousehold)
    return undefined
  }
}

/** 그 로그인 이메일 글자를 포함하는 listitem */
function rowOf(email: string) {
  const row = screen.getAllByRole('listitem').find((li) => (li.textContent ?? '').includes(email))
  if (!row) throw new Error(`행이 없습니다: ${email}`)
  return row
}

const removeCalls = () => callsOf('DELETE', MEMBER_PATH)
const leaveCalls = () => callsOf('DELETE', MEMBERSHIP_PATH)

describe('목록 표시(US3-1)', () => {
  it('US3-1: owner 화면에 표시 이름, 역할, 이메일, "(나)", "검증되지 않음"이 사람마다 맞는 행에 보인다', async () => {
    api = mockApi(server({ household: owner2 }))
    openAt('/')
    await screen.findByText(TITLE)
    const mine = rowOf('user1@example.com')
    expect(mine.textContent).toContain('엄마')
    expect(mine.textContent).toContain('(나)')
    expect(mine.textContent).toContain('owner')
    expect(mine.textContent).not.toContain('검증되지 않음')
    const other = rowOf('user2@example.com')
    expect(other.textContent).toContain('아빠')
    expect(other.textContent).toContain('member')
    expect(other.textContent).toContain('검증되지 않음')
    expect(other.textContent).not.toContain('(나)')
    expect(other.textContent).not.toContain('owner')
  })

  it('US3-1: 표시 이름이 없는 사람은 이메일만 보이고 역할과 "검증되지 않음"은 그대로 보인다', async () => {
    api = mockApi(server({ household: owner2Anonymous }))
    openAt('/')
    await screen.findByText(TITLE)
    const other = rowOf('user2@example.com')
    expect(other.textContent).toContain('member')
    expect(other.textContent).toContain('검증되지 않음')
    expect(other.textContent).not.toMatch(/null|undefined/)
    expect(other.textContent).not.toContain('아빠')
  })
})

describe('구성원 제거(owner)', () => {
  it('US3-7, US3-8: owner는 다른 구성원 행에만 제거 버튼이 있고 가구 나가기 버튼은 어디에도 없다', async () => {
    api = mockApi(server({ household: owner2 }))
    openAt('/')
    await screen.findByText(TITLE)
    within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE })
    expect(within(rowOf('user1@example.com')).queryByRole('button', { name: REMOVE })).toBeNull()
    expect(screen.getAllByRole('button', { name: REMOVE })).toHaveLength(1)
    expect(screen.queryByRole('button', { name: LEAVE_OPEN })).toBeNull()
    await settle()
    expect(removeCalls()).toEqual([])
  })

  it('US3-8: member는 내 행에만 가구 나가기 버튼이 있고 제거 버튼은 어디에도 없다. 누르기 전에는 요청이 없다', async () => {
    api = mockApi(server({ household: asMember }))
    openAt('/')
    await screen.findByText(TITLE)
    within(rowOf('user1@example.com')).getByRole('button', { name: LEAVE_OPEN })
    expect(within(rowOf('owner@example.com')).queryByRole('button', { name: LEAVE_OPEN })).toBeNull()
    expect(screen.queryByRole('button', { name: REMOVE })).toBeNull()
    await settle()
    expect(leaveCalls()).toEqual([])
  })

  it('US3-2, US3-10: 제거를 누르면 확인 화면 없이 DELETE 한 번(본문 없음)을 보내고, 다시 읽어 행이 사라지고 초대 만들기가 다시 보인다', async () => {
    api = mockApi(server({ household: owner2 }))
    openAt('/')
    await screen.findByText(TITLE)
    // 구성원이 둘이면 초대를 만들 수 없다
    expect(screen.queryByLabelText(EXPIRY_LABEL)).toBeNull()
    expect(screen.queryByRole('button', { name: MAKE })).toBeNull()
    const gets = callsOf('GET', '/api/v1/household').length
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    expect(screen.queryByRole('dialog')).toBeNull()
    await screen.findByLabelText(EXPIRY_LABEL)
    screen.getByRole('button', { name: MAKE })
    await settle()
    expect(removeCalls()).toHaveLength(1)
    expect(removeCalls()[0]).toMatchObject({ method: 'DELETE', path: MEMBER_PATH, body: undefined })
    expect(callsOf('GET', '/api/v1/household')).toHaveLength(gets + 1)
    expect(bodyText()).not.toContain('user2@example.com')
    expect(bodyText()).not.toContain('아빠')
    expect(bodyText()).toContain('user1@example.com')
    expect(window.location.pathname).toBe('/')
    expect(bodyText()).not.toContain(RETRY)
  })

  it('US3-3: 제거 뒤 다시 읽은 목록이 403 NO_HOUSEHOLD면 가구 없음 화면이다', async () => {
    // 제거는 성공했지만 그 사이 가구가 없어진 경우: 다시 읽기가 403이다
    const inner = server({ household: owner2 })
    let removed = false
    api = mockApi((call) => {
      if (is(call, 'DELETE', MEMBER_PATH)) {
        removed = true
        return noContent
      }
      if (removed && is(call, 'GET', '/api/v1/household')) return noHousehold
      return inner(call)
    })
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(TITLE)).toBeNull()
  })

  it('US3-3: 제거가 403 NO_HOUSEHOLD면 가구 없음 화면이고 주소는 /이다', async () => {
    api = mockApi(server({ household: owner2, remove: [noHousehold], afterFail: noHousehold }))
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')
    expect(removeCalls()).toHaveLength(1)
  })

  it('401 UNAUTHENTICATED면 로그인 화면이다', async () => {
    api = mockApi(server({ household: owner2, remove: [unauthenticated] }))
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByRole('link', { name: LOGIN })
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(removeCalls()).toHaveLength(1)
  })

  it('404면 목록을 다시 읽어 이미 제거된 행이 사라지고 안내는 없다', async () => {
    api = mockApi(server({ household: owner2, remove: [problem(404, 'NOT_FOUND')], afterFail: members }))
    openAt('/')
    await screen.findByText(TITLE)
    const gets = callsOf('GET', '/api/v1/household').length
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByLabelText(EXPIRY_LABEL)
    await settle()
    expect(callsOf('GET', '/api/v1/household')).toHaveLength(gets + 1)
    expect(bodyText()).not.toContain('user2@example.com')
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(screen.queryByText(REAUTH_GUIDE)).toBeNull()
    expect(removeCalls()).toHaveLength(1)
  })

  it.each([
    { label: '500', reply: serverError },
    { label: '네트워크 오류', reply: networkError },
    { label: '403 PERMISSION_DENIED', reply: problem(403, 'PERMISSION_DENIED') },
  ])('[$label] "잠시 후 다시 시도해 주세요."가 보이고 목록은 그대로다', async ({ reply }) => {
    api = mockApi(server({ household: owner2, remove: [reply] }))
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText(RETRY)
    expect(rowOf('user2@example.com').textContent).toContain('아빠')
    expect(screen.queryByText(REAUTH_GUIDE)).toBeNull()
    expect(removeCalls()).toHaveLength(1)
    expect(window.location.pathname).toBe('/')
  })
})

describe('제거의 재인증', () => {
  it('FR-022: 401 REAUTH_REQUIRED면 안내와 구글 계정 확인 링크가 보이고 목록은 그대로다', async () => {
    api = mockApi(server({ household: owner2, remove: [problem(401, 'REAUTH_REQUIRED')] }))
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText(REAUTH_GUIDE)
    const link = screen.getByRole('link', { name: REAUTH_LINK })
    expect(link.getAttribute('href')).toBe('/oauth2/authorization/google?purpose=reauth&returnTo=/')
    expect(rowOf('user2@example.com').textContent).toContain('아빠')
    expect(screen.queryByText(RETRY)).toBeNull()
    await settle()
    expect(removeCalls()).toHaveLength(1)
  })

  it('ADR-0013: 재인증에서 돌아와도 제거를 자동으로 보내지 않고, 대상과 하려던 동작을 어디에도 남기지 않는다', async () => {
    api = mockApi(server({ household: owner2, remove: [problem(401, 'REAUTH_REQUIRED')] }))
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText(REAUTH_GUIDE)
    await settle()
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    expect(window.location.pathname).toBe('/')
    expect(window.location.search).toBe('')
    expect(window.location.hash).toBe('')
    // BrowserRouter가 자기 기록(idx, key)을 쓰므로 앱이 넣는 자리(usr)만 본다
    expect(window.history.state?.usr ?? null).toBeNull()
    expect(screen.getByRole('link', { name: REAUTH_LINK }).getAttribute('href')).not.toMatch(/user|member|2$/)

    // 재인증에서 돌아온 것을 같은 탭에서 앱을 다시 그리는 것으로 흉내 낸다
    cleanup()
    openAt('/')
    await screen.findByText(TITLE)
    await settle()
    expect(removeCalls()).toHaveLength(1)
    expect(screen.queryByText(REAUTH_GUIDE)).toBeNull()
    expect(rowOf('user2@example.com').textContent).toContain('아빠')
  })

  it('안내는 한 번에 하나만 보인다: 재인증 안내 뒤 새 제거가 실패하면 마지막 응답의 안내만 남는다', async () => {
    api = mockApi(server({ household: owner2, remove: [problem(401, 'REAUTH_REQUIRED'), serverError] }))
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText(REAUTH_GUIDE)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText(RETRY)
    expect(screen.queryByText(REAUTH_GUIDE)).toBeNull()
    expect(screen.queryByRole('link', { name: REAUTH_LINK })).toBeNull()
    expect(removeCalls()).toHaveLength(2)
  })

  it('안내는 한 번에 하나만 보인다: 다시 시도 안내 뒤 새 제거가 재인증이면 재인증 안내만 남는다', async () => {
    api = mockApi(server({ household: owner2, remove: [serverError, problem(401, 'REAUTH_REQUIRED')] }))
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText(RETRY)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText(REAUTH_GUIDE)
    expect(screen.queryByText(RETRY)).toBeNull()
  })

  it('안내는 한 번에 하나만 보인다: 다시 시도 안내 뒤 새 제거가 성공하면 안내가 남지 않는다', async () => {
    api = mockApi(server({ household: owner2, remove: [serverError, noContent] }))
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText(RETRY)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByRole('button', { name: MAKE })
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(removeCalls()).toHaveLength(2)
  })
})

describe('가구 나가기 확인 화면(member)', () => {
  it('US3-5: 가구 나가기를 누르면 요청 없이 /household/leave로 가서 문구와 두 버튼이 보이고 재인증 안내는 없다', async () => {
    api = mockApi(server({ household: asMember }))
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(within(rowOf('user1@example.com')).getByRole('button', { name: LEAVE_OPEN }))
    await screen.findByText(LEAVE_GUIDE)
    expect(window.location.pathname).toBe('/household/leave')
    screen.getByRole('button', { name: LEAVE })
    screen.getByRole('button', { name: CANCEL })
    expect(screen.queryByRole('link', { name: REAUTH_LINK })).toBeNull()
    expect(screen.queryByText(/한 번 더 확인/)).toBeNull()
    await settle()
    expect(leaveCalls()).toEqual([])
  })

  it('US3-5: 취소하면 요청 없이 /로 돌아가 임시 첫 화면(목록)이 보인다', async () => {
    api = mockApi(server({ household: asMember }))
    openAt('/household/leave')
    await screen.findByText(LEAVE_GUIDE)
    fireEvent.click(screen.getByRole('button', { name: CANCEL }))
    await screen.findByText(TITLE)
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(LEAVE_GUIDE)).toBeNull()
    within(rowOf('user1@example.com')).getByRole('button', { name: LEAVE_OPEN })
    await settle()
    expect(leaveCalls()).toEqual([])
  })

  it('US3-6: 나가기를 누르면 DELETE 한 번(본문 없음)을 보내고 가구 없음 화면(주소 /)이 보인다', async () => {
    api = mockApi(server({ household: asMember }))
    openAt('/household/leave')
    await screen.findByText(LEAVE_GUIDE)
    fireEvent.click(screen.getByRole('button', { name: LEAVE }))
    await screen.findByText(NO_HOUSEHOLD)
    await settle()
    expect(leaveCalls()).toHaveLength(1)
    expect(leaveCalls()[0]).toMatchObject({ method: 'DELETE', path: MEMBERSHIP_PATH, body: undefined })
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(LEAVE_GUIDE)).toBeNull()
  })

  it('나가기가 403 NO_HOUSEHOLD면 가구 없음 화면이고 주소는 /이다', async () => {
    api = mockApi(server({ household: asMember, leave: [noHousehold], afterFail: noHousehold }))
    openAt('/household/leave')
    await screen.findByText(LEAVE_GUIDE)
    fireEvent.click(screen.getByRole('button', { name: LEAVE }))
    await screen.findByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')
    expect(leaveCalls()).toHaveLength(1)
  })

  it('나가기가 401 UNAUTHENTICATED면 로그인 화면이다', async () => {
    api = mockApi(server({ household: asMember, leave: [unauthenticated] }))
    openAt('/household/leave')
    await screen.findByText(LEAVE_GUIDE)
    fireEvent.click(screen.getByRole('button', { name: LEAVE }))
    await screen.findByRole('link', { name: LOGIN })
    expect(screen.queryByText(LEAVE_GUIDE)).toBeNull()
    expect(leaveCalls()).toHaveLength(1)
  })

  it.each([
    { label: '500', reply: serverError },
    { label: '네트워크 오류', reply: networkError },
    { label: '403 PERMISSION_DENIED', reply: problem(403, 'PERMISSION_DENIED') },
  ])('나가기 실패 [$label]: 확인 화면에 머물고 "잠시 후 다시 시도해 주세요."가 보인다', async ({ reply }) => {
    api = mockApi(server({ household: asMember, leave: [reply] }))
    openAt('/household/leave')
    await screen.findByText(LEAVE_GUIDE)
    fireEvent.click(screen.getByRole('button', { name: LEAVE }))
    await screen.findByText(RETRY)
    expect(window.location.pathname).toBe('/household/leave')
    screen.getByText(LEAVE_GUIDE)
    screen.getByRole('button', { name: LEAVE })
    screen.getByRole('button', { name: CANCEL })
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(leaveCalls()).toHaveLength(1)
  })

  it('owner가 /household/leave를 직접 열면 확인 화면 대신 첫 화면(목록)이 보이고 나가기 요청은 없다', async () => {
    api = mockApi(server({ household: owner2 }))
    openAt('/household/leave')
    await screen.findByText(TITLE)
    expect(screen.queryByText(LEAVE_GUIDE)).toBeNull()
    expect(screen.queryByRole('button', { name: LEAVE })).toBeNull()
    expect(screen.queryByRole('button', { name: LEAVE_OPEN })).toBeNull()
    await settle()
    expect(leaveCalls()).toEqual([])
  })

  it('가구 없는 사람이 /household/leave를 열면 가구 없음 화면이다', async () => {
    api = mockApi(server({ household: noHousehold }))
    openAt('/household/leave')
    await screen.findByText(NO_HOUSEHOLD)
    expect(screen.queryByText(LEAVE_GUIDE)).toBeNull()
    expect(screen.queryByRole('button', { name: LEAVE })).toBeNull()
    await settle()
    expect(leaveCalls()).toEqual([])
  })

  it('로그인하지 않고 /household/leave를 열면 로그인 화면이다', async () => {
    api = mockApi((call) => (is(call, 'GET', '/api/v1/me') ? unauthenticated : undefined))
    openAt('/household/leave')
    await screen.findByRole('link', { name: LOGIN })
    expect(screen.queryByText(LEAVE_GUIDE)).toBeNull()
    expect(leaveCalls()).toEqual([])
  })
})

describe('늦게 온 응답과 로그인 만료', () => {
  const LOGOUT = '로그아웃'
  const CREATE = '가구 만들기'
  const EMAILS = ['user1@example.com', 'user2@example.com', 'owner@example.com']

  /**
   * 로그인 상태를 갖는 서버 흉내. 로그아웃 204나 401 UNAUTHENTICATED 응답 뒤에는 /me를 포함한 모든 요청이 401이다.
   * 제거 요청 뒤 목록 다시 읽기는 reread(없으면 household)를 돌려준다.
   * 나가기는 leave(없으면 204)를 돌려주고, 204나 403이면 그 뒤 목록은 403이다(취소 뒤 첫 화면은 요청 전에 읽은 목록 그대로다).
   */
  function sessionServer(o: { household: Reply; remove?: Reply; reread?: Reply; leave?: Reply }) {
    const state = { session: true, removed: false, left: false }
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
      if (is(call, 'GET', '/api/v1/household'))
        return answer(state.left ? noHousehold : state.removed ? (o.reread ?? o.household) : o.household)
      if (is(call, 'GET', '/api/v1/household/invitations')) return { status: 200, body: { invitations: [] } }
      if (is(call, 'DELETE', MEMBER_PATH)) {
        state.removed = true
        return answer(o.remove ?? noContent)
      }
      if (is(call, 'DELETE', MEMBERSHIP_PATH)) {
        const reply = o.leave ?? noContent
        // 나가기가 204나 403이면 서버에는 내 멤버십이 없으므로 그 뒤 목록도 403이다
        if (reply === noContent || reply === noHousehold) state.left = true
        return answer(reply)
      }
      return undefined
    }
  }

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

  /** settle 뒤 최종 화면이 로그인 화면뿐이다. 다시 시도 안내, 가구 없음 화면, 목록, 구성원 이메일이 없다. */
  async function expectOnlyLoginScreen() {
    await settle()
    screen.getByRole('link', { name: LOGIN })
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByRole('button', { name: CREATE })).toBeNull()
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(screen.queryByText(LEAVE_GUIDE)).toBeNull()
    for (const email of EMAILS) expect(bodyText()).not.toContain(email)
  }

  it.each([
    { label: '204', remove: noContent },
    { label: '404', remove: problem(404, 'NOT_FOUND') },
  ])('제거 $label 뒤 다시 읽은 목록이 401 UNAUTHENTICATED면 로그인 화면이고 다시 시도 안내는 없다', async ({ remove }) => {
    api = mockApi(sessionServer({ household: owner2, remove, reread: unauthenticated }))
    openAt('/')
    await screen.findByText(TITLE)
    const gets = callsOf('GET', '/api/v1/household').length
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByRole('link', { name: LOGIN })
    await expectOnlyLoginScreen()
    expect(removeCalls()).toHaveLength(1)
    expect(callsOf('GET', '/api/v1/household')).toHaveLength(gets + 1)
  })

  it('제거 204 뒤 다시 읽는 목록 응답이 로그아웃 뒤에 200으로 와도 로그인 화면 그대로이고 구성원 이메일이 없다', async () => {
    api = mockApi(sessionServer({ household: owner2, reread: members }))
    openAt('/')
    await screen.findByText(TITLE)
    await settle()
    const hold = holdNext('GET', '/api/v1/household')
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await waitFor(() => expect(hold.held).toBe(true))
    expect(removeCalls()).toHaveLength(1)

    fireEvent.click(screen.getByRole('button', { name: LOGOUT }))
    await screen.findByRole('link', { name: LOGIN })
    // owner 화면이 다시 그려지면 보내는 초대 목록 요청의 401이 로그인 화면으로 되돌려 결함을 가리므로, 그 응답은 풀지 않는다
    holdNext('GET', '/api/v1/household/invitations')
    act(() => hold.release())
    await expectOnlyLoginScreen()
    expect(callsOf('POST', '/api/v1/logout')).toHaveLength(1)
  })

  it('나가기 204가 취소와 로그아웃 뒤에 늦게 와도 로그인 화면 그대로이고 가구 없음 화면이 나오지 않는다', async () => {
    api = mockApi(sessionServer({ household: asMember }))
    openAt('/household/leave')
    await screen.findByText(LEAVE_GUIDE)
    await settle()
    const hold = holdNext('DELETE', MEMBERSHIP_PATH)
    fireEvent.click(screen.getByRole('button', { name: LEAVE }))
    await waitFor(() => expect(hold.held).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: CANCEL }))
    await screen.findByText(TITLE)
    expect(window.location.pathname).toBe('/')
    fireEvent.click(screen.getByRole('button', { name: LOGOUT }))
    await screen.findByRole('link', { name: LOGIN })
    act(() => hold.release())
    await expectOnlyLoginScreen()
    expect(leaveCalls()).toHaveLength(1)
    expect(callsOf('POST', '/api/v1/logout')).toHaveLength(1)
  })

  it.each([
    { label: '403 NO_HOUSEHOLD', remove: noHousehold },
    { label: '404', remove: problem(404, 'NOT_FOUND') },
  ])('제거 응답 $label 이 로그아웃 뒤에 늦게 와도 로그인 화면 그대로이고 가구 없음 화면이나 목록이 나오지 않는다', async ({ remove }) => {
    api = mockApi(sessionServer({ household: owner2, remove }))
    openAt('/')
    await screen.findByText(TITLE)
    await settle()
    const hold = holdNext('DELETE', MEMBER_PATH)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await waitFor(() => expect(hold.held).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: LOGOUT }))
    await screen.findByRole('link', { name: LOGIN })
    act(() => hold.release())
    await expectOnlyLoginScreen()
    expect(removeCalls()).toHaveLength(1)
    expect(callsOf('POST', '/api/v1/logout')).toHaveLength(1)
  })

  it('재리뷰: 제거 204가 로그아웃 뒤에 늦게 와도 로그인 화면 그대로이고 목록을 다시 읽지 않는다', async () => {
    api = mockApi(sessionServer({ household: owner2 }))
    openAt('/')
    await screen.findByText(TITLE)
    await settle()
    const hold = holdNext('DELETE', MEMBER_PATH)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await waitFor(() => expect(hold.held).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: LOGOUT }))
    await screen.findByRole('link', { name: LOGIN })
    await settle()
    const gets = callsOf('GET', '/api/v1/household').length
    act(() => hold.release())
    await expectOnlyLoginScreen()
    expect(callsOf('GET', '/api/v1/household')).toHaveLength(gets)
    expect(removeCalls()).toHaveLength(1)
  })

  it('재리뷰: 나가기 403 NO_HOUSEHOLD가 취소와 로그아웃 뒤에 늦게 와도 로그인 화면 그대로이고 가구 없음 화면이 나오지 않는다', async () => {
    api = mockApi(sessionServer({ household: asMember, leave: noHousehold }))
    openAt('/household/leave')
    await screen.findByText(LEAVE_GUIDE)
    await settle()
    const hold = holdNext('DELETE', MEMBERSHIP_PATH)
    fireEvent.click(screen.getByRole('button', { name: LEAVE }))
    await waitFor(() => expect(hold.held).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: CANCEL }))
    await screen.findByText(TITLE)
    fireEvent.click(screen.getByRole('button', { name: LOGOUT }))
    await screen.findByRole('link', { name: LOGIN })
    act(() => hold.release())
    await expectOnlyLoginScreen()
    expect(leaveCalls()).toHaveLength(1)
  })

  /** member가 나가기를 누르고 응답을 기다리는 동안 같은 세션에서 취소로 첫 화면(목록)에 돌아온다. */
  async function leaveThenCancel(leave: Reply) {
    api = mockApi(sessionServer({ household: asMember, leave }))
    openAt('/household/leave')
    await screen.findByText(LEAVE_GUIDE)
    await settle()
    const hold = holdNext('DELETE', MEMBERSHIP_PATH)
    fireEvent.click(screen.getByRole('button', { name: LEAVE }))
    await waitFor(() => expect(hold.held).toBe(true))
    fireEvent.click(screen.getByRole('button', { name: CANCEL }))
    await screen.findByText(TITLE)
    expect(window.location.pathname).toBe('/')
    within(rowOf('user1@example.com')).getByRole('button', { name: LEAVE_OPEN })
    return hold
  }

  it.each([
    { label: '204', leave: noContent },
    { label: '403 NO_HOUSEHOLD', leave: noHousehold },
  ])('재리뷰, R14: 같은 세션에서 취소 뒤 늦게 온 나가기 $label 이면 가구 없음 화면이고 주소는 /이다', async ({ leave }) => {
    const hold = await leaveThenCancel(leave)
    act(() => hold.release())
    await settle()
    screen.getByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
    expect(leaveCalls()).toHaveLength(1)
  })

  it('재리뷰: 같은 세션에서 취소 뒤 늦게 온 나가기 500이면 첫 화면 목록이 그대로이고 다시 시도 안내는 없다', async () => {
    const hold = await leaveThenCancel(serverError)
    act(() => hold.release())
    await settle()
    screen.getByText(TITLE)
    within(rowOf('user1@example.com')).getByRole('button', { name: LEAVE_OPEN })
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(RETRY)).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByText(LEAVE_GUIDE)).toBeNull()
    expect(leaveCalls()).toHaveLength(1)
  })

  it('재리뷰, R14: 가구 없음으로 바뀐 뒤 늦게 온 목록 200은 가구 없음 화면을 덮지 않는다', async () => {
    // 첫 제거는 204, 그 뒤 목록은 아직 둘이다. 두 번째 제거는 403 NO_HOUSEHOLD이고 그 뒤 목록도 403 NO_HOUSEHOLD다.
    const state = { removes: 0 }
    api = mockApi((call) => {
      if (is(call, 'GET', '/api/v1/me')) return me
      if (is(call, 'GET', '/api/v1/household')) return state.removes >= 2 ? noHousehold : owner2
      if (is(call, 'GET', '/api/v1/household/invitations')) return { status: 200, body: { invitations: [] } }
      if (is(call, 'DELETE', MEMBER_PATH)) return ++state.removes === 1 ? noContent : noHousehold
      return undefined
    })
    openAt('/')
    await screen.findByText(TITLE)
    await settle()
    const hold = holdNext('GET', '/api/v1/household')
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await waitFor(() => expect(hold.held).toBe(true))

    // 다시 읽기를 기다리는 동안 아직 보이는 같은 행의 제거를 다시 누른다
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText(NO_HOUSEHOLD)
    expect(removeCalls()).toHaveLength(2)
    act(() => hold.release())
    await settle()
    screen.getByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(bodyText()).not.toContain('user2@example.com')
    expect(screen.queryByText(RETRY)).toBeNull()
  })
})
