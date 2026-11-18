/**
 * T047 초대 화면 동작 테스트(spec US2-1~US2-17, FR-009, FR-010, FR-012, FR-013, FR-022, SC-002, research R7, R14).
 * 공개 표면은 login.test.tsx 맨 위 주석에 더해 다음을 따른다.
 * - API는 계약(specs/002-household-sharing/contracts/openapi.yaml)의 초대 확인, 수락, 보낸 초대 목록, 발급, 취소 그대로다.
 * - 만료 기간은 라벨 "만료 기간"의 `<select>`이고 value "1"~"7", 처음 값 "3"이다. 버튼은 R14 문구를 이름으로 갖는 `<button>`이다.
 * - 만든 직후의 링크 `${origin}/invite#${token}`, "M월 D일 만듦, N일 남음", R14의 안내 문장은 각각 한 요소의 글자 전체다.
 * - 보낸 초대 목록은 owner일 때만 요청한다.
 * 토큰이 어디에 남는지와 `#`를 지우는 시점은 이 파일이 아니라 T035 테스트가 본다. 여기서는 화면 동작만 본다.
 * 흉내 응답은 상태를 갖는다(발급 뒤 목록에 방금 만든 초대, 취소 뒤 빈 목록, 수락 뒤 member로 들어온 목록).
 */
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
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

// 계약 패턴 ^[A-Za-z0-9_-]{43}$ 을 따르는 가짜 값(_와 -를 섞음)
const TOKEN = 'invite-tok_'.repeat(3) + 'invite-tok'
const LOGIN = 'Google 계정으로 로그인'
const LOGIN_START = '/oauth2/authorization/google'
const NEW_INVITE_GUIDE = '가족에게 받은 초대 링크로 들어왔다면 로그인한 뒤 들어갈 가구를 확인합니다.'
const NO_HOUSEHOLD = '아직 가구가 없습니다.'
const TITLE = '함께 보는 사람'
const SENT_TITLE = '보낸 초대'
const EXPIRY_LABEL = '만료 기간'
const MAKE = '초대 링크 만들기'
const MAKE_GUIDE = '만든 링크를 복사해 메신저나 메일로 보냅니다.'
const PENDING_REASON = '기다리는 초대가 있어 새 초대를 만들 수 없습니다.'
const FULL_REASON = '구성원이 둘이라 새 초대를 만들 수 없습니다.'
const COPY = '복사'
const CANCEL = '취소'
const JUST_MADE = '방금 만듦'
const LINK_WARNING = '이 화면을 떠나면 링크를 다시 볼 수 없습니다. 가족에게만 보내세요.'
const REAUTH_GUIDE = '초대 링크를 만들려면 구글 계정을 한 번 더 확인해야 합니다.'
const REAUTH_LINK = '구글 계정 확인'
const JOIN = '들어가기'
const TO_HOME = '첫 화면으로'
const CONFIRM_NAMED = '민지님의 가구에 들어갑니다. 가족에게 받은 링크가 맞을 때만 들어가세요.'
const CONFIRM_ANONYMOUS = '가구에 들어갑니다. 가족에게 받은 링크가 맞을 때만 들어가세요.'

// R14 초대 수락 실패 안내
const FAILURE: Record<string, string> = {
  NOT_FOUND: '존재하지 않는 초대 링크입니다. 가족에게 새 초대 링크를 받아 주세요.',
  INVITATION_REVOKED: '취소된 초대 링크입니다. 가족에게 새 초대 링크를 받아 주세요.',
  INVITATION_USED: '이미 사용된 초대 링크입니다. 가족에게 새 초대 링크를 받아 주세요.',
  INVITATION_EXPIRED: '만료된 초대 링크입니다. 가족에게 새 초대 링크를 받아 주세요.',
  HOUSEHOLD_FULL: '이 가구는 구성원이 이미 둘이라 들어갈 수 없습니다.',
  ALREADY_IN_THIS_HOUSEHOLD: '이미 이 가구의 구성원입니다. 링크는 가족에게 보내세요.',
  HAS_HOUSEHOLD: '이미 다른 가구에 속해 있습니다. 가구를 나가거나 삭제한 뒤 이 링크를 다시 여세요.',
}

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
const bodyOf = (call: ApiCall) => JSON.parse(call.body ?? 'null') as unknown

// ---------- 초대 영역(임시 첫 화면) ----------

type Invitation = {
  id: number
  createdAt: string
  expiresAt: string
  remainingDays: number
}

const owner2: Reply = {
  status: 200,
  body: {
    members: [
      {
        userId: 1,
        displayName: '엄마',
        role: 'owner',
        loginEmail: 'user1@example.com',
        emailVerified: true,
        me: true,
      },
      {
        userId: 2,
        displayName: '아빠',
        role: 'member',
        loginEmail: 'user2@example.com',
        emailVerified: true,
        me: false,
      },
    ],
  },
}

