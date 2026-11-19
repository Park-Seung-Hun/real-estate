/**
 * T078 내 표시 이름 화면 동작 테스트(spec US5-1~US5-4, FR-003, FR-027, research R13, R14).
 * 공개 표면은 members.test.tsx 맨 위 주석에 더해 다음을 따른다. 가구가 있는 사람의 임시 첫 화면(`/`) 맨 위, "함께 보는 사람" 앞이고
 * owner와 member 모두 보인다.
 * - 제목 `<h2>` "내 표시 이름". `<dl>`의 `<dt>` "지금 이름" 바로 다음 `<dd>`가 지금 이름이고 이름이 null이면 `<dd>` 글자는 빈 문자열이다.
 *   화면에 term, definition은 이것 하나씩뿐이고, 지금 이름은 GET /api/v1/household 목록에서 me가 true인 항목의 표시 이름이다(따로 요청하지 않음).
 * - `<input type="text">`(접근 이름과 자리표시 "새 이름", 처음 값 빈 문자열)과 `<button>` "저장". 화면의 textbox는 이것 하나뿐이고
 *   다른 구성원의 행에는 입력란이 없다(US5-4).
 * - 저장은 PATCH /api/v1/me이고 본문은 {"displayName": 입력란 값 그대로}다(화면에서 자르거나 길이를 검사하지 않고 서버가 거절한다).
 *   응답을 기다리는 동안 다시 눌러도 요청은 하나다.
 * - 200이면 입력란을 비우고 목록을 다시 읽어 지금 이름과 내 행이 새 이름이 된다.
 * - 400 VALIDATION_FAILED(001 계약 예시 본문)면 글자 전체가 "표시 이름은 1자 이상 20자 이하로 입력해 주세요."인 요소 하나가 보이고,
 *   지금 이름과 목록은 그대로이고 목록을 다시 읽지 않으며 입력란 값은 남는다.
 * - 401 UNAUTHENTICATED는 로그인 화면, 그 밖의 실패(500, 네트워크 오류)는 "잠시 후 다시 시도해 주세요." 요소 하나이고 지금 이름은 그대로다.
 * - 안내는 첫 화면 전체에서 한 번에 하나만 보인다. 저장을 다시 누르면 앞 안내는 응답 전에 사라진다.
 * - 저장 요청 뒤 로그아웃(로그인 화면)이 먼저 되면 늦게 온 200은 목록을 다시 읽지 않고, 늦게 온 400은 안내를 띄우지 않는다.
 * 흉내 응답은 상태를 갖는다(저장하면 목록의 내 이름이 바뀌고, 로그아웃 뒤에는 모든 요청이 401이다).
 */
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../src/App'
import {
  type ApiCall,
  holdNext,
  me,
  mockApi,
  networkError,
  noContent,
  type Reply,
  serverError,
  settle,
  unauthenticated,
  watchNavigation,
} from './api-mock'

const LOGIN = 'Google 계정으로 로그인'
const LOGOUT = '로그아웃'
const TITLE = '함께 보는 사람'
const HEADING = '내 표시 이름'
const NEW_NAME = '새 이름'
const SAVE = '저장'
const RETRY = '잠시 후 다시 시도해 주세요.'
const REASON = '표시 이름은 1자 이상 20자 이하로 입력해 주세요.'
const ME_PATH = '/api/v1/me'
const HOUSEHOLD_PATH = '/api/v1/household'
const MINE = 'user1@example.com'
const OTHER = 'user2@example.com'

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

const is = (call: ApiCall, method: string, path: string) => call.method === method && call.path === path
const callsOf = (method: string, path: string) => api.calls.filter((c) => is(c, method, path))
const saveCalls = () => callsOf('PATCH', ME_PATH)
const listCalls = () => callsOf('GET', HOUSEHOLD_PATH)
const bodyText = () => document.body.textContent ?? ''

// 계약(specs/001-login/contracts/openapi.yaml)의 PATCH /api/v1/me 400 예시
const validationFailed: Reply = {
  status: 400,
  body: {
    type: 'https://example.com/problems/validation-failed',
    title: '입력값이 올바르지 않습니다',
    status: 400,
    detail: '입력값 하나를 확인해 주세요.',
    code: 'VALIDATION_FAILED',
    errors: [{ pointer: '#/displayName', detail: REASON }],
  },
}

