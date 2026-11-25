package estate.trade.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import estate.support.CollectorFixture;
import estate.support.CollectorIntegrationTest;
import estate.support.FakeTradeApiServer.Reply;
import estate.support.FakeTradeApiServer.Request;
import estate.support.MutableClock;
import estate.support.TradeFixtures;
import estate.support.TradeFixtures.Item;

/**
 * T042 관심 지역 설정 변경. spec US4-1, US4-2, US4-3(US2 경우 19, 20, 21), FR-020. 설정 변경은
 * {@link CollectionRun#run(List)}에 넘기는 지역 목록을 바꾸는 것으로 나타내고, 데이터베이스 상태는 실행 사이에 이어서 쓴다.
 * US4-4는 CollectorPropertiesTest가 본다. 값은 모두 가짜다.
 */
@CollectorIntegrationTest
class RegionChangeTest {

    private static final String LAWD = TradeFixtures.LAWD_CD;
    private static final String OTHER = TradeFixtures.OTHER_LAWD_CD;
    /** 한국 시간 2026-10-05 09:00. 이번 달 202610, 재수집 기간 202605~202610. */
    private static final Instant T0 = Instant.parse("2026-10-05T00:00:00Z");
    private static final List<String> WINDOW = List.of("202605", "202606", "202607", "202608", "202609", "202610");
    /** 한국 시간 2027-01-05 09:00(T0에서 3개월 뒤). 재수집 기간 202608~202701. */
    private static final Instant T3 = Instant.parse("2027-01-05T00:00:00Z");
    private static final List<String> WINDOW_T3 =
            List.of("202608", "202609", "202610", "202611", "202612", "202701");
    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    @Autowired
    CollectorFixture fixture;
    @Autowired
    MutableClock clock;
    @Autowired
    CollectionRun run;

    @BeforeEach
    void setUp() {
        fixture.reset();
        clock.freezeAt(T0);
    }

    @AfterEach
    void tearDown() {
        fixture.reset();
    }

    @Test
    @DisplayName("US4-1: 추가한 99998은 매일 수집(99999만)이 끝난 뒤 범위 확장으로 60개월을 이력 없이 적재한다")
    void addedRegionLoadsAfterDaily() {
        fixture.server().fallback(r -> body(a(r)));
        run.run(List.of(LAWD));
        assertThat(rangeRows(LAWD)).as("전제: 99999 초기 적재").hasSize(60);

        Instant t1 = T0.plus(Duration.ofDays(1));
        startRun(t1);
        // 99998은 달마다 A와 B 두 거래, 99999는 202609에만 새 거래 B(같은 실행의 비교가 이력을 남기는지 대조)
        fixture.server().fallback(r -> r.lawdCd().equals(OTHER) || r.dealYmd().equals("202609")
                ? body(a(r), b(r)) : body(a(r)));

        run.run(List.of(LAWD, OTHER));

        List<Request> requests = fixture.server().requests();
        assertThat(requests).hasSize(66);
        // 매일 수집은 99999만, 재수집 기간을 오래된 달부터
        assertThat(requests.subList(0, 6)).extracting(Request::lawdCd).containsOnly(LAWD);
        assertThat(requests.subList(0, 6)).extracting(Request::dealYmd).containsExactlyElementsOf(WINDOW);
        // 그 뒤 99998의 이번 달을 포함한 최근 60개월
        assertThat(requests.subList(6, 66)).extracting(Request::lawdCd).containsOnly(OTHER);
        assertThat(requests.subList(6, 66)).extracting(Request::dealYmd)
                .containsExactlyInAnyOrderElementsOf(months("202610", 60));

        assertThat(rangeRows(OTHER)).hasSize(60)
                .allSatisfy((m, row) -> assertThat(row).as(m).isEqualTo(new RangeRow("성공", t1, null)));
        assertThat(complexes(OTHER)).containsExactly("99998-1");
        assertThat(tradeRows(OTHER)).hasSize(120)
                .allSatisfy(t -> assertThat(t).extracting(TradeRow::count, TradeRow::firstSeenAt).containsExactly(1, t1));
        assertThat(historyRows(OTHER)).isEmpty();
        // 대조: 같은 예약 실행의 매일 수집은 99999의 새 거래 B를 신규로 기록했다
        assertThat(historyRows(LAWD)).containsExactly(new HistoryRow("신규", "202609", 99000, t1, t1));
    }

