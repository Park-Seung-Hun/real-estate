import { Link } from 'react-router'

export function NoHouseholdPage({ onLogout }: { onLogout: () => void }) {
  return (
    <>
      <p>아직 가구가 없습니다.</p>
      <button className="button" type="button" onClick={onLogout}>
        로그아웃
      </button>
      <Link className="button" to="/withdraw">
        탈퇴
      </Link>
    </>
  )
}
