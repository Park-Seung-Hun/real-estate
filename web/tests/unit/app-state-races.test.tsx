/**
 * 앱 상태 전환의 늦은 응답 회귀 테스트(spec US1-2, FR-003, FR-012, FR-019, research R7, R14).
 * 공개 표면은 members.test.tsx, household-delete.test.tsx, invite.test.tsx, invite-token-security.test.tsx 맨 위 주석을 따른다.
 * 이 파일이 보는 상태 전환 규칙:
 * 1. 가구 상태(가구 있음과 목록, 가구 없음)는 `GET /api/v1/household` 응답만 정한다. 여러 번 보냈으면 가장 나중에 보낸 요청의
 *    응답만 반영하고 앞선 요청의 응답(200, 403, 401, 500)은 버린다.
 * 2. 다른 요청의 결과는 "가구가 바뀌었을 수 있다"는 신호일 뿐이다. 받으면 목록을 다시 읽고(1이 결과를 정함) 그것만으로 가구 없음
 *    화면으로 바꾸지 않는다. 가구 삭제와 나가기 204는 주소를 `/`로 바꾸고 다시 읽는다. 아무 요청의 403 NO_HOUSEHOLD는 다시 읽는다.
 *    구성원 제거 204와 404, 초대 발급과 취소의 409, 표시 이름 저장 200도 다시 읽는다. 가구 만들기 201과 409 HAS_HOUSEHOLD는
 *    주소를 `/`로 바꾸고 다시 읽는다(`/household/delete`에서 만들어도 임시 첫 화면).
 * 3. 로그인 화면으로 바꾸는 자리(로그아웃과 그 401, 아무 요청의 401 UNAUTHENTICATED, 탈퇴 성공)만 로그인 세대를 올린다.
 *    보낼 때와 받을 때 로그인 세대가 다르면 그 응답은 앱 상태를 바꾸지 않고 목록도 다시 읽지 않는다. 가구 상태가 바뀌어도
 *    로그인 세대는 그대로이므로, 같은 로그인 세션의 늦은 401은 언제 와도 로그인 화면으로 보낸다.
 * 4. 초대 취소는 응답 전에 다시 눌러도 요청 하나다. 늦게 온 취소 204는 지금 보이는 다른 초대의 방금 만든 링크를 지우지 않는다.
 * 5. 들어가기 응답(성공, 실패)이 오면 탭 저장소의 토큰이 보낸 토큰과 같을 때만 지운다. 확인 화면이 내려간 뒤 온 응답도 같다.
 *    401 UNAUTHENTICATED는 지우지 않는다(R7).
 * 2026-11-19 사용자가 승인한 보강(위 규칙에 더하거나 바꾼다):
 * R1. 보낸 초대 목록의 401 UNAUTHENTICATED와 403 NO_HOUSEHOLD는 요청 순번과 관계없이 처리한다. 같은 로그인 세션이면 401은 로그인
 *     화면, 403은 목록을 다시 읽는 신호다. 순번은 200과 그 밖의 오류 안내에만 쓴다. 방금 만든 링크가 보여도 401이면 로그인 화면이다.
 * R2. 초대 확인의 401 UNAUTHENTICATED는 확인 화면이 내려간 뒤 와도 같은 로그인 세션이면 로그인 화면으로 보낸다. 토큰은 지우지
 *     않는다(R7). 확인 화면과 실패 안내는 그 화면이 남아 있을 때만 그린다.
 * R3. 첫 화면(`/`)에서 받은 다시 읽기 신호(늦은 가구 삭제나 나가기 204 등)는 첫 화면을 내리지 않아 방금 만든 링크, 안내, 입력값이
 *     남는다. 확인 중 상태는 `/`가 아닌 경로에서 `/`로 옮길 때만 쓴다.
 * R4. 들어가기 204가 확인 화면이 내려간 뒤 오면 토큰을 지우고(저장된 값이 같을 때만) 첫 화면에서 목록을 다시 읽는다. 같은 링크로
 *     다시 연 확인 화면이 떠 있었어도 임시 첫 화면(주소 `/`)으로 정리된다.
 * 테스트 이름의 A~G는 최종 리뷰에서 재현한 순서, T-A~T-D는 위 보강의 순서다. 흉내 서버는 상태를 갖고, 다른 탭에서 바뀐 상태는
 * 테스트가 state를 직접 바꿔 표현한다.
 */
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../src/App'
import {
  type ApiCall,
  hasHousehold,
  holdNext,
  me,
  members,
  mockApi,
  noContent,
  noHousehold,
  type Reply,
  serverError,
  settle,
  unauthenticated,
  watchNavigation,
} from './api-mock'