const asMember: Reply = {
  status: 200,
  body: {
    members: [
      {
        userId: 1,
        displayName: '민지',
        role: 'owner',
        loginEmail: 'owner@example.com',
        emailVerified: true,
        me: false,
      },
      {
        userId: 2,
        displayName: '엄마',
        role: 'member',
        loginEmail: 'user1@example.com',
        emailVerified: true,
        me: true,
      },
    ],
  },
}

const pending: Invitation = {
  id: 7,
  createdAt: '2026-11-17T09:30:00+09:00',
  expiresAt: '2026-11-20T09:30:00+09:00',
  remainingDays: 3,
}

const created: Reply = {
  status: 201,
  body: {
    id: 7,
    token: TOKEN,
    createdAt: '2026-11-17T09:30:00+09:00',
    expiresAt: '2026-11-20T09:30:00+09:00',
  },
}

/** 가구와 보낸 초대의 상태를 갖는 서버 흉내. 발급 뒤에는 목록에 방금 만든 초대가, 취소 뒤에는 빈 목록이 나온다. */
function homeServer(
  initial: {
    household?: Reply
    invitations?: Invitation[]
    issue?: Reply
  } = {},
) {
  const state = {
    household: initial.household ?? members,
    invitations: initial.invitations ?? [],
  }
  return (call: ApiCall): Outcome => {
    if (is(call, 'GET', '/api/v1/me')) return me
    if (is(call, 'GET', '/api/v1/household')) return state.household
    if (is(call, 'GET', '/api/v1/household/invitations'))
      return { status: 200, body: { invitations: state.invitations } }
    if (is(call, 'POST', '/api/v1/household/invitations')) {
      const reply = initial.issue ?? created
      if (reply.status === 201) state.invitations = [pending]
      return reply
    }
    if (is(call, 'DELETE', '/api/v1/household/invitations/7')) {
      state.invitations = []
      return noContent
    }
    return undefined
  }
}

function stubClipboard() {
  const writeText = vi.fn(async (_text: string) => undefined)
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  })
  return writeText
}

describe('보낸 초대 영역: 만들기(owner)', () => {
  it('US2-1: 선택(일곱 개, 1일~7일, 처음 값 3일)과 초대 링크 만들기 버튼과 안내가 보이고, 보낸 초대 영역은 없다', async () => {
    api = mockApi(homeServer())
    openAt('/')
    await screen.findByText(TITLE)
    const select = screen.getByLabelText(EXPIRY_LABEL) as HTMLSelectElement
    expect(select.tagName).toBe('SELECT')
    const options = within(select).getAllByRole('option') as HTMLOptionElement[]
    expect(options.map((o) => o.textContent)).toEqual(['1일', '2일', '3일', '4일', '5일', '6일', '7일'])
    expect(options.map((o) => o.value)).toEqual(['1', '2', '3', '4', '5', '6', '7'])
    expect(select.value).toBe('3')
    expect(select.selectedOptions[0].textContent).toBe('3일')
    screen.getByRole('button', { name: MAKE })
    screen.getByText(MAKE_GUIDE)
    // FR-010: 목록에 나올 초대가 없으면 보낸 초대 영역을 보이지 않는다
    expect(screen.queryByText(SENT_TITLE)).toBeNull()
    expect(screen.queryByText(PENDING_REASON)).toBeNull()
    expect(screen.queryByText(FULL_REASON)).toBeNull()
    await settle()
    expect(callsOf('POST', '/api/v1/household/invitations')).toEqual([])
  })

  it('US2-2, FR-006: 6일을 골라 만들면 발급 요청 본문이 JSON {"expiresInDays": 6}(숫자)이다', async () => {
    api = mockApi(homeServer())
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.change(screen.getByLabelText(EXPIRY_LABEL), {
      target: { value: '6' },
    })
    fireEvent.click(screen.getByRole('button', { name: MAKE }))
    await screen.findByText(JUST_MADE)
    await settle()
    const posts = callsOf('POST', '/api/v1/household/invitations')
    expect(posts).toHaveLength(1)
    expect(bodyOf(posts[0])).toEqual({ expiresInDays: 6 })
  })

  it('US2-2: 만든 직후 링크 전체, 복사 버튼, 경고 문구, "방금 만듦", 취소 버튼이 보이고 남은 기간은 없다', async () => {
    api = mockApi(homeServer())
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(screen.getByRole('button', { name: MAKE }))
    await screen.findByText(JUST_MADE)
    screen.getByText(`${window.location.origin}/invite#${TOKEN}`)
    screen.getByRole('button', { name: COPY })
    screen.getByText(LINK_WARNING)
    screen.getByText(SENT_TITLE)
    screen.getByRole('button', { name: CANCEL })
    expect(screen.queryByText(/일 남음/)).toBeNull()
    expect(screen.queryByText(/만듦, /)).toBeNull()
  })

  it('US2-2: 복사 버튼은 링크 전체로 navigator.clipboard.writeText를 부른다', async () => {
    const writeText = stubClipboard()
    api = mockApi(homeServer())
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(screen.getByRole('button', { name: MAKE }))
    fireEvent.click(await screen.findByRole('button', { name: COPY }))
    await settle()
    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/invite#${TOKEN}`)
  })

  it('US2-7: 만든 직후 취소하면 DELETE 한 번 뒤 링크가 사라지고 만료 기간 선택과 만들기 버튼이 다시 보인다', async () => {
    api = mockApi(homeServer())
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(screen.getByRole('button', { name: MAKE }))
    await screen.findByText(JUST_MADE)
    fireEvent.click(screen.getByRole('button', { name: CANCEL }))
    await screen.findByRole('button', { name: MAKE })
    await settle()
    expect(callsOf('DELETE', '/api/v1/household/invitations/7')).toHaveLength(1)
    screen.getByLabelText(EXPIRY_LABEL)
    expect(screen.queryByText(JUST_MADE)).toBeNull()
    expect(bodyText()).not.toContain(TOKEN)
    expect(screen.queryByText(SENT_TITLE)).toBeNull()
  })
})

