package estate.household.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import estate.household.application.HouseholdService;
import estate.household.authz.HouseholdMembership;
import estate.household.domain.HouseholdMember;
import estate.household.domain.HouseholdRole;
import estate.user.application.UserProfiles;

/** 함께 보는 사람 목록의 두 조회(멤버십, 프로필) 사이를 HTTP로 끼워 넣기 어려워 컨트롤러를 직접 부른다. */
class HouseholdControllerTest {

    @Test
    @DisplayName("FR-016: 멤버십을 읽은 뒤 프로필을 읽기 전에 나가고 탈퇴한 사람은 함께 보는 사람 목록에서 빠지고 예외가 없다")
    void listSkipsMembershipWithoutProfile() {
        HouseholdService service = mock(HouseholdService.class);
        UserProfiles profiles = mock(UserProfiles.class);
        Instant joined = Instant.parse("2026-01-01T00:00:00Z");
        when(service.members(10L)).thenReturn(List.of(new HouseholdMember(10L, 1L, HouseholdRole.OWNER, joined),
                new HouseholdMember(10L, 2L, HouseholdRole.MEMBER, joined)));
        when(profiles.find(any())).thenReturn(Map.of(1L, new UserProfiles.Profile(1L, "가구장", "owner@example.com", true)));

        HouseholdController.Members result = new HouseholdController(service, profiles)
                .list(new HouseholdMembership(10L, 1L, HouseholdRole.OWNER));

        assertThat(result.members()).containsExactly(
                new HouseholdController.Member(1L, "가구장", "owner", "owner@example.com", true, true));
    }
}
