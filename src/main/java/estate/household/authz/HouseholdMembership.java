package estate.household.authz;

import estate.household.domain.HouseholdRole;

/** 요청한 사람의 멤버십. 컨트롤러 인자로 받는다. */
public record HouseholdMembership(long householdId, long userId, HouseholdRole role) {
}