describe('보낸 초대 영역: 다시 열었을 때(owner)', () => {
  it.each([
    // 2026-11-18T00:30+09:00 는 UTC로는 11월 17일이다. Asia/Seoul 날짜로 읽어야 18일이다.
    {
      createdAt: '2026-11-18T00:30:00+09:00',
      expiresAt: '2026-11-21T00:30:00+09:00',
      remainingDays: 3,
      text: '11월 18일 만듦, 3일 남음',
    },
    // 앞에 0을 붙이지 않고, N은 remainingDays 값이다.
    {
      createdAt: '2026-03-05T10:00:00+09:00',
      expiresAt: '2026-03-06T10:00:00+09:00',
      remainingDays: 1,
      text: '3월 5일 만듦, 1일 남음',
    },
  ])('US2-4, FR-010: "$text" 와 취소 버튼만 보이고 링크와 복사는 없다', async (row) => {
    api = mockApi(homeServer({ invitations: [{ id: 7, ...row }] }))
    openAt('/')
    await screen.findByText(row.text)
    screen.getByText(SENT_TITLE)
    screen.getByRole('button', { name: CANCEL })
    expect(screen.queryByRole('button', { name: COPY })).toBeNull()
    expect(screen.queryByText(JUST_MADE)).toBeNull()
    expect(screen.queryByText(LINK_WARNING)).toBeNull()
    expect(bodyText()).not.toContain('/invite#')
    expect(bodyText()).not.toContain(TOKEN)
  })

  it('US2-7: 보낸 초대의 취소를 누르면 DELETE 뒤 행이 사라지고 새 초대를 만들 수 있다', async () => {
    api = mockApi(homeServer({ invitations: [pending] }))
    openAt('/')
    await screen.findByText('11월 17일 만듦, 3일 남음')
    expect(screen.queryByRole('button', { name: MAKE })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: CANCEL }))
    await screen.findByRole('button', { name: MAKE })
    await settle()
    expect(callsOf('DELETE', '/api/v1/household/invitations/7')).toHaveLength(1)
    screen.getByLabelText(EXPIRY_LABEL)
    expect(screen.queryByText(/일 남음/)).toBeNull()
  })
})

describe('만들 수 없는 이유', () => {
  it('US2-5, FR-009: 기다리는 초대가 있으면 이유 문구만 보이고 선택과 버튼은 없다', async () => {
    api = mockApi(homeServer({ invitations: [pending] }))
    openAt('/')
    await screen.findByText(PENDING_REASON)
    screen.getByText(TITLE)
    expect(screen.queryByLabelText(EXPIRY_LABEL)).toBeNull()
    expect(screen.queryByRole('button', { name: MAKE })).toBeNull()
    expect(screen.queryByText(FULL_REASON)).toBeNull()
  })

  it('US2-5, FR-009: 구성원이 둘이면 이유 문구만 보이고 선택과 버튼은 없다', async () => {
    api = mockApi(homeServer({ household: owner2 }))
    openAt('/')
    await screen.findByText(FULL_REASON)
    screen.getByText(TITLE)
    expect(screen.queryByLabelText(EXPIRY_LABEL)).toBeNull()
    expect(screen.queryByRole('button', { name: MAKE })).toBeNull()
    expect(screen.queryByText(PENDING_REASON)).toBeNull()
  })

  it('FR-009 끝 문장: 구성원이 둘이면서 보낸 초대 목록도 비어 있지 않으면 구성원이 둘이라는 이유를 보인다', async () => {
    api = mockApi(homeServer({ household: owner2, invitations: [pending] }))
    openAt('/')
    await screen.findByText(FULL_REASON)
    expect(screen.queryByText(PENDING_REASON)).toBeNull()
    expect(screen.queryByLabelText(EXPIRY_LABEL)).toBeNull()
    expect(screen.queryByRole('button', { name: MAKE })).toBeNull()
  })
})

