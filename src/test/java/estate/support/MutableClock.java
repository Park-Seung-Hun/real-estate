package estate.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code @Autowired MutableClock clock}(Clock으로 받아도 같은 객체). {@code advance(Duration)}, {@code setTo(Instant)}는 옮긴 뒤에도 시간이 흐르고
 * {@code freezeAt(Instant)}는 멈춘다. {@code reset()}은 시스템 시각으로 되돌린다.
 * @LoginIntegrationTest는 테스트마다 reset을 부른다.
 */
public final class MutableClock extends Clock {

    /** frozen이 있으면 그 시각에 멈춘 것이고, 없으면 시스템 시각에 offset을 더한다. */
    private record State(Instant frozen, Duration offset) {
    }

    private final AtomicReference<State> state = new AtomicReference<>(new State(null, Duration.ZERO));

    @Override
    public Instant instant() {
        State s = state.get();
        return s.frozen() != null ? s.frozen() : Instant.now().plus(s.offset());
    }

    public void advance(Duration d) {
        state.updateAndGet(s -> s.frozen() != null ? new State(s.frozen().plus(d), s.offset()) : new State(null, s.offset().plus(d)));
    }

    public void setTo(Instant t) {
        state.set(new State(null, Duration.between(Instant.now(), t)));
    }

    public void freezeAt(Instant t) {
        state.set(new State(t, Duration.ZERO));
    }

    public void reset() {
        state.set(new State(null, Duration.ZERO));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    // shortcut: 시간대를 바꾸지 않는다(서버 코드는 Instant만 쓴다), 시간대 변환이 필요해지면 복사본을 돌려주게 바꾼다
    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
