package estate.trade.collect;

import static estate.support.TradeFixtures.LAWD_CD;
import static estate.support.TradeFixtures.OTHER_LAWD_CD;
import static estate.support.TradeFixtures.bytes;
import static estate.support.TradeFixtures.item;
import static estate.support.TradeFixtures.openApiError;
import static estate.support.TradeFixtures.response;
import static estate.support.TradeFixtures.resultError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import estate.support.CollectorFixture;
import estate.support.CollectorIntegrationTest;
import estate.support.FakeTradeApiServer;
import estate.support.FakeTradeApiServer.Reply;
import estate.support.MutableClock;
import estate.support.RecordingRawResponseStore;
import estate.support.TradeFixtures.Item;
import estate.trade.raw.RawObjectKey;

/**
 * T037 실패 격리(spec US3-1~5, 7, 8, SC-004, SC-005, FR-007~FR-009, research R7). 스스로 멈춤은 설정이 달라
 * {@link FailureSelfStopTest}, 반영 롤백은 {@code ApplyRollbackTest}에 있다.
 *
 * <p>공통 장면: 어제(DAY0, 한국 2026-09-30 09:00) 지역 X의 초기 적재(202110~202609)로 거래 A(202601), B(202606),
 * C(202607), D(202608), E(202609)를 기준 적재하고, 202601을 "재수집 기간 밖의 실패한 범위"(fetched_at 어제)로 바꾼다.
 * 오늘(DAY1, 한국 2026-10-01 09:00)의 매일 수집은 202605~202610(202610은 행 없음)을 오래된 달부터, 그다음 202601을
 * 수집한다. 오늘 정상 응답은 달마다 바뀐 것이 있어(신규, 등기, 사라짐, 건수 증가) 비교가 실제로 일어났는지 드러난다.
 * 값은 모두 가짜다.
 */
@CollectorIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class FailureIsolationTest {

    static final String X = LAWD_CD;
    static final String Y = OTHER_LAWD_CD;
    /** 한국 2026-09-30 09:00. 초기 적재 202110~202609. */
    static final Instant DAY0 = Instant.parse("2026-09-30T00:00:00Z");
    /** 한국 2026-10-01 09:00. 재수집 기간 202605~202610. */
    static final Instant DAY1 = DAY0.plus(Duration.ofDays(1));
    static final Instant DAY2 = DAY1.plus(Duration.ofDays(1));
    static final List<String> WINDOW = List.of("202605", "202606", "202607", "202608", "202609", "202610");
    /** 재수집 기간 밖의 달. */
    static final String OLD = "202601";
    static final String PROBE = "failure_isolation_probe";

    @Autowired
    CollectionRun collectionRun;
    @Autowired
    CollectorFixture fixture;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbcTemplate;

    FakeTradeApiServer server;
    RecordingRawResponseStore raw;
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        server = fixture.server();
        raw = fixture.rawStore();
        jdbc = fixture.jdbc();
        dropProbe();
        fixture.reset();
    }

    @AfterEach
    void tearDown() {
        dropProbe();
        fixture.reset();
    }

    // ---- US3-1 ----

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"http503", "disconnect", "readTimeout"})
    @DisplayName("US3-1: HTTP 오류와 네트워크 오류는 3번 시도 뒤 그 범위만 실패, fetched_at과 거래와 이력 그대로, 다음 범위 계속")
    void retryableErrorFailsOneRange(String kind) {
        loadYesterday();
        List<String> julyBefore = monthState("202607");
        assertThat(julyBefore).as("전제: 202607에 어제 거래 C가 있음").anyMatch(l -> l.contains(" trade "));
        todayExcept("202607");
        Reply failure = switch (kind) {
            case "http503" -> Reply.status(503);
            case "disconnect" -> Reply.disconnect();
            default -> Reply.body(response(c())).after(Duration.ofSeconds(2)); // 읽기 시간 초과 1초
        };
        server.enqueue(X, "202607", 1, failure);

        run(X);

        long attempts = server.requestCount(X, "202607", 1);
        if (kind.equals("disconnect")) {
            // JDK HttpClient는 응답 없이 끊긴 GET을 안에서 한 번 더 보낼 수 있다(research R8)
            assertThat(attempts).isBetween(3L, 6L);
        } else {
            assertThat(attempts).isEqualTo(3);
        }
        RangeRow july = range(X, "202607");
        assertThat(july.status()).isEqualTo("실패");
        assertThat(july.fetchedAt()).isEqualTo(DAY0);
        assertThat(july.reason()).startsWith(kind.equals("http503") ? "HTTP_ERROR: 503" : "NETWORK_ERROR: ");
        assertThat(monthState("202607")).isEqualTo(julyBefore);
        assertNextRangesContinued("202607");
    }

    // ---- US3-2 ----

    static Stream<Arguments> nonRetryable() {
        return Stream.of(
                Arguments.of("resultCode", resultError("99", "가상 오류"), "RESULT_CODE: 99"),
                Arguments.of("openApiResultCode", openApiError("12", "NO_OPENAPI_SERVICE_ERROR"), "RESULT_CODE: 12"),
                Arguments.of("notXml", bytes("가상 응답은 XML이 아님"), "PARSE_ERROR"),
                Arguments.of("otherSggCd", response(item(Y, "202607", "99999-1")), "PARSE_ERROR"),
                Arguments.of("otherDealYmd", response(item(X, "202606", "99999-1")), "PARSE_ERROR"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonRetryable")
    @DisplayName("US3-2: 정상이 아닌 결과 코드와 해석 실패는 요청 1번에 그 범위만 실패")
    void badResponseFailsOneRequest(String name, byte[] body, String reasonPrefix) {
        loadYesterday();
        List<String> julyBefore = monthState("202607");
        assertThat(julyBefore).as("전제: 202607에 어제 거래 C가 있음").anyMatch(l -> l.contains(" trade "));
        todayExcept("202607");
        server.enqueue(X, "202607", 1, Reply.body(body));

        run(X);

        assertThat(server.requestCount(X, "202607", 1)).isEqualTo(1);
        RangeRow july = range(X, "202607");
        assertThat(july.status()).isEqualTo("실패");
        assertThat(july.fetchedAt()).isEqualTo(DAY0);
        assertThat(july.reason()).startsWith(reasonPrefix);
        assertThat(monthState("202607")).isEqualTo(julyBefore);
        assertNextRangesContinued("202607");
    }

    // ---- US3-3 ----

    static Stream<Arguments> stopCodes() {
        return Stream.of(
                Arguments.of("header22", resultError("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"),
                        "DAILY_LIMIT: 22", "DAILY_LIMIT"),
                Arguments.of("openApi22", openApiError("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"),
                        "DAILY_LIMIT: 22", "DAILY_LIMIT"),
                Arguments.of("header30", resultError("30", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"),
                        "AUTH_KEY_ERROR: 30", "AUTH_KEY_ERROR"),
                Arguments.of("openApi30", openApiError("30", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"),
                        "AUTH_KEY_ERROR: 30", "AUTH_KEY_ERROR"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stopCodes")
    @DisplayName("US3-3: 멈추는 코드는 그 범위와 남은 행 있는 범위만 실패, 행 없는 이번 달은 기록 안 함, 초기 적재 0회, 다음 날 이번 달은 비교")
    void stopCodeStopsRun(String name, byte[] body, String reason, String code) {
        loadYesterday();
        List<String> before = state();
        assertThat(range(X, "202610")).as("전제: 이번 달은 행이 없음").isNull();
        todayExcept("202607");
        server.enqueue(X, "202607", 1, Reply.body(body));

        run(X, Y);

        // 202607에서 멈춰 그 뒤 범위(202608, 202609, 202610, 202601)와 Y의 초기 적재는 요청하지 않는다
        assertThat(requested()).containsExactly(X + "/202605/p1", X + "/202606/p1", X + "/202607/p1");
        assertSucceeded(X, "202605", DAY1);
        assertSucceeded(X, "202606", DAY1);
        assertThat(range(X, "202607")).isEqualTo(new RangeRow("실패", DAY0, reason));
        for (String ymd : List.of("202608", "202609", OLD)) {
            RangeRow row = range(X, ymd);
            assertThat(row.status()).as(ymd).isEqualTo("실패");
            assertThat(row.fetchedAt()).as(ymd).isEqualTo(DAY0);
            assertThat(row.reason()).as(ymd).startsWith("RUN_STOPPED: " + code);
        }
        assertThat(range(X, "202610")).as("행 없는 이번 달은 기록하지 않음").isNull();
        assertThat(rows(Y)).as("같은 예약 실행의 초기 적재는 시작하지 않음(선기록도 없음)").isEmpty();
        for (String ymd : List.of("202607", "202608", "202609", OLD)) {
            assertThat(monthState(ymd)).as(ymd).isEqualTo(monthOf(before, ymd));
        }
        assertThat(history("202606")).as("멈추기 전에 성공한 범위는 반영됨").containsExactly(new Hist("신규", 99000, DAY1, DAY1));

        // 다음 날: 202610은 앞 달(202609)의 fetched_at(DAY0)이 7일 안이라 빈 직전 결과와 비교해 신규
        clearServer();
        clock.freezeAt(DAY2);
        todayExcept(null);

        run(X, Y);

        assertThat(history("202610")).containsExactly(new Hist("신규", 98500, DAY2, DAY2));
        for (String ymd : List.of("202607", "202608", "202609", "202610", OLD)) {
            assertSucceeded(X, ymd, DAY2);
        }
        assertThat(server.requests().stream().filter(r -> r.lawdCd().equals(Y)).count())
                .as("Y는 다음 날 초기 적재 대상").isEqualTo(60);
        assertThat(rows(Y)).hasSize(60).allSatisfy((ymd, row) -> assertThat(row.status()).isEqualTo("성공"));
    }

    @Test
    @DisplayName("US3-3: 멈춘 범위 자신이 행 없는 달이면 기록하지 않고, 다음 날 비교로 신규")
    void stopAtRowlessMonth() {
        loadYesterday();
        todayExcept("202610");
        server.enqueue(X, "202610", 1, Reply.body(openApiError("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR")));

        run(X);

        assertThat(requested()).containsExactly(WINDOW.stream().map(m -> X + "/" + m + "/p1").toArray(String[]::new));
        assertThat(range(X, "202610")).isNull();
        assertThat(range(X, OLD)).satisfies(row -> {
            assertThat(row.status()).isEqualTo("실패");
            assertThat(row.fetchedAt()).isEqualTo(DAY0);
            assertThat(row.reason()).startsWith("RUN_STOPPED: DAILY_LIMIT");
        });
        assertSucceeded(X, "202609", DAY1);

        clearServer();
        clock.freezeAt(DAY2);
        todayExcept(null);

        run(X);

        assertThat(history("202610")).containsExactly(new Hist("신규", 98500, DAY2, DAY2));
    }

    @Test
    @DisplayName("US3-3: 초기 적재에서 멈추면 그 지역 60개는 실패로 남고 다른 지역의 초기 적재는 시작하지 않음, 다음 날 둘 다 수집")
    void stopDuringInitialLoad() {
        clock.freezeAt(DAY0);
        server.fallback(r -> Reply.body(resultError("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR")));

        run(X, Y);

        assertThat(server.requestCount()).isEqualTo(1);
        String started = server.requests().get(0).lawdCd();
        String other = started.equals(X) ? Y : X;
        Map<String, RangeRow> startedRows = rows(started);
        assertThat(startedRows).hasSize(60).allSatisfy((ymd, row) -> {
            assertThat(row.status()).isEqualTo("실패");
            assertThat(row.fetchedAt()).isNull();
        });
        assertThat(startedRows.values().stream().filter(r -> r.reason().equals("DAILY_LIMIT: 22")).count()).isEqualTo(1);
        assertThat(startedRows.values().stream().filter(r -> r.reason().startsWith("RUN_STOPPED: DAILY_LIMIT")).count())
                .isEqualTo(59);
        assertThat(rows(other)).isEmpty();

        clearServer();
        clock.freezeAt(DAY1);

        run(X, Y);

        // 멈춘 지역은 매일 수집(재수집 기간 202605~202610의 6개 + 그 밖의 실패한 범위 55개), 다른 지역은 초기 적재 60개
        assertThat(server.requestCount()).isEqualTo(121);
        assertThat(rows(started)).hasSize(61).allSatisfy((ymd, row) -> assertThat(row).isEqualTo(new RangeRow("성공", DAY1, null)));
        assertThat(rows(other)).hasSize(60).allSatisfy((ymd, row) -> assertThat(row).isEqualTo(new RangeRow("성공", DAY1, null)));
    }

    // ---- US3-5 ----

    @Test
    @DisplayName("US3-5: 원본 저장이 실패하면 비교하지 않고 실패, 저장된 앞 페이지 파일은 남음")
    void rawSaveFailureSkipsComparison(CapturedOutput output) {
        loadYesterday();
        List<String> julyBefore = monthState("202607");
        assertThat(julyBefore).anyMatch(l -> l.contains(" trade "));
        todayExcept("202607");
        // 두 페이지 1001건(건수 확인은 통과하는 입력). 비교했다면 C 사라짐과 1001건 신규가 남는다
        Item[] firstPage = IntStream.rangeClosed(1, 1000)
                .mapToObj(i -> item(X, "202607", "99999-1").with("dealAmount", String.valueOf(10000 + i)))
                .toArray(Item[]::new);
        byte[] page1 = response(1001, 1, 1000, firstPage);
        byte[] page2 = response(1001, 2, 1000, item(X, "202607", "99999-1").with("dealAmount", "97,000"));
        server.enqueue(X, "202607", 1, Reply.body(page1));
        server.enqueue(X, "202607", 2, Reply.body(page2));
        String key1 = RawObjectKey.of(DAY1, X, "202607", 1);
        String key2 = RawObjectKey.of(DAY1, X, "202607", 2);
        raw.failOnKey(key2::equals);

        run(X);

        assertThat(server.requestCount(X, "202607", 2)).as("전제: 두 페이지를 모두 수집함").isEqualTo(1);
        RangeRow july = range(X, "202607");
        assertThat(july.status()).isEqualTo("실패");
        assertThat(july.fetchedAt()).isEqualTo(DAY0);
        assertThat(july.reason()).startsWith("RAW_SAVE_FAILED");
        // 실패 사유는 예외 클래스 이름뿐이라 원인(저장소 어댑터의 메시지)은 로그로 남긴다
        assertThat(output).contains("injected failure");
        assertThat(monthState("202607")).isEqualTo(julyBefore);
        assertThat(raw.saved().stream().filter(s -> s.key().equals(key1)).toList())
                .singleElement().satisfies(s -> assertThat(s.body()).isEqualTo(page1));
        assertThat(raw.savedKeys()).doesNotContain(key2);
        assertNextRangesContinued("202607");
    }

    @Test
    @DisplayName("US3-5, FR-008: 저장소가 IOException이 아닌 예외를 던져도 그 범위만 실패하고 메시지는 남기지 않음")
    void unexpectedRawSaveErrorFailsOnlyThatRange(CapturedOutput output) {
        loadYesterday();
        List<String> julyBefore = monthState("202607");
        todayExcept("202607");
        server.enqueue(X, "202607", 1, Reply.body(response(item(X, "202607", "99999-1"))));
        raw.failOnKey(RawObjectKey.of(DAY1, X, "202607", 1)::equals)
                .failWith(new IllegalStateException("message-of-unknown-origin"));

        run(X);

        RangeRow july = range(X, "202607");
        assertThat(july.status()).isEqualTo("실패");
        assertThat(july.reason()).isEqualTo("RAW_SAVE_FAILED: IllegalStateException");
        assertThat(monthState("202607")).isEqualTo(julyBefore);
        assertThat(output).doesNotContain("message-of-unknown-origin");
        assertNextRangesContinued("202607");
    }

    @Test
    @DisplayName("FR-008: 2페이지 요청에 1페이지가 다시 오면 건수가 맞아도 그 범위만 해석 실패, 원본 없음, 거래와 이력 그대로")
    void repeatedPageFailsRangeEvenWhenCountsMatch() {
        loadYesterday();
        List<String> julyBefore = monthState("202607");
        assertThat(julyBefore).as("전제: 202607에 어제 거래 C가 있음").anyMatch(l -> l.contains(" trade "));
        todayExcept("202607");
        // 전체 2000건, 두 응답 모두 같은 1000건이라 건수 확인은 통과한다. 비교했다면 C 사라짐과 신규가 남는다
        Item[] firstPage = IntStream.rangeClosed(1, 1000)
                .mapToObj(i -> item(X, "202607", "99999-1").with("dealAmount", String.valueOf(10000 + i)))
                .toArray(Item[]::new);
        byte[] page1 = response(2000, 1, 1000, firstPage);
        server.enqueue(X, "202607", 1, Reply.body(page1));
        server.enqueue(X, "202607", 2, Reply.body(page1));

        run(X);

        assertThat(server.requestCount(X, "202607", 2)).as("전제: 두 번째 페이지를 요청함").isEqualTo(1);
        RangeRow july = range(X, "202607");
        assertThat(july.status()).isEqualTo("실패");
        assertThat(july.fetchedAt()).isEqualTo(DAY0);
        assertThat(july.reason()).startsWith("PARSE_ERROR: ");
        assertThat(monthState("202607")).isEqualTo(julyBefore);
        // 어제 적재의 원본은 남아 있으므로 오늘 실행의 키만 본다
        assertThat(raw.savedKeys()).doesNotContain(RawObjectKey.of(DAY1, X, "202607", 1),
                RawObjectKey.of(DAY1, X, "202607", 2));
        assertNextRangesContinued("202607");
    }

    // ---- US3-7, SC-005 ----

    @ParameterizedTest(name = "succeeds={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("US3-7: 재수집 기간 밖의 실패한 범위를 재수집 기간 뒤에 함께 수집, 성공하면 상태와 fetched_at 갱신, 실패하면 fetched_at 유지")
    void failedOldRangeCollectedNextDay(boolean succeeds) {
        loadYesterday();
        List<String> oldBefore = monthState(OLD);
        todayExcept(OLD);
        server.enqueue(X, OLD, 1, succeeds ? Reply.body(response(a(), a2())) : Reply.status(503));

        run(X);

        List<String> months = server.requests().stream().map(r -> r.dealYmd()).distinct().toList();
        List<String> expectedOrder = new ArrayList<>(WINDOW);
        expectedOrder.add(OLD);
        assertThat(months).containsExactlyElementsOf(expectedOrder);
        if (succeeds) {
            assertThat(range(X, OLD)).isEqualTo(new RangeRow("성공", DAY1, null));
            // fetched_at(DAY0)이 7일 안이라 비교(US2 경우 12)
            assertThat(history(OLD)).containsExactly(new Hist("신규", 99500, DAY1, DAY1));
        } else {
            assertThat(range(X, OLD)).isEqualTo(new RangeRow("실패", DAY0, "HTTP_ERROR: 503"));
            assertThat(monthState(OLD)).isEqualTo(oldBefore);
        }
    }

    @Test
    @DisplayName("SC-005: 초기 적재에서 실패한 범위만 다음 날 다시 수집(재수집 기간 밖의 다른 달은 요청 안 함)")
    void onlyFailedRangesRetried() {
        clock.freezeAt(DAY0);
        server.enqueue(X, "202201", 1, Reply.status(503));
        server.enqueue(X, "202302", 1, Reply.body(resultError("99", "가상 오류")));
        server.enqueue(X, "202303", 1, Reply.body(response(item(X, "202303", "99999-1"))));

        run(X);

        assertThat(range(X, "202201").reason()).startsWith("HTTP_ERROR: 503");
        assertThat(range(X, "202302").reason()).startsWith("RESULT_CODE: 99");
        assertThat(rows(X).values().stream().filter(r -> r.status().equals("성공")).count()).isEqualTo(58);

        clearServer();
        clock.freezeAt(DAY1);
        server.enqueue(X, "202302", 1, Reply.body(response(item(X, "202302", "99999-1"))));

        run(X);

        // 재수집 기간의 달을 오래된 달부터 모두 요청한 뒤 실패한 범위 둘(둘 사이의 순서는 정하지 않음)
        List<String> requested = requested();
        assertThat(requested).hasSize(8);
        assertThat(requested.subList(0, 6)).containsExactly(WINDOW.stream().map(m -> X + "/" + m + "/p1").toArray(String[]::new));
        assertThat(requested.subList(6, 8)).containsExactlyInAnyOrder(X + "/202201/p1", X + "/202302/p1");
        assertSucceeded(X, "202201", DAY1);
        assertSucceeded(X, "202302", DAY1);
        assertThat(range(X, "202303")).as("성공한 범위는 그대로").isEqualTo(new RangeRow("성공", DAY0, null));
        // 앞서 실패만 한 범위는 기준 적재(이력 없음)
        assertThat(history("202302")).isEmpty();
        assertThat(monthState("202302")).anyMatch(l -> l.contains(" trade "));
    }

    // ---- SC-004, US3-8 ----

    /** 재수집 기간 밖의 실패한 범위 202601(매일 수집의 마지막 범위)에 주입하는 실패. 마지막이라 멈추는 실패도 다른 범위와 비교할 수 있다. */
    enum Failure {
        HTTP("HTTP_ERROR: 500"),
        NETWORK("NETWORK_ERROR"),
        RESULT_CODE("RESULT_CODE: 99"),
        PARSE("PARSE_ERROR"),
        AUTH_KEY("AUTH_KEY_ERROR: 30"),
        DAILY_LIMIT("DAILY_LIMIT: 22"),
        RAW_SAVE("RAW_SAVE_FAILED"),
        COUNT_MISMATCH("COUNT_MISMATCH"),
        EMPTY_AFTER_TRADES("EMPTY_AFTER_TRADES"),
        APPLY("APPLY_FAILED");

        final String reasonPrefix;

        Failure(String reasonPrefix) {
            this.reasonPrefix = reasonPrefix;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("SC-004, US3-8: 실패마다 그 범위의 거래와 이력은 실패 전과 같고, 다른 범위 결과는 실패 없는 실행과 같고, 이유는 R7 코드로 시작")
    void failureLeavesOtherRangesSame(Failure failure) {
        loadYesterday();
        todayExcept(null);
        run(X);
        assertThat(range(X, OLD)).as("전제: 실패가 없으면 같은 입력이 성공").isEqualTo(new RangeRow("성공", DAY1, null));
        assertThat(history(OLD)).as("전제: 실패가 없으면 202601에 이력이 생김").isNotEmpty();
        List<String> cleanOthers = othersThan(state(), OLD);

        dropProbe();
        fixture.reset();
        loadYesterday();
        List<String> oldBefore = monthState(OLD);
        todayExcept(OLD);
        inject(failure);

        run(X);

        RangeRow old = range(X, OLD);
        assertThat(old.status()).isEqualTo("실패");
        assertThat(old.fetchedAt()).isEqualTo(DAY0);
        assertThat(old.reason()).startsWith(failure.reasonPrefix);
        assertThat(monthState(OLD)).isEqualTo(oldBefore);
        assertThat(othersThan(state(), OLD)).isEqualTo(cleanOthers);
    }

    private void inject(Failure failure) {
        switch (failure) {
            case HTTP -> server.enqueue(X, OLD, 1, Reply.status(500));
            case NETWORK -> server.enqueue(X, OLD, 1, Reply.disconnect());
            case RESULT_CODE -> server.enqueue(X, OLD, 1, Reply.body(resultError("99", "가상 오류")));
            case PARSE -> server.enqueue(X, OLD, 1, Reply.body(bytes("가상 응답은 XML이 아님")));
            case AUTH_KEY -> server.enqueue(X, OLD, 1,
                    Reply.body(openApiError("30", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR")));
            case DAILY_LIMIT -> server.enqueue(X, OLD, 1,
                    Reply.body(resultError("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR")));
            case RAW_SAVE -> {
                server.enqueue(X, OLD, 1, Reply.body(response(a(), a2())));
                String key = RawObjectKey.of(DAY1, X, OLD, 1);
                raw.failOnKey(key::equals);
            }
            case COUNT_MISMATCH -> server.enqueue(X, OLD, 1, Reply.body(response(3, 1, 1000, a(), a2())));
            case EMPTY_AFTER_TRADES -> server.enqueue(X, OLD, 1, Reply.body(response()));
            case APPLY -> {
                server.enqueue(X, OLD, 1, Reply.body(response(a(), a2())));
                jdbcTemplate.execute("""
                        create function %1$s() returns trigger language plpgsql as $$
                        begin
                            raise exception 'failure isolation probe' using errcode = 'check_violation', constraint = '%1$s';
                        end
                        $$""".formatted(PROBE));
                jdbcTemplate.execute("""
                        create trigger %1$s after insert or update on fetch_range for each row
                        when (new.lawd_cd = '99999' and new.deal_ymd = '202601' and new.status = '성공')
                        execute function %1$s()""".formatted(PROBE));
            }
        }
    }

    // ---- 선기록 실패 ----

    @Test
    @DisplayName("FR-005: 선기록이 실패하면 그 지역은 행이 없고 수집하지 않으며 다음 지역은 계속, 다음 실행에서 다시 초기 적재")
    void preRegistrationFailure() {
        // 60개 가운데 한 행에서만 실패시켜, 선기록이 한 트랜잭션이 아니면 나머지 행이 남아 드러난다
        jdbcTemplate.execute("""
                create function %1$s() returns trigger language plpgsql as $$
                begin
                    raise exception 'failure isolation probe' using errcode = 'check_violation', constraint = '%1$s';
                end
                $$""".formatted(PROBE));
        jdbcTemplate.execute("""
                create trigger %1$s before insert on fetch_range for each row
                when (new.lawd_cd = '99998' and new.deal_ymd = '202606')
                execute function %1$s()""".formatted(PROBE));
        clock.freezeAt(DAY0);

        run(Y, X);

        assertThat(rows(Y)).isEmpty();
        assertThat(server.requests().stream().filter(r -> r.lawdCd().equals(Y)).count()).isZero();
        assertThat(rows(X)).as("선기록이 실패한 지역은 건너뛰고 다음 지역을 계속").hasSize(60)
                .allSatisfy((ymd, row) -> assertThat(row).isEqualTo(new RangeRow("성공", DAY0, null)));

        dropProbe();
        clearServer();
        clock.freezeAt(DAY1);

        run(Y, X);

        assertThat(server.requests().stream().filter(r -> r.lawdCd().equals(Y)).count()).isEqualTo(60);
        assertThat(rows(Y)).hasSize(60).allSatisfy((ymd, row) -> assertThat(row).isEqualTo(new RangeRow("성공", DAY1, null)));
        assertThat(server.requests().stream().filter(r -> r.lawdCd().equals(X)).count()).as("X는 매일 수집").isEqualTo(6);
    }

    // ---- 장면 ----

    private static Item trade(String ymd, String aptSeq, String amount) {
        return item(X, ymd, aptSeq).with("dealAmount", amount);
    }

    private static Item a() {
        return trade(OLD, "99999-1", "98,500");
    }

    private static Item a2() {
        return trade(OLD, "99999-1", "99,500");
    }

    private static Item c() {
        return trade("202607", "99999-1", "98,500");
    }

    /** 어제 초기 적재와 재수집 기간 밖의 실패한 범위를 만들고, 서버를 비운 뒤 시계를 오늘(DAY1)에 둔다. */
    private void loadYesterday() {
        clock.freezeAt(DAY0);
        ok(OLD, a());
        ok("202606", trade("202606", "99999-1", "98,500"));
        ok("202607", c());
        ok("202608", trade("202608", "99999-1", "98,500"));
        ok("202609", trade("202609", "99999-1", "98,500"));
        run(X);
        assertThat(rows(X)).as("전제: 어제 초기 적재 60개 범위 성공").hasSize(60)
                .allSatisfy((ymd, row) -> assertThat(row).isEqualTo(new RangeRow("성공", DAY0, null)));
        assertThat(history(null)).as("전제: 기준 적재라 이력 없음").isEmpty();
        assertThat(jdbc.sql("""
                update fetch_range set status = '실패', failure_reason = 'HTTP_ERROR: 503'
                where lawd_cd = :l and deal_ymd = :y""").param("l", X).param("y", OLD).update()).isEqualTo(1);
        clearServer();
        clock.freezeAt(DAY1);
    }

    /**
     * 오늘의 정상 응답(skip 달은 빼고 지정). 202605 0건, 202606 B2 신규, 202607 C 등기, 202608 D 사라짐과 D2 신규,
     * 202609 E 건수 2(신규), 202610 새 단지 F 신규, 202601 A2 신규.
     */
    private void todayExcept(String skip) {
        Map<String, Item[]> today = new TreeMap<>();
        today.put("202606", new Item[] {trade("202606", "99999-1", "98,500"), trade("202606", "99999-1", "99,000")});
        today.put("202607", new Item[] {c().with("rgstDate", "26.09.30")});
        today.put("202608", new Item[] {trade("202608", "99999-1", "97,000")});
        today.put("202609", new Item[] {trade("202609", "99999-1", "98,500"), trade("202609", "99999-1", "98,500")});
        today.put("202610", new Item[] {trade("202610", "99999-2", "98,500")});
        today.put(OLD, new Item[] {a(), a2()});
        today.forEach((ymd, items) -> {
            if (!ymd.equals(skip)) {
                ok(ymd, items);
            }
        });
    }

    private void ok(String ymd, Item... items) {
        server.enqueue(X, ymd, 1, Reply.body(response(items)));
    }

    private void clearServer() {
        server.reset();
        server.fallback(r -> Reply.body(response()));
    }

    private void run(String... regions) {
        collectionRun.run(List.of(regions));
    }

    private void dropProbe() {
        jdbcTemplate.execute("drop trigger if exists " + PROBE + " on fetch_range");
        jdbcTemplate.execute("drop function if exists " + PROBE + "()");
    }

    /** 실패한 범위 뒤의 범위를 요청했고, 재수집 기간과 202601의 나머지가 오늘 성공했다(다음 범위 계속). */
    private void assertNextRangesContinued(String failed) {
        var requests = server.requests();
        int lastFailed = requests.stream().filter(r -> r.dealYmd().equals(failed)).mapToInt(r -> r.sequence()).max()
                .orElseThrow();
        assertThat(requests.stream().filter(r -> r.sequence() > lastFailed).map(r -> r.dealYmd()).distinct())
                .as("실패한 범위 뒤에 요청한 범위").isNotEmpty();
        List<String> others = new ArrayList<>(WINDOW);
        others.add(OLD);
        others.remove(failed);
        for (String ymd : others) {
            assertSucceeded(X, ymd, DAY1);
        }
        assertThat(history("202608")).as("다음 범위는 비교해 반영됨")
                .containsExactlyInAnyOrder(new Hist("사라짐", 98500, DAY1, DAY0), new Hist("신규", 97000, DAY1, DAY1));
    }

    private void assertSucceeded(String lawd, String ymd, Instant at) {
        assertThat(range(lawd, ymd)).as("%s %s", lawd, ymd).isEqualTo(new RangeRow("성공", at, null));
    }

    private List<String> requested() {
        return server.requests().stream().map(r -> r.lawdCd() + "/" + r.dealYmd() + "/p" + r.pageNo()).toList();
    }

    // ---- 데이터베이스 읽기 ----

    record RangeRow(String status, Instant fetchedAt, String reason) {
    }

    /** 이력 하나: 변경 종류, 거래 금액, 관측 시각, 그 거래 행의 first_seen_at. */
    record Hist(String type, long amount, Instant observedAt, Instant firstSeenAt) {
    }

    private RangeRow range(String lawd, String ymd) {
        return rows(lawd).get(ymd);
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

    /** 이 달(null이면 전부) 거래의 이력. */
    private List<Hist> history(String ymd) {
        return jdbc.sql("""
                select h.change_type, t.amount, h.observed_at, t.first_seen_at from trade_history h
                join trade t on t.id = h.trade_id
                where cast(:y as text) is null or to_char(t.deal_date, 'YYYYMM') = cast(:y as text)
                order by h.id""")
                .param("y", ymd)
                .query((rs, n) -> new Hist(rs.getString(1), rs.getLong(2),
                        instant(rs.getObject(3, OffsetDateTime.class)), instant(rs.getObject(4, OffsetDateTime.class))))
                .list();
    }

    /** 단지, 거래, 이력, 범위 결과를 달("YYYYMM ")이나 "complex " 접두어를 붙인 정렬한 줄로. */
    private List<String> state() {
        List<String> lines = new ArrayList<>();
        lines.addAll(jdbc.sql("select apt_seq, name, sgg_cd, umd_nm, jibun from complex")
                .query((rs, n) -> "complex " + rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3) + " "
                        + rs.getString(4) + " " + rs.getString(5))
                .list());
        lines.addAll(jdbc.sql("""
                select to_char(t.deal_date, 'YYYYMM'), c.apt_seq, t.deal_date, t.floor, t.exclusive_area, t.amount,
                       t.canceled, t.same_key_count, t.cancel_date, t.registry_date, t.apt_dong, t.first_seen_at
                from trade t join complex c on c.id = t.complex_id""")
                .query((rs, n) -> {
                    StringBuilder line = new StringBuilder(rs.getString(1)).append(" trade");
                    for (int i = 2; i <= 12; i++) {
                        line.append(' ').append(rs.getString(i));
                    }
                    return line.toString();
                }).list());
        lines.addAll(jdbc.sql("""
                select to_char(t.deal_date, 'YYYYMM'), c.apt_seq, t.deal_date, t.amount, t.canceled, h.change_type,
                       h.observed_at
                from trade_history h join trade t on t.id = h.trade_id join complex c on c.id = t.complex_id""")
                .query((rs, n) -> rs.getString(1) + " history " + rs.getString(2) + " " + rs.getString(3) + " "
                        + rs.getString(4) + " " + rs.getString(5) + " " + rs.getString(6) + " " + rs.getString(7))
                .list());
        lines.addAll(jdbc.sql("select deal_ymd, lawd_cd, status, fetched_at, failure_reason from fetch_range")
                .query((rs, n) -> rs.getString(1) + " range " + rs.getString(2) + " " + rs.getString(3) + " "
                        + rs.getString(4) + " " + rs.getString(5))
                .list());
        return lines.stream().sorted().toList();
    }

    /** 이 달의 거래와 이력(범위 결과 줄은 빼고). */
    private List<String> monthState(String ymd) {
        return monthOf(state(), ymd);
    }

    private static List<String> monthOf(List<String> state, String ymd) {
        return state.stream().filter(l -> l.startsWith(ymd + " ") && !l.startsWith(ymd + " range")).toList();
    }

    private static List<String> othersThan(List<String> state, String ymd) {
        return state.stream().filter(l -> !l.startsWith(ymd + " ")).toList();
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