    @Test
    @DisplayName("US4-2, FR-020: 뺀 99999는 재수집 기간 달도 실패한 범위도 요청하지 않고 자료를 그대로 둔다")
    void removedRegionIsLeftAlone() {
        // 첫날 두 지역 초기 적재. 99999의 202601(재수집 기간 밖)은 HTTP 500으로 실패한 범위로 남긴다
        fixture.server().fallback(r -> isLawd202601(r) ? Reply.status(500) : body(a(r)));
        run.run(List.of(LAWD, OTHER));

        // 다음 날 두 지역 매일 수집. 99999의 202609에 새 거래 B, 202601은 계속 실패
        Instant t1 = T0.plus(Duration.ofDays(1));
        startRun(t1);
        fixture.server().fallback(r -> isLawd202601(r) ? Reply.status(500)
                : r.lawdCd().equals(LAWD) && r.dealYmd().equals("202609") ? body(a(r), b(r)) : body(a(r)));
        run.run(List.of(LAWD, OTHER));

        // 전제: 99999에 단지, 거래, 이력, 실패한 범위가 있고, 그 실패한 범위는 지역이 설정에 있으면 요청된다
        assertThat(fixture.server().requestCount(LAWD, "202601", 1)).as("전제: 실패한 범위를 매일 수집이 요청함")
                .isEqualTo(3);
        RangeRow failed = rangeRows(LAWD).get("202601");
        assertThat(failed.status()).as("전제: 실패한 범위").isEqualTo("실패");
        assertThat(failed.fetchedAt()).isNull();
        assertThat(failed.reason()).startsWith("HTTP_ERROR");
        assertThat(complexes(LAWD)).as("전제: 단지").containsExactly("99999-1");
        assertThat(tradeRows(LAWD)).as("전제: 거래(202601 빼고 59개월 A, 202609 B)").hasSize(60);
        assertThat(historyRows(LAWD)).as("전제: 이력").containsExactly(new HistoryRow("신규", "202609", 99000, t1, t1));

        List<String> complexesBefore = complexes(LAWD);
        List<TradeRow> tradesBefore = tradeRows(LAWD);
        List<HistoryRow> historyBefore = historyRows(LAWD);
        Map<String, RangeRow> rangesBefore = rangeRows(LAWD);

        // 설정에서 99999를 뺀다. 99999를 요청하면 새 거래 C가 생기고 202601도 성공할 응답을 둔다
        Instant t2 = T0.plus(Duration.ofDays(2));
        startRun(t2);
        fixture.server().fallback(r -> r.lawdCd().equals(LAWD) ? body(a(r), c(r))
                : r.dealYmd().equals("202609") ? body(a(r), b(r)) : body(a(r)));

        run.run(List.of(OTHER));

        // 남은 99998은 매일 수집되고, 99999는 한 번도 요청되지 않는다
        List<Request> requests = fixture.server().requests();
        assertThat(requests).extracting(Request::lawdCd).containsOnly(OTHER);
        assertThat(requests).extracting(Request::dealYmd).containsExactlyElementsOf(WINDOW);
        WINDOW.forEach(m -> assertThat(rangeRows(OTHER).get(m)).as(m).isEqualTo(new RangeRow("성공", t2, null)));
        assertThat(historyRows(OTHER)).containsExactly(new HistoryRow("신규", "202609", 99000, t2, t2));

        // FR-020: 99999의 단지, 거래, 이력, fetch_range는 삭제도 변경도 없다
        assertThat(complexes(LAWD)).isEqualTo(complexesBefore);
        assertThat(tradeRows(LAWD)).isEqualTo(tradesBefore);
        assertThat(historyRows(LAWD)).isEqualTo(historyBefore);
        assertThat(rangeRows(LAWD)).isEqualTo(rangesBefore).hasSize(60);
    }

