package estate.common.reauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 재인증 판정 단위 테스트(US2-2, FR-011, research R8, 결정 기록 0013). 스프링 컨텍스트 없이 돈다.
 * 통합 테스트(ReauthOrderSecurityTest)가 만들 수 없는 "마지막 구글 인증 시각 없음"과 경계값을 여기서 본다.
 *
 * <p>구현이 따라야 할 공개 표면:
 * <ul>
 *   <li>클래스 {@code estate.common.reauth.ReauthJudge}({@code @Component})</li>
 *   <li>생성자 {@code ReauthJudge(java.time.Clock clock)}: 현재 시각은 이 {@code Clock}으로만 읽는다.</li>
 *   <li>{@code void require(Instant lastGoogleAuthAt)}: 시각이 없거나, 현재 시각과 차이가 5분보다 크거나,
 *       현재 시각보다 60초를 넘게 미래이면 {@code new ProblemException(ProblemCode.REAUTH_REQUIRED)}를 던지고,
 *       그 밖에는 아무것도 하지 않는다. 경계(정확히 5분 전, 정확히 60초 미래)는 통과다.</li>
 *   <li>차이는 결정 기록 0013대로 초 단위로 계산한다. 차이의 절댓값에서 초 아래를 버린 뒤 비교하므로
 *       5분 0.999초 전과 60.999초 미래는 통과다. 미래 쪽에서 음수 Duration의 초를 내림하면(-60.5초가 -61초)
 *       틀린다.</li>
 * </ul>
 */
class ReauthJudgeTest {

    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    // 초 아래 자리가 0이 아닌 현재 시각. 두 시각을 각각 초로 자르는 구현이나 버림 방향이 틀린 구현을 잡는다.
    private static final Instant NOW_FRACTION = Instant.parse("2026-11-16T03:00:00.700Z");

    private final ReauthJudge judge = new ReauthJudge(Clock.fixed(NOW, ZoneOffset.UTC));

    private final ReauthJudge fractionJudge = new ReauthJudge(Clock.fixed(NOW_FRACTION, ZoneOffset.UTC));

    private void assertPasses(Instant lastGoogleAuthAt) {
        assertPasses(judge, lastGoogleAuthAt);
    }

    private void assertReauthRequired(Instant lastGoogleAuthAt) {
        assertReauthRequired(judge, lastGoogleAuthAt);
    }

    private static void assertPasses(ReauthJudge judge, Instant lastGoogleAuthAt) {
        assertThatCode(() -> judge.require(lastGoogleAuthAt)).doesNotThrowAnyException();
    }

    private static void assertReauthRequired(ReauthJudge judge, Instant lastGoogleAuthAt) {
        Throwable thrown = catchThrowable(() -> judge.require(lastGoogleAuthAt));
        assertThat(thrown).isInstanceOf(ProblemException.class);
        assertThat(((ProblemException) thrown).problem().code()).isEqualTo(ProblemCode.REAUTH_REQUIRED);
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8: 마지막 구글 인증 시각이 없으면 REAUTH_REQUIRED")
    void missingAuthTimeRequiresReauth() {
        assertReauthRequired(null);
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8: 마지막 구글 인증이 지금이면 통과")
    void authAtNowPasses() {
        assertPasses(NOW);
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8: 정확히 5분 전이면 통과(5분보다 클 때만 거절)")
    void exactlyFiveMinutesAgoPasses() {
        assertPasses(NOW.minus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8: 5분 1초 전이면 REAUTH_REQUIRED")
    void fiveMinutesOneSecondAgoRequiresReauth() {
        assertReauthRequired(NOW.minus(Duration.ofMinutes(5).plusSeconds(1)));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8: 정확히 60초 미래이면 통과(60초를 넘을 때만 거절)")
    void exactlySixtySecondsAheadPasses() {
        assertPasses(NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8: 61초 미래이면 REAUTH_REQUIRED")
    void sixtyOneSecondsAheadRequiresReauth() {
        assertReauthRequired(NOW.plusSeconds(61));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8: 하루 전이면 REAUTH_REQUIRED")
    void oneDayAgoRequiresReauth() {
        assertReauthRequired(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8: 하루 뒤(미래)이면 REAUTH_REQUIRED")
    void oneDayAheadRequiresReauth() {
        assertReauthRequired(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8, 결정 기록 0013 초 단위 계산: 5분 1나노초 전은 300초로 보아 통과")
    void fiveMinutesOneNanoAgoPasses() {
        assertPasses(NOW.minus(Duration.ofMinutes(5).plusNanos(1)));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8, 결정 기록 0013 초 단위 계산: 5분 999밀리초 전은 300초로 보아 통과")
    void fiveMinutes999MillisAgoPasses() {
        assertPasses(NOW.minus(Duration.ofMinutes(5).plusMillis(999)));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8, 결정 기록 0013 초 단위 계산: 60초 1나노초 미래는 60초로 보아 통과")
    void sixtySecondsOneNanoAheadPasses() {
        assertPasses(NOW.plusSeconds(60).plusNanos(1));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8, 결정 기록 0013 초 단위 계산: 60초 999밀리초 미래는 60초로 보아 통과")
    void sixtySeconds999MillisAheadPasses() {
        assertPasses(NOW.plusSeconds(60).plusMillis(999));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8, 결정 기록 0013 초 단위 계산: 현재 시각 .700초에서 5분 0.5초 전은 통과")
    void fractionalNowFiveMinutesHalfSecondAgoPasses() {
        assertPasses(fractionJudge, NOW_FRACTION.minus(Duration.ofMinutes(5).plusMillis(500)));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8, 결정 기록 0013 초 단위 계산: 현재 시각 .700초에서 5분 1초 전은 REAUTH_REQUIRED")
    void fractionalNowFiveMinutesOneSecondAgoRequiresReauth() {
        assertReauthRequired(fractionJudge, NOW_FRACTION.minus(Duration.ofMinutes(5).plusSeconds(1)));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8, 결정 기록 0013 초 단위 계산: 현재 시각 .700초에서 60.5초 미래는 통과")
    void fractionalNowSixtyHalfSecondsAheadPasses() {
        assertPasses(fractionJudge, NOW_FRACTION.plusSeconds(60).plusMillis(500));
    }

    @Test
    @DisplayName("US2-2, FR-011, research R8, 결정 기록 0013 초 단위 계산: 현재 시각 .700초에서 61초 미래는 REAUTH_REQUIRED")
    void fractionalNowSixtyOneSecondsAheadRequiresReauth() {
        assertReauthRequired(fractionJudge, NOW_FRACTION.plusSeconds(61));
    }
}
