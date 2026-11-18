import type { Member } from '../api/client'

export function HomePage({ members, onLogout }: { members: Member[]; onLogout: () => void }) {
  return (
    <>
      <h2>함께 보는 사람</h2>
      <ul>
        {members.map((m) => (
          <li key={m.userId}>
            {m.displayName && (
              <>
                {m.displayName}
                {m.me && ' (나)'} {m.role}{' '}
              </>
            )}
            {!m.displayName && (
              <>
                {m.me && '(나) '}
                {m.role}{' '}
              </>
            )}
            {m.loginEmail}
            {!m.emailVerified && ' 검증되지 않음'}
          </li>
        ))}
      </ul>
      <button className="button" type="button" onClick={onLogout}>
        로그아웃
      </button>
    </>
  )
}