// R14: 발급이나 취소가 409면 다른 탭에서 바뀐 것이므로 보낸 초대 목록과 함께 보는 사람 목록을 다시 읽는다.
describe('다른 탭에서 바뀐 경우 (409)', () => {
  /** 첫 번째 요청(발급이나 취소)이 409를 받는 순간 서버 상태가 바뀐다. 이후 두 목록은 바뀐 상태를 돌려준다. */
  function changedElsewhere(o: {
    method: string
    path: string
    code: string
    household: Reply
    invitations: Invitation[]
  }) {
    const base = homeServer({ invitations: o.method === 'DELETE' ? [pending] : [] })
    let changed = false
    return (call: ApiCall): Outcome => {
      if (is(call, o.method, o.path)) {
        changed = true
        return problem(409, o.code)
      }
      if (changed && is(call, 'GET', '/api/v1/household')) return o.household
      if (changed && is(call, 'GET', '/api/v1/household/invitations'))
        return { status: 200, body: { invitations: o.invitations } }
      return base(call)
    }
  }

  it('T046: 발급이 409 INVITATION_PENDING이면 보낸 초대 목록을 다시 읽어 그 초대와 이유 문구를 보인다', async () => {
    const path = '/api/v1/household/invitations'
    api = mockApi(
      changedElsewhere({
        method: 'POST',
        path,
        code: 'INVITATION_PENDING',
        household: members,
        invitations: [pending],
      }),
    )
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: MAKE }))
    await screen.findByText(PENDING_REASON)
    screen.getByText('11월 17일 만듦, 3일 남음')
    expect(screen.queryByLabelText(EXPIRY_LABEL)).toBeNull()
  })

  it('T046: 발급이 409 HOUSEHOLD_FULL이면 함께 보는 사람 목록을 다시 읽어 새 구성원과 이유 문구를 보인다', async () => {
    const path = '/api/v1/household/invitations'
    api = mockApi(
      changedElsewhere({ method: 'POST', path, code: 'HOUSEHOLD_FULL', household: owner2, invitations: [] }),
    )
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: MAKE }))
    await screen.findByText(FULL_REASON)
    screen.getByText('user2@example.com', { exact: false })
    expect(screen.queryByRole('button', { name: MAKE })).toBeNull()
  })

  it('T046: 취소가 409이면 두 목록을 다시 읽어 사라진 초대 행을 지우고 새 구성원을 보인다(다른 곳에서 수락됨)', async () => {
    const path = '/api/v1/household/invitations/7'
    api = mockApi(
      changedElsewhere({ method: 'DELETE', path, code: 'INVITATION_NOT_PENDING', household: owner2, invitations: [] }),
    )
    openAt('/')
    await screen.findByText('11월 17일 만듦, 3일 남음')
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    await screen.findByText(FULL_REASON)
    expect(screen.queryByText('11월 17일 만듦, 3일 남음')).toBeNull()
    screen.getByText('user2@example.com', { exact: false })
  })
})

describe('member 화면', () => {
  it('US2-6: member에게는 만료 기간 선택, 만들기 버튼, 보낸 초대, 취소가 없고 보낸 초대 목록 요청도 보내지 않는다', async () => {
    api = mockApi(homeServer({ household: asMember }))
    openAt('/')
    await screen.findByText(TITLE)
    expect(bodyText()).toContain('민지')
    expect(bodyText()).toContain('member')
    await settle()
    expect(screen.queryByLabelText(EXPIRY_LABEL)).toBeNull()
    expect(screen.queryByRole('button', { name: MAKE })).toBeNull()
    expect(screen.queryByText(MAKE_GUIDE)).toBeNull()
    expect(screen.queryByText(SENT_TITLE)).toBeNull()
    expect(screen.queryByRole('button', { name: CANCEL })).toBeNull()
    expect(callsOf('GET', '/api/v1/household/invitations')).toEqual([])
  })
})

