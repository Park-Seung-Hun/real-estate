package estate.trade.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * T031 초기 적재. spec US1-1, US1-2, US1-4, US1-5, US1-6, US2 경우 8, 9, 22, Edge Cases(달이 바뀐 첫날 0건),
 * FR-003, FR-005, FR-006, FR-010, FR-011, SC-001, SC-003, SC-006, SC-007. 값은 모두 가짜다.
 */
@CollectorIntegrationTest
class InitialLoadTest {

    private static final String LAWD = TradeFixtures.LAWD_CD;
    /** 한국 시간 2026-10-05 09:00. 이번 달 202610, 재수집 기간 202605~202610. */
    private static final Instant T0 = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant YESTERDAY = T0.minus(Duration.ofDays(1));
    private static final List<String> WINDOW = List.of("202605", "202606", "202607", "202608", "202609", "202610");

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
    @DisplayName("US1-1, US1-2, US1-4, US1-5, SC-001, SC-003, SC-006: 빈 DB에서 60개월을 기준 적재로 반영한다")
    void emptyDbLoadsSixtyMonthsAsBaseline() {
        everyMonthReturns("99999-1");
        // 202608은 두 페이지(1001건): 1쪽은 99999-2가 1000건, 2쪽은 99999-1 한 건
        Item[] thousand = Collections.nCopies(1000, TradeFixtures.item(LAWD, "202608", "99999-2")).toArray(Item[]::new);
        fixture.server().enqueue(LAWD, "202608", 1, Reply.body(TradeFixtures.response(1001, 1, 1000, thousand)));
        fixture.server().enqueue(LAWD, "202608", 2, Reply.body(
                TradeFixtures.response(1001, 2, 1000, TradeFixtures.item(LAWD, "202608", "99999-1"))));

        run.run(List.of(LAWD));

        List<String> months = CollectionCalendar.initialLoadMonths(T0);
        assertThat(months).hasSize(60).startsWith("202111").endsWith("202610");

        // US1-1: 행 없는 지역은 매일 수집이 하지 않으므로 요청은 60개월(202608만 두 페이지)뿐이다
        List<Request> requests = fixture.server().requests();
        assertThat(requests).hasSize(61);
        assertThat(requests).allSatisfy(r -> {
            assertThat(r.lawdCd()).isEqualTo(LAWD);
            assertThat(r.query()).containsEntry("numOfRows", "1000");
        });
        assertThat(requests.stream().map(Request::dealYmd).distinct()).containsExactlyInAnyOrderElementsOf(months);
        assertThat(fixture.server().requestCount(LAWD, "202608", 2)).isEqualTo(1);
        assertThat(requests.stream().filter(r -> r.pageNo() != 1).map(Request::dealYmd)).containsExactly("202608");

        // US1-2, SC-001: 60개 범위가 모두 성공이고 fetched_at은 반영한 시각(멈춘 시계 T0)
        Map<String, RangeRow> ranges = ranges(LAWD);
        assertThat(ranges.keySet()).containsExactlyInAnyOrderElementsOf(months);
        assertThat(ranges.values()).allSatisfy(r -> assertThat(r).isEqualTo(new RangeRow("성공", T0, null)));

        // US1-4: 단지 번호마다 complex 한 행(99999-1은 60개월에 나와도 한 행)
        assertThat(complexes()).containsExactly(
                "99999-1|가상단지|99999|가상동|123", "99999-2|가상단지|99999|가상동|123");

        // US1-2: 거래 행마다 first_seen_at, SC-003: 이력 0건
        List<TradeRow> trades = trades();
        assertThat(trades).hasSize(61);
        assertThat(trades).allSatisfy(t -> assertThat(t.firstSeenAt()).isEqualTo(T0));
        assertThat(trades).filteredOn(t -> t.aptSeq().equals("99999-2"))
                .singleElement().satisfies(t -> assertThat(t.count()).isEqualTo(1000));
        assertThat(historyCount()).isZero();

        // US1-5, SC-006: 페이지마다 원본, 키는 {YYYY-MM-DD}/{HHmmss}/{시군구}-{계약년월}-p{페이지}.xml.gz(한국 시간)
        List<String> expectedKeys = new ArrayList<>(
                months.stream().map(m -> "2026-10-05/090000/99999-" + m + "-p1.xml.gz").toList());
        expectedKeys.add("2026-10-05/090000/99999-202608-p2.xml.gz");
        assertThat(fixture.rawStore().savedKeys()).containsExactlyInAnyOrderElementsOf(expectedKeys);
        // 원본은 받은 응답 바이트 그대로 저장 포트에 넘긴다(압축은 어댑터의 일)
        assertThat(saved("2026-10-05/090000/99999-202610-p1.xml.gz"))
                .isEqualTo(TradeFixtures.response(TradeFixtures.item(LAWD, "202610", "99999-1")));
    }

