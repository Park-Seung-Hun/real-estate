package estate.trade.detect;

import static estate.trade.detect.ChangeDetectionTest.DAY_BEFORE;
import static estate.trade.detect.ChangeDetectionTest.TODAY;
import static estate.trade.detect.ChangeDetectionTest.YESTERDAY;
import static estate.trade.detect.DetectionDb.A;
import static estate.trade.detect.DetectionDb.B;
import static estate.trade.detect.DetectionDb.C;
import static estate.trade.detect.DetectionDb.YMD;
import static estate.trade.detect.DetectionDb.all;
import static estate.trade.detect.DetectionDb.area;
import static estate.trade.detect.DetectionDb.canceled;
import static estate.trade.detect.DetectionDb.dong;
import static estate.trade.detect.DetectionDb.registered;
import static estate.trade.detect.DetectionDb.times;
import static estate.trade.detect.DetectionDb.types;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.trade.api.TradeItem;
import estate.trade.detect.DetectionDb.History;
import estate.trade.detect.DetectionDb.Trade;

/** T023 US2 경우 14~18과 변경 감지 규칙(research R5, data-model, FR-014~FR-017). 어제 결과는 기준 적재로 만들어 어제 이력이 없다. */
@LoginIntegrationTest
class ChangeDetectionRulesTest {

    static final LocalDate R1 = LocalDate.of(2026, 9, 20);
    static final LocalDate R2 = LocalDate.of(2026, 9, 22);
    static final LocalDate X1 = LocalDate.of(2026, 9, 18);
    static final LocalDate X2 = LocalDate.of(2026, 9, 19);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    RangeApplier applier;

    DetectionDb db;

    @BeforeEach
    void setUp() {
        db = new DetectionDb(jdbc, clock, applier);
        db.clean();
    }

    @AfterEach
    void tearDown() {
        db.clean();
        clock.reset();
    }

    /** 비교로 반영했는지와 fetch_range 성공 기록(FR-019)을 함께 본다. */
    private ApplyResult applyAt(Instant at, List<TradeItem> items) {
        ApplyResult result = db.applyAt(at, items);
        assertThat(result.baseline()).isFalse();
        db.assertSucceeded(YMD, at);
        return result;
    }

    private ApplyResult applyToday(List<TradeItem> items) {
        return applyAt(TODAY, items);
    }

    // ---- 경우 14, 15 ----

    @Test
    void case14_해제와_함께_사라진_해제되지_않은_키() {
        db.baselineAt(YESTERDAY, List.of(A));
        ApplyResult result = applyToday(List.of(canceled(A)));

        Trade a = db.trade(98500, false);
        Trade canceled = db.trade(98500, true);
        assertThat(types(db.history())).containsExactly("사라짐", "해제");
        assertThat(types(db.historyOf(a))).containsExactly("사라짐");
        assertThat(types(db.historyOf(canceled))).containsExactly("해제");
        assertThat(a.count()).isZero();
        assertThat(canceled.count()).isEqualTo(1);
        assertThat(result.count(ChangeType.VANISHED)).isEqualTo(1);
        assertThat(result.count(ChangeType.CANCELED)).isEqualTo(1);
    }

    @Test
    void case15_정정은_사라짐과_신규() {
        db.baselineAt(YESTERDAY, List.of(A));
        ApplyResult result = applyToday(List.of(C));

        Trade a = db.trade(98500, false);
        Trade c = db.trade(98000, false);
        assertThat(types(db.history())).containsExactly("사라짐", "신규");
        assertThat(types(db.historyOf(a))).containsExactly("사라짐");
        assertThat(types(db.historyOf(c))).containsExactly("신규");
        assertThat(a.count()).isZero();
        assertThat(c.count()).isEqualTo(1);
        assertThat(result.count(ChangeType.VANISHED)).isEqualTo(1);
        assertThat(result.count(ChangeType.NEW)).isEqualTo(1);
    }

    // ---- 경우 16: 건수 2 이상 행의 등기 ----

