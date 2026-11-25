package estate.trade.detect;

import static estate.trade.detect.DetectionDb.A;
import static estate.trade.detect.DetectionDb.B;
import static estate.trade.detect.DetectionDb.C;
import static estate.trade.detect.DetectionDb.LAWD;
import static estate.trade.detect.DetectionDb.OTHER_LAWD;
import static estate.trade.detect.DetectionDb.YMD;
import static estate.trade.detect.DetectionDb.all;
import static estate.trade.detect.DetectionDb.canceled;
import static estate.trade.detect.DetectionDb.dong;
import static estate.trade.detect.DetectionDb.item;
import static estate.trade.detect.DetectionDb.range;
import static estate.trade.detect.DetectionDb.registered;
import static estate.trade.detect.DetectionDb.times;
import static estate.trade.detect.DetectionDb.types;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.trade.api.TradeItem;
import estate.trade.detect.DetectionDb.Trade;
import estate.trade.detect.RunSnapshot.Range;
import estate.trade.detect.RunSnapshot.Row;

/**
 * T024 US2 경우 19~21과 연속 수집 조건(FR-018, research R6). 판정은 실행 시작 때의 스냅숏으로만 하므로,
 * 이 테스트는 스냅숏을 직접 만들어 넘긴다(DB의 지금 값과 일부러 다르게 만드는 곳이 있다).
 * 수집 시각(시계)은 실행 시작 RUN보다 1분 뒤인 COLLECT에 멈춘다.
 */
@LoginIntegrationTest
class ContinuityTest {

    static final Instant RUN = Instant.parse("2026-10-05T00:00:00Z");
    static final Instant COLLECT = RUN.plusSeconds(60);
    static final Duration DAY = Duration.ofDays(1);
    static final String PREV = "202608";
    static final String NEXT = "202610";

    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    RangeApplier applier;

