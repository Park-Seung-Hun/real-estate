package estate.household.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import estate.household.domain.HouseholdLocks;
import estate.household.domain.HouseholdMember;
import estate.household.domain.HouseholdMemberRepository;
import estate.household.domain.HouseholdRole;
import estate.household.domain.Invitation;
import estate.household.domain.InvitationRepository;
import estate.household.domain.InvitationStatus;

/** 초대 확인과 수락(research R8, R9). 두 요청이 같은 확인 함수를 쓴다. 토큰과 해시를 예외 메시지와 로그에 쓰지 않는다. */
@Service
public class InvitationAcceptance {

    private final InvitationRepository invitations;
    private final HouseholdMemberRepository members;
    private final HouseholdLocks locks;
    private final JdbcClient jdbc;
    private final Clock clock;

    InvitationAcceptance(InvitationRepository invitations, HouseholdMemberRepository members, HouseholdLocks locks,
            JdbcClient jdbc, Clock clock) {
        this.invitations = invitations;
        this.members = members;
        this.locks = locks;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 잠금 없이 확인만 하고, 통과하면 초대를 만든 owner의 사용자 번호를 반환한다. 표시 이름은 화면 API가 합친다. */
    @Transactional(readOnly = true)
    public long preview(String token, long userId) {
        Invitation invitation = invitations.findByTokenHash(hashOf(token)).orElseThrow(() -> new ProblemException(ProblemCode.NOT_FOUND));
        return check(invitation, userId);
    }

    /** 가구 행을 잠근 뒤 초대 행을 다시 읽어 확인하고, 초대를 수락으로 바꾼 뒤 멤버십을 넣는다(data-model의 순서). */
    @Transactional
    public void accept(String token, long userId) {
        byte[] hash = hashOf(token);
        // 잠금 전에 초대 엔티티를 영속성 컨텍스트에 올리면 잠금 뒤 다시 읽을 때 옛 상태가 나올 수 있어 JdbcClient로 가구 번호만 읽는다
        long householdId = jdbc.sql("select household_id from invitation where token_hash = :hash").param("hash", hash)
                .query(Long.class).optional().orElseThrow(() -> new ProblemException(ProblemCode.NOT_FOUND));
        if (!locks.lock(householdId)) {
            throw new ProblemException(ProblemCode.NOT_FOUND);
        }
        Invitation invitation = invitations.findByTokenHash(hash).orElseThrow(() -> new ProblemException(ProblemCode.NOT_FOUND));
        check(invitation, userId);
        invitation.accept();
        invitations.saveAndFlush(invitation);
        try {
            members.saveAndFlush(new HouseholdMember(householdId, userId, HouseholdRole.MEMBER, Instant.now(clock)));
        } catch (DataIntegrityViolationException e) {
            if (HouseholdService.isUserIdUnique(e)) {
                throw new ProblemException(ProblemCode.HAS_HOUSEHOLD);
            }
            throw e;
        }
    }

    private static byte[] hashOf(String token) {
        if (!InvitationToken.isValid(token)) {
            throw new ProblemException(ProblemCode.NOT_FOUND);
        }
        return InvitationToken.hash(token);
    }

    /** research R8 순서도에서 처음 걸린 사유로 던진다. 통과하면 created_by. */
    private long check(Invitation invitation, long userId) {
        if (invitation.getStatus() == InvitationStatus.REVOKED) {
            throw new ProblemException(ProblemCode.INVITATION_REVOKED);
        }
        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            throw new ProblemException(ProblemCode.INVITATION_USED);
        }
        if (Instant.now(clock).isAfter(invitation.getExpiresAt())) {
            throw new ProblemException(ProblemCode.INVITATION_EXPIRED);
        }
        if (members.countByHouseholdId(invitation.getHouseholdId()) >= 2) {
            throw new ProblemException(ProblemCode.HOUSEHOLD_FULL);
        }
        var mine = members.findByUserId(userId);
        if (mine.isPresent()) {
            throw new ProblemException(mine.get().getHouseholdId() == invitation.getHouseholdId()
                    ? ProblemCode.ALREADY_IN_THIS_HOUSEHOLD : ProblemCode.HAS_HOUSEHOLD);
        }
        return invitation.getCreatedBy();
    }
}