    @Test
    void case16_건수2행의_등기는_처음_한_번이고_그_뒤_값은_유지() {
        db.baselineAt(YESTERDAY, times(A, 2));

        // 한 건의 등기일이 있음(묶음의 첫 행은 빈 값, 둘째 행에 등기일)
        ApplyResult first = applyToday(all(A, registered(A, R1)));
        Trade a = db.trade(98500, false);
        assertThat(types(db.history())).containsExactly("등기");
        assertThat(first.count(ChangeType.REGISTERED)).isEqualTo(1);
        assertThat(a.registryDate()).isEqualTo(R1);
        assertThat(a.count()).isEqualTo(2);

        // 다음 날 다른 등기일이 와도 값을 바꾸지 않고 등기를 다시 기록하지 않음
        ApplyResult second = applyAt(TODAY.plus(Duration.ofDays(1)), times(registered(A, R2), 2));
        assertThat(db.trade(98500, false).registryDate()).isEqualTo(R1);
        assertThat(second.count(ChangeType.REGISTERED)).isZero();
        assertThat(types(db.history())).containsExactly("등기");

        // 그 다음 날 동이 채워져도 등기를 다시 기록하지 않음(빈 열은 채움)
        ApplyResult third = applyAt(TODAY.plus(Duration.ofDays(2)), all(dong(registered(A, R2), "101동"), A));
        Trade after = db.trade(98500, false);
        assertThat(after.dong()).isEqualTo("101동");
        assertThat(after.registryDate()).isEqualTo(R1);
        assertThat(third.count(ChangeType.REGISTERED)).isZero();
        assertThat(types(db.history())).containsExactly("등기");
    }

    @Test
    void case16_건수2행에_동만_처음_생겨도_등기_한_행() {
        db.baselineAt(YESTERDAY, times(A, 2));
        ApplyResult result = applyToday(all(A, dong(A, "101동")));

        Trade a = db.trade(98500, false);
        assertThat(types(db.history())).containsExactly("등기");
        assertThat(result.count(ChangeType.REGISTERED)).isEqualTo(1);
        assertThat(a.dong()).isEqualTo("101동");
        assertThat(a.registryDate()).isNull();
    }

    // ---- 경우 17: 건수 1 행의 갱신 ----

    @Test
    void case17_건수1행의_세_값은_오늘_값으로_갱신되고_이미_값이_있던_열은_등기가_아님() {
        db.baselineAt(YESTERDAY, List.of(dong(registered(canceled(A, X1), R1), "101동")));
        ApplyResult result = applyToday(List.of(dong(registered(canceled(A, X2), R2), "102동")));

        Trade canceled = db.trade(98500, true);
        assertThat(canceled.cancelDate()).isEqualTo(X2);
        assertThat(canceled.registryDate()).isEqualTo(R2);
        assertThat(canceled.dong()).isEqualTo("102동");
        assertThat(db.history()).isEmpty();
        assertThat(result.historyCounts()).isEmpty();
    }

    @Test
    void case17_비어_있던_동에_값이_생기면_등기() {
        db.baselineAt(YESTERDAY, List.of(registered(A, R1)));
        ApplyResult result = applyToday(List.of(dong(registered(A, R1), "101동")));

        Trade a = db.trade(98500, false);
        assertThat(types(db.history())).containsExactly("등기");
        assertThat(result.count(ChangeType.REGISTERED)).isEqualTo(1);
        assertThat(a.dong()).isEqualTo("101동");
        assertThat(a.registryDate()).isEqualTo(R1);
    }

    @Test
    void case17_같은_날_등기일과_동이_함께_생겨도_등기는_한_행() {
        db.baselineAt(YESTERDAY, List.of(A));
        ApplyResult result = applyToday(List.of(dong(registered(A, R1), "101동")));

        Trade a = db.trade(98500, false);
        assertThat(types(db.history())).containsExactly("등기");
        assertThat(result.count(ChangeType.REGISTERED)).isEqualTo(1);
        assertThat(a.registryDate()).isEqualTo(R1);
        assertThat(a.dong()).isEqualTo("101동");
    }

    @Test
    void case17_해제_사유_발생일에_값이_생겨도_등기가_아님() {
        db.baselineAt(YESTERDAY, List.of(canceled(A, null)));
        assertThat(db.trade(98500, true).cancelDate()).isNull();

        applyToday(List.of(canceled(A, X2)));

        assertThat(db.trade(98500, true).cancelDate()).isEqualTo(X2);
        assertThat(db.history()).isEmpty();
    }

    // ---- 경우 18: 수집 시각 하나 ----