describe('재인증', () => {
  it('US2-2, FR-022: 발급이 401 REAUTH_REQUIRED면 안내와 구글 계정 확인 링크(purpose=reauth&returnTo=/)가 보이고, 자동으로 다시 보내지 않으며, 돌아오면 선택은 3일이다', async () => {
    api = mockApi(homeServer({ issue: problem(401, 'REAUTH_REQUIRED') }))
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.change(screen.getByLabelText(EXPIRY_LABEL), {
      target: { value: '5' },
    })
    fireEvent.click(screen.getByRole('button', { name: MAKE }))
    await screen.findByText(REAUTH_GUIDE)
    const link = screen.getByRole('link', { name: REAUTH_LINK })
    expect(link.getAttribute('href')).toBe(`${LOGIN_START}?purpose=reauth&returnTo=/`)
    expect(screen.queryByText(JUST_MADE)).toBeNull()
    await settle()
    expect(callsOf('POST', '/api/v1/household/invitations')).toHaveLength(1)

    // 재인증에서 돌아온 것을 같은 탭에서 앱을 다시 그리는 것으로 흉내 낸다
    cleanup()
    openAt('/')
    await screen.findByText(TITLE)
    expect((screen.getByLabelText(EXPIRY_LABEL) as HTMLSelectElement).value).toBe('3')
    await settle()
    expect(callsOf('POST', '/api/v1/household/invitations')).toHaveLength(1)
  })
})

describe('수락 뒤 owner의 목록', () => {
  it('US2-17: 수락한 사람이 목록에 member 역할과 표시 이름, 로그인 이메일로 보인다', async () => {
    api = mockApi(homeServer({ household: owner2 }))
    openAt('/')
    await screen.findByText(TITLE)
    const text = bodyText()
    expect(text).toContain('아빠')
    expect(text).toContain('member')
    expect(text).toContain('user2@example.com')
    expect(text).toContain('owner')
  })
})

// ---------- 초대 확인과 수락(/invite) ----------

/** 초대 확인과 수락의 서버 흉내. 수락 204 뒤에는 목록이 afterAccept로 바뀐다. */
function inviteServer(o: { household?: Reply; preview?: Reply; accept?: Reply; afterAccept?: Reply; session?: Reply }) {
  const state = { household: o.household ?? noHousehold }
  return (call: ApiCall): Outcome => {
    if (is(call, 'GET', '/api/v1/me')) return o.session ?? me
    if (is(call, 'GET', '/api/v1/household')) return state.household
    if (is(call, 'POST', '/api/v1/invitations/preview'))
      return o.preview ?? { status: 200, body: { inviterDisplayName: '민지' } }
    if (is(call, 'POST', '/api/v1/invitations/accept')) {
      const reply = o.accept ?? noContent
      if (reply.status === 204 && o.afterAccept) state.household = o.afterAccept
      return reply
    }
    return undefined
  }
}

const openInvite = () => openAt(`/invite#${TOKEN}`)
const previewCalls = () => callsOf('POST', '/api/v1/invitations/preview')
const acceptCalls = () => callsOf('POST', '/api/v1/invitations/accept')

describe('로그인 화면의 초대 안내와 로그인 뒤 확인 화면', () => {
  it('US2-8, FR-012: 로그인하지 않으면 새 초대 안내가 보이고, 로그인한 뒤 다른 입력 없이 초대 확인 화면이 열린다', async () => {
    api = mockApi((call) =>
      is(call, 'GET', '/api/v1/me') || is(call, 'POST', '/api/v1/invitations/preview') ? unauthenticated : undefined,
    )
    openInvite()
    await screen.findByText(NEW_INVITE_GUIDE)
    const login = screen.getByRole('link', { name: LOGIN })
    const url = new URL(login.getAttribute('href') ?? '', window.location.origin)
    expect(url.pathname).toBe(LOGIN_START)
    expect([...url.searchParams]).toEqual([['returnTo', '/invite']])
    // 로그인하지 않은 사람에게는 확인 화면이 보이지 않는다
    expect(screen.queryByText(CONFIRM_NAMED)).toBeNull()
    expect(screen.queryByRole('button', { name: JOIN })).toBeNull()

    // 로그인을 마치고 같은 탭의 /invite로 돌아온 것을 흉내 낸다(sessionStorage는 그대로)
    cleanup()
    api = mockApi(inviteServer({}))
    openAt('/invite')
    await screen.findByText(CONFIRM_NAMED)
    screen.getByRole('button', { name: JOIN })
    await settle()
    expect(acceptCalls()).toEqual([])
    expect(previewCalls().length).toBeGreaterThanOrEqual(1)
    expect(bodyOf(previewCalls()[0])).toEqual({ token: TOKEN })
  })
})

