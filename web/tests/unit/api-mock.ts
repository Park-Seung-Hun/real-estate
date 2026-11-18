// 화면 테스트 공용 준비: fetch 흉내와 문서 이동 감시. 실제 네트워크는 쓰지 않는다.
import { vi } from 'vitest'

export type ApiCall = {
  method: string
  path: string
  search: string
  credentials: RequestCredentials | undefined
  sameOrigin: boolean
}

export type Reply = { status: number; body?: unknown }

// 계약(specs/001-login/contracts/openapi.yaml)의 응답 예시
export const unauthenticated: Reply = {
  status: 401,
  body: {
    type: 'https://example.com/problems/unauthenticated',
    title: '로그인이 필요합니다',
    status: 401,
    detail: '다시 로그인해 주세요.',
    code: 'UNAUTHENTICATED',
  },
}

export const me: Reply = {
  status: 200,
  body: { displayName: '엄마', loginEmail: 'user1@example.com', emailVerified: true },
}

export const members: Reply = {
  status: 200,
  body: {
    members: [{ userId: 1, displayName: '엄마', role: 'owner', loginEmail: 'user1@example.com', emailVerified: true, me: true }],
  },
}

// 계약(specs/002-household-sharing/contracts/openapi.yaml)의 NoHousehold, HasHousehold 예시
export const noHousehold: Reply = {
  status: 403,
  body: {
    type: 'https://example.com/problems/no-household',
    title: '가구가 없습니다',
    status: 403,
    detail: '가구를 만들거나 초대 링크로 들어온 뒤 다시 시도해 주세요.',
    code: 'NO_HOUSEHOLD',
  },
}

export const hasHousehold: Reply = {
  status: 409,
  body: {
    type: 'https://example.com/problems/has-household',
    title: '이미 가구가 있습니다',
    status: 409,
    detail: '가구를 나가거나 삭제한 뒤 다시 시도해 주세요.',
    code: 'HAS_HOUSEHOLD',
  },
}

export const noContent: Reply = { status: 204 }

export const originRejected: Reply = {
  status: 403,
  body: {
    type: 'https://example.com/problems/origin-rejected',
    title: '허용하지 않은 요청입니다',
    status: 403,
    detail: '서비스 화면에서 다시 시도해 주세요.',
    code: 'ORIGIN_REJECTED',
  },
}

// 계약이 500 본문 형식을 정하지 않아 본문 없이 보낸다.
export const serverError: Reply = { status: 500 }

/** 네트워크 끊김. handler가 이것을 돌려주면 fetch가 TypeError로 거부된다. */
export const networkError = 'network-error' as const

/**
 * 전역 fetch를 흉내 낸다. handler가 networkError를 돌려주면 네트워크 오류로 끝난다.
 * undefined를 돌려주면(흉내 내지 않은 요청) 역시 네트워크 오류로 끝나고 unmatched에 남는다.
 */
export function mockApi(handler: (call: ApiCall) => Reply | typeof networkError | undefined) {
  const calls: ApiCall[] = []
  const unmatched: ApiCall[] = []
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : undefined
    const url = new URL(request ? request.url : String(input), window.location.href)
    const call: ApiCall = {
      method: (init?.method ?? request?.method ?? 'GET').toUpperCase(),
      path: url.pathname,
      search: url.search,
      credentials: init?.credentials ?? request?.credentials,
      sameOrigin: url.origin === window.location.origin,
    }
    calls.push(call)
    const reply = handler(call)
    if (reply === networkError) throw new TypeError('Failed to fetch')
    if (!reply) {
      unmatched.push(call)
      throw new TypeError(`흉내 내지 않은 요청: ${call.method} ${url.href}`)
    }
    const json = reply.body !== undefined
    return new Response(json ? JSON.stringify(reply.body) : null, {
      status: reply.status,
      headers: json ? { 'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json' } : {},
    })
  })
  vi.stubGlobal('fetch', fetch)
  return { calls, unmatched }
}

/** 남은 비동기 작업(응답 처리, effect, 다음 작업으로 미룬 이동)이 끝나도록 여러 작업 단계를 기다린다. */
export async function settle() {
  for (let i = 0; i < 10; i++) await new Promise((resolve) => setTimeout(resolve, 5))
}

type VirtualConsole = {
  on(event: 'jsdomError', listener: (error: Error) => void): void
  off(event: 'jsdomError', listener: (error: Error) => void): void
}

/**
 * jsdom이 구현하지 않은 동작의 호출을 모은다. 문서 이동(location.href 대입, location.assign/replace, 링크 클릭)은
 * "Not implemented: navigation to another Document", 폼 제출은 "HTMLFormElement's submit()/requestSubmit() method",
 * window.open은 "Window's open() method"로 virtualConsole에 jsdomError를 낸다. 셋을 모두 잡으려고 "Not implemented"
 * 전체를 센다(그래서 window.scrollTo 같은 다른 미구현 호출도 걸린다).
 * jsdom은 다른 문서로 이동하지 못해 location이 그대로 남으므로 href 비교로는 이 이동을 잡을 수 없다.
 * window.jsdom은 Vitest jsdom 환경이 노출하는 JSDOM 인스턴스다. 링크 클릭은 다음 작업에서 이동하므로 stop()이 settle()로 기다린다.
 */
export function watchNavigation() {
  const attempts: string[] = []
  const virtualConsole = (window as unknown as { jsdom: { virtualConsole: VirtualConsole } }).jsdom.virtualConsole
  const listener = (error: Error) => {
    if (/Not implemented/.test(error.message)) attempts.push(error.message)
  }
  virtualConsole.on('jsdomError', listener)
  return {
    attempts,
    async stop() {
      await settle()
      virtualConsole.off('jsdomError', listener)
    },
  }
}
