package estate.trade.detect;

import static estate.support.TradeFixtures.LAWD_CD;
import static estate.support.TradeFixtures.item;
import static estate.support.TradeFixtures.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import estate.support.CollectorFixture;
import estate.support.CollectorIntegrationTest;
import estate.support.FakeTradeApiServer;
import estate.support.FakeTradeApiServer.Reply;
import estate.support.MutableClock;
import estate.support.TradeFixtures.Item;
import estate.trade.collect.CollectionRun;
import estate.trade.raw.RawObjectKey;

/**
 * T038 반영 롤백(spec US3-6, FR-012, research R7). 실패 기록은 범위 수집이 하므로 {@link CollectionRun#run}으로 돌린다.
 *
 * <p>어제(DAY0) 초기 적재로 202609에 A(단지 99999-1, 건수 1)를 기준 적재하고, 오늘(DAY1) 매일 수집에서 202609가
 * A 두 건과 처음 보는 단지 99999-2의 거래 N을 돌려준다. 202609는 fetched_at이 7일 안이라 비교 범위이고, 반영하면
 * 새 단지, 새 거래 N, A 건수 2, 이력 둘(A 신규, N 신규), 성공 기록이 생긴다.
 *
 * <p>테스트가 만든 트리거는 202609의 {@code 성공} 기록에 걸려 그 순간 위의 네 쓰기가 이미 보이는지 확인하고,
 * 모두 보이면 {@code <접두어>_all_visible}, 하나라도 안 보이면 {@code <접두어>_missing_...}을 제약 이름으로 던진다.
 * 그래서 실패 이유에 담긴 이름으로 "성공 기록이 범위 반영의 마지막 쓰기"인지가 드러난다. 값은 모두 가짜다.
 */
@CollectorIntegrationTest
class ApplyRollbackTest {

    static final String X = LAWD_CD;
    static final String YMD = "202609";
    /** 한국 2026-10-04 09:00. 초기 적재 202111~202610. */
    static final Instant DAY0 = Instant.parse("2026-10-04T00:00:00Z");
    /** 재수집 기간 202605~202610. */
    static final Instant DAY1 = DAY0.plus(Duration.ofDays(1));
    static final String PROBE = "apply_rollback_probe";

    @Autowired
    CollectionRun collectionRun;
    @Autowired
    CollectorFixture fixture;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbcTemplate;

