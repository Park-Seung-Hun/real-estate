package estate.household.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, HouseholdMember.Key> {

    /** user_id가 유니크라 0행이나 1행. */
    Optional<HouseholdMember> findByUserId(long userId);

    List<HouseholdMember> findByHouseholdId(long householdId);

    long countByHouseholdId(long householdId);

    /** 엔티티를 읽지 않는 존재 확인: 잠금 전에 읽으면 1차 캐시에 남아 잠금 뒤 읽기가 옛 상태를 돌려준다. */
    boolean existsByHouseholdIdAndUserId(long householdId, long userId);

    /** 엔티티를 읽지 않는 존재 확인(탈퇴의 잠금 뒤 재확인). */
    boolean existsByUserId(long userId);

    /** 가구의 멤버십 행을 모두 지운 건수. */
    @Modifying
    @Query("delete from HouseholdMember m where m.householdId = :householdId")
    int deleteAllByHousehold(@Param("householdId") long householdId);

    /** 멤버십 행 하나만 지운 건수. */
    @Modifying
    @Query("delete from HouseholdMember m where m.householdId = :householdId and m.userId = :userId")
    int deleteMembership(@Param("householdId") long householdId, @Param("userId") long userId);
}
