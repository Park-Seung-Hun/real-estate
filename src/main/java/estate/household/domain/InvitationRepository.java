package estate.household.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    /** 상태와 만료 시각 조건에 맞는 초대. 수락 대기 중이면서 만료되지 않은 초대를 찾을 때 쓴다. */
    List<Invitation> findByHouseholdIdAndStatusAndExpiresAtGreaterThanEqual(long householdId, InvitationStatus status, Instant now);

    Optional<Invitation> findByHouseholdIdAndId(long householdId, long id);

    Optional<Invitation> findByTokenHash(byte[] tokenHash);

    /** 가구의 초대 행을 엔티티를 읽지 않고 모두 지운다. */
    @Modifying
    @Query("delete from Invitation i where i.householdId = :householdId")
    int deleteAllByHousehold(@Param("householdId") long householdId);
}