// 계약 패턴 ^[A-Za-z0-9_-]{43}$ 을 따르는 가짜 값
const TOKEN = 'invite-tok_'.repeat(3) + 'invite-tok'
const TOKEN_Y = 'second-tok'.repeat(4) + 'sec'

const LOGIN = 'Google 계정으로 로그인'
const LOGOUT = '로그아웃'
const NO_HOUSEHOLD = '아직 가구가 없습니다.'
const CREATE = '가구 만들기'
const TITLE = '함께 보는 사람'
const REMOVE = '제거'
const DELETE_OPEN = '가구 삭제'
const CANCEL = '취소'
const MAKE = '초대 링크 만들기'
const COPY = '복사'
const NEW_NAME = '새 이름'
const SAVE = '저장'
const JOIN = '들어가기'
const X_ROW = '11월 17일 만듦, 3일 남음'
const HOUSEHOLD_PATH = '/api/v1/household'
const INVITATIONS_PATH = '/api/v1/household/invitations'
const MEMBER_PATH = '/api/v1/household/members/2'
const X_PATH = '/api/v1/household/invitations/7'
const ACCEPT_PATH = '/api/v1/invitations/accept'
const PREVIEW_PATH = '/api/v1/invitations/preview'

const guideFor = (n: number) => `가구를 삭제하면 가구, 구성원 ${n}명의 연결, 보낸 초대가 삭제되며 되돌릴 수 없습니다.`

type Outcome = ReturnType<Parameters<typeof mockApi>[0]>
type Invitation = { id: number; createdAt: string; expiresAt: string; remainingDays: number }

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
  Reflect.deleteProperty(navigator, 'clipboard')
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
    body: { type: `https://example.com/problems/${code.toLowerCase()}`, title: code, status, detail: '계약 예시', code },
  }
}

const is = (call: ApiCall, method: string, path: string) => call.method === method && call.path === path
const callsOf = (method: string, path: string) => api.calls.filter((c) => is(c, method, path))
const listCalls = () => callsOf('GET', HOUSEHOLD_PATH)
const bodyText = () => document.body.textContent ?? ''
const press = (name: string) => fireEvent.click(screen.getByRole('button', { name }))
const hasToken = () => Object.values({ ...sessionStorage }).some((value) => String(value).includes(TOKEN))

const owner2: Reply = {
  status: 200,
  body: {
    members: [
      { userId: 1, displayName: '엄마', role: 'owner', loginEmail: 'user1@example.com', emailVerified: true, me: true },
      { userId: 2, displayName: '아빠', role: 'member', loginEmail: 'user2@example.com', emailVerified: true, me: false },
    ],
  },
}

// 다른 탭에서 새로 만든 가구(새 구성원 user3)
const newHousehold: Reply = {
  status: 200,
  body: {
    members: [
      { userId: 1, displayName: '엄마', role: 'owner', loginEmail: 'user1@example.com', emailVerified: true, me: true },
      { userId: 3, displayName: '이모', role: 'member', loginEmail: 'user3@example.com', emailVerified: true, me: false },
    ],
  },
}

const joined: Reply = {
  status: 200,
  body: {
    members: [
      { userId: 2, displayName: '민지', role: 'owner', loginEmail: 'user2@example.com', emailVerified: true, me: false },
      { userId: 1, displayName: '엄마', role: 'member', loginEmail: 'user1@example.com', emailVerified: true, me: true },
    ],
  },
}

const invitationX: Invitation = {
  id: 7,
  createdAt: '2026-11-17T09:30:00+09:00',
  expiresAt: '2026-11-20T09:30:00+09:00',
  remainingDays: 3,
}
const invitationY: Invitation = { ...invitationX, id: 8 }