    @Test
    @DisplayName("US4-3, US2 경우 19~21: 3개월 뒤 다시 넣은 99999는 끊긴 동안 신고된 거래를 신규로 남기지 않는다")
    void readdedRegionIsBaseline() {
        fixture.server().fallback(r -> body(a(r)));
        run.run(List.of(LAWD, OTHER));

        // 99999를 뺀다. 끊기지 않은 대조 지역 99998은 다시 넣는 날의 전날에도 수집되어 재수집 기간의 모든 달이 7일 안에
        // 성공해 있다(그 사이 날들의 실행은 이 상태를 바꾸지 않아 생략한다)
        Instant dayBefore = T3.minus(Duration.ofDays(1));
        startRun(dayBefore);
        fixture.server().fallback(r -> body(a(r)));
        run.run(List.of(OTHER));

        Map<String, RangeRow> lawdBefore = rangeRows(LAWD);
        for (String m : List.of("202608", "202609", "202610")) {
            assertThat(lawdBefore.get(m)).as("전제: 행이 있는 달은 T0에 성공(7일보다 오래됨) " + m)
                    .isEqualTo(new RangeRow("성공", T0, null));
        }
        assertThat(lawdBefore).as("전제: 행이 없는 달").doesNotContainKeys("202611", "202612", "202701");
        Map<String, RangeRow> otherBefore = rangeRows(OTHER);
        WINDOW_T3.forEach(m -> assertThat(otherBefore.get(m)).as("전제: 99998은 전날 성공 " + m)
                .isEqualTo(new RangeRow("성공", dayBefore, null)));
        assertThat(historyCount()).as("전제: 이력 없음").isZero();

        // 99999를 다시 넣는다. 두 지역에 같은 모양의 응답: 202608은 A 대신 C, 나머지 달은 A와 끊긴 동안 신고된 B
        startRun(T3);
        fixture.server().fallback(r -> r.dealYmd().equals("202608") ? body(c(r)) : body(a(r), b(r)));

        run.run(List.of(LAWD, OTHER));

        // 행이 있는 지역이라 매일 수집 대상: 범위 확장(60개월) 없이 재수집 기간만 요청한다
        assertThat(fixture.server().requests()).filteredOn(r -> r.lawdCd().equals(LAWD)).extracting(Request::dealYmd)
                .containsExactlyElementsOf(WINDOW_T3);
        Map<String, RangeRow> lawdAfter = rangeRows(LAWD);
        assertThat(lawdAfter).hasSize(63);
        WINDOW_T3.forEach(m -> assertThat(lawdAfter.get(m)).as(m).isEqualTo(new RangeRow("성공", T3, null)));

        // 행이 있는 달(202608~202610)도 행이 없는 달(202611~202701)도 기준 적재: 이력 0건
        assertThat(historyRows(LAWD)).isEmpty();
        // 거래 행은 생긴다. 경우 21: 그때의 A는 오늘 없으면 건수 0(사라짐 없음), 있으면 건수 1이고 first_seen_at은 그대로
        List<TradeRow> expected = new ArrayList<>(List.of(
                new TradeRow("202608", 97000, 1, T3), new TradeRow("202608", 98500, 0, T0),
                new TradeRow("202609", 98500, 1, T0), new TradeRow("202609", 99000, 1, T3),
                new TradeRow("202610", 98500, 1, T0), new TradeRow("202610", 99000, 1, T3)));
        for (String m : List.of("202611", "202612", "202701")) {
            expected.add(new TradeRow(m, 98500, 1, T3));
            expected.add(new TradeRow(m, 99000, 1, T3));
        }
        assertThat(tradeRows(LAWD)).filteredOn(t -> WINDOW_T3.contains(t.ymd())).containsExactlyElementsOf(expected);

        // 대조: 끊기지 않은 99998은 같은 응답에서 B와 C를 신규로, 202608의 A를 사라짐으로 기록한다
        List<HistoryRow> expectedOther = new ArrayList<>(List.of(
                new HistoryRow("사라짐", "202608", 98500, T3, T0), new HistoryRow("신규", "202608", 97000, T3, T3)));
        WINDOW_T3.subList(1, 6).forEach(m -> expectedOther.add(new HistoryRow("신규", m, 99000, T3, T3)));
        assertThat(historyRows(OTHER)).containsExactlyInAnyOrderElementsOf(expectedOther);
    }

