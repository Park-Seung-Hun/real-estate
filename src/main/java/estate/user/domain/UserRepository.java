package estate.user.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    // 콜백과 탈퇴가 같은 사용자 행에서 한 줄로 서게 잠근다(research R5)
    @Query(value = "select * from \"user\" where provider = ?1 and provider_subject = ?2 for update", nativeQuery = true)
    Optional<User> findForUpdate(String provider, String providerSubject);
}
