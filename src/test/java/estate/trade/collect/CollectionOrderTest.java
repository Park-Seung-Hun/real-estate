package estate.trade.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
 * T032 순서(research R10의 순서 넷): 원본 저장 뒤 비교, 재수집 기간의 달 먼저 그다음 실패한 범위(spec US2 경우 23, FR-004),
 * 매일 수집 뒤 초기 적재(FR-003), 선기록 뒤 수집(spec US1-3, FR-005). 값은 모두 가짜다.
 */
@CollectorIntegrationTest
class CollectionOrderTest {

    private static final String LAWD = TradeFixtures.LAWD_CD;
    private static final String OTHER = TradeFixtures.OTHER_LAWD_CD;
    /** 한국 시간 2026-10-05 09:00. 이번 달 202610, 재수집 기간 202605~202610. */
    private static final Instant T0 = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant YESTERDAY = T0.minus(Duration.ofDays(1));
    private static final List<String> WINDOW = List.of("202605", "202606", "202607", "202608", "202609", "202610");
    private static final String FAIL_TRIGGER = "collection_order_test_fail_prerecord";

    @Autowired
    CollectorFixture fixture;
    @Autowired
    MutableClock clock;
    @Autowired
    CollectionRun run;

    @BeforeEach
    void setUp() {
        dropTrigger();
        fixture.reset();
        clock.freezeAt(T0);
    }

    @AfterEach
    void tearDown() {
        dropTrigger();
        fixture.reset();
    }

    @Test
    @DisplayName("원본 저장 뒤 비교: 범위마다 원본을 저장하는 순간 그 범위는 아직 반영 전이고, 모든 페이지를 받은 뒤다")
    void rawSavedBeforeRangeApplied() {
        everyMonthReturns("99999-1");
        Item[] thousand = Collections.nCopies(1000, TradeFixtures.item(LAWD, "202608", "99999-2")).toArray(Item[]::new);
        fixture.server().enqueue(LAWD, "202608", 1, Reply.body(TradeFixtures.response(1001, 1, 1000, thousand)));
        fixture.server().enqueue(LAWD, "202608", 2, Reply.body(
                TradeFixtures.response(1001, 2, 1000, TradeFixtures.item(LAWD, "202608", "99999-1"))));
        // 저장을 시도하는 순간의 그 범위 상태(fetch_range와 거래 행 수), 그 범위 2쪽의 요청 수를 기록한다
        Map<String, String> atSave = new ConcurrentHashMap<>();
        fixture.rawStore().onSave(key -> {
            String ymd = key.substring(key.lastIndexOf('/') + 7, key.lastIndexOf('/') + 13);
            RangeRow row = ranges(LAWD).get(ymd);
            atSave.put(key, row.status() + "|" + row.fetchedAt() + "|trades=" + tradeCount(ymd)
                    + "|p2=" + fixture.server().requestCount(LAWD, ymd, 2));
        });

        run.run(List.of(LAWD));

        assertThat(atSave).hasSize(61);
        // 202608은 1쪽 원본을 저장할 때 이미 2쪽까지 받았다(모든 페이지를 수집한 뒤 저장)
        assertThat(atSave).allSatisfy((key, state) -> assertThat(state).as(key)
                .isEqualTo(key.contains("-202608-") ? "실패|null|trades=0|p2=1" : "실패|null|trades=0|p2=0"));
        // 반영은 일어났다(저장 순간의 상태와 달라짐)
        Map<String, RangeRow> after = ranges(LAWD);
        assertThat(after).hasSize(60);
        assertThat(after.values()).allSatisfy(r -> assertThat(r).isEqualTo(new RangeRow("성공", T0, null)));
        assertThat(tradeCount("202608")).isEqualTo(2);
    }

