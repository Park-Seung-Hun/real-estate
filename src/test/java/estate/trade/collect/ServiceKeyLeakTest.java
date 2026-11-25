package estate.trade.collect;

import static estate.support.TradeFixtures.LAWD_CD;
import static estate.support.TradeFixtures.bytes;
import static estate.support.TradeFixtures.item;
import static estate.support.TradeFixtures.openApiError;
import static estate.support.TradeFixtures.response;
import static estate.support.TradeFixtures.resultError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import estate.support.CollectorFixture;
import estate.support.CollectorIntegrationTest;
import estate.support.CollectorTestConfiguration;
import estate.support.FakeTradeApiServer;
import estate.support.FakeTradeApiServer.Reply;
import estate.support.MutableClock;
import estate.support.RecordingRawResponseStore;
import estate.trade.api.CallCounter;
import estate.trade.api.TradeApiClient;

/**
 * T039 인증키 노출(spec SC-008, FR-022, Edge Cases 마지막 줄, research R9). 가짜 인증키(+, /, = 포함)로 실패 경로
 * 넷(HTTP 500, 연결 끊김, 읽기 시간 초과, 결과 코드 오류)을 일으키고, 로그, failure_reason, 원본 바이트, 던져진 예외의
 * 메시지와 원인 사슬에 그 키와 인코딩한 키가 0건인지 본다. 오류 본문과 결과 메시지에는 일부러 키를 담는다.
 */
@CollectorIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ServiceKeyLeakTest {

    static final String X = LAWD_CD;
    static final String KEY = CollectorTestConfiguration.SERVICE_KEY;
    static final String ENCODED = URLEncoder.encode(KEY, StandardCharsets.UTF_8);
    /** 키 자체, 인코딩한 키, 그리고 어느 인코딩이든 남는 앞부분. */
    static final List<String> FORBIDDEN = List.of(KEY, ENCODED, URLEncoder.encode(ENCODED, StandardCharsets.UTF_8),
            KEY.substring(0, KEY.indexOf('+')));
    /** 한국 2026-10-05 09:00. 초기 적재 202111~202610. */
    static final Instant DAY0 = Instant.parse("2026-10-05T00:00:00Z");

    /** 실패 경로 넷. 202606~202609에 하나씩 둔다. */
    static final Map<String, Reply> FAILURES = Map.of(
            "202606", new Reply(500, bytes("<error>invalid serviceKey=" + KEY + " (" + ENCODED + ")</error>"), false,
                    Duration.ZERO),
            "202607", Reply.disconnect(),
            "202608", Reply.body(response()).after(Duration.ofSeconds(2)),
            "202609", Reply.body(resultError("99", "SERVICE ERROR serviceKey=" + KEY + " " + ENCODED)));

    @Autowired
    CollectionRun collectionRun;
    @Autowired
    CollectorFixture fixture;
    @Autowired
    MutableClock clock;
    @Autowired
    TradeApiClient tradeApiClient;

    FakeTradeApiServer server;
    RecordingRawResponseStore raw;
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        server = fixture.server();
        raw = fixture.rawStore();
        jdbc = fixture.jdbc();
        fixture.reset();
        clock.freezeAt(DAY0);
    }

    @AfterEach
    void tearDown() {
        fixture.reset();
    }

    @Test
    @DisplayName("SC-008: 실패 경로 넷에서 로그, failure_reason, 원본 바이트에 인증키 0건")
    void noKeyInLogsReasonsOrRaw(CapturedOutput output) {
        FAILURES.forEach((ymd, reply) -> server.enqueue(X, ymd, 1, reply));
        server.enqueue(X, "202605", 1, Reply.body(response(item(X, "202605", "99999-1"))));
        int logStart = output.getAll().length();

        assertThatCode(() -> collectionRun.run(List.of(X))).doesNotThrowAnyException();

        String log = output.getAll().substring(logStart);
        assertKeyWasSent();
        Map<String, String> reasons = reasons();
        assertThat(reasons.get("202606")).startsWith("HTTP_ERROR: 500");
        assertThat(reasons.get("202607")).startsWith("NETWORK_ERROR");
        assertThat(reasons.get("202608")).startsWith("NETWORK_ERROR");
        assertThat(reasons.get("202609")).startsWith("RESULT_CODE: 99");
        assertThat(log).as("전제: 실패 범위 로그가 남음").contains("계약년월=202606", "계약년월=202609");
        assertNoKey("로그", log);
        reasons.forEach((ymd, reason) -> assertNoKey("failure_reason " + ymd, String.valueOf(reason)));
        assertThat(raw.saved()).as("전제: 성공한 범위의 원본이 저장됨").hasSize(56);
        raw.saved().forEach(s -> {
            assertNoKey("원본 키 " + s.key(), s.key());
            assertNoKey("원본 바이트 " + s.key(), new String(s.body(), StandardCharsets.UTF_8));
        });
    }

    @Test
    @DisplayName("SC-008: 실행을 멈추는 인증키 오류(결과 메시지에 키가 있음)에서도 로그와 failure_reason에 키 0건")
    void noKeyOnAuthKeyError(CapturedOutput output) {
        server.enqueue(X, "202605", 1,
                Reply.body(openApiError("30", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR " + KEY + " " + ENCODED)));
        server.fallback(r -> Reply.body(resultError("30", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR " + KEY)));
        int logStart = output.getAll().length();

        assertThatCode(() -> collectionRun.run(List.of(X))).doesNotThrowAnyException();

        String log = output.getAll().substring(logStart);
        assertKeyWasSent();
        Map<String, String> reasons = reasons();
        assertThat(reasons).as("전제: 선기록한 60개가 실패로 남음").hasSize(60);
        assertThat(reasons.values()).anyMatch(r -> r.startsWith("AUTH_KEY_ERROR: 30"));
        assertNoKey("로그", log);
        reasons.forEach((ymd, reason) -> assertNoKey("failure_reason " + ymd, String.valueOf(reason)));
    }

    @Test
    @DisplayName("SC-008: 실패 경로 넷에서 API 어댑터가 던진 예외의 메시지와 원인 사슬에 인증키 0건")
    void noKeyInThrownExceptions() {
        for (Map.Entry<String, Reply> failure : FAILURES.entrySet()) {
            String ymd = failure.getKey();
            server.enqueue(X, ymd, 1, failure.getValue());

            Throwable thrown = catchThrowable(() -> tradeApiClient.fetchPage(X, ymd, 1, new CallCounter(1000)));

            assertThat(thrown).as(ymd).isNotNull();
            StringWriter trace = new StringWriter();
            thrown.printStackTrace(new PrintWriter(trace));
            assertNoKey("예외 " + ymd, trace.toString());
            for (Throwable t = thrown; t != null; t = t.getCause()) {
                assertNoKey("예외 메시지 " + ymd, String.valueOf(t.getMessage()));
                assertNoKey("예외 문자열 " + ymd, t.toString());
            }
        }
        assertKeyWasSent();
    }

    /** 가짜 서버가 받은 쿼리에 그 키가 실제로 있었다(0건 확인이 헛돌지 않게). */
    private void assertKeyWasSent() {
        assertThat(server.requests()).isNotEmpty().allSatisfy(r -> {
            assertThat(r.serviceKey()).isEqualTo(KEY);
            assertThat(r.rawQuery()).containsIgnoringCase("serviceKey=" + ENCODED);
        });
    }

    private static void assertNoKey(String where, String text) {
        for (String forbidden : FORBIDDEN) {
            assertThat(text).as(where).doesNotContainIgnoringCase(forbidden);
        }
    }

    private Map<String, String> reasons() {
        Map<String, String> reasons = new TreeMap<>();
        jdbc.sql("select deal_ymd, failure_reason from fetch_range where lawd_cd = :l").param("l", X)
                .query((rs, n) -> reasons.put(rs.getString(1), rs.getString(2))).list();
        return reasons;
    }
}