    DetectionDb db;
    /** seed가 DB에 넣은 행과 같은 내용의 스냅숏 행. */
    final Map<Range, Row> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        db = new DetectionDb(jdbc, clock, applier);
        db.clean();
        rows.clear();
    }

    @AfterEach
    void tearDown() {
        db.clean();
        clock.reset();
    }

    /** fetch_range 행을 DB와 스냅숏에 함께 둔다. */
    private void seed(String lawd, String ymd, String status, Instant fetchedAt) {
        db.insertRange(lawd, ymd, status, fetchedAt, "성공".equals(status) ? null : "HTTP_ERROR");
        rows.put(new Range(lawd, ymd), new Row(fetchedAt));
    }

    private void seed(String ymd, String status, Instant fetchedAt) {
        seed(LAWD, ymd, status, fetchedAt);
    }

    private ApplyResult applyAtCollect(String ymd, List<TradeItem> items, Map<Range, Row> snapshotRows) {
        clock.freezeAt(COLLECT);
        return db.apply(ymd, items, new RunSnapshot(RUN, snapshotRows));
    }

    private void assertBaselineOnlyRow(ApplyResult result, String ymd) {
        assertThat(result.baseline()).isTrue();
        assertThat(db.trade(ymd, 98500, false).count()).isEqualTo(1);
        assertThat(db.trade(ymd, 98500, false).firstSeenAt()).isEqualTo(COLLECT);
        assertThat(db.history()).isEmpty();
        assertThat(result.historyCounts()).isEmpty();
        db.assertSucceeded(ymd, COLLECT);
    }

    private void assertCompared(ApplyResult result, String ymd) {
        assertThat(result.baseline()).isFalse();
        assertThat(types(db.history())).containsExactly("신규");
        assertThat(db.history().get(0).observedAt()).isEqualTo(COLLECT);
        assertThat(db.trade(ymd, 98500, false).firstSeenAt()).isEqualTo(COLLECT);
        db.assertSucceeded(ymd, COLLECT);
    }

    // ---- 경우 19: 행 없는 달에서 바로 앞 달 성공이 7일 안에 없음 ----

    @Test
    void case19_앞_달_행이_없으면_기준_적재() {
        assertBaselineOnlyRow(applyAtCollect(YMD, List.of(A), rows), YMD);
    }

    @Test
    void case19_앞_달의_마지막_성공이_7일보다_오래됐으면_기준_적재() {
        seed(PREV, "성공", RUN.minus(Duration.ofDays(8)));
        assertBaselineOnlyRow(applyAtCollect(YMD, List.of(A), rows), YMD);
    }

    @Test
    void case19_앞_달에_행만_있고_성공한_적_없으면_기준_적재() {
        seed(PREV, "실패", null);
        assertBaselineOnlyRow(applyAtCollect(YMD, List.of(A), rows), YMD);
    }

    @Test
    void case19_다른_지역의_앞_달_성공은_조건이_아니다() {
        seed(OTHER_LAWD, PREV, "성공", RUN.minus(DAY));
        assertBaselineOnlyRow(applyAtCollect(YMD, List.of(A), rows), YMD);
    }

    // ---- 경우 20: 같은 실행 안의 성공은 조건에 넣지 않음 ----

    @Test
    void case20_같은_실행에서_끊긴_앞_달을_반영한_직후_다음_달도_기준_적재() {
        // M(202609)과 M+1(202610)이 행 없이 이어져 있고 M의 바로 앞 달은 성공한 적 없음
        Map<Range, Row> snapshotRows = Map.of();

        ApplyResult first = applyAtCollect(YMD, List.of(A), snapshotRows);
        assertBaselineOnlyRow(first, YMD);
        // DB에는 이제 M의 성공(이 실행의 것)이 있지만 같은 스냅숏으로 판정하므로 M+1도 기준 적재다
        assertThat(db.fetchRange(YMD).fetchedAt()).isEqualTo(COLLECT);

        ApplyResult second = applyAtCollect(NEXT, List.of(item(NEXT, 98500)), snapshotRows);
        assertBaselineOnlyRow(second, NEXT);
    }

    // ---- 경우 21: 오래 끊겼던 범위 ----

    @ParameterizedTest
    @ValueSource(strings = {"성공", "실패"})
    void case21_오래_끊긴_범위는_기준_적재로_건수를_오늘_값에_맞추고_사라짐을_남기지_않는다(String status) {
        Instant old = RUN.minus(Duration.ofDays(8));
        db.baselineAt(old, all(A, C)); // 그때 결과 A(1), C(1)
        db.setStatus(YMD, status); // 상태와 관계없음
        RunSnapshot snapshot = db.snapshot(RUN);
        clock.freezeAt(COLLECT);

        ApplyResult result = db.apply(YMD, all(times(A, 2), B), snapshot);

        assertThat(result.baseline()).isTrue();
        assertThat(db.trade(99000, false).count()).isEqualTo(1);
        assertThat(db.trade(99000, false).firstSeenAt()).isEqualTo(COLLECT);
        assertThat(db.trade(98500, false).count()).isEqualTo(2);
        assertThat(db.trade(98500, false).firstSeenAt()).isEqualTo(old);
        assertThat(db.trade(98000, false).count()).isZero(); // 그때 있던 C는 오늘 없음
        assertThat(db.trades()).hasSize(3);
        assertThat(db.history()).isEmpty();
        assertThat(result.historyCounts()).isEmpty();
        db.assertSucceeded(YMD, COLLECT);
    }

    @Test
    void case21_오래_끊긴_범위의_세_값은_건수1은_오늘_값_건수2_이상은_빈_열만_채운다() {
        LocalDate r1 = LocalDate.of(2026, 9, 20);
        LocalDate r2 = LocalDate.of(2026, 9, 22);
        LocalDate x1 = LocalDate.of(2026, 9, 18);
        LocalDate x2 = LocalDate.of(2026, 9, 19);
        TradeItem single = canceled(A, x1);
        TradeItem pair = B;
        db.baselineAt(RUN.minus(Duration.ofDays(8)),
                all(dong(registered(single, r1), "101동"), registered(pair, r1), pair));
        RunSnapshot snapshot = db.snapshot(RUN);
        clock.freezeAt(COLLECT);

        ApplyResult result = db.apply(YMD,
                all(dong(registered(canceled(A, x2), r2), "102동"), registered(pair, r2), dong(pair, "103동")), snapshot);

        assertThat(result.baseline()).isTrue();
        Trade s = db.trade(98500, true); // 건수 1: 오늘 값
        assertThat(s.cancelDate()).isEqualTo(x2);
        assertThat(s.registryDate()).isEqualTo(r2);
        assertThat(s.dong()).isEqualTo("102동");
        Trade p = db.trade(99000, false); // 건수 2: 비어 있던 동만 채움
        assertThat(p.count()).isEqualTo(2);
        assertThat(p.registryDate()).isEqualTo(r1);
        assertThat(p.dong()).isEqualTo("103동");
        assertThat(db.history()).isEmpty();
    }

    // ---- 경계값: 앞 달의 fetched_at(범위 행이 없는 달)과 그 범위 자신의 fetched_at ----

    /** 실행 시작 정확히 7일 전은 비교, 7일 1초 전은 기준 적재, 1초 전은 비교, 실행 시작과 같으면 기준 적재. */
    private static final String BOUNDARY = """
            -604800, true
            -604801, false
            -1, true
            0, false""";

    @ParameterizedTest
    @CsvSource(textBlock = BOUNDARY)
    void 경계값_그_범위_자신의_fetched_at(long offsetSeconds, boolean compared) {
        seed(YMD, "성공", RUN.plusSeconds(offsetSeconds));
        ApplyResult result = applyAtCollect(YMD, List.of(A), rows);

        if (compared) {
            assertCompared(result, YMD);
        } else {
            assertBaselineOnlyRow(result, YMD);
        }
    }

    @ParameterizedTest
    @CsvSource(textBlock = BOUNDARY)
    void 경계값_행이_없는_달의_바로_앞_달_fetched_at(long offsetSeconds, boolean compared) {
        seed(PREV, "성공", RUN.plusSeconds(offsetSeconds));
        ApplyResult result = applyAtCollect(YMD, List.of(A), rows);

        if (compared) {
            assertCompared(result, YMD);
        } else {
            assertBaselineOnlyRow(result, YMD);
        }
    }

    @Test
    void 바로_앞_달의_상태가_실패여도_fetched_at이_7일_안이면_비교한다() {
        seed(PREV, "실패", RUN.minus(Duration.ofDays(2))); // 7일 전에 성공하고 어제 실패한 앞 달도 같은 뜻이다
        assertCompared(applyAtCollect(YMD, List.of(A), rows), YMD);
    }

    @Test
    void 일월_범위의_바로_앞_달은_전해_십이월() {
        String january = "202601";
        seed("202512", "성공", RUN.minus(DAY));
        ApplyResult result = applyAtCollect(january, List.of(item(january, 98500)), rows);

        assertCompared(result, january);
    }

    @Test
    void 일월_범위는_같은_해_십이월의_성공을_앞_달로_보지_않는다() {
        String january = "202601";
        seed("202612", "성공", RUN.minus(DAY)); // 잘못 계산한 앞 달(같은 해 12월)
        ApplyResult result = applyAtCollect(january, List.of(item(january, 98500)), rows);

        assertBaselineOnlyRow(result, january);
    }

    // ---- research R10: 실행 시작 때의 스냅숏으로 판정(같은 스냅숏이면 반영 순서가 결과를 바꾸지 않음) ----

    /** 새 테스트 상태에서 rows를 DB에도 넣고, 같은 스냅숏으로 달을 주어진 순서로 반영한 뒤 거래와 이력을 글자로 돌려준다. */
    private List<String> applyInOrder(List<String> months, Map<Range, Row> snapshotRows) {
        db.clean();
        snapshotRows.forEach((r, row) -> db.insertRange(r.lawdCd(), r.dealYmd(), "성공", row.fetchedAt(), null));
        RunSnapshot snapshot = new RunSnapshot(RUN, snapshotRows);
        clock.freezeAt(COLLECT);
        months.forEach(ymd -> db.apply(ymd, List.of(item(ymd, 98500)), snapshot));
        return db.dump();
    }

    @Test
    void 순서_M과_M_더하기_1이_행_없이_이어진_달은_어느_순서로_반영해도_둘_다_기준_적재() {
        Map<Range, Row> snapshotRows = Map.of(); // M의 바로 앞 달은 성공한 적 없음

        List<String> forward = applyInOrder(List.of(YMD, NEXT), snapshotRows);
        List<String> reverse = applyInOrder(List.of(NEXT, YMD), snapshotRows);

        assertThat(forward).isEqualTo(reverse);
        assertThat(forward).hasSize(2).allMatch(line -> line.startsWith("trade ")); // 이력 없음
    }

    @Test
    void 순서_어제_성공한_앞_달을_먼저_반영해도_이번_달은_스냅숏의_앞_달로_비교한다() {
        Map<Range, Row> snapshotRows = Map.of(range(PREV), new Row(RUN.minus(DAY)));

        List<String> previousFirst = applyInOrder(List.of(PREV, YMD), snapshotRows);
        List<String> thisFirst = applyInOrder(List.of(YMD, PREV), snapshotRows);

        assertThat(previousFirst).isEqualTo(thisFirst);
        assertThat(previousFirst.stream().filter(line -> line.startsWith("history "))).hasSize(2).allMatch(line -> line.contains(" 신규 "));
    }
}