    @Test
    @DisplayName("원본 저장 뒤 비교, FR-010: 원본 저장이 실패한 범위는 비교하지 않아 거래가 그대로다")
    void rawSaveFailureLeavesTradesUnchanged() {
        WINDOW.forEach(m -> insertRange(LAWD, m, "성공", YESTERDAY, null));
        // 202608과 202609 모두 어제 A(1), 오늘 A 대신 B(1). 저장은 202609만 실패시킨다
        insertTrade("202608", 98500, 1, YESTERDAY);
        insertTrade("202609", 98500, 1, YESTERDAY);
        for (String m : List.of("202608", "202609")) {
            fixture.server().enqueue(LAWD, m, 1, Reply.body(TradeFixtures.response(
                    TradeFixtures.item(LAWD, m, "99999-1").with("dealAmount", "99,000"))));
        }
        fixture.rawStore().failOnKey(key -> key.endsWith("/99999-202609-p1.xml.gz"));

        run.run(List.of(LAWD));

        RangeRow failed = ranges(LAWD).get("202609");
        assertThat(failed.status()).isEqualTo("실패");
        assertThat(failed.reason()).startsWith("RAW_SAVE_FAILED");
        assertThat(failed.fetchedAt()).isEqualTo(YESTERDAY);
        assertThat(trades("202609")).containsExactly("98500 count=1");
        // 같은 실행의 202608은 저장에 성공해 비교했다(A 사라짐, B 신규)
        assertThat(ranges(LAWD).get("202608")).isEqualTo(new RangeRow("성공", T0, null));
        assertThat(trades("202608")).containsExactly("98500 count=0", "99000 count=1");
        assertThat(historyTypes()).containsExactlyInAnyOrder("202608 98500 사라짐", "202608 99000 신규");
    }

    @Test
    @DisplayName("US2 경우 23, FR-004: 재수집 기간의 달을 오래된 달부터 모두 요청한 뒤 실패한 범위를 요청한다")
    void refetchWindowBeforeFailedRanges() {
        WINDOW.forEach(m -> insertRange(LAWD, m, "성공", YESTERDAY, null));
        // 재수집 기간 안의 실패(202607)는 한 번만, 기간 밖의 성공(202604)은 요청하지 않는다
        insertRange(LAWD, "202607", "실패", YESTERDAY.minus(Duration.ofDays(1)), "HTTP_ERROR: 503");
        insertRange(LAWD, "202604", "성공", YESTERDAY, null);
        insertRange(LAWD, "202512", "실패", YESTERDAY.minus(Duration.ofDays(2)), "HTTP_ERROR: 500");
        insertRange(LAWD, "202201", "실패", null, "NOT_YET_COLLECTED");

        run.run(List.of(LAWD));

        List<String> order = fixture.server().requests().stream().map(Request::dealYmd).toList();
        assertThat(order).hasSize(8);
        assertThat(order.subList(0, 6)).containsExactlyElementsOf(WINDOW);
        assertThat(order.subList(6, 8)).containsExactlyInAnyOrder("202201", "202512");
        assertThat(ranges(LAWD).get("202512").status()).isEqualTo("성공");
        assertThat(ranges(LAWD).get("202201").status()).isEqualTo("성공");
    }

    @Test
    @DisplayName("FR-003, US1-1: 매일 수집의 요청이 모두 끝난 뒤 행 없는 지역의 초기 적재를 요청한다")
    void dailyCollectionBeforeInitialLoad() {
        WINDOW.forEach(m -> insertRange(LAWD, m, "성공", YESTERDAY, null));

        run.run(List.of(LAWD, OTHER));

        List<Request> requests = fixture.server().requests();
        assertThat(requests).hasSize(66);
        List<Request> daily = requests.stream().filter(r -> r.lawdCd().equals(LAWD)).toList();
        List<Request> load = requests.stream().filter(r -> r.lawdCd().equals(OTHER)).toList();
        assertThat(daily.stream().map(Request::dealYmd)).containsExactlyElementsOf(WINDOW);
        assertThat(load.stream().map(Request::dealYmd))
                .containsExactlyInAnyOrderElementsOf(CollectionCalendar.initialLoadMonths(T0));
        assertThat(daily.getLast().sequence()).isLessThan(load.getFirst().sequence());
    }

    @Test
    @DisplayName("research R10, FR-008: 매일 수집이 코드 22로 멈추면 같은 예약 실행의 초기 적재를 요청하지 않는다")
    void dailyLimitSkipsInitialLoad() {
        WINDOW.forEach(m -> insertRange(LAWD, m, "성공", YESTERDAY, null));
        fixture.server().enqueue(LAWD, "202607", 1,
                Reply.body(TradeFixtures.resultError("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR")));

        run.run(List.of(LAWD, OTHER));

        assertThat(fixture.server().requests().stream().map(Request::dealYmd))
                .containsExactly("202605", "202606", "202607");
        assertThat(fixture.server().requests()).allSatisfy(r -> assertThat(r.lawdCd()).isEqualTo(LAWD));
        assertThat(ranges(LAWD).get("202607").reason()).startsWith("DAILY_LIMIT");
        // 99998은 행이 없는 채로 남아 다음 예약 실행에서 다시 초기 적재 대상이 된다
        assertThat(ranges(OTHER)).isEmpty();
    }

