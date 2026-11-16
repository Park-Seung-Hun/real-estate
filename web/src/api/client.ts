export type Me = { displayName: string; loginEmail: string; emailVerified: boolean }

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code?: string,
  ) {
    super(`API ${status} ${code ?? ''}`)
  }
}

// 같은 출처 상대 주소로만 요청한다(credentials 기본값 same-origin). 오류 본문은 Problem Details의 code만 읽는다.
async function request(method: string, path: string): Promise<Response> {
  const response = await fetch(path, { method, headers: { Accept: 'application/json' } })
  if (!response.ok) {
    const problem = await response.json().catch(() => undefined)
    throw new ApiError(response.status, problem?.code)
  }
  return response
}

export const isUnauthenticated = (error: unknown) => error instanceof ApiError && error.code === 'UNAUTHENTICATED'

export const getMe = async (): Promise<Me> => (await request('GET', '/api/v1/me')).json()

export const logout = async (): Promise<void> => {
  await request('POST', '/api/v1/logout')
}
