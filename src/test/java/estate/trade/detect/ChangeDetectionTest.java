package estate.trade.detect;

import static estate.trade.detect.DetectionDb.A;
import static estate.trade.detect.DetectionDb.B;
import static estate.trade.detect.DetectionDb.YMD;
import static estate.trade.detect.DetectionDb.all;
import static estate.trade.detect.DetectionDb.canceled;
import static estate.trade.detect.DetectionDb.registered;
import static estate.trade.detect.DetectionDb.times;
import static estate.trade.detect.DetectionDb.types;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.trade.detect.DetectionDb.History;
import estate.trade.detect.DetectionDb.Trade;

/**
 * T022 US2 경우 1~7, 10~13: 변경 감지 표(docs/architecture/flows.md)의 행 순서. 어제 결과를 반영하고(기준 적재로 만들어 두므로 어제 이력은 없다)
 * 시계를 옮겨 오늘 결과를 반영한다. 경우 8, 9는 범위 반영 전에 수집 쪽이 거르는 규칙이라 수집 테스트(T031)가 맡는다.
 */
@LoginIntegrationTest
class ChangeDetectionTest {

    static final Instant DAY_BEFORE = Instant.parse("2026-10-03T00:00:00Z");
    static final Instant YESTERDAY = Instant.parse("2026-10-04T00:00:00Z");
    static final Instant TODAY = Instant.parse("2026-10-05T00:00:00Z");

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

    /** 오늘 반영 뒤의 공통 확인: 비교로 반영했고, fetch_range는 성공이며 fetched_at이 이 반영의 수집 시각이다(FR-019). */
    private ApplyResult applyToday(List<estate.trade.api.TradeItem> items) {
        ApplyResult result = db.applyAt(TODAY, items);
        assertThat(result.baseline()).isFalse();
        db.assertSucceeded(YMD, TODAY);
        return result;
    }

    @Test
    void case01_신규() {
        db.insertRange(YMD, "성공", YESTERDAY, null); // 어제 결과에 A 없음
        ApplyResult result = applyToday(List.of(A));

        Trade a = db.trade(98500, false);
        List<History> history = db.history();
        assertThat(types(history)).containsExactly("신규");
        assertThat(history.get(0).tradeId()).isEqualTo(a.id());
        assertThat(result.count(ChangeType.NEW)).isEqualTo(1);
        assertThat(a.count()).isEqualTo(1);
        assertThat(history.get(0).observedAt()).isEqualTo(a.firstSeenAt()).isEqualTo(TODAY);
    }

    @Test
    void case02_해제() {
        db.baselineAt(YESTERDAY, List.of(A));
        ApplyResult result = applyToday(all(A, canceled(A)));

        Trade a = db.trade(98500, false);
        Trade canceled = db.trade(98500, true);
        List<History> history = db.history();
        assertThat(types(history)).containsExactly("해제");
        assertThat(history.get(0).tradeId()).isEqualTo(canceled.id());
        assertThat(history.get(0).observedAt()).isEqualTo(canceled.firstSeenAt()).isEqualTo(TODAY);
        assertThat(db.historyOf(a)).isEmpty();
        assertThat(a.count()).isEqualTo(1);
        assertThat(canceled.count()).isEqualTo(1);
        assertThat(canceled.cancelDate()).isEqualTo(canceled(A).canceledOn());
        assertThat(result.count(ChangeType.CANCELED)).isEqualTo(1);
    }

    @Test
    void case03_등기() {
        db.baselineAt(YESTERDAY, List.of(A));
        LocalDate registryDate = LocalDate.of(2026, 9, 28);
        ApplyResult result = applyToday(List.of(registered(A, registryDate)));

        Trade a = db.trade(98500, false);
        List<History> history = db.history();
        assertThat(types(history)).containsExactly("등기");
        assertThat(history.get(0).tradeId()).isEqualTo(a.id());
        assertThat(a.registryDate()).isEqualTo(registryDate);
        assertThat(a.firstSeenAt()).isEqualTo(YESTERDAY);
        assertThat(history.get(0).observedAt()).isEqualTo(TODAY).isNotEqualTo(a.firstSeenAt());
        assertThat(result.count(ChangeType.REGISTERED)).isEqualTo(1);
    }

    @Test
    void case04_사라짐() {
        db.baselineAt(YESTERDAY, all(A, B));
        // 어제 A 행이 있었음을 먼저 확인한다
        Trade aYesterday = db.trade(98500, false);
        assertThat(aYesterday.count()).isEqualTo(1);
        assertThat(db.trades()).hasSize(2);

        ApplyResult result = applyToday(List.of(B));

        Trade a = db.trade(98500, false);
        assertThat(a.id()).isEqualTo(aYesterday.id());
        assertThat(a.count()).isZero();
        assertThat(db.trades()).hasSize(2); // 삭제되지 않음
        List<History> history = db.history();
        assertThat(types(history)).containsExactly("사라짐");
        assertThat(history.get(0).tradeId()).isEqualTo(a.id());
        assertThat(db.historyOf(db.trade(99000, false))).isEmpty();
        assertThat(result.count(ChangeType.VANISHED)).isEqualTo(1);
    }