const NORMAL = 'normal' as const

/**
 * 표시 이름 상태를 갖는 서버 흉내. role은 내 역할이다(owner면 다른 구성원이 member 아빠, member면 다른 구성원이 owner 민지).
 * 저장 응답은 patch를 차례로 쓰고 마지막 것을 되풀이한다. NORMAL이면 서버 규칙대로(앞뒤 공백을 뺀 1자 이상 20자 이하)
 * 성공하면 내 이름을 바꾸고 200, 아니면 계약의 400을 돌려준다. 401 UNAUTHENTICATED나 로그아웃 204 뒤에는 모든 요청이 401이다.
 */
function server(o: { role?: 'owner' | 'member'; name?: string | null; patch?: Array<Outcome | typeof NORMAL> }) {
  const role = o.role ?? 'owner'
  const state = { name: o.name === undefined ? '엄마' : o.name, loggedOut: false, saved: 0 }
  const household = (): Reply => {
    const mine = { displayName: state.name, loginEmail: MINE, emailVerified: true, me: true }
    const other = { displayName: role === 'owner' ? '아빠' : '민지', loginEmail: OTHER, emailVerified: true, me: false }
    return {
      status: 200,
      body: {
        members:
          role === 'owner'
            ? [{ userId: 1, role: 'owner', ...mine }, { userId: 2, role: 'member', ...other }]
            : [{ userId: 1, role: 'owner', ...other }, { userId: 2, role: 'member', ...mine }],
      },
    }
  }
  return (call: ApiCall): Outcome => {
    if (is(call, 'POST', '/api/v1/logout')) {
      state.loggedOut = true
      return noContent
    }
    if (state.loggedOut) return unauthenticated
    if (is(call, 'GET', ME_PATH)) return me
    if (is(call, 'GET', HOUSEHOLD_PATH)) return household()
    if (is(call, 'GET', '/api/v1/household/invitations')) return { status: 200, body: { invitations: [] } }
    if (is(call, 'PATCH', ME_PATH)) {
      const replies = o.patch ?? [NORMAL]
      const reply = replies[Math.min(state.saved++, replies.length - 1)]
      if (reply === unauthenticated) state.loggedOut = true
      if (reply !== NORMAL) return reply
      const displayName = String(JSON.parse(call.body ?? '{}').displayName ?? '')
      const trimmed = displayName.trim()
      if (trimmed.length < 1 || trimmed.length > 20) return validationFailed
      state.name = trimmed
      return { status: 200, body: { displayName: trimmed, loginEmail: MINE, emailVerified: true } }
    }
    return undefined
  }
}

async function openHome() {
  window.history.replaceState(null, '', '/')
  render(<App />)
  await screen.findByText(TITLE)
}

/** 그 로그인 이메일 글자를 포함하는 listitem */
function rowOf(email: string) {
  const row = screen.getAllByRole('listitem').find((li) => (li.textContent ?? '').includes(email))
  if (!row) throw new Error(`행이 없습니다: ${email}`)
  return row
}

const input = () => screen.getByRole('textbox', { name: NEW_NAME }) as HTMLInputElement
const currentName = () => screen.getByRole('definition').textContent
const type = (value: string) => fireEvent.change(input(), { target: { value } })
const save = () => fireEvent.click(screen.getByRole('button', { name: SAVE }))
/** 입력해 저장 버튼을 누른다 */
function saveAs(value: string) {
  type(value)
  save()
}
const sentName = (n: number) => JSON.parse(saveCalls()[n].body ?? 'null')