type State = { session: boolean; household: Reply; invitations: Invitation[] }

/**
 * 로그인, 가구, 보낸 초대의 상태를 갖는 서버 흉내. routes(`'METHOD path'`)가 기본 응답보다 먼저다.
 * 로그아웃 뒤에는 모든 요청이 401이다. 401 UNAUTHENTICATED를 돌려주는 route는 세션을 끝내지 않는다(늦은 401만 흉내 낸다).
 */
function server(household: Reply, routes: Record<string, (s: State) => Outcome> = {}, invitations: Invitation[] = []) {
  const state: State = { session: true, household, invitations }
  const handler = (call: ApiCall): Outcome => {
    if (is(call, 'POST', '/api/v1/logout')) {
      state.session = false
      return noContent
    }
    if (!state.session) return unauthenticated
    const route = routes[`${call.method} ${call.path}`]
    if (route) return route(state)
    if (is(call, 'GET', '/api/v1/me')) return me
    if (is(call, 'GET', HOUSEHOLD_PATH)) return state.household
    if (is(call, 'GET', INVITATIONS_PATH)) return { status: 200, body: { invitations: state.invitations } }
    return undefined
  }
  return { state, handler }
}

/** 그 로그인 이메일 글자를 포함하는 listitem */
function rowOf(email: string) {
  const row = screen.getAllByRole('listitem').find((li) => (li.textContent ?? '').includes(email))
  if (!row) throw new Error(`행이 없습니다: ${email}`)
  return row
}

/** 첫 화면에서 표시 이름을 저장한다(200이면 목록을 다시 읽는다) */
function saveName(value: string) {
  fireEvent.change(screen.getByRole('textbox', { name: NEW_NAME }), { target: { value } })
  press(SAVE)
}

const nameSaved: Reply = { status: 200, body: { displayName: '엄마', loginEmail: 'user1@example.com', emailVerified: true } }

describe('로그인 세대(규칙 3)', () => {
  it('A: 로그아웃 뒤 늦게 온 보낸 초대 목록의 403 NO_HOUSEHOLD는 로그인 화면을 바꾸지 않고 목록도 다시 읽지 않는다', async () => {
    const { handler } = server(members, { [`GET ${INVITATIONS_PATH}`]: () => noHousehold })
    api = mockApi(handler)
    const hold = holdNext('GET', INVITATIONS_PATH)
    openAt('/')
    await screen.findByText(TITLE)
    await waitFor(() => expect(hold.held).toBe(true))
    await settle()
    // 붙잡은 요청 하나뿐이어야 늦은 응답의 효과를 볼 수 있다
    expect(callsOf('GET', INVITATIONS_PATH)).toHaveLength(1)
    screen.getByText(TITLE)

    press(LOGOUT)
    await screen.findByRole('link', { name: LOGIN })
    await settle()
    const gets = listCalls().length
    act(() => hold.release())
    await settle()
    screen.getByRole('link', { name: LOGIN })
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByRole('button', { name: CREATE })).toBeNull()
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(listCalls()).toHaveLength(gets)
    expect(callsOf('POST', '/api/v1/logout')).toHaveLength(1)
  })

  it('C: 가구 없음 화면이 된 뒤 같은 로그인 세션에서 보낸 제거 요청의 401 UNAUTHENTICATED가 오면 로그인 화면이다', async () => {
    const { state, handler } = server(owner2, {
      [`DELETE ${MEMBER_PATH}`]: () => unauthenticated,
      'PATCH /api/v1/me': () => nameSaved,
    })
    api = mockApi(handler)
    openAt('/')
    await screen.findByText(TITLE)
    await settle()
    const hold = holdNext('DELETE', MEMBER_PATH)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await waitFor(() => expect(hold.held).toBe(true))

    // 다른 탭에서 가구가 삭제되었고, 표시 이름 저장 200 뒤 다시 읽은 목록이 403이라 가구 없음 화면이 된다
    state.household = noHousehold
    saveName('엄마')
    await screen.findByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')

    act(() => hold.release())
    await screen.findByRole('link', { name: LOGIN })
    await settle()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByRole('button', { name: CREATE })).toBeNull()
    expect(bodyText()).not.toContain('user2@example.com')
  })

  it('규칙 3: 앞서 보낸 목록 요청의 401이 나중에 보낸 목록 200보다 늦게 와도 로그인 화면이다', async () => {
    let lists = 0
    const { handler } = server(owner2, {
      // 첫 조회 다음의 목록(첫 저장 뒤 다시 읽기)만 401이고 세션은 끝내지 않는다
      [`GET ${HOUSEHOLD_PATH}`]: (s) => (++lists === 2 ? unauthenticated : s.household),
      'PATCH /api/v1/me': () => nameSaved,
    })
    api = mockApi(handler)
    openAt('/')
    await screen.findByText(TITLE)
    await settle()
    const first = holdNext('GET', HOUSEHOLD_PATH)
    saveName('엄마')
    await waitFor(() => expect(first.held).toBe(true))
    saveName('엄마')
    await waitFor(() => expect(listCalls()).toHaveLength(3))
    await settle()
    screen.getByText(TITLE)

    act(() => first.release())
    await screen.findByRole('link', { name: LOGIN })
    expect(bodyText()).not.toContain('user2@example.com')
  })
})

