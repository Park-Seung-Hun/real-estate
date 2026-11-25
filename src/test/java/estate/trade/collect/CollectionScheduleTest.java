package estate.trade.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;

import estate.support.CollectorFixture;
import estate.support.CollectorIntegrationTest;
import estate.support.FakeTradeApiServer.Reply;
import estate.support.FakeTradeApiServer.Request;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.TradeFixtures;

/**
 * T033 예약: cron 자리표시와 기본값, zone(FR-002), 지역이 없을 때, 실행 요약 로그와 실패한 범위 로그(FR-021),
 * 실행 종류(research R3). 멈춰 있던 날을 따라잡지 않는 것은 Spring 스케줄러의 동작이라 테스트하지 않는다(research R3).
 * 값은 모두 가짜다.
 */
@CollectorIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class CollectionScheduleTest {

    private static final String LAWD = TradeFixtures.LAWD_CD;
    private static final String OTHER = TradeFixtures.OTHER_LAWD_CD;
    /** 한국 시간 2026-10-05 09:00. 이번 달 202610, 재수집 기간 202605~202610. */
    private static final Instant T0 = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant YESTERDAY = T0.minus(Duration.ofDays(1));
    private static final List<String> WINDOW = List.of("202605", "202606", "202607", "202608", "202609", "202610");
    private static final String SKIP_LINE = "수집할 지역이 없어 예약 실행을 건너뜁니다";
    private static final String SUMMARY = "수집 실행 요약: ";
    private static final String NO_HISTORY = "이력={신규=0, 해제=0, 등기=0, 사라짐=0}";

    /** {@link WithoutRegions}를 돌릴 때는 바깥 인스턴스도 그 컨텍스트(가짜 서버 없음)로 주입되어 비어 있다. */
    @Autowired(required = false)
    CollectorFixture fixture;
    @Autowired
    MutableClock clock;
    @Autowired
    CollectionRun run;
    @Autowired
    ApplicationContext context;

    @BeforeEach
    void setUp() {
        if (fixture != null) {
            fixture.reset();
        }
        clock.freezeAt(T0);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.reset();
        }
    }

    @Test
    @DisplayName("FR-002: 예약 cron은 설정값 자리표시이고 기본값은 0 0 9 * * *, zone은 Asia/Seoul")
    void cronIsPlaceholderInSeoulZone() throws Exception {
        Scheduled scheduled = CollectionRun.class.getMethod("scheduledRun").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${estate.collector.cron}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");

        // 환경 변수 없이 application.yml만으로 풀면 기본값(테스트 컨텍스트의 cron은 "-"로 덮여 있다)
        assertThat(resolveWithApplicationYmlOnly(scheduled.cron())).isEqualTo("0 0 9 * * *");
        // 예약이 켜져 있어야 cron이 돈다(@EnableScheduling)
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty();
    }

    @Test
    @DisplayName("예약 메서드는 설정의 지역(99999)으로 실행한다")
    void scheduledRunUsesConfiguredRegions() {
        fixture.server().fallback(r -> Reply.body(TradeFixtures.response()));

        run.scheduledRun();

        List<Request> requests = fixture.server().requests();
        assertThat(requests).allSatisfy(r -> assertThat(r.lawdCd()).isEqualTo(LAWD));
        assertThat(requests.stream().map(Request::dealYmd))
                .containsExactlyInAnyOrderElementsOf(CollectionCalendar.initialLoadMonths(T0));
    }

    @Test
    @DisplayName("지역 목록이 비면 요청 0회이고 건너뛴다는 로그 한 줄만 남긴다")
    void emptyRegionListLogsOneLine(CapturedOutput output) {
        // 설정(99999)이나 DB의 지역을 대신 쓰면 요청이 생기게 둔다
        WINDOW.forEach(m -> insertRange(LAWD, m, "성공", YESTERDAY, null));

        run.run(List.of());

        assertThat(fixture.server().requestCount()).isZero();
        assertThat(lines(output, SKIP_LINE)).hasSize(1);
        assertThat(lines(output, SUMMARY)).isEmpty();
    }

    @Test
    @DisplayName("FR-021: 매일 수집 요약(대상, 성공, 실패, 재시도 포함 호출 수, 종류별 이력, 멈춘 이유)과 실패 범위 로그")
    void dailySummaryAndFailureLines(CapturedOutput output) {
        WINDOW.forEach(m -> insertRange(LAWD, m, "성공", YESTERDAY, null));
        // 어제 202609: A(1, 등기일 빔), B(1)
        insertTrade("202609", 98500, 1, YESTERDAY);
        insertTrade("202609", 99000, 1, YESTERDAY);
        // 오늘 202609: A(등기일 생김) -> 등기, A의 해제 키 -> 해제, 새 키 C -> 신규, B 없음 -> 사라짐
        fixture.server().enqueue(LAWD, "202609", 1, Reply.body(TradeFixtures.response(
                TradeFixtures.item(LAWD, "202609", "99999-1").with("rgstDate", "26.10.01"),
                TradeFixtures.item(LAWD, "202609", "99999-1").canceled("26.09.20"),
                TradeFixtures.item(LAWD, "202609", "99999-1").with("dealAmount", "98,000"))));
        // 202607은 세 번 모두 HTTP 503(호출 3회)
        fixture.server().enqueue(LAWD, "202607", 1, Reply.status(503));

        run.run(List.of(LAWD));

        assertThat(fixture.server().requestCount(LAWD, "202607", 1)).as("전제: 세 번 시도").isEqualTo(3);
        assertThat(historyTypes()).as("전제: 종류별 한 행").containsExactlyInAnyOrder("신규", "해제", "등기", "사라짐");
        assertThat(lines(output, SUMMARY)).singleElement().asString().contains(
                "수집 실행 요약: 종류=매일 수집, 대상 범위=6, 성공=5, 실패=1, 호출=8, "
                        + "이력={신규=1, 해제=1, 등기=1, 사라짐=1}, 멈춘 이유=없음");
        assertThat(lines(output, "수집 실패 범위: ")).singleElement().asString()
                .contains("수집 실패 범위: 지역=99999, 계약년월=202607, 이유=HTTP_ERROR: 503");
    }

    @Test
    @DisplayName("FR-021, research R3: 빈 DB는 매일 수집 요약(대상 0)과 초기 적재 요약을 남긴다")
    void initialLoadSummary(CapturedOutput output) {
        run.run(List.of(LAWD));

        assertThat(lines(output, SUMMARY)).hasSize(2);
        assertThat(lines(output, "종류=매일 수집")).singleElement().asString().contains(
                "수집 실행 요약: 종류=매일 수집, 대상 범위=0, 성공=0, 실패=0, 호출=0, " + NO_HISTORY + ", 멈춘 이유=없음");
        assertThat(lines(output, "종류=초기 적재")).singleElement().asString().contains(
                "수집 실행 요약: 종류=초기 적재, 대상 범위=60, 성공=60, 실패=0, 호출=60, " + NO_HISTORY + ", 멈춘 이유=없음");
        assertThat(lines(output, "종류=범위 확장")).isEmpty();
        assertThat(lines(output, "수집 실패 범위: ")).isEmpty();
    }

    @Test
    @DisplayName("research R3: fetch_range에 다른 지역(설정에서 뺀 지역 포함)의 행이 있으면 새 지역은 범위 확장")
    void newRegionBesideOthersIsExpansion(CapturedOutput output) {
        WINDOW.forEach(m -> insertRange(LAWD, m, "성공", YESTERDAY, null));

        run.run(List.of(OTHER));

        assertThat(fixture.server().requests()).hasSize(60).allSatisfy(r -> assertThat(r.lawdCd()).isEqualTo(OTHER));
        assertThat(lines(output, "종류=범위 확장")).singleElement().asString().contains(
                "수집 실행 요약: 종류=범위 확장, 대상 범위=60, 성공=60, 실패=0, 호출=60, " + NO_HISTORY + ", 멈춘 이유=없음");
        assertThat(lines(output, "종류=초기 적재")).isEmpty();
    }

    @Test
    @DisplayName("research R3: 빈 DB에 두 지역이면 먼저 적재한 지역은 초기 적재, 다음 지역은 범위 확장")
    void twoNewRegionsInitialThenExpansion(CapturedOutput output) {
        run.run(List.of(LAWD, OTHER));

        assertThat(fixture.server().requestCount()).isEqualTo(120);
        assertThat(lines(output, "종류=초기 적재")).hasSize(1);
        assertThat(lines(output, "종류=범위 확장")).hasSize(1);
        assertThat(lines(output, SUMMARY)).hasSize(3);
    }

    @Test
    @DisplayName("FR-021: 멈춘 실행의 요약은 멈춘 이유를, 실패 범위 로그는 멈춘 범위와 시도하지 않은 범위를 적는다")
    void stoppedRunSummaryHasReason(CapturedOutput output) {
        WINDOW.forEach(m -> insertRange(LAWD, m, "성공", YESTERDAY, null));
        fixture.server().enqueue(LAWD, "202607", 1,
                Reply.body(TradeFixtures.resultError("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR")));

        run.run(List.of(LAWD));

        assertThat(lines(output, SUMMARY)).singleElement().asString().contains(
                "수집 실행 요약: 종류=매일 수집, 대상 범위=6, 성공=2, 실패=4, 호출=3, " + NO_HISTORY
                        + ", 멈춘 이유=DAILY_LIMIT");
        List<String> failures = lines(output, "수집 실패 범위: ");
        assertThat(failures).hasSize(4);
        assertThat(failures).anySatisfy(l -> assertThat(l)
                .contains("수집 실패 범위: 지역=99999, 계약년월=202607, 이유=DAILY_LIMIT"));
        for (String m : List.of("202608", "202609", "202610")) {
            assertThat(failures).anySatisfy(l -> assertThat(l)
                    .contains("수집 실패 범위: 지역=99999, 계약년월=" + m + ", 이유=RUN_STOPPED: DAILY_LIMIT"));
        }
    }

    /** 지역 설정이 없는 컨텍스트(collector를 쓰지 않는 001, 002 테스트와 같은 컨텍스트, 가짜 서버 빈 없음). */
    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @LoginIntegrationTest
    class WithoutRegions {

        @Autowired
        CollectionRun noRegionRun;
        @Autowired
        Environment environment;

        @Test
        @DisplayName("지역을 설정하지 않으면 예약 실행은 예외 없이 건너뛴다는 로그 한 줄만 남긴다")
        void scheduledRunLogsSkipOnce(CapturedOutput output) {
            assertThat(environment.getProperty("estate.collector.regions")).as("전제: 지역 설정 없음").isNullOrEmpty();

            assertThatCode(noRegionRun::scheduledRun).doesNotThrowAnyException();

            assertThat(lines(output, SKIP_LINE)).hasSize(1);
            assertThat(lines(output, SUMMARY)).isEmpty();
        }
    }

    // ---- 도우미 ----

    private static List<String> lines(CapturedOutput output, String fragment) {
        return output.getOut().lines().filter(l -> l.contains(fragment)).toList();
    }

    private static String resolveWithApplicationYmlOnly(String text) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources).resolvePlaceholders(text);
    }

    private void insertRange(String lawd, String ymd, String status, Instant fetchedAt, String reason) {
        fixture.jdbc().sql("""
                insert into fetch_range (lawd_cd, deal_ymd, status, fetched_at, failure_reason)
                values (:l, :y, :s, cast(:f as timestamptz), :r)""")
                .param("l", lawd).param("y", ymd).param("s", status)
                .param("f", fetchedAt == null ? null : fetchedAt.atOffset(ZoneOffset.UTC)).param("r", reason)
                .update();
    }

    /** 단지 99999-1, 그 달 15일, 7층, 84.97(TradeFixtures.item과 같은 키), 등기일과 동은 빈 거래 행. */
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

    private List<String> historyTypes() {
        return fixture.jdbc().sql("select change_type from trade_history").query(String.class).list();
    }
}
