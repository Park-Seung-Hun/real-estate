package estate.household.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import estate.common.web.Problem;
import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import estate.household.application.InvitationAcceptance;
import estate.household.application.InvitationService;
import estate.household.authz.Access;
import estate.household.authz.HouseholdMembership;
import estate.household.authz.MembershipRule;
import estate.household.authz.Permission;
import estate.user.application.UserProfiles;
import estate.user.security.SessionLoginInfo;
import tools.jackson.databind.JsonNode;

/** 초대 발급, 목록, 취소, 확인, 수락(contracts, research R3, R4). 초대한 사람의 표시 이름은 화면 API인 여기서 합친다. */
@RestController
class InvitationController {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    record Invitation(long id, OffsetDateTime createdAt, OffsetDateTime expiresAt, long remainingDays) {
    }

    record Invitations(List<Invitation> invitations) {
    }

    record CreatedInvitation(long id, String token, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
    }

    // 계약이 string이라 Jackson이 숫자와 참거짓을 문자열로 바꿔 받지 않게 노드로 받는다
    record TokenBody(JsonNode token) {
    }

    record Preview(String inviterDisplayName) {
    }

    private final InvitationService service;
    private final InvitationAcceptance acceptance;
    private final UserProfiles profiles;

    InvitationController(InvitationService service, InvitationAcceptance acceptance, UserProfiles profiles) {
        this.service = service;
        this.acceptance = acceptance;
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/household/invitations")
    @Access(Permission.INVITATION_READ)
    Invitations list(HouseholdMembership membership) {
        return new Invitations(service.list(membership.householdId()).stream()
                .map(i -> new Invitation(i.id(), seoul(i.createdAt()), seoul(i.expiresAt()), i.remainingDays())).toList());
    }

    // 본문은 재인증 판정 뒤 서비스가 읽는다(research R3)
    @PostMapping("/api/v1/household/invitations")
    @Access(Permission.INVITATION_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    CreatedInvitation create(HouseholdMembership membership, SessionLoginInfo login, @RequestBody(required = false) String body) {
        var issued = service.issue(membership.householdId(), membership.userId(), login.lastGoogleAuthAt(), body);
        return new CreatedInvitation(issued.id(), issued.token(), seoul(issued.createdAt()), seoul(issued.expiresAt()));
    }

    // 경로 값은 문자열로 받아 숫자가 아니면 서비스가 404로 응답한다(research R3)
    @DeleteMapping("/api/v1/household/invitations/{invitationId}")
    @Access(Permission.INVITATION_REVOKE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(HouseholdMembership membership, @PathVariable String invitationId) {
        service.revoke(membership.householdId(), invitationId);
    }

    @PostMapping("/api/v1/invitations/preview")
    @Access(value = Permission.NONE, membership = MembershipRule.OPTIONAL)
    Preview preview(SessionLoginInfo login, @RequestBody TokenBody request) {
        long inviterId = acceptance.preview(token(request), login.userId());
        UserProfiles.Profile inviter = profiles.find(List.of(inviterId)).get(inviterId);
        return new Preview(inviter == null ? null : inviter.displayName());
    }

    @PostMapping("/api/v1/invitations/accept")
    @Access(value = Permission.NONE, membership = MembershipRule.OPTIONAL)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void accept(SessionLoginInfo login, @RequestBody TokenBody request) {
        acceptance.accept(token(request), login.userId());
    }

    // 키 없음은 null, JSON null은 NullNode로 온다. 둘 다 문자열 노드가 아니다
    private static String token(TokenBody request) {
        JsonNode node = request.token();
        if (node == null || !node.isString()) {
            throw new ProblemException(ProblemCode.VALIDATION_FAILED,
                    List.of(new Problem.FieldError("#/token", "초대 링크를 확인해 주세요.")));
        }
        return node.stringValue();
    }

    private static OffsetDateTime seoul(Instant instant) {
        return OffsetDateTime.ofInstant(instant, SEOUL);
    }
}