    @Test
    @DisplayName("US1-6, SC-007, FR-004: 적재 다음 날은 매일 수집 6회(오래된 달부터)이고 초기 적재 요청은 없다")
    void nextDayCollectsOnlyRefetchWindow() {
        everyMonthReturns("99999-1");
        run.run(List.of(LAWD));
        assertThat(fixture.server().requestCount()).as("전제: 첫날 초기 적재").isEqualTo(60);
        assertThat(trades()).as("전제: 첫날 거래 행").hasSize(60);

        clock.advance(Duration.ofDays(1));
        Instant t1 = T0.plus(Duration.ofDays(1));
        fixture.server().reset();
        everyMonthReturns("99999-1");
        fixture.rawStore().clear();

        run.run(List.of(LAWD));

        List<Request> requests = fixture.server().requests();
        assertThat(requests).allSatisfy(r -> {
            assertThat(r.lawdCd()).isEqualTo(LAWD);
            assertThat(r.pageNo()).isEqualTo(1);
        });
        assertThat(requests.stream().map(Request::dealYmd)).containsExactlyElementsOf(WINDOW);
        assertThat(fixture.rawStore().savedKeys()).containsExactlyElementsOf(
                WINDOW.stream().map(m -> "2026-10-06/090000/99999-" + m + "-p1.xml.gz").toList());

        Map<String, RangeRow> ranges = ranges(LAWD);
        assertThat(ranges).hasSize(60);
        WINDOW.forEach(m -> assertThat(ranges.get(m)).isEqualTo(new RangeRow("성공", t1, null)));
        assertThat(ranges.get("202604")).isEqualTo(new RangeRow("성공", T0, null));
        // 같은 결과를 비교했으므로 이력 없음, 거래 행은 그대로
        assertThat(historyCount()).isZero();
        assertThat(trades()).hasSize(60).allSatisfy(t -> assertThat(t.firstSeenAt()).isEqualTo(T0));
    }

    @Test
    @DisplayName("US2 경우 8, FR-011: 직전에 거래가 있던 범위가 0건이면 실패로 두고 비교하지 않는다")
    void emptyAfterTradesFailsWithoutCompare() {
        dailyRegionYesterday(WINDOW);
        insertTrade("202609", "99999-1", 98500, 1, YESTERDAY);
        TradeRow before = singleTrade();
        assertThat(before.count()).as("전제: 어제 A(1)").isEqualTo(1);

        run.run(List.of(LAWD));

        RangeRow row = ranges(LAWD).get("202609");
        assertThat(row.status()).isEqualTo("실패");
        assertThat(row.reason()).startsWith("EMPTY_AFTER_TRADES");
        assertThat(row.fetchedAt()).isEqualTo(YESTERDAY);
        assertThat(singleTrade()).isEqualTo(before);
        assertThat(historyCount()).isZero();
        // 원본은 건수 확인보다 먼저 저장되어 남는다
        assertThat(fixture.rawStore().savedKeys()).contains("2026-10-05/090000/99999-202609-p1.xml.gz");
        // 거래가 없던 나머지 달의 0건은 성공
        WINDOW.stream().filter(m -> !m.equals("202609"))
                .forEach(m -> assertThat(ranges(LAWD).get(m)).isEqualTo(new RangeRow("성공", T0, null)));
    }

    @Test
    @DisplayName("US2 경우 9, FR-011: 수집 건수가 totalCount와 다르면 실패로 두고 비교하지 않는다")
    void countMismatchFailsWithoutCompare() {
        dailyRegionYesterday(WINDOW);
        insertTrade("202609", "99999-1", 98500, 1, YESTERDAY);
        TradeRow before = singleTrade();
        // 항목 둘(A와 새 키 C)인데 totalCount는 3. 비교했다면 C 신규가 남는다
        fixture.server().enqueue(LAWD, "202609", 1, Reply.body(TradeFixtures.response(3, 1, 1000,
                TradeFixtures.item(LAWD, "202609", "99999-1"),
                TradeFixtures.item(LAWD, "202609", "99999-1").with("dealAmount", "98,000"))));

        run.run(List.of(LAWD));

        RangeRow row = ranges(LAWD).get("202609");
        assertThat(row.status()).isEqualTo("실패");
        assertThat(row.reason()).startsWith("COUNT_MISMATCH");
        assertThat(row.fetchedAt()).isEqualTo(YESTERDAY);
        assertThat(trades()).containsExactly(before);
        assertThat(historyCount()).isZero();
        assertThat(fixture.rawStore().savedKeys()).contains("2026-10-05/090000/99999-202609-p1.xml.gz");
    }