describe('가구 상태는 목록 응답만 정한다(규칙 1, 2)', () => {
  it('B: 가구 삭제 응답을 기다리는 사이 다른 탭의 새 가구를 다시 읽어 그렸으면, 늦게 온 삭제 204 뒤에도 새 가구 화면이다', async () => {
    const { state, handler } = server(owner2, {
      [`DELETE ${HOUSEHOLD_PATH}`]: (s) => {
        s.household = noHousehold
        return noContent
      },
      [`DELETE ${MEMBER_PATH}`]: () => problem(404, 'NOT_FOUND'),
    })
    api = mockApi(handler)
    openAt('/household/delete')
    await screen.findByText(guideFor(2))
    await settle()
    const hold = holdNext('DELETE', HOUSEHOLD_PATH)
    press(DELETE_OPEN)
    await waitFor(() => expect(hold.held).toBe(true))
    press(CANCEL)
    await screen.findByText(TITLE)
    expect(window.location.pathname).toBe('/')

    // 삭제가 처리된 뒤 다른 탭에서 새 가구를 만들었다. 제거 404가 목록을 다시 읽게 해 새 가구를 그린다
    state.household = newHousehold
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await screen.findByText('user3@example.com', { exact: false })
    await settle()
    const gets = listCalls().length

    act(() => hold.release())
    await settle()
    expect(listCalls().length).toBeGreaterThan(gets)
    screen.getByText(TITLE)
    screen.getByText('user3@example.com', { exact: false })
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByRole('button', { name: CREATE })).toBeNull()
    expect(window.location.pathname).toBe('/')
    expect(callsOf('DELETE', HOUSEHOLD_PATH)).toHaveLength(1)
  })

  it('D: 가구 만들기 201 뒤 목록을 읽는 동안 만들기 전에 보낸 제거의 403 NO_HOUSEHOLD가 오면 다시 읽어 임시 첫 화면이다', async () => {
    const { state, handler } = server(owner2, {
      [`DELETE ${MEMBER_PATH}`]: () => noHousehold,
      'PATCH /api/v1/me': () => nameSaved,
      [`POST ${HOUSEHOLD_PATH}`]: (s) => {
        s.household = members
        return { status: 201 }
      },
    })
    api = mockApi(handler)
    openAt('/')
    await screen.findByText(TITLE)
    await settle()

    // 다른 탭에서 가구가 삭제되었다. 제거(403 NO_HOUSEHOLD)는 붙잡아 두고, 표시 이름 저장으로 가구 없음 화면이 된다
    state.household = noHousehold
    const old = holdNext('DELETE', MEMBER_PATH)
    fireEvent.click(within(rowOf('user2@example.com')).getByRole('button', { name: REMOVE }))
    await waitFor(() => expect(old.held).toBe(true))
    saveName('엄마')
    await screen.findByText(NO_HOUSEHOLD)

    const reread = holdNext('GET', HOUSEHOLD_PATH)
    press(CREATE)
    await waitFor(() => expect(reread.held).toBe(true))
    expect(callsOf('POST', HOUSEHOLD_PATH)).toHaveLength(1)
    const gets = listCalls().length

    act(() => old.release())
    await settle()
    expect(listCalls().length).toBeGreaterThan(gets)
    act(() => reread.release())
    await settle()
    screen.getByText(TITLE)
    expect(rowOf('user1@example.com').textContent).toContain('owner')
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(bodyText()).not.toContain('user2@example.com')
    expect(window.location.pathname).toBe('/')
  })

  it.each([
    { path: '/household/delete', label: '201', reply: { status: 201 } as Reply },
    { path: '/household/delete', label: '409 HAS_HOUSEHOLD', reply: hasHousehold },
    { path: '/household/leave', label: '201', reply: { status: 201 } as Reply },
  ])('F: 가구 없는 사람이 $path 를 열어 가구 만들기 $label 이면 임시 첫 화면이고 주소는 /다', async ({ path, reply }) => {
    const { handler } = server(noHousehold, {
      [`POST ${HOUSEHOLD_PATH}`]: (s) => {
        s.household = members
        return reply
      },
    })
    api = mockApi(handler)
    openAt(path)
    await screen.findByText(NO_HOUSEHOLD)
    press(CREATE)
    await screen.findByText(TITLE)
    await settle()
    expect(window.location.pathname).toBe('/')
    expect(rowOf('user1@example.com').textContent).toContain('owner')
    expect(screen.queryByText(/가구를 삭제하면/)).toBeNull()
    expect(screen.queryByText(/가구를 나가면/)).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(callsOf('POST', HOUSEHOLD_PATH)).toHaveLength(1)
  })

  it('규칙 2: 가구 삭제 204 뒤 목록을 다시 읽는 동안 지운 가구의 목록과 버튼이 보이지 않고 보낸 초대 목록도 읽지 않는다', async () => {
    const { handler } = server(owner2, {
      [`DELETE ${HOUSEHOLD_PATH}`]: (s) => {
        s.household = noHousehold
        return noContent
      },
    })
    api = mockApi(handler)
    openAt('/household/delete')
    await screen.findByText(guideFor(2))
    await settle()
    const reread = holdNext('GET', HOUSEHOLD_PATH)
    press(DELETE_OPEN)
    await waitFor(() => expect(reread.held).toBe(true))
    await settle()
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(bodyText()).not.toContain('user2@example.com')
    expect(callsOf('GET', INVITATIONS_PATH)).toHaveLength(0)

    act(() => reread.release())
    await screen.findByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')
  })

  it('규칙 3: 가구 만들기 201이 로그아웃 뒤 늦게 오면 목록을 다시 읽지 않고 로그인 화면과 주소가 그대로다', async () => {
    const { handler } = server(noHousehold, {
      [`POST ${HOUSEHOLD_PATH}`]: (s) => {
        s.household = members
        return { status: 201 }
      },
    })
    api = mockApi(handler)
    openAt('/')
    await screen.findByText(NO_HOUSEHOLD)
    const created = holdNext('POST', HOUSEHOLD_PATH)
    press(CREATE)
    await waitFor(() => expect(created.held).toBe(true))
    press(LOGOUT)
    await screen.findByRole('link', { name: LOGIN })
    const gets = listCalls().length
    window.history.pushState(null, '', '/somewhere')

    act(() => created.release())
    await settle()
    expect(listCalls()).toHaveLength(gets)
    screen.getByRole('link', { name: LOGIN })
    expect(window.location.pathname).toBe('/somewhere')
  })
})

