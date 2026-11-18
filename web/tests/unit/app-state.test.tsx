/**
 * T016 웹 앱 상태 화면 테스트(research R14, FR-003, FR-019). 공개 표면은 login.test.tsx 맨 위 주석과 같다.
 * 내 정보 조회가 성공하면 함께 보는 사람 목록을 불러 200이면 가구 있음, 403 NO_HOUSEHOLD면 가구 없음,
 * 그 밖의 오류면 다시 시도 화면이다. 내 정보가 401이면 로그인 화면이다.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../src/App'
import {
  type ApiCall,
  hasHousehold,
  me,
  members,
  mockApi,
  networkError,
  noHousehold,
  type Reply,
  serverError,
  settle,
  unauthenticated,
  watchNavigation,
} from './api-mock'

const TITLE = '함께 보는 사람'
const NO_HOUSEHOLD = '아직 가구가 없습니다.'
const CREATE = '가구 만들기'
const LOGIN = 'Google 계정으로 로그인'
const RETRY_GUIDE = '잠시 후 다시 시도해 주세요.'
const RETRY = '다시 시도'

type Outcome = Reply | typeof networkError

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
})

function openAt(path: string) {
  window.history.replaceState(null, '', path)
  render(<App />)
}

const listCalls = () => api.calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/household')

/** 내 정보는 200, 목록 응답은 차례로 정한다. 비면 마지막 값을 되풀이한다. */
function withList(...replies: Outcome[]) {
  return (call: ApiCall): Outcome | undefined => {
    if (call.method === 'GET' && call.path === '/api/v1/me') return me
    if (call.method === 'GET' && call.path === '/api/v1/household') return replies.length > 1 ? replies.shift() : replies[0]
    return undefined
  }
}

describe('앱 상태 갈래 (R14)', () => {
  it('내 정보 200 뒤 목록 200이면 가구 있음, 임시 첫 화면이다', async () => {
    api = mockApi(withList(members))
    openAt('/')
    await screen.findByText(TITLE)
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(listCalls()).toHaveLength(1)
  })

  it('내 정보 200 뒤 목록 403 NO_HOUSEHOLD면 가구 없음 화면이다', async () => {
    api = mockApi(withList(noHousehold))
    openAt('/')
    await screen.findByText(NO_HOUSEHOLD)
    screen.getByRole('button', { name: CREATE })
    expect(screen.queryByText(TITLE)).toBeNull()
  })

  it.each([
    { label: '500', reply: serverError as Outcome },
    { label: '네트워크 끊김', reply: networkError as Outcome },
  ])('목록이 $label 이면 다시 시도 화면이고, 다시 시도로 목록 200을 받으면 임시 첫 화면이다', async ({ reply }) => {
    api = mockApi(withList(reply, members))
    openAt('/')
    await screen.findByText(RETRY_GUIDE)
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByText(TITLE)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: RETRY }))
    await screen.findByText(TITLE)
    expect(screen.queryByText(RETRY_GUIDE)).toBeNull()
  })

  it('내 정보가 401이면 로그인 화면이고 목록을 부르지 않는다', async () => {
    api = mockApi((call) => (call.method === 'GET' && call.path === '/api/v1/me' ? unauthenticated : undefined))
    openAt('/')
    await screen.findByRole('link', { name: LOGIN })
    await settle()
    expect(listCalls()).toEqual([])
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(screen.queryByText(TITLE)).toBeNull()
  })
})

describe('가구 요청의 403 NO_HOUSEHOLD (FR-019)', () => {
  // 이번 단계에서 가구 요청이 일어나는 화면은 가구 만들기와 임시 첫 화면뿐이라, 만들기 뒤 목록 재조회가 403인 경우로 본다
  it.each([
    { label: '201', reply: { status: 201 } as Reply },
    { label: '409 HAS_HOUSEHOLD', reply: hasHousehold },
  ])('가구 만들기 $label 뒤 목록 재조회가 403이면 가구 없음 화면이고 경로는 /다', async ({ reply }) => {
    api = mockApi((call) => {
      if (call.method === 'POST' && call.path === '/api/v1/household') return reply
      return withList(noHousehold)(call)
    })
    // /가 아닌 경로에서 시작해야 /로의 이동이 드러난다
    openAt('/no-household')
    fireEvent.click(await screen.findByRole('button', { name: CREATE }))
    await settle()
    expect(api.calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/household')).toHaveLength(1)
    expect(listCalls()).toHaveLength(2)
    screen.getByText(NO_HOUSEHOLD)
    screen.getByRole('button', { name: CREATE })
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(window.location.pathname).toBe('/')
  })
})