    @Test
    @DisplayName("US2 경우 9, FR-011: 페이지마다 totalCount가 다르면 합이 첫 페이지 값과 같아도 실패")
    void pageTotalCountMismatchFails() {
        dailyRegionYesterday(WINDOW);
        insertTrade("202609", "99999-1", 98500, 1, YESTERDAY);
        TradeRow before = singleTrade();
        // 1쪽 totalCount 1001(항목 1000), 2쪽 totalCount 1500(항목 1). 수집 건수 1001은 1쪽 값과 같다
        Item[] thousand = Collections.nCopies(1000, TradeFixtures.item(LAWD, "202609", "99999-1")).toArray(Item[]::new);
        fixture.server().enqueue(LAWD, "202609", 1, Reply.body(TradeFixtures.response(1001, 1, 1000, thousand)));
        fixture.server().enqueue(LAWD, "202609", 2, Reply.body(TradeFixtures.response(1500, 2, 1000,
                TradeFixtures.item(LAWD, "202609", "99999-1").with("dealAmount", "98,000"))));

        run.run(List.of(LAWD));

        assertThat(fixture.server().requestCount(LAWD, "202609", 2)).as("마지막 페이지까지 수집").isEqualTo(1);
        RangeRow row = ranges(LAWD).get("202609");
        assertThat(row.status()).isEqualTo("실패");
        assertThat(row.reason()).startsWith("COUNT_MISMATCH");
        assertThat(row.fetchedAt()).isEqualTo(YESTERDAY);
        assertThat(trades()).containsExactly(before);
        assertThat(historyCount()).isZero();
        assertThat(fixture.rawStore().savedKeys()).contains(
                "2026-10-05/090000/99999-202609-p1.xml.gz", "2026-10-05/090000/99999-202609-p2.xml.gz");
    }

