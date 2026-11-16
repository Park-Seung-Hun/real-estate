/**
 * T056 재인증 화면 테스트(spec US2-2, US2-4, FR-011, FR-013, research R7, R13, tasks T055).
 *
 * 공개 표면은 login.test.tsx 맨 위 주석과 같다(`App`을 경로를 정해 그림, 자료는 전역 fetch).
 * 이 파일이 더 기대하는 것:
 * - 탈퇴 확인 화면의 "탈퇴"가 401 `REAUTH_REQUIRED`를 받으면 안내와 "구글 계정 확인"을 보인다.
 *   "구글 계정 확인"은 로그인 버튼처럼 `<a href="/oauth2/authorization/google?purpose=reauth&returnTo=/withdraw">` 링크다.
 *   안내를 보이는 것만으로 재인증을 시작하지 않는다(결정 기록 0013, afterEach의 문서 이동 검사).
 * - `/reauth-failed`는 내 정보 조회 결과와 관계없이 재인증 실패 문구를 보인다.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../src/App'
import { type ApiCall, me, mockApi, type Reply, settle, unauthenticated, watchNavigation } from './api-mock'

const WITHDRAW = '탈퇴'
const WITHDRAW_NOTICE = '탈퇴하면 계정, 로그인 이메일, 표시 이름과 모든 기기의 로그인 상태가 삭제되며 되돌릴 수 없습니다.'
const REAUTH_NOTICE = '탈퇴하려면 구글 계정을 한 번 더 확인해야 합니다.'
const REAUTH = '구글 계정 확인'
const REAUTH_FAILED = '구글 계정 확인에 실패했습니다. 다시 시도해 주세요.'
const ACCOUNT_MISMATCH = '다른 구글 계정을 골라 로그인이 해제되었습니다. 다시 로그인해 주세요.'
const LOGIN = 'Google 계정으로 로그인'
const LOGIN_START = '/oauth2/authorization/google'

/** contracts/openapi.yaml `DELETE /api/v1/me` 401 예시 reauthRequired. */
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

const needsReauth = (call: ApiCall) => {
  if (call.method === 'GET' && call.path === '/api/v1/me') return me
  if (call.method === 'DELETE' && call.path === '/api/v1/me') return reauthRequired
  return undefined
}

function deleteCalls() {
  return api.calls.filter((call) => call.method === 'DELETE' && call.path === '/api/v1/me')
}

describe('탈퇴가 REAUTH_REQUIRED 를 받을 때', () => {
  it('US2-2: 재인증 안내와 purpose=reauth&returnTo=/withdraw 로 가는 구글 계정 확인 링크를 보인다', async () => {
    api = mockApi(needsReauth)
    openAt('/withdraw')
    await screen.findByText(WITHDRAW_NOTICE)
    expect(screen.queryByText(REAUTH_NOTICE)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: WITHDRAW }))
    await screen.findByText(REAUTH_NOTICE)
    const link = screen.getByRole('link', { name: REAUTH })
    const url = new URL(link.getAttribute('href') ?? '', window.location.origin)
    expect(url.origin).toBe(window.location.origin)
    expect(url.pathname).toBe(LOGIN_START)
    expect([...url.searchParams].sort()).toEqual([
      ['purpose', 'reauth'],
      ['returnTo', '/withdraw'],
    ])
    // 로그인 상태는 그대로이므로 로그인 화면으로 바뀌지 않고, 탈퇴를 스스로 다시 보내지 않는다
    expect(screen.queryByRole('link', { name: LOGIN })).toBeNull()
    await settle()
    expect(deleteCalls()).toHaveLength(1)
  })
})

describe('재인증 실패 화면', () => {
  it.each([
    { label: '401', reply: unauthenticated },
    { label: '200', reply: me },
  ])('US2-4: /me 가 $label 이어도 /reauth-failed 는 기본 실패 문구를 보인다', async ({ reply }) => {
    api = mockApi((call) => (call.method === 'GET' && call.path === '/api/v1/me' ? reply : undefined))
    openAt('/reauth-failed')
    await screen.findByText(REAUTH_FAILED)
    expect(screen.queryByText(ACCOUNT_MISMATCH)).toBeNull()
  })

  it('US2-4: /me 가 401 일 때 /reauth-failed?reason=account-mismatch 는 다른 계정 문구를 보인다', async () => {
    api = mockApi((call) => (call.method === 'GET' && call.path === '/api/v1/me' ? unauthenticated : undefined))
    openAt('/reauth-failed?reason=account-mismatch')
    await screen.findByText(ACCOUNT_MISMATCH)
    expect(screen.queryByText(REAUTH_FAILED)).toBeNull()
  })

  it('US2-4: 알 수 없는 reason 이면 기본 실패 문구를 보인다', async () => {
    api = mockApi((call) => (call.method === 'GET' && call.path === '/api/v1/me' ? unauthenticated : undefined))
    openAt('/reauth-failed?reason=something-else')
    await screen.findByText(REAUTH_FAILED)
    expect(screen.queryByText(ACCOUNT_MISMATCH)).toBeNull()
  })
})
