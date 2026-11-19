/**
 * T025 가구 없음 화면과 임시 첫 화면 테스트(spec US1-1, US1-2, US1-5, FR-002, FR-003, SC-001, research R14).
 * 공개 표면은 login.test.tsx 맨 위 주석과 같다.
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
  watchNavigation,
} from './api-mock'

const NO_HOUSEHOLD = '아직 가구가 없습니다.'
const CREATE_GUIDE = '가족과 함께 볼 기록을 모으려면 가구를 만드세요.'
const INVITE_LINK_GUIDE = '가족에게 받은 초대 링크가 있으면 그 링크를 여세요.'
const CREATE = '가구 만들기'
const LOGOUT = '로그아웃'
const WITHDRAW = '탈퇴'
const TITLE = '함께 보는 사람'
const SELF = '(나)'
const UNVERIFIED = '검증되지 않음'
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

const hasControl = (name: string) =>
  screen.queryByRole('button', { name }) !== null || screen.queryByRole('link', { name }) !== null

const postCalls = () => api.calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/household')
const listCalls = () => api.calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/household')

/** 목록은 가구를 만든 뒤에만 members, 만들기 응답은 create. */
function server(create: Outcome, afterCreate: Outcome = members) {
  let created = false
  return (call: ApiCall): Outcome | undefined => {
    if (call.method === 'GET' && call.path === '/api/v1/me') return me
    if (call.method === 'GET' && call.path === '/api/v1/household') return created ? afterCreate : noHousehold
    // owner 임시 첫 화면이 읽는 보낸 초대 목록(US2)
    if (call.method === 'GET' && call.path === '/api/v1/household/invitations') return { status: 200, body: { invitations: [] } }
    if (call.method === 'POST' && call.path === '/api/v1/household') {
      created = true
      return create
    }
    return undefined
  }
}

describe('가구 없음 화면', () => {
  it('US1-1, FR-002: 안내 셋과 가구 만들기, 로그아웃, 탈퇴 버튼이 보인다', async () => {
    api = mockApi(server({ status: 201 }))
    openAt('/')
    await screen.findByText(NO_HOUSEHOLD)
    screen.getByText(CREATE_GUIDE)
    screen.getByText(INVITE_LINK_GUIDE)
    screen.getByRole('button', { name: CREATE })
    screen.getByRole('button', { name: LOGOUT })
    expect(hasControl(WITHDRAW)).toBe(true)
    expect(screen.queryByText(TITLE)).toBeNull()
    await settle()
    expect(postCalls()).toEqual([])
  })
})

describe('가구 만들기', () => {
  it('US1-2, SC-001: 누르면 POST 한 번, 목록을 다시 읽어 본인 한 명이 (나)와 owner로 보이는 임시 첫 화면이다', async () => {
    api = mockApi(server({ status: 201 }))
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: CREATE }))
    await screen.findByText(TITLE)
    await settle()
    expect(postCalls()).toHaveLength(1)
    expect(listCalls()).toHaveLength(2)
    expect(api.calls.filter((c) => c.path !== '/api/v1/household/invitations').map((c) => `${c.method} ${c.path}`).slice(-2)).toEqual(['POST /api/v1/household', 'GET /api/v1/household'])
    const body = document.body.textContent ?? ''
    expect(body).toContain('엄마')
    expect(body).toContain(SELF)
    expect(body).toContain('owner')
    expect(body).toContain('user1@example.com')
    expect(body).not.toContain(UNVERIFIED)
    expect(body).not.toContain('member')
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
  })

  it('가구 만들기 409 HAS_HOUSEHOLD도 목록을 다시 읽어 임시 첫 화면이다', async () => {
    api = mockApi(server(hasHousehold))
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: CREATE }))
    await screen.findByText(TITLE)
    await settle()
    expect(postCalls()).toHaveLength(1)
    expect(listCalls()).toHaveLength(2)
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
  })

  it.each([
    { label: '500', reply: serverError as Outcome },
    { label: '네트워크 끊김', reply: networkError as Outcome },
  ])('가구 만들기가 $label 로 끝나면 다시 시도 화면이다', async ({ reply }) => {
    api = mockApi(server(reply))
    openAt('/')
    fireEvent.click(await screen.findByRole('button', { name: CREATE }))
    await screen.findByText(RETRY_GUIDE)
    screen.getByRole('button', { name: RETRY })
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(postCalls()).toHaveLength(1)
  })
})

describe('로그인 뒤 첫 화면', () => {
  it('US1-5, FR-003: 목록이 200이면 임시 첫 화면이고 로그아웃만 있고 탈퇴가 없다 (가구 없음 화면에는 탈퇴가 있다)', async () => {
    // 이미 가구가 있는 사용자
    api = mockApi((call) => (call.method === 'GET' && call.path === '/api/v1/household' ? members : server({ status: 201 })(call)))
    openAt('/')
    await screen.findByText(TITLE)
    screen.getByRole('button', { name: LOGOUT })
    expect(hasControl(WITHDRAW)).toBe(false)
    expect(screen.queryByText(/탈퇴/)).toBeNull()
    expect(screen.queryByText(NO_HOUSEHOLD)).toBeNull()
    expect(postCalls()).toEqual([])
  })

  it('US1-5, FR-003: 목록이 403 NO_HOUSEHOLD면 가구 없음 화면이다', async () => {
    api = mockApi(server({ status: 201 }))
    openAt('/')
    await screen.findByText(NO_HOUSEHOLD)
    expect(screen.queryByText(TITLE)).toBeNull()
    expect(hasControl(WITHDRAW)).toBe(true)
  })

  it('이메일이 검증되지 않았으면 "검증되지 않음"이 보이고, 표시 이름이 없으면 로그인 이메일만 보인다', async () => {
    const other: Reply = {
      status: 200,
      body: {
        members: [
          { userId: 1, displayName: null, role: 'owner', loginEmail: 'user1@example.com', emailVerified: false, me: true },
          { userId: 2, displayName: '아빠', role: 'member', loginEmail: 'user2@example.com', emailVerified: true, me: false },
        ],
      },
    }
    api = mockApi((call) => (call.method === 'GET' && call.path === '/api/v1/household' ? other : server({ status: 201 })(call)))
    openAt('/')
    await screen.findByText(TITLE)
    const body = document.body.textContent ?? ''
    expect(body).toContain('user1@example.com')
    expect(body).toContain('user2@example.com')
    expect(body).toContain('아빠')
    expect(body).toContain('member')
    expect(body.split(UNVERIFIED)).toHaveLength(2)
    expect(body.split(SELF)).toHaveLength(2)
    expect(body).not.toContain('null')
  })
})