    // ---- 도우미 ----

    /** 다음 예약 실행의 시각으로 시계를 옮기고 가짜 서버의 응답과 받은 요청을 비운다(데이터베이스는 그대로). */
    private void startRun(Instant at) {
        clock.freezeAt(at);
        fixture.server().reset();
    }

    private static boolean isLawd202601(Request r) {
        return r.lawdCd().equals(LAWD) && r.dealYmd().equals("202601");
    }

    /** A: 요청한 지역, 그 달 15일, 단지 {지역}-1, 7층, 84.97, 98,500. */
    private static Item a(Request r) {
        return TradeFixtures.item(r.lawdCd(), r.dealYmd(), r.lawdCd() + "-1");
    }

    /** B: A와 금액만 다른 거래(99,000). */
    private static Item b(Request r) {
        return a(r).with("dealAmount", "99,000");
    }

    /** C: A와 금액만 다른 거래(97,000). */
    private static Item c(Request r) {
        return a(r).with("dealAmount", "97,000");
    }

    private static Reply body(Item... items) {
        return Reply.body(TradeFixtures.response(items));
    }

    /** last를 마지막으로 하는 n개월(오래된 달부터). */
    private static List<String> months(String last, int n) {
        YearMonth end = YearMonth.parse(last, YYYYMM);
        return IntStream.range(0, n).mapToObj(i -> end.minusMonths(n - 1 - i).format(YYYYMM)).toList();
    }

    record RangeRow(String status, Instant fetchedAt, String reason) {
    }

    private Map<String, RangeRow> rangeRows(String lawd) {
        Map<String, RangeRow> rows = new LinkedHashMap<>();
        fixture.jdbc().sql("""
                select deal_ymd, status, fetched_at, failure_reason from fetch_range
                where lawd_cd = :l order by deal_ymd""").param("l", lawd)
                .query((rs, n) -> rows.put(rs.getString(1), new RangeRow(rs.getString(2), instant(rs, 3), rs.getString(4))))
                .list();
        return rows;
    }

    private List<String> complexes(String lawd) {
        return fixture.jdbc().sql("select apt_seq from complex where sgg_cd = :l order by apt_seq").param("l", lawd)
                .query(String.class).list();
    }

    record TradeRow(String ymd, long amount, int count, Instant firstSeenAt) {
    }

    /** 이 지역 단지의 거래 행(계약일, 금액 순). */
    private List<TradeRow> tradeRows(String lawd) {
        return fixture.jdbc().sql("""
                select to_char(t.deal_date, 'YYYYMM'), t.amount, t.same_key_count, t.first_seen_at
                from trade t join complex c on c.id = t.complex_id
                where c.sgg_cd = :l order by t.deal_date, t.amount""").param("l", lawd)
                .query((rs, n) -> new TradeRow(rs.getString(1), rs.getLong(2), rs.getInt(3), instant(rs, 4))).list();
    }

    record HistoryRow(String type, String ymd, long amount, Instant observedAt, Instant tradeFirstSeenAt) {
    }

    /** 이 지역 단지 거래의 이력(기록 순). */
    private List<HistoryRow> historyRows(String lawd) {
        return fixture.jdbc().sql("""
                select h.change_type, to_char(t.deal_date, 'YYYYMM'), t.amount, h.observed_at, t.first_seen_at
                from trade_history h join trade t on t.id = h.trade_id join complex c on c.id = t.complex_id
                where c.sgg_cd = :l order by h.id""").param("l", lawd)
                .query((rs, n) -> new HistoryRow(rs.getString(1), rs.getString(2), rs.getLong(3), instant(rs, 4),
                        instant(rs, 5)))
                .list();
    }

    private long historyCount() {
        return fixture.jdbc().sql("select count(*) from trade_history").query(Long.class).single();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
