package estate.household.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import estate.household.domain.Household;
import estate.household.domain.HouseholdMember;
import estate.household.domain.HouseholdMemberRepository;
import estate.household.domain.HouseholdRepository;
import estate.household.domain.HouseholdRole;

/** 가구 만들기와 구성원 조회(research R9). */
@Service
public class HouseholdService {

    private static final String USER_ID_UNIQUE = "household_member_user_id_key";

    private final HouseholdRepository households;
    private final HouseholdMemberRepository members;
    private final Clock clock;

    HouseholdService(HouseholdRepository households, HouseholdMemberRepository members, Clock clock) {
        this.households = households;
        this.members = members;
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