describe('초대 취소(규칙 4)', () => {
  /** owner 혼자이고 기다리는 초대 X(7)가 있다. 두 번째 X 취소는 409, 발급은 Y(8)다. */
  function inviteServer() {
    return server(
      members,
      {
        [`DELETE ${X_PATH}`]: (s) => {
          if (!s.invitations.some((i) => i.id === 7)) return problem(409, 'INVITATION_NOT_PENDING')
          s.invitations = []
          return noContent
        },
        [`POST ${INVITATIONS_PATH}`]: (s) => {
          s.invitations = [invitationY]
          return {
            status: 201,
            body: { id: 8, token: TOKEN_Y, createdAt: invitationY.createdAt, expiresAt: invitationY.expiresAt },
          }
        },
      },
      [invitationX],
    )
  }

  it('E: 응답 전에 같은 초대의 취소를 두 번 눌러도 DELETE는 하나다', async () => {
    api = mockApi(inviteServer().handler)
    openAt('/')
    await screen.findByText(X_ROW)
    await settle()
    const hold = holdNext('DELETE', X_PATH)
    const cancel = screen.getByRole('button', { name: CANCEL })
    fireEvent.click(cancel)
    await waitFor(() => expect(hold.held).toBe(true))
    fireEvent.click(cancel)
    await settle()
    expect(callsOf('DELETE', X_PATH)).toHaveLength(1)
    act(() => hold.release())
    await screen.findByRole('button', { name: MAKE })
    expect(callsOf('DELETE', X_PATH)).toHaveLength(1)
  })

  it('E: 첫 목록이 늦는 사이 보낸 발급의 409로 다시 읽어 X가 사라진 뒤 Y를 만들면, 늦게 온 X 취소 204가 Y의 링크를 지우지 않는다', async () => {
    let issues = 0
    const { handler } = server(
      members,
      {
        [`DELETE ${X_PATH}`]: (s) => {
          s.invitations = []
          return noContent
        },
        [`POST ${INVITATIONS_PATH}`]: (s) => {
          if (++issues === 1) return problem(409, 'INVITATION_PENDING')
          s.invitations = [invitationY]
          return {
            status: 201,
            body: { id: 8, token: TOKEN_Y, createdAt: invitationY.createdAt, expiresAt: invitationY.expiresAt },
          }
        },
      },
      [invitationX],
    )
    api = mockApi(handler)
    // 첫 보낸 초대 목록(X가 있음)을 붙잡아 두면 만들기 버튼이 먼저 보인다
    const list = holdNext('GET', INVITATIONS_PATH)
    openAt('/')
    const make = await screen.findByRole('button', { name: MAKE })
    await waitFor(() => expect(list.held).toBe(true))
    const issue = holdNext('POST', INVITATIONS_PATH)
    fireEvent.click(make)
    await waitFor(() => expect(issue.held).toBe(true))
    act(() => list.release())
    await screen.findByText(X_ROW)

    const cancel = holdNext('DELETE', X_PATH)
    press(CANCEL)
    await waitFor(() => expect(cancel.held).toBe(true))
    // 발급 409가 목록을 다시 읽고, 서버에서는 X가 이미 취소되어 만들기 버튼이 다시 보인다
    act(() => issue.release())
    fireEvent.click(await screen.findByRole('button', { name: MAKE }))
    const link = `${window.location.origin}/invite#${TOKEN_Y}`
    await screen.findByText(link)

    act(() => cancel.release())
    await settle()
    screen.getByText(link)
    screen.getByRole('button', { name: COPY })
    expect(callsOf('DELETE', X_PATH)).toHaveLength(1)
    expect(callsOf('POST', INVITATIONS_PATH)).toHaveLength(2)
  })
})

