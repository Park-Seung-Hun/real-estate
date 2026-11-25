package estate.trade.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

import estate.support.MutableClock;
import estate.trade.api.TradeItem;
import estate.trade.detect.RunSnapshot.Range;
import estate.trade.detect.RunSnapshot.Row;

/**
 * 변경 감지 테스트 셋(ChangeDetectionTest, ChangeDetectionRulesTest, ContinuityTest)이 같이 쓰는 도우미.
 * 오직 RangeApplier, 스냅숏 타입, JdbcClient만 쓴다(FetchRangeStore 같은 다른 구현 클래스는 쓰지 않는다).
 * 값은 모두 가짜다(시군구 코드 99999, 단지 "가상단지").
 */
final class DetectionDb {

    static final String LAWD = "99999";
    static final String OTHER_LAWD = "99998";
    static final String YMD = "202609";
    static final String APT_SEQ = "99999-1";
    static final BigDecimal AREA = new BigDecimal("84.9");

    private final JdbcClient jdbc;
    private final MutableClock clock;
    private final RangeApplier applier;

    DetectionDb(JdbcClient jdbc, MutableClock clock, RangeApplier applier) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.applier = applier;
    }

    // ---- 응답 항목 ----

    /** 키 A(98500), B(99000), C(98000)는 금액만 다른 같은 단지, 같은 계약일, 같은 층, 같은 면적의 거래다. */
    static TradeItem item(long amount) {
        return item(YMD, amount);
    }

    /** 계약년월 달 15일의 거래. */
    static TradeItem item(String ymd, long amount) {
        LocalDate dealDate = LocalDate.of(Integer.parseInt(ymd.substring(0, 4)), Integer.parseInt(ymd.substring(4)), 15);
        return new TradeItem(APT_SEQ, "가상단지", LAWD, "가상동", "123", dealDate, 7, AREA, amount,
                false, null, null, null);
    }

    static final TradeItem A = item(98500);
    static final TradeItem B = item(99000);
    static final TradeItem C = item(98000);

    /** 해제 키: 같은 값에 canceled=true와 해제 사유 발생일. */
    static TradeItem canceled(TradeItem i) {
        return canceled(i, i.dealDate().plusDays(5));
    }

    static TradeItem canceled(TradeItem i, LocalDate canceledOn) {
        return new TradeItem(i.aptSeq(), i.aptName(), i.sggCd(), i.umdName(), i.jibun(), i.dealDate(), i.floor(),
                i.exclusiveArea(), i.amount(), true, canceledOn, i.registeredOn(), i.dong());
    }

    static TradeItem registered(TradeItem i, LocalDate registeredOn) {
        return new TradeItem(i.aptSeq(), i.aptName(), i.sggCd(), i.umdName(), i.jibun(), i.dealDate(), i.floor(),
                i.exclusiveArea(), i.amount(), i.canceled(), i.canceledOn(), registeredOn, i.dong());
    }

    static TradeItem dong(TradeItem i, String dong) {
        return new TradeItem(i.aptSeq(), i.aptName(), i.sggCd(), i.umdName(), i.jibun(), i.dealDate(), i.floor(),
                i.exclusiveArea(), i.amount(), i.canceled(), i.canceledOn(), i.registeredOn(), dong);
    }

    static TradeItem area(TradeItem i, String area) {
        return new TradeItem(i.aptSeq(), i.aptName(), i.sggCd(), i.umdName(), i.jibun(), i.dealDate(), i.floor(),
                new BigDecimal(area), i.amount(), i.canceled(), i.canceledOn(), i.registeredOn(), i.dong());
    }

    /** 같은 항목 n개(건수 n). */
    static List<TradeItem> times(TradeItem i, int n) {
        return java.util.Collections.nCopies(n, i);
    }

    /** 항목({@link TradeItem})과 목록({@code List<TradeItem>})을 이어 한 응답으로 만든다. */
    @SuppressWarnings("unchecked")
    static List<TradeItem> all(Object... parts) {
        List<TradeItem> result = new ArrayList<>();
        for (Object p : parts) {
            if (p instanceof TradeItem t) {
                result.add(t);
            } else {
                result.addAll((List<TradeItem>) p);
            }
        }
        return result;
    }

    // ---- 스냅숏과 반영 ----

    static Range range(String ymd) {
        return new Range(LAWD, ymd);
    }

    /** 반영 전의 fetch_range를 읽어 만든 실행 스냅숏(행만 있고 성공한 적 없으면 fetchedAt이 null). */
    RunSnapshot snapshot(Instant runStartedAt) {
        Map<Range, Row> rows = new HashMap<>();
        jdbc.sql("select lawd_cd, deal_ymd, fetched_at from fetch_range").query((rs, n) -> {
            rows.put(new Range(rs.getString(1), rs.getString(2)), new Row(instant(rs, 3)));
            return null;
        }).list();
        return new RunSnapshot(runStartedAt, rows);
    }

    /** 시계를 옮기지 않고 주어진 스냅숏으로 반영한다(수집 시각은 지금 시계 값). */
    ApplyResult apply(String ymd, List<TradeItem> items, RunSnapshot snapshot) {
        return applier.apply(new RangeInput(LAWD, ymd, items, snapshot));
    }

    /** 시계를 at에 멈추고, 그 시각을 실행 시작으로 하는 스냅숏(DB의 지금 값)으로 반영한다. */
    ApplyResult applyAt(Instant at, String ymd, List<TradeItem> items) {
        clock.freezeAt(at);
        return apply(ymd, items, snapshot(at));
    }

    ApplyResult applyAt(Instant at, List<TradeItem> items) {
        return applyAt(at, YMD, items);
    }

    /** 선기록(실패, fetched_at 빔) 위에 기준 적재로 어제 결과를 만들어 둔다. 기준 적재가 아니면 전제가 틀린 것이다. */
    void baselineAt(Instant at, String ymd, List<TradeItem> items) {
        jdbc.sql("""
                insert into fetch_range (lawd_cd, deal_ymd, status, fetched_at, failure_reason)
                values (:l, :y, '실패', null, 'NOT_YET_COLLECTED') on conflict do nothing""")
                .param("l", LAWD).param("y", ymd).update();
        assertThat(applyAt(at, ymd, items).baseline()).as("전제: 기준 적재로 만든 결과").isTrue();
    }

    void baselineAt(Instant at, List<TradeItem> items) {
        baselineAt(at, YMD, items);
    }

    // ---- fetch_range 직접 만들기와 읽기 ----

    void insertRange(String lawd, String ymd, String status, Instant fetchedAt, String failureReason) {
        jdbc.sql("""
                insert into fetch_range (lawd_cd, deal_ymd, status, fetched_at, failure_reason)
                values (:l, :y, :s, cast(:f as timestamptz), :r)
                on conflict (lawd_cd, deal_ymd) do update
                set status = :s, fetched_at = cast(:f as timestamptz), failure_reason = :r""")
                .param("l", lawd).param("y", ymd).param("s", status)
                .param("f", fetchedAt == null ? null : fetchedAt.atOffset(ZoneOffset.UTC))
                .param("r", failureReason).update();
    }

    void insertRange(String ymd, String status, Instant fetchedAt, String failureReason) {
        insertRange(LAWD, ymd, status, fetchedAt, failureReason);
    }

    void setStatus(String ymd, String status) {
        assertThat(jdbc.sql("update fetch_range set status = :s where lawd_cd = :l and deal_ymd = :y")
                .param("s", status).param("l", LAWD).param("y", ymd).update()).isEqualTo(1);
    }

    record RangeRow(String status, Instant fetchedAt, String failureReason) {
    }

    RangeRow fetchRange(String ymd) {
        return jdbc.sql("select status, fetched_at, failure_reason from fetch_range where lawd_cd = :l and deal_ymd = :y")
                .param("l", LAWD).param("y", ymd)
                .query((rs, n) -> new RangeRow(rs.getString(1), instant(rs, 2), rs.getString(3))).optional().orElse(null);
    }

    /** FR-019: 성공한 반영 뒤 fetch_range가 성공이고 fetched_at이 그 반영의 수집 시각이며 failure_reason이 비어 있다. */
    void assertSucceeded(String ymd, Instant collectedAt) {
        assertThat(fetchRange(ymd)).isEqualTo(new RangeRow("성공", collectedAt, null));
    }

    // ---- 거래와 이력 읽기 ----

    record Trade(long id, LocalDate dealDate, long amount, boolean canceled, int count, LocalDate cancelDate,
                 LocalDate registryDate, String dong, Instant firstSeenAt) {
    }

    record History(long id, long tradeId, String type, Instant observedAt) {
    }

    List<Trade> trades() {
        return jdbc.sql("""
                select id, deal_date, amount, canceled, same_key_count, cancel_date, registry_date, apt_dong, first_seen_at
                from trade order by deal_date, amount, canceled""")
                .query((rs, n) -> new Trade(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getLong(3), rs.getBoolean(4),
                        rs.getInt(5), rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class),
                        rs.getString(8), instant(rs, 9))).list();
    }

    /** 금액과 해제 여부로 거래 행 하나를 찾는다(없거나 둘 이상이면 실패). 여러 달이면 {@link #trade(String, long, boolean)}. */
    Trade trade(long amount, boolean canceled) {
        List<Trade> found = trades().stream().filter(t -> t.amount() == amount && t.canceled() == canceled).toList();
        assertThat(found).as("금액 %d, 해제 %s인 거래 행", amount, canceled).hasSize(1);
        return found.get(0);
    }

    Trade trade(String ymd, long amount, boolean canceled) {
        String prefix = ymd.substring(0, 4) + "-" + ymd.substring(4);
        List<Trade> found = trades().stream().filter(t -> t.amount() == amount && t.canceled() == canceled
                && t.dealDate().toString().startsWith(prefix)).toList();
        assertThat(found).as("%s 달 금액 %d, 해제 %s인 거래 행", ymd, amount, canceled).hasSize(1);
        return found.get(0);
    }

    List<History> history() {
        return jdbc.sql("select id, trade_id, change_type, observed_at from trade_history order by id")
                .query((rs, n) -> new History(rs.getLong(1), rs.getLong(2), rs.getString(3), instant(rs, 4))).list();
    }

    List<History> historyOf(Trade trade) {
        return history().stream().filter(h -> h.tradeId() == trade.id()).toList();
    }

    List<History> historyAt(Instant observedAt) {
        return history().stream().filter(h -> h.observedAt().equals(observedAt)).toList();
    }

    static List<String> types(List<History> history) {
        return history.stream().map(History::type).sorted().toList();
    }

    /** 순서 테스트 비교용: 거래 행과 이력(거래 id 대신 달, 금액으로 적음)을 정렬한 글자 목록. */
    List<String> dump() {
        Map<Long, Trade> byId = new HashMap<>();
        trades().forEach(t -> byId.put(t.id(), t));
        List<String> lines = new ArrayList<>();
        byId.values().forEach(t -> lines.add("trade %s %d %s count=%d first=%s".formatted(
                t.dealDate(), t.amount(), t.canceled(), t.count(), t.firstSeenAt())));
        history().forEach(h -> {
            Trade t = byId.get(h.tradeId());
            lines.add("history %s %d %s at=%s".formatted(t.dealDate(), t.amount(), h.type(), h.observedAt()));
        });
        return lines.stream().sorted().toList();
    }

    // ---- 정리 ----

    /** 같은 데이터베이스를 다른 통합 테스트가 같이 쓰므로, 외래 키 순서로 지운다. @BeforeEach와 @AfterEach에서 부른다. */
    void clean() {
        jdbc.sql("delete from trade_history").update();
        jdbc.sql("delete from trade").update();
        jdbc.sql("delete from complex").update();
        jdbc.sql("delete from fetch_range").update();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
