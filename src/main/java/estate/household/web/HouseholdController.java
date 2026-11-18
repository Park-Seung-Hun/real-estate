package estate.household.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import estate.household.application.HouseholdService;
import estate.household.authz.Access;
import estate.household.authz.HouseholdMembership;
import estate.household.authz.MembershipRule;
import estate.household.authz.Permission;
import estate.user.application.UserProfiles;
import estate.user.security.SessionLoginInfo;

/** 가구 만들기와 함께 보는 사람 목록(contracts `POST`, `GET /api/v1/household`). 가구 구성원과 사용자 표시 자료를 화면 API가 합친다. */
@RestController
class HouseholdController {

    record Member(long userId, String displayName, String role, String loginEmail, boolean emailVerified, boolean me) {
    }

    record Members(List<Member> members) {
    }

    private final HouseholdService service;
    private final UserProfiles profiles;

    HouseholdController(HouseholdService service, UserProfiles profiles) {
        this.service = service;
        this.profiles = profiles;
    }

    @PostMapping("/api/v1/household")
    @Access(value = Permission.NONE, membership = MembershipRule.ABSENT)
    @ResponseStatus(HttpStatus.CREATED)
    void create(SessionLoginInfo login) {
        service.create(login.userId());
    }

    @GetMapping("/api/v1/household")
    @Access(Permission.HOUSEHOLD_READ)
    Members list(HouseholdMembership membership) {
        var found = service.members(membership.householdId());
        Map<Long, UserProfiles.Profile> byUser = profiles.find(found.stream().map(m -> m.getUserId()).toList());
        return new Members(found.stream().map(m -> {
            UserProfiles.Profile p = byUser.get(m.getUserId());
            return new Member(m.getUserId(), p.displayName(), m.getRole().dbValue(), p.loginEmail(), p.emailVerified(),
                    m.getUserId() == membership.userId());
        }).toList());
    }
}