    @Test
    void case18_새_거래_행과_모든_이력과_성공_기록의_시각이_같은_값() {
        db.baselineAt(YESTERDAY, all(A, B));
        // 시계를 멈추지 않고 옮기기만 해서(시간이 흐름) 반영이 시계를 여러 번 읽으면 시각이 어긋나게 한다
        clock.setTo(TODAY);
        // 신규 C, 해제 A', 등기 B, 사라짐 A가 한 반영에서 모두 생긴다
        ApplyResult result = db.apply(YMD, all(registered(B, R1), C, canceled(A)), db.snapshot(TODAY));

        assertThat(result.baseline()).isFalse();
        List<History> history = db.history();
        assertThat(types(history)).containsExactly("등기", "사라짐", "신규", "해제");
        Set<Instant> times = new HashSet<>();
        history.forEach(h -> times.add(h.observedAt()));
        times.add(db.trade(98000, false).firstSeenAt());
        times.add(db.trade(98500, true).firstSeenAt());
        times.add(db.fetchRange(YMD).fetchedAt());
        assertThat(times).hasSize(1);
        assertThat(times.iterator().next()).isAfterOrEqualTo(TODAY);
        assertThat(db.fetchRange(YMD).status()).isEqualTo("성공");
    }

    // ---- research R5, data-model ----

    @Test
    void 처음_보는_키는_등기일이_있어도_등기가_없다() {
        db.baselineAt(YESTERDAY, List.of(B));
        ApplyResult result = applyToday(all(B, registered(A, R1), registered(canceled(A), R1)));

        assertThat(types(db.history())).containsExactly("신규", "해제");
        assertThat(result.count(ChangeType.REGISTERED)).isZero();
        assertThat(result.count(ChangeType.NEW)).isEqualTo(1);
        assertThat(result.count(ChangeType.CANCELED)).isEqualTo(1);
    }

    @Test
    void 사라졌다_다시_보인_키는_등기일이_있어도_등기가_없다() {
        db.baselineAt(DAY_BEFORE, all(A, B));
        db.applyAt(YESTERDAY, List.of(B));
        assertThat(db.trade(98500, false).count()).isZero();

        ApplyResult result = applyToday(all(registered(A, R1), B));

        assertThat(types(db.historyAt(TODAY))).containsExactly("신규");
        assertThat(result.count(ChangeType.REGISTERED)).isZero();
        assertThat(result.count(ChangeType.NEW)).isEqualTo(1);
    }

    @Test
    void 건수가_늘면서_비어_있던_등기일이_채워지면_신규와_등기_두_행() {
        db.baselineAt(YESTERDAY, List.of(A));
        ApplyResult result = applyToday(all(A, registered(A, R1)));

        Trade a = db.trade(98500, false);
        assertThat(types(db.history())).containsExactly("등기", "신규");
        assertThat(db.history()).allSatisfy(h -> assertThat(h.tradeId()).isEqualTo(a.id()));
        assertThat(result.count(ChangeType.NEW)).isEqualTo(1);
        assertThat(result.count(ChangeType.REGISTERED)).isEqualTo(1);
        assertThat(a.count()).isEqualTo(2);
        assertThat(a.registryDate()).isEqualTo(R1);
    }

    // ---- 전용면적 84.9와 84.90 ----

    @Test
    void 전용면적_84_9와_84_90은_같은_키() {
        db.baselineAt(YESTERDAY, List.of(A)); // 84.9
        ApplyResult result = applyToday(List.of(area(A, "84.90")));

        assertThat(db.trades()).hasSize(1);
        assertThat(db.trades().get(0).count()).isEqualTo(1);
        assertThat(db.history()).isEmpty();
        assertThat(result.historyCounts()).isEmpty();
    }

    @Test
    void 한_응답에_84_9와_84_90이_섞이면_한_키_건수2() {
        db.baselineAt(YESTERDAY, List.of(A));
        ApplyResult result = applyToday(all(A, area(A, "84.90")));

        assertThat(db.trades()).hasSize(1);
        assertThat(db.trades().get(0).count()).isEqualTo(2);
        assertThat(types(db.history())).containsExactly("신규");
        assertThat(result.count(ChangeType.NEW)).isEqualTo(1);
    }

    @Test
    void 기준_적재에서도_84_9와_84_90은_한_행() {
        db.baselineAt(YESTERDAY, all(A, area(A, "84.90")));

        assertThat(db.trades()).hasSize(1);
        assertThat(db.trades().get(0).count()).isEqualTo(2);
        assertThat(db.history()).isEmpty();
    }
}