    @Test
    void case05_사라졌다_다시_보인_키() {
        db.baselineAt(DAY_BEFORE, all(A, B));
        db.applyAt(YESTERDAY, List.of(B)); // 전날 A가 사라져 건수 0
        Trade aGone = db.trade(98500, false);
        assertThat(aGone.count()).isZero();
        assertThat(types(db.history())).containsExactly("사라짐");

        ApplyResult result = applyToday(all(A, B));

        Trade a = db.trade(98500, false);
        assertThat(a.id()).isEqualTo(aGone.id());
        assertThat(a.count()).isEqualTo(1);
        List<History> today = db.historyAt(TODAY);
        assertThat(types(today)).containsExactly("신규");
        assertThat(today.get(0).tradeId()).isEqualTo(a.id());
        assertThat(a.firstSeenAt()).isEqualTo(DAY_BEFORE);
        assertThat(today.get(0).observedAt()).isNotEqualTo(a.firstSeenAt());
        assertThat(db.history()).hasSize(2); // 어제의 사라짐과 오늘의 신규뿐
        assertThat(result.count(ChangeType.NEW)).isEqualTo(1);
        assertThat(db.historyOf(db.trade(99000, false))).isEmpty();
    }

    @Test
    void case06_건수가_늘어난_기존_키() {
        db.baselineAt(YESTERDAY, List.of(A)); // 앞선 수집에서 처음 발견
        ApplyResult result = applyToday(times(A, 2));

        Trade a = db.trade(98500, false);
        assertThat(a.count()).isEqualTo(2);
        List<History> history = db.history();
        assertThat(types(history)).containsExactly("신규");
        assertThat(history.get(0).tradeId()).isEqualTo(a.id());
        assertThat(a.firstSeenAt()).isEqualTo(YESTERDAY);
        assertThat(history.get(0).observedAt()).isEqualTo(TODAY).isNotEqualTo(a.firstSeenAt());
        assertThat(result.count(ChangeType.NEW)).isEqualTo(1);
    }

    @Test
    void case07_같은_날_기준_적재_뒤_같은_키_건수_증가() {
        db.baselineAt(TODAY, List.of(A)); // 같은 날 앞 수집이 기준 적재로 A(1) 행을 만듦
        assertThat(db.history()).isEmpty();

        // 뒤이은 수집은 앞 수집보다 늦어야 비교가 된다(같은 시각이면 기준 적재가 됨)
        Instant later = TODAY.plus(Duration.ofHours(1));
        ApplyResult result = db.applyAt(later, times(A, 2));

        assertThat(result.baseline()).isFalse();
        Trade a = db.trade(98500, false);
        assertThat(a.count()).isEqualTo(2);
        List<History> history = db.history();
        assertThat(types(history)).containsExactly("신규");
        assertThat(history.get(0).observedAt()).isEqualTo(later).isNotEqualTo(a.firstSeenAt());
        assertThat(a.firstSeenAt()).isEqualTo(TODAY);
        db.assertSucceeded(YMD, later);
    }

    @Test
    void case10_기준_적재_선기록() {
        db.insertRange(YMD, "실패", null, "NOT_YET_COLLECTED");
        assertBaselineWithRowOnly();
    }

    @Test
    void case11_앞서_실패만_한_달() {
        db.insertRange(YMD, "실패", null, "HTTP_ERROR"); // 새 달의 첫 매일 수집이 실패한 달
        assertBaselineWithRowOnly();
    }

    private void assertBaselineWithRowOnly() {
        ApplyResult result = db.applyAt(TODAY, List.of(A));

        assertThat(result.baseline()).isTrue();
        Trade a = db.trade(98500, false);
        assertThat(a.count()).isEqualTo(1);
        assertThat(a.firstSeenAt()).isEqualTo(TODAY);
        assertThat(db.history()).isEmpty();
        assertThat(result.historyCounts()).isEmpty();
        db.assertSucceeded(YMD, TODAY);
    }

    @Test
    void case12_재수집_기간을_벗어난_실패한_범위() {
        db.baselineAt(YESTERDAY, List.of(A)); // 어제 fetched_at, 어제 결과 A(1)
        db.setStatus(YMD, "실패");

        ApplyResult result = applyToday(all(A, B));

        Trade b = db.trade(99000, false);
        List<History> history = db.history();
        assertThat(types(history)).containsExactly("신규");
        assertThat(history.get(0).tradeId()).isEqualTo(b.id());
        assertThat(history.get(0).observedAt()).isEqualTo(b.firstSeenAt()).isEqualTo(TODAY);
        assertThat(db.historyOf(db.trade(98500, false))).isEmpty();
        assertThat(result.count(ChangeType.NEW)).isEqualTo(1);
    }

    @Test
    void case13_재수집_기간에_새로_들어온_달() {
        // 이번 달 행 없음, 같은 지역의 바로 앞 달이 실행 시작 전 7일 안에 성공
        db.insertRange("202608", "성공", YESTERDAY, null);
        assertThat(db.fetchRange(YMD)).isNull();

        ApplyResult result = applyToday(List.of(A));

        Trade a = db.trade(98500, false);
        List<History> history = db.history();
        assertThat(types(history)).containsExactly("신규");
        assertThat(history.get(0).tradeId()).isEqualTo(a.id());
        assertThat(history.get(0).observedAt()).isEqualTo(a.firstSeenAt()).isEqualTo(TODAY);
        assertThat(result.count(ChangeType.NEW)).isEqualTo(1);
    }
}
