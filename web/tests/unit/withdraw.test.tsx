/**
 * T048 탈퇴 확인 화면 테스트(spec US3-1, US3-2, US3-3, FR-016, research R13, tasks T047).
 *
 * 공개 표면은 login.test.tsx 맨 위 주석과 같다(`App`을 경로를 정해 그림, 자료는 전역 fetch).
 * 이 파일이 더 기대하는 것:
 * - 탈퇴 확인 화면의 경로는 `/withdraw`이고(R13), 가구 없음 화면의 "탈퇴"는 문서 이동 없이 이 화면을 연다.
 * - 확인 화면의 "탈퇴"와 "취소"는 버튼이다. 탈퇴는 `DELETE /api/v1/me` 한 번, 취소는 요청 없이 가구 없음 화면이다.
 * - 탈퇴 요청 결과(메인 결정): 응답 전에 다시 눌러도 요청은 한 번, 오류로 끝나면 다시 누를 수 있음,
 *   401 UNAUTHENTICATED면 로그인 화면, 그 밖의 오류(403, 500, 네트워크 끊김)면 화면 글자가 요청 전과 같음.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../src/App'
import {
  type ApiCall,
  me,
  mockApi,
  networkError,
  noContent,
  originRejected,
  type Reply,
  serverError,
  settle,
  unauthenticated,
  watchNavigation,
} from './api-mock'

const NO_HOUSEHOLD = '아직 가구가 없습니다.'
const WITHDRAW = '탈퇴'
const CANCEL = '취소'
const WITHDRAW_NOTICE = '탈퇴하면 계정, 로그인 이메일, 표시 이름과 모든 기기의 로그인 상태가 삭제되며 되돌릴 수 없습니다.'
const LOGIN = 'Google 계정으로 로그인'

let navigation: ReturnType<typeof watchNavigation>
let api: ReturnType<typeof mockApi>

beforeEach(() => {
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

/** 가구 없음 화면의 "탈퇴"는 버튼이나 화면 안 이동 링크일 수 있다. */
function getControl(name: string) {
  return screen.queryByRole('button', { name }) ?? screen.getByRole('link', { name })
}

function deleteCalls() {
  return api.calls.filter((call) => call.method === 'DELETE' && call.path === '/api/v1/me')
}

/** 탈퇴하기 전에는 로그인 상태, DELETE 204 뒤에는 로그인 상태 없음. */
function withdrawable() {
  let session = true
  return (call: ApiCall) => {
    if (call.method === 'GET' && call.path === '/api/v1/me') return session ? me : unauthenticated
    if (call.method === 'DELETE' && call.path === '/api/v1/me') {
      session = false
      return noContent
    }
    return undefined
  }
}

async function expectConfirmScreen() {
  await screen.findByText(WITHDRAW_NOTICE)
  screen.getByRole('button', { name: WITHDRAW })
  screen.getByRole('button', { name: CANCEL })
  expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
}

describe('탈퇴 확인 화면', () => {
  it('US3-1: 가구 없음 화면의 탈퇴를 누르면 요청 없이 확인 화면이 먼저 보인다', async () => {
    api = mockApi(withdrawable())
    openAt('/')
    await screen.findByText(NO_HOUSEHOLD)
    fireEvent.click(getControl(WITHDRAW))
    await expectConfirmScreen()
    expect(window.location.pathname).toBe('/withdraw')
    await settle()
    expect(deleteCalls()).toEqual([])
  })

  it('US3-1: /withdraw 를 열면 확인 화면이고 열기만 해서는 탈퇴 요청을 보내지 않는다', async () => {
    api = mockApi(withdrawable())
    openAt('/withdraw')
    await expectConfirmScreen()
    await settle()
    expect(deleteCalls()).toEqual([])
  })

  it('US3-2: 취소하면 요청 없이 가구 없음 화면으로 돌아간다', async () => {
    api = mockApi(withdrawable())
    openAt('/')
    await screen.findByText(NO_HOUSEHOLD)
    fireEvent.click(getControl(WITHDRAW))
    await expectConfirmScreen()
    fireEvent.click(screen.getByRole('button', { name: CANCEL }))
    await screen.findByText(NO_HOUSEHOLD)
    expect(screen.queryByText(WITHDRAW_NOTICE)).toBeNull()
    await settle()
    expect(api.calls.filter((call) => !(call.method === 'GET' && call.path === '/api/v1/me'))).toEqual([])
  })

  it('US3-3: 확인 화면에서 탈퇴하면 DELETE /api/v1/me 한 번 뒤 로그인 화면이다', async () => {
    api = mockApi(withdrawable())
    openAt('/')
    await screen.findByText(NO_HOUSEHOLD)
    fireEvent.click(getControl(WITHDRAW))
    await expectConfirmScreen()
    expect(deleteCalls()).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: WITHDRAW }))
    await screen.findByRole('link', { name: LOGIN })
    expect(screen.queryByText(WITHDRAW_NOTICE)).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    await settle()
    expect(deleteCalls()).toHaveLength(1)
  })
})

