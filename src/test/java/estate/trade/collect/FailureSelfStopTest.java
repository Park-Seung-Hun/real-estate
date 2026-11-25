package estate.trade.collect;

import static estate.support.TradeFixtures.LAWD_CD;
import static estate.support.TradeFixtures.OTHER_LAWD_CD;
import static estate.support.TradeFixtures.item;
import static estate.support.TradeFixtures.response;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import estate.support.CollectorFixture;
import estate.support.CollectorIntegrationTest;
import estate.support.FakeTradeApiServer;
import estate.support.FakeTradeApiServer.Reply;
import estate.support.MutableClock;
import estate.support.RecordingRawResponseStore;
import estate.support.TradeFixtures.Item;

/**
 * T037 스스로 멈춤(spec US3-4, FR-009, research R2, R8). 기준값은 10 - 4 = 6이라 실행 안에서 호출 6번까지 하고 7번째는
 * 하지 않는다. 초기 적재(60회)로 어제 결과를 만들 수 없어 SQL로 넣는다.
 *
 * <p>어제(DAY0, 한국 2026-09-30 09:00) 결과: 지역 X의 202605~202609 성공, 202601 실패(fetched_at 어제), 거래 B(202606),
 * C(202607), E(202609), A(202601). 오늘(DAY1, 한국 2026-10-01)의 매일 수집 순서는 202605~202610(202610은 행 없음),
 * 그다음 202601. 값은 모두 가짜다.
 */
@CollectorIntegrationTest
@TestPropertySource(properties = {"estate.collector.daily-call-limit=10", "estate.collector.self-stop-margin=4"})
class FailureSelfStopTest {

    static final String X = LAWD_CD;
    static final String Y = OTHER_LAWD_CD;
    static final Instant DAY0 = Instant.parse("2026-09-30T00:00:00Z");
    static final Instant DAY1 = DAY0.plus(Duration.ofDays(1));
    static final Instant DAY2 = DAY1.plus(Duration.ofDays(1));
    static final String OLD = "202601";

    @Autowired
    CollectionRun collectionRun;
    @Autowired
    CollectorFixture fixture;
    @Autowired
    MutableClock clock;

