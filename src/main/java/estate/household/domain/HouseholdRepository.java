package estate.household.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseholdRepository extends JpaRepository<Household, Long> {

    /** 엔티티를 읽지 않고 가구 행 하나를 지운다. */
    @Modifying
    @Query("delete from Household h where h.id = :householdId")
    int deleteHousehold(@Param("householdId") long householdId);
}