describe('초대 토큰 지우기(규칙 5, R7)', () => {
  /** 가구 없는 사람. 수락 204 뒤에는 member로 들어온 목록이다. */
  function joinServer(accept: Reply) {
    return server(noHousehold, {
      'POST /api/v1/invitations/preview': () => ({ status: 200, body: { inviterDisplayName: '민지' } }),
      [`POST ${ACCEPT_PATH}`]: (s) => {
        if (accept.status === 204) s.household = joined
        return accept
      },
    }).handler
  }

  /**
   * `/` 다음에 `/invite#토큰`을 연 탭에서 들어가기를 누르고, 응답을 붙잡아 둔 채 뒤로 가기로 확인 화면을 떠난다.
   * whileHeld가 있으면 응답을 풀기 전에 부른다.
   */
  async function joinThenGoBack(accept: Reply, whileHeld?: () => Promise<void>) {
    api = mockApi(joinServer(accept))
    window.history.replaceState(null, '', '/')
    window.history.pushState(null, '', `/invite#${TOKEN}`)
    render(<App />)
    const join = await screen.findByRole('button', { name: JOIN })
    await settle()
    expect(hasToken()).toBe(true)
    const hold = holdNext('POST', ACCEPT_PATH)
    fireEvent.click(join)
    await waitFor(() => expect(hold.held).toBe(true))
    act(() => window.history.back())
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    await waitFor(() => expect(screen.queryByRole('button', { name: JOIN })).toBeNull())
    await whileHeld?.()
    act(() => hold.release())
    await settle()
    expect(callsOf('POST', ACCEPT_PATH)).toHaveLength(1)
  }

  it.each([
    { label: '204', accept: noContent },
    { label: '409 INVITATION_REVOKED', accept: problem(409, 'INVITATION_REVOKED') },
    { label: '500', accept: serverError },
  ])('G: 들어가기 응답 $label 이 뒤로 가기로 확인 화면을 떠난 뒤 와도 탭 저장소에 토큰이 남지 않는다', async ({ accept }) => {
    await joinThenGoBack(accept)
    expect(hasToken()).toBe(false)
  })

  it('G: 들어가기 응답 401 UNAUTHENTICATED가 뒤로 가기로 확인 화면을 떠난 뒤 오면 탭 저장소의 토큰은 남고 로그인 화면이다', async () => {
    await joinThenGoBack(unauthenticated)
    expect(hasToken()).toBe(true)
    await screen.findByRole('link', { name: LOGIN })
  })

  it('G: 들어가기 204가 뒤로 가기로 확인 화면을 떠난 뒤 오면 첫 화면에서 목록을 다시 읽어 임시 첫 화면이다', async () => {
    await joinThenGoBack(noContent)
    await screen.findByText(TITLE)
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
  })

  it('T-D, R4: 들어가기 204가 뒤로 가기로 떠나 같은 링크로 다시 연 확인 화면이 떠 있을 때 와도 임시 첫 화면이고 토큰이 없다', async () => {
    await joinThenGoBack(noContent, async () => {
      // 같은 문서에서 링크를 다시 연다(새로 렌더하면 붙잡은 응답을 받을 앱이 사라진다)
      act(() => {
        window.history.pushState(null, '', `/invite#${TOKEN}`)
        window.dispatchEvent(new PopStateEvent('popstate'))
      })
      await screen.findByRole('button', { name: JOIN })
      await settle()
      expect(hasToken()).toBe(true)
    })
    await screen.findByText(TITLE)
    expect(window.location.pathname).toBe('/')
    expect(hasToken()).toBe(false)
    expect(screen.queryByRole('button', { name: JOIN })).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
  })

  it('T-B, R2: 초대 확인 401이 뒤로 가기로 확인 화면을 떠난 뒤 와도 로그인 화면이고 탭 저장소에 토큰이 남는다', async () => {
    api = mockApi(server(noHousehold, { [`POST ${PREVIEW_PATH}`]: () => unauthenticated }).handler)
    const hold = holdNext('POST', PREVIEW_PATH)
    window.history.replaceState(null, '', '/')
    window.history.pushState(null, '', `/invite#${TOKEN}`)
    render(<App />)
    await waitFor(() => expect(hold.held).toBe(true))
    act(() => window.history.back())
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    await screen.findByText(NO_HOUSEHOLD)
    await settle()
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()

    act(() => hold.release())
    await screen.findByRole('link', { name: LOGIN })
    await settle()
    expect(hasToken()).toBe(true)
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(callsOf('POST', PREVIEW_PATH)).toHaveLength(1)
  })
})