describe('표시 영역(US5-1, US5-4)', () => {
  it.each([
    { role: 'owner' as const, other: '아빠' },
    { role: 'member' as const, other: '민지' },
  ])('US5-1: [$role] 제목, 지금 이름(내 행의 이름), 빈 "새 이름" 입력란, 저장 버튼이 보이고 요청은 목록뿐이다', async ({ role, other }) => {
    api = mockApi(server({ role }))
    await openHome()
    screen.getByRole('heading', { name: HEADING })
    expect(screen.getByRole('term').textContent).toBe('지금 이름')
    expect(currentName()).toBe('엄마')
    expect(rowOf(MINE).textContent).toContain('엄마')
    expect(rowOf(OTHER).textContent).toContain(other)
    expect(input().type).toBe('text')
    expect(input().value).toBe('')
    expect(input().placeholder).toBe(NEW_NAME)
    screen.getByRole('button', { name: SAVE })
    await settle()
    expect(saveCalls()).toEqual([])
  })

  it.each(['owner' as const, 'member' as const])('US5-1: [%s] 내 이름이 null이면 지금 이름이 빈칸이고 입력란과 저장 버튼은 그대로 있다', async (role) => {
    api = mockApi(server({ role, name: null }))
    await openHome()
    expect(screen.getByRole('term').textContent).toBe('지금 이름')
    expect(currentName()).toBe('')
    expect(bodyText()).not.toMatch(/null|undefined/)
    expect(input().value).toBe('')
    screen.getByRole('button', { name: SAVE })
  })

  it.each(['owner' as const, 'member' as const])('US5-4: [%s] 입력란은 화면에 하나뿐이고 다른 구성원의 행에는 없다', async (role) => {
    api = mockApi(server({ role }))
    await openHome()
    expect(screen.getAllByRole('textbox')).toHaveLength(1)
    expect(within(rowOf(OTHER)).queryByRole('textbox')).toBeNull()
    expect(within(rowOf(OTHER)).queryByRole('button', { name: SAVE })).toBeNull()
    expect(screen.getAllByRole('button', { name: SAVE })).toHaveLength(1)
  })
})

describe('저장 성공(US5-2)', () => {
  it.each(['owner' as const, 'member' as const])('[%s] PATCH 본문은 displayName 하나이고, 목록을 다시 읽어 지금 이름과 내 행이 새 이름이 되고 입력란이 빈다', async (role) => {
    api = mockApi(server({ role }))
    await openHome()
    const gets = listCalls().length
    saveAs('새이름')
    await waitFor(() => expect(currentName()).toBe('새이름'))
    await settle()
    expect(saveCalls()).toHaveLength(1)
    expect(saveCalls()[0].sameOrigin).toBe(true)
    expect(sentName(0)).toEqual({ displayName: '새이름' })
    expect(listCalls()).toHaveLength(gets + 1)
    expect(rowOf(MINE).textContent).toContain('새이름')
    expect(rowOf(MINE).textContent).not.toContain('엄마')
    expect(input().value).toBe('')
    expect(bodyText()).not.toContain(REASON)
    expect(bodyText()).not.toContain(RETRY)
  })

  it('US5-2: 이름이 null이던 사람도 저장하면 지금 이름이 새 이름이 된다', async () => {
    api = mockApi(server({ name: null }))
    await openHome()
    saveAs('엄마')
    await waitFor(() => expect(currentName()).toBe('엄마'))
    expect(rowOf(MINE).textContent).toContain('엄마')
  })
})

describe('저장 거절(US5-3)', () => {
  it.each([
    { label: '공백만', value: '   ' },
    { label: '21자', value: '가나다라마바사아자차카타파하가나다라마바사' },
  ])('[$label] 이유 문구가 보이고, 입력값을 그대로 보내며, 지금 이름과 목록이 그대로이고 목록을 다시 읽지 않는다', async ({ value }) => {
    api = mockApi(server({}))
    await openHome()
    const gets = listCalls().length
    saveAs(value)
    await screen.findByText(REASON)
    await settle()
    expect(saveCalls()).toHaveLength(1)
    expect(sentName(0)).toEqual({ displayName: value })
    expect(currentName()).toBe('엄마')
    expect(rowOf(MINE).textContent).toContain('엄마')
    expect(listCalls()).toHaveLength(gets)
    expect(input().value).toBe(value)
    expect(screen.queryByText(RETRY)).toBeNull()
  })
})

