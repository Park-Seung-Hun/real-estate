package estate.household.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, HouseholdMember.Key> {

    /** user_id가 유니크라 0행이나 1행. */
    Optional<HouseholdMember> findByUserId(long userId);

    List<HouseholdMember> findByHouseholdId(long householdId);

    long countByHouseholdId(long householdId);
}