    @Test
    @DisplayName("US1-3, FR-005: 초기 적재의 첫 요청을 받는 순간 그 지역 60개 범위가 실패, 아직 수집하지 않음으로 있다")
    void prerecordBeforeFirstRequest() {
        AtomicReference<Map<String, RangeRow>> atFirstRequest = new AtomicReference<>();
        fixture.server().onRequest(r -> {
            if (r.sequence() == 1) {
                atFirstRequest.set(ranges(LAWD));
            }
        });

        run.run(List.of(LAWD));

        Map<String, RangeRow> rows = atFirstRequest.get();
        assertThat(rows).as("첫 요청 때 읽은 fetch_range").isNotNull();
        assertThat(rows.keySet()).containsExactlyInAnyOrderElementsOf(CollectionCalendar.initialLoadMonths(T0));
        assertThat(rows.values()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("실패");
            assertThat(r.fetchedAt()).isNull();
            assertThat(r.reason()).startsWith("NOT_YET_COLLECTED");
        });
        assertThat(ranges(LAWD).values()).allSatisfy(r -> assertThat(r.status()).isEqualTo("성공"));
    }

    @Test
    @DisplayName("US1-3: 선기록이 실패한 지역은 수집하지 않고 행도 남지 않으며, 다음 지역은 적재하고, 다음 날 다시 초기 적재한다")
    void prerecordFailureSkipsRegion() {
        fixture.jdbc().sql("""
                create function %s() returns trigger language plpgsql as $$
                begin
                    if new.lawd_cd = '%s' then
                        raise exception 'injected prerecord failure';
                    end if;
                    return new;
                end $$""".formatted(FAIL_TRIGGER, OTHER)).update();
        fixture.jdbc().sql("create trigger %1$s before insert on fetch_range for each row execute function %1$s()"
                .formatted(FAIL_TRIGGER)).update();

        run.run(List.of(LAWD, OTHER));

        assertThat(fixture.server().requests()).allSatisfy(r -> assertThat(r.lawdCd()).isEqualTo(LAWD));
        assertThat(fixture.server().requestCount()).isEqualTo(60);
        assertThat(ranges(OTHER)).isEmpty();
        assertThat(ranges(LAWD)).hasSize(60);
        assertThat(ranges(LAWD).values()).allSatisfy(r -> assertThat(r.status()).isEqualTo("성공"));

        dropTrigger();
        clock.advance(Duration.ofDays(1));
        fixture.server().reset();
        everyMonthReturns(null);

        run.run(List.of(LAWD, OTHER));

        assertThat(fixture.server().requests().stream().filter(r -> r.lawdCd().equals(OTHER)).map(Request::dealYmd))
                .containsExactlyInAnyOrderElementsOf(CollectionCalendar.initialLoadMonths(T0.plus(Duration.ofDays(1))));
        assertThat(ranges(OTHER)).hasSize(60);
    }

    @Test
    @DisplayName("research R3, R6: 실행 시작 시각은 한 번 읽는다. 시계가 흘러도 매일 수집과 초기 적재의 원본 키가 같은 시각이다")
    void oneRunStartForAllRanges() {
        WINDOW.forEach(m -> insertRange(LAWD, m, "성공", YESTERDAY, null));
        fixture.server().onRequest(r -> clock.advance(Duration.ofMinutes(1)));

        run.run(List.of(LAWD, OTHER));

        assertThat(fixture.rawStore().savedKeys()).hasSize(66)
                .allSatisfy(key -> assertThat(key).startsWith("2026-10-05/090000/"));
        assertThat(clock.instant()).as("전제: 실행 중에 시계가 흘렀다").isEqualTo(T0.plus(Duration.ofMinutes(66)));
    }

    @Test
    @DisplayName("research R7: 실패 기록 자체가 실패하면 이유를 APPLY_FAILED로 바꿔 이어 가지 않고 실행이 끝난다")
    void failureRecordErrorEndsRun() {
        WINDOW.forEach(m -> insertRange(LAWD, m, "성공", YESTERDAY, null));
        insertTrade("202607", 98500, 1, YESTERDAY);
        fixture.jdbc().sql("""
                create function %s() returns trigger language plpgsql as $$
                begin
                    if new.failure_reason = 'EMPTY_AFTER_TRADES' then
                        raise exception 'injected record failure';
                    end if;
                    return new;
                end $$""".formatted(FAIL_TRIGGER)).update();
        fixture.jdbc().sql("create trigger %1$s before insert on fetch_range for each row execute function %1$s()"
                .formatted(FAIL_TRIGGER)).update();

        assertThatThrownBy(() -> run.run(List.of(LAWD))).hasStackTraceContaining("injected record failure");

        assertThat(fixture.server().requests().stream().map(Request::dealYmd))
                .containsExactly("202605", "202606", "202607");
        assertThat(ranges(LAWD).get("202607")).isEqualTo(new RangeRow("성공", YESTERDAY, null));
    }

    // ---- 도우미 ----

    /** 지정하지 않은 범위의 응답. aptSeq가 있으면 그 지역, 그 달 15일의 이 단지 거래 한 건, null이면 0건. */
    private void everyMonthReturns(String aptSeq) {
        fixture.server().fallback(r -> Reply.body(aptSeq == null ? TradeFixtures.response()
                : TradeFixtures.response(TradeFixtures.item(r.lawdCd(), r.dealYmd(), aptSeq))));
    }

    private void dropTrigger() {
        fixture.jdbc().sql("drop trigger if exists %1$s on fetch_range".formatted(FAIL_TRIGGER)).update();
        fixture.jdbc().sql("drop function if exists %s()".formatted(FAIL_TRIGGER)).update();
    }

    private void insertRange(String lawd, String ymd, String status, Instant fetchedAt, String reason) {
        fixture.jdbc().sql("""
                insert into fetch_range (lawd_cd, deal_ymd, status, fetched_at, failure_reason)
                values (:l, :y, :s, cast(:f as timestamptz), :r)
                on conflict (lawd_cd, deal_ymd) do update
                set status = :s, fetched_at = cast(:f as timestamptz), failure_reason = :r""")
                .param("l", lawd).param("y", ymd).param("s", status)
                .param("f", fetchedAt == null ? null : fetchedAt.atOffset(ZoneOffset.UTC)).param("r", reason)
                .update();
    }

    /** 단지 99999-1, 그 달 15일, 7층, 84.97(TradeFixtures.item과 같은 키)의 거래 행. */
    private void insertTrade(String ymd, long amount, int count, Instant firstSeenAt) {
        fixture.jdbc().sql("""
                insert into complex (id, apt_seq, name, sgg_cd, umd_nm, jibun)
                values (nextval('complex_id_seq'), '99999-1', '가상단지', :l, '가상동', '123')
                on conflict (apt_seq) do nothing""")
                .param("l", LAWD).update();
        fixture.jdbc().sql("""
                insert into trade (id, complex_id, deal_date, floor, exclusive_area, amount, canceled,
                    same_key_count, first_seen_at)
                select nextval('trade_id_seq'), id, :d, 7, 84.97, :amt, false, :c, cast(:f as timestamptz)
                from complex where apt_seq = '99999-1'""")
                .param("d", LocalDate.of(Integer.parseInt(ymd.substring(0, 4)), Integer.parseInt(ymd.substring(4)), 15))
                .param("amt", amount).param("c", count).param("f", firstSeenAt.atOffset(ZoneOffset.UTC)).update();
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

    private long tradeCount(String ymd) {
        return fixture.jdbc().sql("""
                select count(*) from trade t join complex c on c.id = t.complex_id
                where c.sgg_cd = :l and to_char(t.deal_date, 'YYYYMM') = :y""")
                .param("l", LAWD).param("y", ymd).query(Long.class).single();
    }

    /** 그 달의 거래 행을 "금액 count=건수"로, 금액 순. */
    private List<String> trades(String ymd) {
        return fixture.jdbc().sql("""
                select t.amount, t.same_key_count from trade t join complex c on c.id = t.complex_id
                where c.sgg_cd = :l and to_char(t.deal_date, 'YYYYMM') = :y order by t.amount""")
                .param("l", LAWD).param("y", ymd)
                .query((rs, n) -> rs.getLong(1) + " count=" + rs.getInt(2)).list();
    }

    /** 이력을 "계약년월 금액 변경 종류"로. */
    private List<String> historyTypes() {
        return fixture.jdbc().sql("""
                select to_char(t.deal_date, 'YYYYMM'), t.amount, h.change_type
                from trade_history h join trade t on t.id = h.trade_id order by h.id""")
                .query((rs, n) -> rs.getString(1) + " " + rs.getLong(2) + " " + rs.getString(3)).list();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
