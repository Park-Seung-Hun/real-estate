package estate.household.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import estate.common.reauth.ReauthJudge;
import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import estate.household.domain.Household;
import estate.household.domain.HouseholdLocks;
import estate.household.domain.HouseholdMember;
import estate.household.domain.HouseholdMemberRepository;
import estate.household.domain.HouseholdRepository;
import estate.household.domain.HouseholdRole;
import estate.household.domain.InvitationRepository;

/** 가구 만들기, 구성원 조회, 구성원 제거, 가구 나가기, 가구 삭제(research R3, R9, R10). */
@Service
public class HouseholdService {

    private static final String USER_ID_UNIQUE = "household_member_user_id_key";

    private final HouseholdRepository households;
    private final HouseholdMemberRepository members;
    private final InvitationRepository invitations;
    private final HouseholdLocks locks;
    private final ReauthJudge reauthJudge;
    private final Clock clock;

    HouseholdService(HouseholdRepository households, HouseholdMemberRepository members,
            InvitationRepository invitations, HouseholdLocks locks, ReauthJudge reauthJudge, Clock clock) {
        this.households = households;
        this.members = members;
        this.invitations = invitations;
        this.locks = locks;
        this.reauthJudge = reauthJudge;
        this.clock = clock;
    }

    /**
     * 가구 행과 owner 멤버십 행을 한 트랜잭션에서 넣는다. 같은 사람의 동시 요청이 나서 user_id 유니크 위반이 나면 409 HAS_HOUSEHOLD다.
     * 위반이 커밋 때가 아니라 여기서 나게 바로 flush하고, ProblemException(런타임)이 나가므로 트랜잭션 전체가 롤백된다.
     */
    @Transactional
    public void create(long userId) {
        Household household = households.save(new Household());
        try {
            members.saveAndFlush(new HouseholdMember(household.getId(), userId, HouseholdRole.OWNER, Instant.now(clock)));
        } catch (DataIntegrityViolationException e) {
            if (isUserIdUnique(e)) {
                throw new ProblemException(ProblemCode.HAS_HOUSEHOLD);
            }
            throw e;
        }
    }

    /** owner, member 순서의 구성원. */
    public List<HouseholdMember> members(long householdId) {
        return members.findByHouseholdId(householdId).stream().sorted(Comparator.comparing(HouseholdMember::getRole)).toList();
    }

    /** 4단계 존재 확인, 5단계 자기 자신, 6단계 재인증, 가구 행 잠금, 삭제 순서다(research R3). 다른 가구의 사람과 없는 번호는 같은 404다. */
    @Transactional
    public void removeMember(long householdId, long userId, Instant lastGoogleAuthAt, String targetUserId) {
        long target = PathIds.parse(targetUserId);
        if (!members.existsByHouseholdIdAndUserId(householdId, target)) {
            // 권한 확인기가 읽은 뒤 가구가 삭제되었으면 404가 아니라 403 NO_HOUSEHOLD(research R3)
            if (!households.existsById(householdId)) {
                throw new ProblemException(ProblemCode.NO_HOUSEHOLD);
            }
            throw new ProblemException(ProblemCode.NOT_FOUND);
        }
        if (target == userId) {
            throw new ProblemException(ProblemCode.PERMISSION_DENIED);
        }
        reauthJudge.require(lastGoogleAuthAt);
        lock(householdId);
        // 007: 그 사람의 임장 기록 author_id 비우기를 여기(잠금 뒤, 멤버십 삭제 전)에 더한다(research R10)
        if (members.deleteMembership(householdId, target) == 0) {
            throw new ProblemException(ProblemCode.NOT_FOUND);
        }
    }

    /** 재인증은 없다. 권한 확인기가 읽은 뒤 잠금 전에 owner가 제거했으면 삭제 건수가 0이라 403 NO_HOUSEHOLD다(FR-019). */
    @Transactional
    public void leave(long householdId, long userId) {
        lock(householdId);
        // 007: 그 사람의 임장 기록 author_id 비우기를 여기(잠금 뒤, 멤버십 삭제 전)에 더한다(research R10)
        if (members.deleteMembership(householdId, userId) == 0) {
            throw new ProblemException(ProblemCode.NO_HOUSEHOLD);
        }
    }

    /** 재인증, 가구 행 잠금, 초대, 멤버십, 가구 순 삭제. 사용자 행과 세션은 남긴다(research R10). */
    @Transactional
    public void deleteHousehold(long householdId, Instant lastGoogleAuthAt) {
        reauthJudge.require(lastGoogleAuthAt);
        lock(householdId);
        // 004 관심 단지, 매물, 호가 이력. 007 임장 기록, 체크리스트 항목. 008 사진. 010 방문 알림 발송 기록: 그 가구의 행 삭제를 여기(잠금 뒤, 초대 삭제 앞)에 더한다(research R10)
        invitations.deleteAllByHousehold(householdId);
        members.deleteAllByHousehold(householdId);
        households.deleteHousehold(householdId);
    }

    // 잠금 전에 가구가 삭제되었으면 빈 결과라 403 NO_HOUSEHOLD(research R3)
    private void lock(long householdId) {
        if (!locks.lock(householdId)) {
            throw new ProblemException(ProblemCode.NO_HOUSEHOLD);
        }
    }

    // 메시지 문장이 아니라 서버가 보낸 제약 필드로 읽어 DB 메시지 언어(lc_messages)와 관계없다. 키 값은 읽지 않는다
    static boolean isUserIdUnique(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return USER_ID_UNIQUE.equals(psql.getServerErrorMessage().getConstraint());
            }
        }
        return false;
    }
}