describe('초대 확인 화면', () => {
  it('US2-9: 이름이 있으면 "민지님의 가구에 들어갑니다. ..." 와 들어가기, 취소 버튼이 보이고 이메일과 구성원 목록은 없다', async () => {
    api = mockApi(inviteServer({}))
    openInvite()
    await screen.findByText(CONFIRM_NAMED)
    screen.getByRole('button', { name: JOIN })
    screen.getByRole('button', { name: CANCEL })
    expect(bodyText()).not.toContain('user1@example.com')
    expect(bodyText()).not.toContain('@')
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(screen.queryByText(SENT_TITLE)).toBeNull()
    expect(bodyOf(previewCalls()[0])).toEqual({ token: TOKEN })
  })

  it('US2-9: 이름이 없으면 이름 없이 "가구에 들어갑니다. ..." 로 시작한다', async () => {
    api = mockApi(
      inviteServer({
        preview: { status: 200, body: { inviterDisplayName: null } },
      }),
    )
    openInvite()
    await screen.findByText(CONFIRM_ANONYMOUS)
    screen.getByRole('button', { name: JOIN })
    screen.getByRole('button', { name: CANCEL })
    expect(bodyText()).not.toContain('null')
    expect(bodyText()).not.toContain('님')
    expect(bodyText()).not.toContain('@')
    expect(screen.queryByText(TITLE)).toBeNull()
  })
})

describe('들어가기와 취소', () => {
  it('US2-10, SC-002: 들어가기 한 번이면 수락 요청 한 번(본문 token) 뒤 다시 읽은 목록대로 임시 첫 화면이 열린다', async () => {
    api = mockApi(inviteServer({ accept: noContent, afterAccept: asMember }))
    openInvite()
    fireEvent.click(await screen.findByRole('button', { name: JOIN }))
    await screen.findByText(TITLE)
    await settle()
    expect(acceptCalls()).toHaveLength(1)
    expect(bodyOf(acceptCalls()[0])).toEqual({ token: TOKEN })
    expect(window.location.pathname).toBe('/')
    const text = bodyText()
    expect(text).toContain('민지')
    expect(text).toContain('owner')
    expect(text).toContain('member')
    expect(text).toContain('owner@example.com')
    expect(screen.queryByText(CONFIRM_NAMED)).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    // 방금 member가 되었으므로 owner 영역은 없다
    expect(screen.queryByLabelText(EXPIRY_LABEL)).toBeNull()
    expect(callsOf('GET', '/api/v1/household/invitations')).toEqual([])
  })

  it('US2-10: 취소하면 수락 요청 없이 가구 없음 화면이 열리고, 같은 링크를 다시 열면 확인 화면이 다시 나온다', async () => {
    api = mockApi(inviteServer({}))
    openInvite()
    fireEvent.click(await screen.findByRole('button', { name: CANCEL }))
    await screen.findByText(NO_HOUSEHOLD)
    await settle()
    expect(acceptCalls()).toEqual([])
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(CONFIRM_NAMED)).toBeNull()

    cleanup()
    openInvite()
    await screen.findByText(CONFIRM_NAMED)
    screen.getByRole('button', { name: JOIN })
    expect(acceptCalls()).toEqual([])
  })
})