type Outcome = Reply | typeof networkError

/** DELETE 응답을 차례로 정한다. 비면 204. 로그인 상태는 204나 401 뒤에 없어진다. */
function deleteReplies(...replies: Outcome[]) {
  let session = true
  return (call: ApiCall): Outcome | undefined => {
    if (call.method === 'GET' && call.path === '/api/v1/me') return session ? me : unauthenticated
    if (call.method === 'DELETE' && call.path === '/api/v1/me') {
      const reply = replies.shift() ?? noContent
      if (reply !== networkError && (reply.status === 204 || reply.status === 401)) session = false
      return reply
    }
    return undefined
  }
}

/** mockApi가 만든 fetch를 감싸 첫 DELETE 응답을 release()까지 미룬다(요청은 mockApi가 바로 기록한다). */
function holdFirstDelete() {
  const inner = globalThis.fetch
  let release!: () => void
  const gate = new Promise<void>((resolve) => (release = resolve))
  let held = false
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    const response = inner(input, init)
    const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase()
    if (method === 'DELETE' && !held) {
      held = true
      response.catch(() => {})
      await gate
    }
    return response
  })
  return { release }
}

async function openConfirm() {
  openAt('/withdraw')
  await expectConfirmScreen()
  await settle()
}

describe('탈퇴 요청 결과 (메인 결정)', () => {
  it('US3-3: 응답 전에 다시 눌러도 DELETE 는 한 번이고, 오류로 끝나면 다시 눌러 두 번째 요청이 간다', async () => {
    api = mockApi(deleteReplies(serverError, noContent))
    const { release } = holdFirstDelete()
    await openConfirm()
    fireEvent.click(screen.getByRole('button', { name: WITHDRAW }))
    await settle()
    expect(deleteCalls()).toHaveLength(1)
    // 응답 전 다시 누름. 버튼이 사라졌거나 막혔어도 된다
    const again = screen.queryByRole('button', { name: WITHDRAW })
    if (again) fireEvent.click(again)
    await settle()
    expect(deleteCalls()).toHaveLength(1)

    release()
    await settle()
    await screen.findByText(WITHDRAW_NOTICE)
    fireEvent.click(screen.getByRole('button', { name: WITHDRAW }))
    await screen.findByRole('link', { name: LOGIN })
    await settle()
    expect(deleteCalls()).toHaveLength(2)
  })

  it('US3-3: 탈퇴 요청이 401 UNAUTHENTICATED 면 로그인 화면이다', async () => {
    api = mockApi(deleteReplies(unauthenticated))
    await openConfirm()
    fireEvent.click(screen.getByRole('button', { name: WITHDRAW }))
    await screen.findByRole('link', { name: LOGIN })
    expect(screen.queryByText(WITHDRAW_NOTICE)).toBeNull()
    await settle()
    expect(deleteCalls()).toHaveLength(1)
  })

  it.each([
    { label: '403 ORIGIN_REJECTED', reply: originRejected as Outcome },
    { label: '500', reply: serverError },
    { label: '네트워크 끊김', reply: networkError },
  ])('탈퇴 요청이 $label 로 끝나면 확인 화면의 글자가 요청 전과 같다', async ({ reply }) => {
    api = mockApi(deleteReplies(reply))
    await openConfirm()
    const before = document.body.textContent
    expect(before).toContain(WITHDRAW_NOTICE)
    fireEvent.click(screen.getByRole('button', { name: WITHDRAW }))
    await settle()
    expect(deleteCalls()).toHaveLength(1)
    expect(document.body.textContent).toBe(before)
    screen.getByText(WITHDRAW_NOTICE)
    screen.getByRole('button', { name: WITHDRAW })
    screen.getByRole('button', { name: CANCEL })
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
    expect(window.location.pathname).toBe('/withdraw')
  })
})
