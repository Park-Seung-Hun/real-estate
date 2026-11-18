export type Me = { displayName: string | null; loginEmail: string; emailVerified: boolean }
export type Member = {
  userId: number
  displayName: string | null
  role: 'owner' | 'member'
  loginEmail: string
  emailVerified: boolean
  me: boolean
}
export type Members = { members: Member[] }

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code?: string,
  ) {
    super(`API ${status} ${code ?? ''}`)
  }
}

// 같은 출처 상대 주소로만 요청한다(credentials 기본값 same-origin). 오류 본문은 Problem Details의 code만 읽는다.
async function request(method: string, path: string, body?: unknown): Promise<Response> {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? { Accept: 'application/json' } : { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
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

export const withdraw = async (): Promise<void> => {
  await request('DELETE', '/api/v1/me')
}

export const isReauthRequired = (error: unknown) => error instanceof ApiError && error.code === 'REAUTH_REQUIRED'

export const isNoHousehold = (error: unknown) => error instanceof ApiError && error.code === 'NO_HOUSEHOLD'

export const isHasHousehold = (error: unknown) => error instanceof ApiError && error.code === 'HAS_HOUSEHOLD'

export const getHousehold = async (): Promise<Members> => (await request('GET', '/api/v1/household')).json()

export const createHousehold = async (): Promise<void> => {
  await request('POST', '/api/v1/household')
}

// 로그인을 거치는 동안 초대 토큰을 두는 탭 저장소 키(research R7)
export const INVITE_TOKEN_KEY = 'inviteToken'

export type Invitation = { id: number; createdAt: string; expiresAt: string; remainingDays: number }
export type CreatedInvitation = { id: number; token: string; createdAt: string; expiresAt: string }

export const errorStatus = (error: unknown) => (error instanceof ApiError ? error.status : undefined)

export const errorCode = (error: unknown) => (error instanceof ApiError ? error.code : undefined)

export const previewInvitation = async (token: string): Promise<{ inviterDisplayName: string | null }> =>
  (await request('POST', '/api/v1/invitations/preview', { token })).json()

export const acceptInvitation = async (token: string): Promise<void> => {
  await request('POST', '/api/v1/invitations/accept', { token })
}

export const listInvitations = async (): Promise<{ invitations: Invitation[] }> =>
  (await request('GET', '/api/v1/household/invitations')).json()

export const createInvitation = async (expiresInDays: number): Promise<CreatedInvitation> =>
  (await request('POST', '/api/v1/household/invitations', { expiresInDays })).json()

export const cancelInvitation = async (id: number): Promise<void> => {
  await request('DELETE', `/api/v1/household/invitations/${id}`)
}
