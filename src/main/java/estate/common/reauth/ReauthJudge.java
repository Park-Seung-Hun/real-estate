package estate.common.reauth;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;

/** 민감한 동작 앞의 재인증 판정(research R8). */
@Component
public class ReauthJudge {

    /** 마지막 구글 인증이 이보다 오래 지났으면 재인증(초). */
    static final long MAX_AGE_SECONDS = 5 * 60;
    /** 서버 시계가 뒤로 조정된 경우에 허용하는 미래 폭(초). */
    static final long MAX_FUTURE_SECONDS = 60;

    private final Clock clock;

    public ReauthJudge(Clock clock) {
        this.clock = clock;
    }

    /**
     * 마지막 구글 인증 시각이 재인증 조건(research R8)을 어기면 ProblemException(REAUTH_REQUIRED)을
     * 던진다.
     */
    public void require(Instant lastGoogleAuthAt) {
        // 차이는 초 아래를 버린 초로 센다(결정 기록 0013). SECONDS.between은 0 쪽으로 잘라 미래 방향도 같은 규칙이 된다
        if (lastGoogleAuthAt == null) {
            throw new ProblemException(ProblemCode.REAUTH_REQUIRED);
        }

        Instant now = clock.instant();
        long elapsed = ChronoUnit.SECONDS.between(lastGoogleAuthAt, now);

        if (elapsed > MAX_AGE_SECONDS || -elapsed > MAX_FUTURE_SECONDS) {
            throw new ProblemException(ProblemCode.REAUTH_REQUIRED);
        }
    }
}