describe('수락할 수 없는 초대(확인 단계)', () => {
  it.each([
    {
      scenario: 'US2-12 존재하지 않음',
      status: 404,
      code: 'NOT_FOUND',
      hasHousehold: false,
    },
    {
      scenario: 'US2-12 취소됨',
      status: 409,
      code: 'INVITATION_REVOKED',
      hasHousehold: false,
    },
    {
      scenario: 'US2-12 사용됨',
      status: 409,
      code: 'INVITATION_USED',
      hasHousehold: false,
    },
    {
      scenario: 'US2-11 만료됨',
      status: 409,
      code: 'INVITATION_EXPIRED',
      hasHousehold: false,
    },
    {
      scenario: 'US2-12 구성원 둘',
      status: 409,
      code: 'HOUSEHOLD_FULL',
      hasHousehold: false,
    },
    {
      scenario: 'US2-13 다른 가구',
      status: 409,
      code: 'HAS_HOUSEHOLD',
      hasHousehold: true,
    },
    {
      scenario: 'US2-15 owner가 자기 링크',
      status: 409,
      code: 'ALREADY_IN_THIS_HOUSEHOLD',
      hasHousehold: true,
    },
    // US2-14: 다른 가구에 속한 사람에게도 링크 자체의 사유(만료, 취소)를 먼저 안내한다
    {
      scenario: 'US2-14 다른 가구 사람이 만료 링크',
      status: 409,
      code: 'INVITATION_EXPIRED',
      hasHousehold: true,
    },
    {
      scenario: 'US2-14 다른 가구 사람이 취소 링크',
      status: 409,
      code: 'INVITATION_REVOKED',
      hasHousehold: true,
    },
  ])(
    '$scenario($code): 확인 화면 대신 그 안내와 "첫 화면으로" 버튼이 보인다',
    async ({ status, code, hasHousehold }) => {
      api = mockApi(
        inviteServer({
          household: hasHousehold ? members : noHousehold,
          preview: problem(status, code),
        }),
      )
      openInvite()
      await screen.findByText(FAILURE[code])
      screen.getByRole('button', { name: TO_HOME })
      expect(screen.queryByRole('button', { name: JOIN })).toBeNull()
      expect(screen.queryByText(CONFIRM_NAMED)).toBeNull()
      // 이 안내 하나만 보인다(다른 사유 안내가 섞이지 않는다)
      for (const [other, message] of Object.entries(FAILURE)) {
        if (other !== code) expect(screen.queryByText(message)).toBeNull()
      }
      await settle()
      expect(acceptCalls()).toEqual([])
    },
  )

  it('R7: 토큰이 없으면 API를 부르지 않고 존재하지 않는 초대 링크 안내와 "첫 화면으로" 버튼이 보인다', async () => {
    api = mockApi(inviteServer({}))
    openAt('/invite')
    await screen.findByText(FAILURE.NOT_FOUND)
    screen.getByRole('button', { name: TO_HOME })
    await settle()
    expect(previewCalls()).toEqual([])
    expect(acceptCalls()).toEqual([])
  })

  it('"첫 화면으로"를 누르면 /로 화면 안에서 이동해 가구 없음 화면이 보인다', async () => {
    api = mockApi(inviteServer({ preview: problem(409, 'INVITATION_USED') }))
    openInvite()
    await screen.findByText(FAILURE.INVITATION_USED)
    fireEvent.click(screen.getByRole('button', { name: TO_HOME }))
    await screen.findByText(NO_HOUSEHOLD)
    expect(window.location.pathname).toBe('/')
    expect(screen.queryByText(FAILURE.INVITATION_USED)).toBeNull()
  })
})

describe('수락할 수 없는 초대(들어가기를 누른 순간)', () => {
  it.each([
    { scenario: 'US2-11 확인 화면을 연 채로 만료', code: 'INVITATION_EXPIRED' },
    { scenario: 'US2-12 그 사이에 취소', code: 'INVITATION_REVOKED' },
    { scenario: 'US2-12 그 사이에 구성원 둘', code: 'HOUSEHOLD_FULL' },
    { scenario: 'US2-16 동시 수락에서 진 쪽', code: 'INVITATION_USED' },
  ])(
    '$scenario($code): 들어가기가 409면 그 안내와 "첫 화면으로" 버튼이 보이고 임시 첫 화면은 아니다',
    async ({ code }) => {
      api = mockApi(inviteServer({ accept: problem(409, code) }))
      openInvite()
      fireEvent.click(await screen.findByRole('button', { name: JOIN }))
      await screen.findByText(FAILURE[code])
      screen.getByRole('button', { name: TO_HOME })
      await settle()
      expect(acceptCalls()).toHaveLength(1)
      expect(screen.queryByRole('button', { name: JOIN })).toBeNull()
      expect(screen.queryByText(CONFIRM_NAMED)).toBeNull()
      expect(screen.queryByText(TITLE)).toBeNull()
      expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    },
  )
})

const RETRY_GUIDE = '잠시 후 다시 시도해 주세요.'