    FakeTradeApiServer server;
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        server = fixture.server();
        jdbc = fixture.jdbc();
        dropProbe();
        fixture.reset();
    }

    @AfterEach
    void tearDown() {
        dropProbe();
        fixture.reset();
    }

    @Test
    @DisplayName("US3-6: 성공 기록(마지막 쓰기)이 실패하면 새 단지, 새 거래, 이력, 건수, 성공 기록이 모두 롤백되고 APPLY_FAILED만 남음")
    void rollsBackWhenSuccessWriteFails() {
        assertSameInputSucceedsWithoutTrigger();

        fixture.reset();
        List<String> before = prepareYesterday();
        createProbe("""
                create trigger %1$s after insert or update on fetch_range for each row
                when (new.lawd_cd = '99999' and new.deal_ymd = '202609' and new.status = '성공')
                execute function %1$s('immediate_probe')""");

        assertThatCode(this::runToday).doesNotThrowAnyException();

        assertRolledBack(before, "immediate_probe");
    }

    @Test
    @DisplayName("US3-6, research R7: 커밋 때에만 나는 오류(DEFERRABLE INITIALLY DEFERRED)도 롤백되고 APPLY_FAILED만 남음")
    void rollsBackOnCommitTimeError() {
        assertSameInputSucceedsWithoutTrigger();

        fixture.reset();
        List<String> before = prepareYesterday();
        createProbe("""
                create constraint trigger %1$s after insert or update on fetch_range
                deferrable initially deferred for each row
                when (new.lawd_cd = '99999' and new.deal_ymd = '202609' and new.status = '성공')
                execute function %1$s('commit_probe')""");

        assertThatCode(this::runToday).doesNotThrowAnyException();

        assertRolledBack(before, "commit_probe");
    }

    /** 롤백을 보기 전에, 트리거 없이 같은 입력이 성공하고 네 쓰기가 모두 남는 것을 먼저 확인한다. */
    private void assertSameInputSucceedsWithoutTrigger() {
        prepareYesterday();

        runToday();

        assertThat(range(YMD)).isEqualTo(new RangeRow("성공", DAY1, null));
        assertThat(jdbc.sql("select count(*) from complex where apt_seq = '99999-2'").query(Long.class).single())
                .isEqualTo(1);
        assertThat(trades()).containsExactly(
                "99999-1 2026-09-15 98500 count=2 first=" + DAY0,
                "99999-2 2026-09-15 98500 count=1 first=" + DAY1);
        assertThat(histories()).containsExactly("99999-1 신규 " + DAY1, "99999-2 신규 " + DAY1);
    }

    private void assertRolledBack(List<String> before, String probePrefix) {
        RangeRow row = range(YMD);
        assertThat(row.status()).isEqualTo("실패");
        assertThat(row.fetchedAt()).as("fetched_at은 어제 그대로").isEqualTo(DAY0);
        assertThat(row.reason()).startsWith("APPLY_FAILED")
                .as("성공 기록 때 새 단지, 새 거래, 이력, 건수 갱신이 모두 이미 보였음(제약 이름)")
                .contains(probePrefix + "_all_visible");
        assertThat(state()).as("단지, 거래, 이력이 어제와 같음").isEqualTo(before);
        assertThat(jdbc.sql("select count(*) from complex where apt_seq = '99999-2'").query(Long.class).single())
                .isZero();
        assertThat(histories()).isEmpty();
        // 원본 파일은 남는다
        assertThat(fixture.rawStore().savedKeys()).contains(RawObjectKey.of(DAY1, X, YMD, 1));
        // 다른 범위는 그대로 성공
        for (String ymd : List.of("202605", "202606", "202607", "202608", "202610")) {
            assertThat(range(ymd)).as(ymd).isEqualTo(new RangeRow("성공", DAY1, null));
        }
    }

    // ---- 장면 ----

    private static Item a() {
        return item(X, YMD, "99999-1");
    }

    /** 어제 초기 적재를 하고, 오늘 응답을 준비한 뒤 시계를 오늘에 둔다. 어제 상태(단지, 거래, 이력)를 돌려준다. */
    private List<String> prepareYesterday() {
        clock.freezeAt(DAY0);
        server.enqueue(X, YMD, 1, Reply.body(response(a())));
        collectionRun.run(List.of(X));
        assertThat(range(YMD)).as("전제: 어제 기준 적재").isEqualTo(new RangeRow("성공", DAY0, null));
        assertThat(trades()).containsExactly("99999-1 2026-09-15 98500 count=1 first=" + DAY0);
        assertThat(histories()).isEmpty();

        server.reset();
        server.fallback(r -> Reply.body(response()));
        server.enqueue(X, YMD, 1, Reply.body(response(a(), a(), item(X, YMD, "99999-2"))));
        clock.freezeAt(DAY1);
        return state();
    }

    private void runToday() {
        collectionRun.run(List.of(X));
    }

    /** 트리거 함수: 성공 기록 시점에 네 쓰기가 보이는지 보고, 보인 결과를 제약 이름으로 던진다. */
    private void createProbe(String triggerSql) {
        jdbcTemplate.execute("""
                create function %1$s() returns trigger language plpgsql as $$
                declare
                    missing text = '';
                begin
                    if not exists (select 1 from complex where apt_seq = '99999-2') then
                        missing = missing || '_complex';
                    end if;
                    if not exists (select 1 from trade t join complex c on c.id = t.complex_id
                                   where c.apt_seq = '99999-2') then
                        missing = missing || '_trade';
                    end if;
                    if (select count(*) from trade_history) <> 2 then
                        missing = missing || '_history';
                    end if;
                    if not exists (select 1 from trade t join complex c on c.id = t.complex_id
                                   where c.apt_seq = '99999-1' and t.same_key_count = 2) then
                        missing = missing || '_count';
                    end if;
                    if missing = '' then
                        raise exception 'apply rollback probe'
                            using errcode = 'check_violation', constraint = tg_argv[0] || '_all_visible';
                    end if;
                    raise exception 'apply rollback probe'
                        using errcode = 'check_violation', constraint = tg_argv[0] || '_missing' || missing;
                end
                $$""".formatted(PROBE));
        jdbcTemplate.execute(triggerSql.formatted(PROBE));
    }

    private void dropProbe() {
        jdbcTemplate.execute("drop trigger if exists " + PROBE + " on fetch_range");
        jdbcTemplate.execute("drop function if exists " + PROBE + "()");
    }

    // ---- 데이터베이스 읽기 ----

    record RangeRow(String status, Instant fetchedAt, String reason) {
    }

    private RangeRow range(String ymd) {
        return jdbc.sql("select status, fetched_at, failure_reason from fetch_range where lawd_cd = :l and deal_ymd = :y")
                .param("l", X).param("y", ymd)
                .query((rs, n) -> new RangeRow(rs.getString(1), instant(rs.getObject(2, OffsetDateTime.class)),
                        rs.getString(3)))
                .optional().orElse(null);
    }

    private List<String> trades() {
        return jdbc.sql("""
                select c.apt_seq, t.deal_date, t.amount, t.same_key_count, t.first_seen_at
                from trade t join complex c on c.id = t.complex_id order by c.apt_seq, t.deal_date, t.amount""")
                .query((rs, n) -> rs.getString(1) + " " + rs.getString(2) + " " + rs.getLong(3) + " count="
                        + rs.getInt(4) + " first=" + instant(rs.getObject(5, OffsetDateTime.class)))
                .list();
    }

    private List<String> histories() {
        return jdbc.sql("""
                select c.apt_seq, h.change_type, h.observed_at from trade_history h
                join trade t on t.id = h.trade_id join complex c on c.id = t.complex_id order by c.apt_seq""")
                .query((rs, n) -> rs.getString(1) + " " + rs.getString(2) + " "
                        + instant(rs.getObject(3, OffsetDateTime.class)))
                .list();
    }

    /** 단지, 거래(모든 열), 이력 전체를 정렬한 줄로. */
    private List<String> state() {
        List<String> lines = new ArrayList<>();
        lines.addAll(jdbc.sql("select id, apt_seq, name, sgg_cd, umd_nm, jibun from complex")
                .query((rs, n) -> "complex " + rs.getLong(1) + " " + rs.getString(2) + " " + rs.getString(3) + " "
                        + rs.getString(4) + " " + rs.getString(5) + " " + rs.getString(6))
                .list());
        lines.addAll(jdbc.sql("""
                select id, complex_id, deal_date, floor, exclusive_area, amount, canceled, same_key_count, cancel_date,
                       registry_date, apt_dong, first_seen_at from trade""")
                .query((rs, n) -> {
                    StringBuilder line = new StringBuilder("trade");
                    for (int i = 1; i <= 12; i++) {
                        line.append(' ').append(rs.getString(i));
                    }
                    return line.toString();
                }).list());
        lines.addAll(jdbc.sql("select id, trade_id, change_type, observed_at from trade_history")
                .query((rs, n) -> "history " + rs.getLong(1) + " " + rs.getLong(2) + " " + rs.getString(3) + " "
                        + rs.getString(4))
                .list());
        return lines.stream().sorted().toList();
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