describe('저장 실패', () => {
  it('401 UNAUTHENTICATED면 로그인 화면이다', async () => {
    api = mockApi(server({ patch: [unauthenticated] }))
    await openHome()
    saveAs('새이름')
    await screen.findByRole('link', { name: LOGIN })
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(screen.queryByText(HEADING)).toBeNull()
    expect(saveCalls()).toHaveLength(1)
  })

  it.each([
    { label: '500', reply: serverError },
    { label: '네트워크 오류', reply: networkError },
  ])('[$label] "잠시 후 다시 시도해 주세요." 하나가 보이고 지금 이름과 목록이 그대로다', async ({ reply }) => {
    api = mockApi(server({ patch: [reply] }))
    await openHome()
    const gets = listCalls().length
    saveAs('새이름')
    await screen.findByText(RETRY)
    await settle()
    expect(screen.getAllByText(RETRY)).toHaveLength(1)
    expect(screen.queryByText(REASON)).toBeNull()
    expect(currentName()).toBe('엄마')
    expect(rowOf(MINE).textContent).toContain('엄마')
    expect(listCalls()).toHaveLength(gets)
    expect(saveCalls()).toHaveLength(1)
  })
})

describe('안내는 하나만(R13)', () => {
  it('400 뒤 다시 저장하면 이유 문구가 응답 전에 사라지고, 성공해도 남지 않는다', async () => {
    api = mockApi(server({ patch: [NORMAL, NORMAL] }))
    await openHome()
    saveAs('   ')
    await screen.findByText(REASON)
    const hold = holdNext('PATCH', ME_PATH)
    saveAs('새이름')
    await waitFor(() => expect(hold.held).toBe(true))
    expect(screen.queryByText(REASON)).toBeNull()
    act(() => hold.release())
    await waitFor(() => expect(currentName()).toBe('새이름'))
    await settle()
    expect(bodyText()).not.toContain(REASON)
    expect(bodyText()).not.toContain(RETRY)
    expect(saveCalls()).toHaveLength(2)
  })

  it('400 뒤 다시 저장한 것이 500이면 다시 시도 안내만 남는다', async () => {
    api = mockApi(server({ patch: [NORMAL, serverError] }))
    await openHome()
    saveAs('   ')
    await screen.findByText(REASON)
    save()
    await screen.findByText(RETRY)
    expect(screen.queryByText(REASON)).toBeNull()
    expect(saveCalls()).toHaveLength(2)
  })

  it('500 뒤 다시 저장한 것이 400이면 이유 문구만 남는다', async () => {
    api = mockApi(server({ patch: [serverError, NORMAL] }))
    await openHome()
    saveAs('   ')
    await screen.findByText(RETRY)
    save()
    await screen.findByText(REASON)
    expect(screen.queryByText(RETRY)).toBeNull()
  })

  it('응답을 기다리는 동안 저장을 다시 눌러도 PATCH는 하나다', async () => {
    api = mockApi(server({}))
    await openHome()
    const hold = holdNext('PATCH', ME_PATH)
    saveAs('새이름')
    await waitFor(() => expect(hold.held).toBe(true))
    save()
    save()
    await settle()
    expect(saveCalls()).toHaveLength(1)
    act(() => hold.release())
    await waitFor(() => expect(currentName()).toBe('새이름'))
    await settle()
    expect(saveCalls()).toHaveLength(1)
  })
})

describe('늦게 온 응답과 로그인 만료', () => {
  /** 저장 응답을 붙잡아 둔 채 로그아웃해 로그인 화면이 된 뒤 응답을 푼다. */
  async function lateReply(value: string) {
    api = mockApi(server({}))
    await openHome()
    await settle()
    const hold = holdNext('PATCH', ME_PATH)
    saveAs(value)
    await waitFor(() => expect(hold.held).toBe(true))
    fireEvent.click(screen.getByRole('button', { name: LOGOUT }))
    await screen.findByRole('link', { name: LOGIN })
    await settle()
    const gets = listCalls().length
    act(() => hold.release())
    await settle()
    expect(saveCalls()).toHaveLength(1)
    expect(callsOf('POST', '/api/v1/logout')).toHaveLength(1)
    expect(listCalls()).toHaveLength(gets)
    screen.getByRole('link', { name: LOGIN })
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(screen.queryByText(HEADING)).toBeNull()
    for (const text of [MINE, OTHER, REASON, RETRY]) expect(bodyText()).not.toContain(text)
  }

  it('로그아웃 뒤 저장 200이 늦게 와도 목록을 다시 읽지 않고 로그인 화면 그대로이다', async () => {
    await lateReply('새이름')
  })

  it('로그아웃 뒤 저장 400이 늦게 와도 안내 문구가 없고 로그인 화면 그대로이다', async () => {
    await lateReply('   ')
  })
})