describe('게이트: 늦게 온 첫 목록 응답', () => {
  /**
   * mockApi 위에 fetch를 한 겹 더 씌워 첫 GET /api/v1/household/invitations 응답을 붙잡아 두었다가 release()로 푼다.
   * 요청은 mockApi에 그대로 기록된다. late가 있으면 그 순간 서버의 답 대신 late로 푼다.
   */
  function holdFirstList(late?: Reply) {
    const mocked = globalThis.fetch
    const hold = { held: false, release: () => {} }
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : undefined
      const url = new URL(request ? request.url : String(input), window.location.href)
      const method = (init?.method ?? request?.method ?? 'GET').toUpperCase()
      if (hold.held || method !== 'GET' || url.pathname !== '/api/v1/household/invitations') return mocked(input, init)
      hold.held = true
      const answer = await mocked(input, init)
      await new Promise<void>((resolve) => {
        hold.release = resolve
      })
      if (!late) return answer
      const json = late.body !== undefined
      return new Response(json ? JSON.stringify(late.body) : null, {
        status: late.status,
        headers: json ? { 'Content-Type': 'application/problem+json' } : {},
      })
    })
    return hold
  }

  it('게이트, US2-2: 첫 보낸 초대 목록 응답이 발급 201보다 늦게 빈 목록으로 와도 만든 직후의 링크와 버튼이 그대로 보인다', async () => {
    api = mockApi(homeServer())
    const hold = holdFirstList()
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: MAKE }))
    await screen.findByText(JUST_MADE)
    expect(hold.held).toBe(true)

    act(() => hold.release())
    await settle()
    screen.getByText(`${window.location.origin}/invite#${TOKEN}`)
    screen.getByRole('button', { name: COPY })
    screen.getByText(JUST_MADE)
    screen.getByRole('button', { name: CANCEL })
    expect(screen.queryByRole('button', { name: MAKE })).toBeNull()
  })

  it.each([
    { name: '500', late: serverError },
    { name: '401 UNAUTHENTICATED', late: unauthenticated },
  ])('재리뷰, R14: 늦게 온 첫 목록 응답이 $name 이어도 발급 결과를 덮지 않는다', async ({ late }) => {
    api = mockApi(homeServer())
    const hold = holdFirstList(late)
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: MAKE }))
    await screen.findByText(JUST_MADE)
    expect(hold.held).toBe(true)

    act(() => hold.release())
    await settle()
    screen.getByText(`${window.location.origin}/invite#${TOKEN}`)
    screen.getByRole('button', { name: COPY })
    screen.getByText(JUST_MADE)
    screen.getByText(TITLE)
    expect(screen.queryByText(RETRY_GUIDE)).toBeNull()
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
  })
})

describe('게이트: 그 밖의 오류 (R14)', () => {
  it('게이트, R14: 보낸 초대의 취소가 404 NOT_FOUND면 두 목록을 다시 읽어 그 결과대로 그린다', async () => {
    const base = homeServer({ invitations: [pending] })
    let gone = false
    api = mockApi((call) => {
      if (is(call, 'DELETE', '/api/v1/household/invitations/7')) {
        gone = true
        return problem(404, 'NOT_FOUND')
      }
      if (gone && is(call, 'GET', '/api/v1/household/invitations')) return { status: 200, body: { invitations: [] } }
      return base(call)
    })
    openAt('/')
    await screen.findByText('11월 17일 만듦, 3일 남음')
    await settle()
    const households = callsOf('GET', '/api/v1/household').length
    const lists = callsOf('GET', '/api/v1/household/invitations').length
    fireEvent.click(screen.getByRole('button', { name: CANCEL }))
    await screen.findByRole('button', { name: MAKE })
    await settle()
    expect(callsOf('GET', '/api/v1/household').length).toBeGreaterThan(households)
    expect(callsOf('GET', '/api/v1/household/invitations').length).toBeGreaterThan(lists)
    expect(screen.queryByText('11월 17일 만듦, 3일 남음')).toBeNull()
    screen.getByLabelText(EXPIRY_LABEL)
  })

  it.each([
    { name: '발급이 500', method: 'POST', path: '/api/v1/household/invitations', reply: serverError as Outcome },
    { name: '발급이 네트워크 오류', method: 'POST', path: '/api/v1/household/invitations', reply: networkError as Outcome },
    { name: '취소가 500', method: 'DELETE', path: '/api/v1/household/invitations/7', reply: serverError as Outcome },
  ])('게이트, R14: $name 면 "잠시 후 다시 시도해 주세요."가 보이고 임시 첫 화면 그대로다', async ({ method, path, reply }) => {
    const base = homeServer({ invitations: method === 'DELETE' ? [pending] : [] })
    api = mockApi((call) => (is(call, method, path) ? reply : base(call)))
    openAt('/')
    await screen.findByText(TITLE)
    fireEvent.click(await screen.findByRole('button', { name: method === 'DELETE' ? CANCEL : MAKE }))
    await screen.findByText(RETRY_GUIDE)
    await settle()
    expect(callsOf(method, path)).toHaveLength(1)
    screen.getByText(TITLE)
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
  })

  it('게이트, R14: 발급 500 뒤 다시 만들기를 눌러 201이면 그 안내가 사라진다', async () => {
    const base = homeServer()
    let failed = false
    api = mockApi((call) => {
      if (!failed && is(call, 'POST', '/api/v1/household/invitations')) {
        failed = true
        return serverError
      }
      return base(call)
    })
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: MAKE }))
    await screen.findByText(RETRY_GUIDE)
    fireEvent.click(screen.getByRole('button', { name: MAKE }))
    await screen.findByText(JUST_MADE)
    await settle()
    expect(callsOf('POST', '/api/v1/household/invitations')).toHaveLength(2)
    expect(screen.queryByText(RETRY_GUIDE)).toBeNull()
  })
})
