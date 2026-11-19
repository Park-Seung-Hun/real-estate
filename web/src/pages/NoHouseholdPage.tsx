import { Link } from 'react-router'

export function NoHouseholdPage({ onCreate, onLogout }: { onCreate: () => void; onLogout: () => void }) {
  return (
    <>
      <p>아직 가구가 없습니다.</p>
      <p>가족과 함께 볼 기록을 모으려면 가구를 만드세요.</p>
      <p>가족에게 받은 초대 링크가 있으면 그 링크를 여세요.</p>
      <button className="button" type="button" onClick={onCreate}>
        가구 만들기
      </button>
      <button className="button" type="button" onClick={onLogout}>
        로그아웃
      </button>
      <Link className="button" to="/withdraw">
        탈퇴
      </Link>
    </>
  )
}