    @Test
    @DisplayName("US2 경우 22, FR-018: 같은 실행에서 앞 달을 먼저 반영해도 이번 달 A는 실행 시작 때 값으로 신규")
    void previousMonthAppliedFirstStillNew() {
        // 앞 달까지 어제 성공, 이번 달(202610)은 행 없음
        dailyRegionYesterday(WINDOW.subList(0, 5));
        fixture.server().enqueue(LAWD, "202610", 1,
                Reply.body(TradeFixtures.response(TradeFixtures.item(LAWD, "202610", "99999-1"))));

        run.run(List.of(LAWD));

        // 앞 달(202609)이 이번 달보다 먼저 요청되고 먼저 반영되어 fetched_at이 이 실행 값(T0)으로 바뀌어 있다
        List<String> order = fixture.server().requests().stream().map(Request::dealYmd).toList();
        assertThat(order).containsExactlyElementsOf(WINDOW);
        assertThat(order.indexOf("202609")).isLessThan(order.indexOf("202610"));
        assertThat(ranges(LAWD).get("202609")).isEqualTo(new RangeRow("성공", T0, null));
        assertThat(ranges(LAWD).get("202610")).isEqualTo(new RangeRow("성공", T0, null));

        TradeRow a = singleTrade();
        assertThat(a.count()).isEqualTo(1);
        assertThat(history()).containsExactly(new HistoryRow(a.id(), "신규", a.firstSeenAt()));
        assertThat(a.firstSeenAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Edge Cases, FR-011: 달이 바뀐 첫날 거래 행이 없는(건수 0 행만 있는) 범위의 0건은 성공")
    void emptyNewMonthWithoutTradesSucceeds() {
        // 한국 시간 2026-10-01 09:00. 어제의 재수집 기간은 202604~202609였다
        Instant firstDay = Instant.parse("2026-10-01T00:00:00Z");
        Instant dayBefore = firstDay.minus(Duration.ofDays(1));
        clock.freezeAt(firstDay);
        List.of("202604", "202605", "202606", "202607", "202608", "202609")
                .forEach(m -> insertRange(LAWD, m, "성공", dayBefore, null));
        // 202609는 거래가 있고 오늘도 같은 결과, 202608은 사라진 키(건수 0)만 있다
        insertTrade("202609", "99999-1", 98500, 1, dayBefore);
        insertTrade("202608", "99999-1", 98500, 0, dayBefore.minus(Duration.ofDays(3)));
        fixture.server().enqueue(LAWD, "202609", 1,
                Reply.body(TradeFixtures.response(TradeFixtures.item(LAWD, "202609", "99999-1"))));

        run.run(List.of(LAWD));

        assertThat(fixture.server().requests().stream().map(Request::dealYmd)).containsExactlyElementsOf(WINDOW);
        Map<String, RangeRow> ranges = ranges(LAWD);
        assertThat(ranges.get("202610")).isEqualTo(new RangeRow("성공", firstDay, null));
        assertThat(ranges.get("202608")).isEqualTo(new RangeRow("성공", firstDay, null));
        assertThat(ranges.get("202609")).isEqualTo(new RangeRow("성공", firstDay, null));
        assertThat(trades()).extracting(TradeRow::count).containsExactlyInAnyOrder(0, 1);
        assertThat(historyCount()).isZero();
    }

    // ---- 도우미 ----

    /** 지정하지 않은 범위는 그 지역, 그 달 15일의 이 단지 거래 한 건으로 응답한다. */
    private void everyMonthReturns(String aptSeq) {
        fixture.server().fallback(r -> Reply.body(
                TradeFixtures.response(TradeFixtures.item(r.lawdCd(), r.dealYmd(), aptSeq))));
    }

    /** 이 달들을 어제 성공한 범위로 둬 99999를 매일 수집 대상으로 만든다. */
    private void dailyRegionYesterday(List<String> months) {
        months.forEach(m -> insertRange(LAWD, m, "성공", YESTERDAY, null));
    }

    private void insertRange(String lawd, String ymd, String status, Instant fetchedAt, String reason) {
        fixture.jdbc().sql("""
                insert into fetch_range (lawd_cd, deal_ymd, status, fetched_at, failure_reason)
                values (:l, :y, :s, cast(:f as timestamptz), :r)""")
                .param("l", lawd).param("y", ymd).param("s", status)
                .param("f", fetchedAt == null ? null : fetchedAt.atOffset(ZoneOffset.UTC)).param("r", reason)
                .update();
    }

    /** TradeFixtures.item(99999, ymd, aptSeq)와 같은 키(15일, 7층, 84.97, 해제 아님)의 거래 행. */
    private void insertTrade(String ymd, String aptSeq, long amount, int count, Instant firstSeenAt) {
        fixture.jdbc().sql("""
                insert into complex (id, apt_seq, name, sgg_cd, umd_nm, jibun)
                values (nextval('complex_id_seq'), :a, '가상단지', :l, '가상동', '123')
                on conflict (apt_seq) do nothing""")
                .param("a", aptSeq).param("l", LAWD).update();
        fixture.jdbc().sql("""
                insert into trade (id, complex_id, deal_date, floor, exclusive_area, amount, canceled,
                    same_key_count, first_seen_at)
                select nextval('trade_id_seq'), id, :d, 7, 84.97, :amt, false, :c, cast(:f as timestamptz)
                from complex where apt_seq = :a""")
                .param("d", LocalDate.of(Integer.parseInt(ymd.substring(0, 4)), Integer.parseInt(ymd.substring(4)), 15))
                .param("amt", amount).param("c", count).param("f", firstSeenAt.atOffset(ZoneOffset.UTC))
                .param("a", aptSeq).update();
    }

    record RangeRow(String status, Instant fetchedAt, String reason) {
    }

    private Map<String, RangeRow> ranges(String lawd) {
        Map<String, RangeRow> rows = new LinkedHashMap<>();
        fixture.jdbc().sql("""
                select deal_ymd, status, fetched_at, failure_reason from fetch_range
                where lawd_cd = :l order by deal_ymd""").param("l", lawd)
                .query((rs, n) -> rows.put(rs.getString(1), new RangeRow(rs.getString(2), instant(rs, 3), rs.getString(4))))
                .list();
        return rows;
    }

    private List<String> complexes() {
        return fixture.jdbc().sql("select apt_seq, name, sgg_cd, umd_nm, jibun from complex order by apt_seq")
                .query((rs, n) -> String.join("|", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .list();
    }

    record TradeRow(long id, String aptSeq, LocalDate dealDate, long amount, int count, Instant firstSeenAt) {
    }

    private List<TradeRow> trades() {
        return fixture.jdbc().sql("""
                select t.id, c.apt_seq, t.deal_date, t.amount, t.same_key_count, t.first_seen_at
                from trade t join complex c on c.id = t.complex_id order by t.deal_date, c.apt_seq, t.amount""")
                .query((rs, n) -> new TradeRow(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getLong(4), rs.getInt(5), instant(rs, 6)))
                .list();
    }

    private TradeRow singleTrade() {
        List<TradeRow> trades = trades();
        assertThat(trades).hasSize(1);
        return trades.get(0);
    }

    record HistoryRow(long tradeId, String type, Instant observedAt) {
    }

    private List<HistoryRow> history() {
        return fixture.jdbc().sql("select trade_id, change_type, observed_at from trade_history order by id")
                .query((rs, n) -> new HistoryRow(rs.getLong(1), rs.getString(2), instant(rs, 3))).list();
    }

    private long historyCount() {
        return fixture.jdbc().sql("select count(*) from trade_history").query(Long.class).single();
    }

    private byte[] saved(String key) {
        return fixture.rawStore().saved().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().body();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
