package estate.household.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import estate.common.reauth.ReauthJudge;
import estate.common.web.Problem;
import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import estate.household.domain.HouseholdLocks;
import estate.household.domain.HouseholdMemberRepository;
import estate.household.domain.Invitation;
import estate.household.domain.InvitationRepository;
import estate.household.domain.InvitationStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 초대 발급, 취소, 보낸 초대 목록(research R3, R9, R11, R12). */
@Service
public class InvitationService {

    private static final String EXPIRES_IN_DAYS = "expiresInDays";
    private static final long DAY_NANOS = Duration.ofHours(24).toNanos();

    public record Issued(long id, String token, Instant createdAt, Instant expiresAt) {
    }

    public record Listed(long id, Instant createdAt, Instant expiresAt, long remainingDays) {
    }

    private final InvitationRepository invitations;
    private final HouseholdMemberRepository members;
    private final HouseholdLocks locks;
    private final ReauthJudge reauthJudge;
    private final ObjectMapper json;
    private final Clock clock;

    InvitationService(InvitationRepository invitations, HouseholdMemberRepository members, HouseholdLocks locks,
            ReauthJudge reauthJudge, ObjectMapper json, Clock clock) {
        this.invitations = invitations;
        this.members = members;
        this.locks = locks;
        this.reauthJudge = reauthJudge;
        this.json = json;
        this.clock = clock;
    }

    /** 재인증, 본문 검증, 가구 행 잠금, 상태 충돌 순서다(research R3). 토큰 원문은 반환값에만 담는다. */
    @Transactional
    public Issued issue(long householdId, long userId, Instant lastGoogleAuthAt, String body) {
        reauthJudge.require(lastGoogleAuthAt);
        int days = parseExpiresInDays(body);
        lock(householdId);
        // 구성원 수를 먼저 확인한다: 둘 다 해당하면 HOUSEHOLD_FULL(spec FR-009)
        if (members.countByHouseholdId(householdId) >= 2) {
            throw new ProblemException(ProblemCode.HOUSEHOLD_FULL);
        }
        Instant now = Instant.now(clock);
        Instant createdAt = now.truncatedTo(ChronoUnit.MICROS);
        if (!pending(householdId, now).isEmpty()) {
            throw new ProblemException(ProblemCode.INVITATION_PENDING);
        }
        String token = InvitationToken.generate();
        Invitation invitation = invitations.save(new Invitation(householdId, userId, InvitationToken.hash(token),
                createdAt.plus(Duration.ofHours(24L * days)), createdAt));
        return new Issued(invitation.getId(), token, invitation.getCreatedAt(), invitation.getExpiresAt());
    }

    /** 다른 가구의 초대와 없는 초대는 같은 404다(FR-024). 행은 지우지 않고 상태만 바꾼다. */
    @Transactional
    public void revoke(long householdId, String invitationId) {
        // 경로 값을 예외 메시지에 넣지 않는다
        long id;
        try {
            id = invitationId.matches("[0-9]{1,19}") ? Long.parseLong(invitationId) : -1;
        } catch (NumberFormatException e) {
            id = -1;
        }
        if (id < 0) {
            throw new ProblemException(ProblemCode.NOT_FOUND);
        }
        lock(householdId);
        Invitation invitation = invitations.findByHouseholdIdAndId(householdId, id)
                .orElseThrow(() -> new ProblemException(ProblemCode.NOT_FOUND));
        if (invitation.getStatus() != InvitationStatus.PENDING || Instant.now(clock).isAfter(invitation.getExpiresAt())) {
            throw new ProblemException(ProblemCode.INVITATION_NOT_PENDING);
        }
        invitation.revoke();
    }

    @Transactional(readOnly = true)
    public List<Listed> list(long householdId) {
        Instant now = Instant.now(clock);
        return pending(householdId, now).stream()
                .map(i -> new Listed(i.getId(), i.getCreatedAt(), i.getExpiresAt(),
                        // 남은 시간을 24시간 단위로 올림하고 최소 1(research R11)
                        Math.max(1, Math.ceilDiv(Duration.between(now, i.getExpiresAt()).toNanos(), DAY_NANOS))))
                .toList();
    }

    // 드라이버가 나노초를 마이크로초로 반올림하므로 올림한 값을 넘긴다. expires_at이 마이크로초 단위라 now.isAfter(expiresAt)와 정확히 반대가 된다
    private List<Invitation> pending(long householdId, Instant now) {
        Instant floor = now.truncatedTo(ChronoUnit.MICROS);
        Instant ceil = floor.equals(now) ? now : floor.plus(1, ChronoUnit.MICROS);
        return invitations.findByHouseholdIdAndStatusAndExpiresAtGreaterThanEqual(householdId, InvitationStatus.PENDING, ceil);
    }

    // 잠금 전에 가구가 삭제되었으면 빈 결과라 403 NO_HOUSEHOLD(research R3)
    private void lock(long householdId) {
        if (!locks.lock(householdId)) {
            throw new ProblemException(ProblemCode.NO_HOUSEHOLD);
        }
    }

    /** 결과가 객체이고 키가 expiresInDays 하나이며 값이 정수 리터럴 1~7일 때만 받는다(research R12). 본문 값은 응답과 로그에 담지 않는다. */
    private int parseExpiresInDays(String body) {
        JsonNode node = null;
        if (body != null && !body.isBlank()) {
            try {
                node = json.readTree(body);
            } catch (JacksonException e) {
                node = null;
            }
        }
        // detail은 ProblemAdvice(확인, 수락, 표시 이름 수정)와 같은 규칙: 다른 키면 알 수 없는 필드, 그 밖의 읽기 실패는 읽을 수 없음
        if (node == null || !node.isObject()) {
            throw invalid("#", "요청 본문을 읽을 수 없습니다.");
        }
        if (node.propertyNames().stream().anyMatch(name -> !EXPIRES_IN_DAYS.equals(name))) {
            throw invalid("#", "알 수 없는 필드가 있습니다.");
        }
        JsonNode value = node.get(EXPIRES_IN_DAYS);
        if (value == null || !value.isInt() || value.intValue() < 1 || value.intValue() > 7) {
            throw invalid("#/expiresInDays", "만료 기간은 1일부터 7일까지 고를 수 있습니다.");
        }
        return value.intValue();
    }

    private static ProblemException invalid(String pointer, String detail) {
        return new ProblemException(ProblemCode.VALIDATION_FAILED, List.of(new Problem.FieldError(pointer, detail)));
    }
}