describe('2026-11-19 보강(R1, R3)', () => {
  /** 발급은 Y(8)다 */
  const issueY = (s: State): Outcome => {
    s.invitations = [invitationY]
    return { status: 201, body: { id: 8, token: TOKEN_Y, createdAt: invitationY.createdAt, expiresAt: invitationY.expiresAt } }
  }
  const linkY = () => `${window.location.origin}/invite#${TOKEN_Y}`

  /** owner 혼자인 가구에서 첫 보낸 초대 목록 응답을 붙잡아 둔 채 초대를 만들어 링크가 보이게 한다 */
  async function makeWhileFirstListHeld() {
    const hold = holdNext('GET', INVITATIONS_PATH)
    openAt('/')
    const make = await screen.findByRole('button', { name: MAKE })
    await waitFor(() => expect(hold.held).toBe(true))
    fireEvent.click(make)
    await screen.findByText(linkY())
    await settle()
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
    return hold
  }

  it('T-A, R1: 첫 보낸 초대 목록의 401이 발급 201 뒤 늦게 와도 로그인 화면이고 구성원 이메일과 링크가 보이지 않는다', async () => {
    let lists = 0
    const { handler } = server(members, {
      [`GET ${INVITATIONS_PATH}`]: (s) => (++lists === 1 ? unauthenticated : { status: 200, body: { invitations: s.invitations } }),
      [`POST ${INVITATIONS_PATH}`]: issueY,
    })
    api = mockApi(handler)
    const hold = await makeWhileFirstListHeld()

    act(() => hold.release())
    await screen.findByRole('link', { name: LOGIN })
    await settle()
    expect(screen.queryByText(linkY())).toBeNull()
    expect(screen.queryByRole('button', { name: COPY })).toBeNull()
    expect(bodyText()).not.toContain('user1@example.com')
    expect(callsOf('POST', INVITATIONS_PATH)).toHaveLength(1)
  })

  it('T-A2, R1: 첫 보낸 초대 목록의 403 NO_HOUSEHOLD가 발급 201 뒤 늦게 오면 목록을 다시 읽어 가구 없음 화면이다', async () => {
    let lists = 0
    const { state, handler } = server(members, {
      [`GET ${INVITATIONS_PATH}`]: (s) =>
        ++lists === 1 || s.household === noHousehold ? noHousehold : { status: 200, body: { invitations: s.invitations } },
      [`POST ${INVITATIONS_PATH}`]: issueY,
    })
    api = mockApi(handler)
    const hold = await makeWhileFirstListHeld()
    state.household = noHousehold
    const gets = listCalls().length

    act(() => hold.release())
    await screen.findByText(NO_HOUSEHOLD)
    await settle()
    expect(listCalls().length).toBeGreaterThan(gets)
    expect(screen.queryByText(linkY())).toBeNull()
    expect(screen.queryByRole('button', { name: COPY })).toBeNull()
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(window.location.pathname).toBe('/')
  })

  it('T-C, R3: 첫 화면에서 받은 늦은 가구 삭제 204는 목록을 다시 읽어도 첫 화면을 내리지 않아 방금 만든 링크가 그대로다', async () => {
    const { state, handler } = server(owner2, {
      [`DELETE ${HOUSEHOLD_PATH}`]: (s) => {
        s.household = noHousehold
        return noContent
      },
      'PATCH /api/v1/me': () => nameSaved,
      [`POST ${INVITATIONS_PATH}`]: issueY,
    })
    api = mockApi(handler)
    openAt('/household/delete')
    await screen.findByText(guideFor(2))
    await settle()
    const old = holdNext('DELETE', HOUSEHOLD_PATH)
    press(DELETE_OPEN)
    await waitFor(() => expect(old.held).toBe(true))
    press(CANCEL)
    await screen.findByText(TITLE)
    expect(window.location.pathname).toBe('/')

    // 다른 탭에서 옛 가구가 지워지고 owner 혼자인 새 가구가 생겼다. 이름 저장 200으로 다시 읽어 새 가구를 그린다.
    state.household = members
    saveName('엄마')
    await waitFor(() => expect(bodyText()).not.toContain('user2@example.com'))
    await settle()
    press(MAKE)
    await screen.findByText(linkY())
    await settle()

    const reread = holdNext('GET', HOUSEHOLD_PATH)
    act(() => old.release())
    await waitFor(() => expect(reread.held).toBe(true))
    await settle()
    screen.getByText(linkY())
    screen.getByRole('button', { name: COPY })
    act(() => reread.release())
    await settle()
    screen.getByText(linkY())
    screen.getByRole('button', { name: COPY })
    screen.getByText(TITLE)
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(window.location.pathname).toBe('/')
    expect(callsOf('DELETE', HOUSEHOLD_PATH)).toHaveLength(1)
    expect(callsOf('POST', INVITATIONS_PATH)).toHaveLength(1)
  })
})