    FakeTradeApiServer server;
    RecordingRawResponseStore raw;
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        server = fixture.server();
        raw = fixture.rawStore();
        jdbc = fixture.jdbc();
        fixture.reset();
        seedYesterday();
        clock.freezeAt(DAY1);
    }

    @AfterEach
    void tearDown() {
        fixture.reset();
    }

    @Test
    @DisplayName("US3-4: 페이지를 수집하던 범위와 남은 행 있는 범위만 실패, 그 범위의 원본 없음, 행 없는 달 기록 안 함, 초기 적재 시작 안 함")
    void stopsWhilePaging() {
        List<String> julyBefore = trades("202607");
        assertThat(julyBefore).as("전제: 202607에 어제 거래 C").hasSize(1);
        ok("202606", b(), trade("202606", "99999-1", "99,000"));
        // 5쪽 범위: 앞 두 범위(호출 2번) 뒤 1~4쪽으로 호출 6번, 5쪽은 기준값에 걸린다
        for (int page = 1; page <= 5; page++) {
            server.enqueue(X, "202607", page, Reply.body(response(4500, page, 1000, c())));
        }
        ok("202609", e());

        collectionRun.run(List.of(X, Y));

        assertThat(requested()).containsExactly(X + "/202605/p1", X + "/202606/p1", X + "/202607/p1",
                X + "/202607/p2", X + "/202607/p3", X + "/202607/p4");
        assertThat(range("202607")).isEqualTo(new RangeRow("실패", DAY0, "SELF_STOP: 6"));
        for (String ymd : List.of("202608", "202609", OLD)) {
            RangeRow row = range(ymd);
            assertThat(row.status()).as(ymd).isEqualTo("실패");
            assertThat(row.fetchedAt()).as(ymd).isEqualTo(DAY0);
            assertThat(row.reason()).as(ymd).startsWith("RUN_STOPPED: SELF_STOP");
        }
        assertThat(range("202610")).isNull();
        assertThat(range("202605")).isEqualTo(new RangeRow("성공", DAY1, null));
        assertThat(range("202606")).isEqualTo(new RangeRow("성공", DAY1, null));
        assertThat(history("202606")).as("멈추기 전 범위는 반영됨").containsExactly("신규 99000 " + DAY1 + " " + DAY1);
        assertThat(trades("202607")).isEqualTo(julyBefore);
        assertThat(history(null)).hasSize(1);
        // 앞서 끝난 범위의 파일은 남고, 페이지를 수집하던 범위의 원본은 저장하지 않는다
        assertThat(raw.savedKeys()).anyMatch(k -> k.contains("/" + X + "-202606-p1."))
                .noneMatch(k -> k.contains("-202607-"));
        assertThat(server.requests()).noneMatch(r -> r.lawdCd().equals(Y));
        assertThat(rows(Y)).isEmpty();
    }

    @Test
    @DisplayName("US3-4: 재시도도 한 번으로 세고, 기준값 바로 아래 호출은 허용, 멈춘 행 없는 달은 기록 안 해 다음 날 비교")
    void retriesCountAndRowlessMonth() {
        // 202605는 500 뒤 성공(호출 2번), 202606~202609 호출 4번으로 6번. 202610은 기준값에 걸린다
        server.enqueue(X, "202605", 1, Reply.status(500), Reply.body(response()));
        ok("202606", b());
        ok("202607", c());
        ok("202609", e());

        collectionRun.run(List.of(X));

        assertThat(requested()).containsExactly(X + "/202605/p1", X + "/202605/p1", X + "/202606/p1",
                X + "/202607/p1", X + "/202608/p1", X + "/202609/p1");
        assertThat(range("202609")).isEqualTo(new RangeRow("성공", DAY1, null));
        assertThat(range("202610")).isNull();
        RangeRow old = range(OLD);
        assertThat(old.status()).isEqualTo("실패");
        assertThat(old.fetchedAt()).isEqualTo(DAY0);
        assertThat(old.reason()).startsWith("RUN_STOPPED: SELF_STOP");

        // 다음 날: 호출 6번째가 202610이라 수집되고(앞 달 202609가 7일 안에 성공) 빈 직전 결과와 비교해 신규
        server.reset();
        server.fallback(r -> Reply.body(response()));
        clock.freezeAt(DAY2);
        ok("202606", b());
        ok("202607", c());
        ok("202609", e());
        ok("202610", trade("202610", "99999-2", "98,500"));

        collectionRun.run(List.of(X));

        assertThat(server.requestCount()).isEqualTo(6);
        assertThat(range("202610")).isEqualTo(new RangeRow("성공", DAY2, null));
        assertThat(history("202610")).containsExactly("신규 98500 " + DAY2 + " " + DAY2);
        assertThat(range(OLD).reason()).startsWith("SELF_STOP");
    }

    @Test
    @DisplayName("FR-009: 호출 수는 실행마다 따로 센다. 매일 수집이 6번 호출한 뒤의 범위 확장도 6번까지 호출한다")
    void callsCountedPerExecution() {
        jdbc.sql("delete from fetch_range where deal_ymd = :y").param("y", OLD).update();
        ok("202606", b());
        ok("202607", c());
        ok("202609", e());

        collectionRun.run(List.of(X, Y));

        // 매일 수집은 기준값만큼(6번) 호출하고 멈추지 않는다
        assertThat(range("202610")).isEqualTo(new RangeRow("성공", DAY1, null));
        assertThat(server.requests()).filteredOn(r -> r.lawdCd().equals(Y)).hasSize(6);
        assertThat(rows(Y)).hasSize(60);
        assertThat(rows(Y).values()).filteredOn(r -> r.status().equals("성공")).hasSize(6);
        assertThat(rows(Y).values()).filteredOn(r -> "SELF_STOP: 6".equals(r.reason())).hasSize(1);
        assertThat(rows(Y).values()).filteredOn(r -> "RUN_STOPPED: SELF_STOP".equals(r.reason())).hasSize(53);
    }

    // ---- 장면 ----

    private static Item trade(String ymd, String aptSeq, String amount) {
        return item(X, ymd, aptSeq).with("dealAmount", amount);
    }

    private static Item b() {
        return trade("202606", "99999-1", "98,500");
    }

    private static Item c() {
        return trade("202607", "99999-1", "98,500");
    }

    private static Item e() {
        return trade("202609", "99999-1", "98,500");
    }

    private void seedYesterday() {
        for (String ymd : List.of("202605", "202606", "202607", "202608", "202609")) {
            insertRange(ymd, "성공", null);
        }
        insertRange(OLD, "실패", "HTTP_ERROR: 503");
        long complexId = jdbc.sql("""
                insert into complex (id, apt_seq, name, sgg_cd, umd_nm, jibun)
                values (nextval('complex_id_seq'), '99999-1', '가상단지', '99999', '가상동', '123') returning id""")
                .query(Long.class).single();
        for (String date : List.of("2026-06-15", "2026-07-15", "2026-09-15", "2026-01-15")) {
            jdbc.sql("""
                    insert into trade (id, complex_id, deal_date, floor, exclusive_area, amount, canceled, same_key_count,
                                       first_seen_at)
                    values (nextval('trade_id_seq'), :c, cast(:d as date), 7, 84.97, 98500, false, 1, :f)""")
                    .param("c", complexId).param("d", date).param("f", DAY0.atOffset(ZoneOffset.UTC)).update();
        }
    }

    private void insertRange(String ymd, String status, String reason) {
        jdbc.sql("""
                insert into fetch_range (lawd_cd, deal_ymd, status, fetched_at, failure_reason)
                values (:l, :y, :s, :f, :r)""")
                .param("l", X).param("y", ymd).param("s", status).param("f", DAY0.atOffset(ZoneOffset.UTC))
                .param("r", reason).update();
    }

    private void ok(String ymd, Item... items) {
        server.enqueue(X, ymd, 1, Reply.body(response(items)));
    }

    private List<String> requested() {
        return server.requests().stream().map(r -> r.lawdCd() + "/" + r.dealYmd() + "/p" + r.pageNo()).toList();
    }

    // ---- 데이터베이스 읽기 ----

    record RangeRow(String status, Instant fetchedAt, String reason) {
    }

    private RangeRow range(String ymd) {
        return rows(X).get(ymd);
    }

    private Map<String, RangeRow> rows(String lawd) {
        Map<String, RangeRow> rows = new TreeMap<>();
        jdbc.sql("select deal_ymd, status, fetched_at, failure_reason from fetch_range where lawd_cd = :l")
                .param("l", lawd)
                .query((rs, n) -> rows.put(rs.getString(1), new RangeRow(rs.getString(2),
                        instant(rs.getObject(3, OffsetDateTime.class)), rs.getString(4))))
                .list();
        return rows;
    }

    /** 이 달 거래 행(금액, 건수, 등기일, first_seen_at). */
    private List<String> trades(String ymd) {
        return jdbc.sql("""
                select t.amount, t.same_key_count, t.registry_date, t.first_seen_at from trade t
                where to_char(t.deal_date, 'YYYYMM') = :y order by t.amount""")
                .param("y", ymd)
                .query((rs, n) -> rs.getLong(1) + " count=" + rs.getInt(2) + " reg=" + rs.getString(3) + " first="
                        + instant(rs.getObject(4, OffsetDateTime.class)))
                .list();
    }

    /** "변경 종류 금액 observed_at first_seen_at". ymd가 null이면 전부. */
    private List<String> history(String ymd) {
        return jdbc.sql("""
                select h.change_type, t.amount, h.observed_at, t.first_seen_at from trade_history h
                join trade t on t.id = h.trade_id
                where cast(:y as text) is null or to_char(t.deal_date, 'YYYYMM') = cast(:y as text)
                order by h.id""")
                .param("y", ymd)
                .query((rs, n) -> rs.getString(1) + " " + rs.getLong(2) + " "
                        + instant(rs.getObject(3, OffsetDateTime.class)) + " "
                        + instant(rs.getObject(4, OffsetDateTime.class)))
                .list();
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
