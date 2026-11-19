package estate.user.application;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import estate.common.reauth.ReauthJudge;
import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import estate.household.application.Memberships;
import estate.user.domain.UserRepository;

/** 탈퇴(research R9). 사용자 행을 먼저 잠가 같은 사용자의 로그인 콜백과 한 줄로 선다(research R5). 잠금 뒤 멤버십을 다시 확인해 같은 사람의 초대 수락이나 가구 만들기와의 경쟁을 막는다(research R13). */
@Service
public class WithdrawService {

    private final UserRepository users;
    private final JdbcClient jdbc;
    private final ReauthJudge reauthJudge;
    private final Memberships memberships;

    public WithdrawService(UserRepository users, JdbcClient jdbc, ReauthJudge reauthJudge, Memberships memberships) {
        this.users = users;
        this.jdbc = jdbc;
        this.reauthJudge = reauthJudge;
        this.memberships = memberships;
    }

    /** 재인증을 확인하고, 사용자 행과 그 사용자의 모든 세션 행을 지운다. 다른 기기가 먼저 탈퇴해 사용자 행이 없으면 401, 가구 구성원이면 409 HAS_HOUSEHOLD. */
    @Transactional
    public void withdraw(long userId, Instant lastGoogleAuthAt) {
        reauthJudge.require(lastGoogleAuthAt);
        var user = users.findByIdForUpdate(userId).orElseThrow(() -> new ProblemException(ProblemCode.UNAUTHENTICATED));
        if (memberships.hasMembership(userId)) {
            throw new ProblemException(ProblemCode.HAS_HOUSEHOLD);
        }
        // 세션 속성 행은 외래 키 연쇄로 함께 지워진다
        jdbc.sql("delete from spring_session where principal_name = :principal").param("principal", Long.toString(userId)).update();
        users.delete(user);
    }
}
