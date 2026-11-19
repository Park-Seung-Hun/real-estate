package estate.trade.api;

import static estate.support.TradeFixtures.DEAL_YMD;
import static estate.support.TradeFixtures.LAWD_CD;
import static estate.support.TradeFixtures.item;
import static estate.support.TradeFixtures.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import estate.support.FakeTradeApiServer;
import estate.support.FakeTradeApiServer.Reply;
import estate.support.FakeTradeApiServer.Request;
import estate.support.TradeFixtures;
import estate.trade.CollectorProperties;
import estate.trade.api.TradeApiClient.PageResult;
import estate.trade.api.TradeApiException.Kind;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** T018: API 어댑터. 가짜 서버와 대기 0인 재시도 정책으로 확인한다. 인증키와 값은 모두 가짜다. */
class TradeApiClientTest {

    private static final String SERVICE_KEY = "fake+key/with=chars==";
    private static final int SELF_STOP_CALLS = 1000;

    private FakeTradeApiServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = FakeTradeApiServer.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    /** 운영과 같은 대상과 횟수, 대기만 없다. */
    private static RetryPolicy noWaitPolicy() {
        return RetryPolicy.builder().maxRetries(2).delay(Duration.ofMillis(1))
                .includes(ResourceAccessException.class, org.springframework.web.client.HttpStatusCodeException.class)
                .build();
    }

    private static CollectorProperties properties(String baseUrl, Duration readTimeout) {
        return new CollectorProperties(List.of(), SERVICE_KEY, "-", baseUrl, 10000, 500, Duration.ofSeconds(5),
                readTimeout, null);
    }

    private TradeApiClient client() {
        return new TradeApiClient(properties(server.baseUrl(), Duration.ofSeconds(5)), noWaitPolicy());
    }

    private static CallCounter counter() {
        return new CallCounter(SELF_STOP_CALLS);
    }

    private static TradeApiException failure(Runnable call) {
        return (TradeApiException) assertThatThrownBy(call::run).isInstanceOf(TradeApiException.class).actual();
    }

    private void assertNoSecretInside(TradeApiException e) {
        assertThat(e.getCause()).isNull();
        assertThat(e.getSuppressed()).isEmpty();
        List<String> texts = List.of(String.valueOf(e.getMessage()), e.failureReason(), String.valueOf(e));
        for (String text : texts) {
            assertThat(text).doesNotContain(SERVICE_KEY, "fake%2Bkey", "fake+key", "serviceKey", "127.0.0.1",
                    "getRTMSDataSvcAptTradeDev", "http://");
        }
    }

    @Test
    void succeedsOnThirdRequestAfterTwoServerErrors() {
        byte[] ok = response(item());
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.status(500), Reply.status(500), Reply.body(ok));
        CallCounter counter = counter();

        PageResult result = client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter);

        assertThat(server.requestCount()).isEqualTo(3);
        assertThat(counter.calls()).isEqualTo(3);
        assertThat(result.rawBody()).isEqualTo(ok);
        assertThat(result.page().items()).hasSize(1);
    }

    @Test
    void failsWithHttpErrorWhenAllThreeRequestsFail() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.status(503));

        TradeApiException e = failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter()));

        assertThat(server.requestCount()).isEqualTo(3);
        assertThat(e.kind()).isEqualTo(Kind.HTTP_ERROR);
        assertThat(e.failureReason()).isEqualTo("HTTP_ERROR: 503");
        assertThat(e.isRunStopping()).isFalse();
        assertNoSecretInside(e);
    }

    @Test
    void doesNotParseBodyWhenStatusIsNotOk() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, new Reply(500, response(item()), false, Duration.ZERO));

        TradeApiException e = failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter()));

        assertThat(e.failureReason()).isEqualTo("HTTP_ERROR: 500");
        assertThat(server.requestCount()).isEqualTo(3);
    }

    @Test
    void treatsRedirectStatusAsHttpErrorToo() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.status(302));

        TradeApiException e = failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter()));

        assertThat(e.failureReason()).isEqualTo("HTTP_ERROR: 302");
        assertThat(server.requestCount()).isEqualTo(3);
    }

    @Test
    void failsWithNetworkErrorWhenConnectionIsDroppedEveryTime() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.disconnect());
        CallCounter counter = counter();

        TradeApiException e = failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter));

        // JDK HttpClient는 응답 없이 끊긴 GET을 한 번 더 보내므로 서버가 받은 수는 시도 수보다 클 수 있다
        assertThat(counter.calls()).isEqualTo(3);
        assertThat(server.requestCount()).isGreaterThanOrEqualTo(3);
        assertThat(e.kind()).isEqualTo(Kind.NETWORK_ERROR);
        assertThat(e.failureReason()).startsWith("NETWORK_ERROR: ").contains("Exception");
        assertNoSecretInside(e);
    }

    @Test
    void recoversWhenTheConnectionDropsOnlyOnce() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.disconnect(), Reply.body(response(item())));

        assertThat(client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter()).page().items()).hasSize(1);
        assertThat(server.requestCount()).isEqualTo(2);
    }

    @Test
    void failsWithNetworkErrorOnReadTimeout() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(response(item())).after(Duration.ofMillis(1500)));
        TradeApiClient impatient = new TradeApiClient(properties(server.baseUrl(), Duration.ofMillis(200)),
                noWaitPolicy());

        TradeApiException e = failure(() -> impatient.fetchPage(LAWD_CD, DEAL_YMD, 1, counter()));

        assertThat(e.kind()).isEqualTo(Kind.NETWORK_ERROR);
        assertThat(e.failureReason()).contains("TimeoutException");
        assertThat(server.requestCount()).isEqualTo(3);
        assertNoSecretInside(e);
    }

    @Test
    void failsWithNetworkErrorWhenNobodyListens() throws IOException {
        String deadUrl = server.baseUrl();
        server.close();
        server = FakeTradeApiServer.start();
        TradeApiClient client = new TradeApiClient(properties(deadUrl, Duration.ofSeconds(5)), noWaitPolicy());

        TradeApiException e = failure(() -> client.fetchPage(LAWD_CD, DEAL_YMD, 1, counter()));

        assertThat(e.kind()).isEqualTo(Kind.NETWORK_ERROR);
        assertNoSecretInside(e);
    }

    @Test
    void doesNotRetryResultCodeErrors() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(TradeFixtures.resultError("03", "NODATA")));
        CallCounter counter = counter();

        TradeApiException e = failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter));

        assertThat(server.requestCount()).isEqualTo(1);
        assertThat(counter.calls()).isEqualTo(1);
        assertThat(e.kind()).isEqualTo(Kind.RESULT_CODE);
        assertThat(e.failureReason()).isEqualTo("RESULT_CODE: 3 NODATA");
        assertNoSecretInside(e);
    }

    @Test
    void doesNotRetryStopCodes() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(TradeFixtures.openApiError("22", "LIMITED")));

        TradeApiException e = failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter()));

        assertThat(server.requestCount()).isEqualTo(1);
        assertThat(e.kind()).isEqualTo(Kind.STOP_CODE);
        assertThat(e.isRunStopping()).isTrue();
        assertThat(e.failureReason()).isEqualTo("DAILY_LIMIT: 22");
    }

    @Test
    void doesNotRetryParseErrors() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(TradeFixtures.bytes("<html>not the api</html>")));

        TradeApiException e = failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter()));

        assertThat(server.requestCount()).isEqualTo(1);
        assertThat(e.kind()).isEqualTo(Kind.PARSE_ERROR);
        assertThat(e.isRunStopping()).isFalse();
    }

    @Test
    void doesNotRetryAnItemThatDiffersFromTheRequest() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(response(item().with("sggCd", "99998"))));

        assertThat(failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter())).kind())
                .isEqualTo(Kind.PARSE_ERROR);
        assertThat(server.requestCount()).isEqualTo(1);
    }

    @Test
    void masksTheServiceKeyInResultMessages() {
        String encoded = URLEncoder.encode(SERVICE_KEY, StandardCharsets.UTF_8);
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(TradeFixtures.resultError("10",
                "bad key " + SERVICE_KEY + " or " + encoded + " or fake%2Bkey%2Fwith%3Dchars%3D%3D")));

        TradeApiException e = failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter()));

        assertThat(e.kind()).isEqualTo(Kind.RESULT_CODE);
        assertThat(e.failureReason()).isEqualTo("RESULT_CODE: 10 bad key *** or *** or ***");
        assertNoSecretInside(e);
    }

    @Test
    void masksTheServiceKeyBeforeCuttingLongResultMessages() {
        // 키가 200자 경계에 걸쳐도 앞부분이 남지 않아야 한다
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(TradeFixtures.resultError("10", "x".repeat(195) + SERVICE_KEY)));

        TradeApiException e = failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter()));

        assertThat(e.kind()).isEqualTo(Kind.RESULT_CODE);
        assertThat(e.failureReason()).doesNotContain(SERVICE_KEY.substring(0, 4)).endsWith("***");
    }

    @Test
    void requestsTwoPagesInOrder() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(response(1500, 1, 1000, item("99999-1"))));
        server.enqueue(LAWD_CD, DEAL_YMD, 2, Reply.body(response(1500, 2, 1000, item("99999-2"))));
        TradeApiClient client = client();
        CallCounter counter = counter();

        TradePage first = client.fetchPage(LAWD_CD, DEAL_YMD, 1, counter).page();
        TradePage second = client.fetchPage(LAWD_CD, DEAL_YMD, 2, counter).page();

        assertThat(first.totalCount()).isEqualTo(1500);
        assertThat(first.pageNo()).isEqualTo(1);
        assertThat(second.pageNo()).isEqualTo(2);
        assertThat(second.items()).extracting(TradeItem::aptSeq).containsExactly("99999-2");
        assertThat(server.requests()).extracting(Request::pageNo).containsExactly(1, 2);
        assertThat(server.requests()).extracting(r -> r.query().get("numOfRows")).containsOnly("1000");
        assertThat(counter.calls()).isEqualTo(2);
    }

    @Test
    void failsOnlyTheRangeWhenSecondPageHasResultCodeError() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(response(1500, 1, 1000, item())));
        server.enqueue(LAWD_CD, DEAL_YMD, 2, Reply.body(TradeFixtures.resultError("02", "DB ERROR")));
        TradeApiClient client = client();
        CallCounter counter = counter();

        assertThat(client.fetchPage(LAWD_CD, DEAL_YMD, 1, counter).page().items()).hasSize(1);
        TradeApiException e = failure(() -> client.fetchPage(LAWD_CD, DEAL_YMD, 2, counter));

        assertThat(e.kind()).isEqualTo(Kind.RESULT_CODE);
        assertThat(e.isRunStopping()).isFalse();
        assertThat(server.requestCount(LAWD_CD, DEAL_YMD, 2)).isEqualTo(1);
    }

    @Test
    void sendsTheRequestedRangeAndPage() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(response(item())));

        client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter());

        Request request = server.requests().get(0);
        assertThat(request.query()).containsEntry("LAWD_CD", LAWD_CD).containsEntry("DEAL_YMD", DEAL_YMD)
                .containsEntry("pageNo", "1").containsEntry("numOfRows", "1000");
    }

    @Test
    void countsEveryRequestIncludingRetries() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.status(500), Reply.status(500), Reply.body(response(item())));
        server.enqueue(LAWD_CD, DEAL_YMD, 2, Reply.body(response(item())));
        TradeApiClient client = client();
        CallCounter counter = counter();

        client.fetchPage(LAWD_CD, DEAL_YMD, 1, counter);
        assertThat(counter.calls()).isEqualTo(3);
        client.fetchPage(LAWD_CD, DEAL_YMD, 2, counter);
        assertThat(counter.calls()).isEqualTo(4);
    }

    @Test
    void requestsWhenCallCountIsOneBelowTheThresholdAndStopsAtTheThreshold() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(response(item())));
        CallCounter counter = new CallCounter(3);
        TradeApiClient client = client();
        client.fetchPage(LAWD_CD, DEAL_YMD, 1, counter);
        client.fetchPage(LAWD_CD, DEAL_YMD, 1, counter);
        assertThat(counter.calls()).isEqualTo(2);

        client.fetchPage(LAWD_CD, DEAL_YMD, 1, counter);
        assertThat(counter.calls()).isEqualTo(3);
        assertThat(server.requestCount()).isEqualTo(3);

        TradeApiException e = failure(() -> client.fetchPage(LAWD_CD, DEAL_YMD, 1, counter));

        assertThat(e.kind()).isEqualTo(Kind.SELF_STOP);
        assertThat(e.isRunStopping()).isTrue();
        assertThat(e.failureReason()).isEqualTo("SELF_STOP: 3");
        assertThat(server.requestCount()).isEqualTo(3);
        assertThat(counter.calls()).isEqualTo(3);
    }

    @Test
    void doesNotRequestAtAllWhenThresholdIsAlreadyReached() {
        TradeApiException e = failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, new CallCounter(0)));

        assertThat(e.kind()).isEqualTo(Kind.SELF_STOP);
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void stopsRetryingWhenThresholdIsReachedInTheMiddleOfRetries() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.status(500));
        CallCounter counter = new CallCounter(2);

        TradeApiException e = failure(() -> client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter));

        assertThat(e.kind()).isEqualTo(Kind.SELF_STOP);
        assertThat(server.requestCount()).isEqualTo(2);
        assertThat(counter.calls()).isEqualTo(2);
    }

    @Test
    void sendsTheKeyEncodedExactlyOnce() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(response(item())));

        client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter());

        Request request = server.requests().get(0);
        assertThat(request.serviceKey()).isEqualTo(SERVICE_KEY);
        assertThat(request.rawQuery()).contains("serviceKey=fake%2Bkey%2Fwith%3Dchars%3D%3D&")
                .doesNotContain("%25");
    }

    @Test
    void sendsKeysWithOtherReservedCharactersUnchanged() {
        String tricky = "a b&c?d#e%f+g/h=i";
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(response(item())));
        TradeApiClient client = new TradeApiClient(new CollectorProperties(List.of(), tricky, "-", server.baseUrl(),
                10000, 500, Duration.ofSeconds(5), Duration.ofSeconds(5), null), noWaitPolicy());

        client.fetchPage(LAWD_CD, DEAL_YMD, 1, counter());

        Request request = server.requests().get(0);
        assertThat(request.serviceKey()).isEqualTo(tricky);
        assertThat(request.query()).containsEntry("LAWD_CD", LAWD_CD);
    }

    @Test
    void replacesUriBuildFailureWithFixedSentence() {
        TradeApiClient broken = new TradeApiClient(properties("http://[not-a-host/path", Duration.ofSeconds(5)),
                noWaitPolicy());
        CallCounter counter = counter();

        TradeApiException e = failure(() -> broken.fetchPage(LAWD_CD, DEAL_YMD, 1, counter));

        assertThat(e.kind()).isEqualTo(Kind.PARSE_ERROR);
        assertThat(counter.calls()).isZero();
        assertNoSecretInside(e);
        assertThat(e.getMessage()).doesNotContain("not-a-host");
    }

    @Test
    void notifiesTheRequestCallbackBeforeAnswering() {
        server.enqueue(LAWD_CD, DEAL_YMD, 1, Reply.body(response(item())));
        List<Integer> seen = new ArrayList<>();
        server.onRequest(request -> seen.add(request.sequence()));

        client().fetchPage(LAWD_CD, DEAL_YMD, 1, counter());

        assertThat(seen).containsExactly(1);
    }

    // 운영 재시도 정책(research R2). 대기 0인 시험용 정책이 아니라 운영 정책 객체를 직접 확인한다

    @Test
    void productionPolicyWaitsTwoThenFourSecondsThenStops() {
        BackOffExecution backOff = TradeApiConfiguration.productionRetryPolicy().getBackOff().start();

        assertThat(backOff.nextBackOff()).isEqualTo(2000);
        assertThat(backOff.nextBackOff()).isEqualTo(4000);
        assertThat(backOff.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void productionPolicyRetriesOnlyNetworkAndHttpErrors() {
        RetryPolicy policy = TradeApiConfiguration.productionRetryPolicy();

        assertThat(policy.shouldRetry(new ResourceAccessException("x"))).isTrue();
        assertThat(policy.shouldRetry(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "x", null,
                null, null))).isTrue();
        assertThat(policy.shouldRetry(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "x", null, null,
                null))).isTrue();
        assertThat(policy.shouldRetry(TradeApiException.resultCode(3, "x"))).isFalse();
        assertThat(policy.shouldRetry(TradeApiException.stopCode(22))).isFalse();
        assertThat(policy.shouldRetry(TradeApiException.selfStop(5))).isFalse();
        assertThat(policy.shouldRetry(TradeApiException.parseError("x"))).isFalse();
        assertThat(policy.shouldRetry(new IllegalStateException("x"))).isFalse();
    }

    @Test
    void createsPolicyAndClientBeansOnlyWhenRegionsAreConfigured() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(EnableProperties.class, TradeApiConfiguration.class)
                .withPropertyValues("estate.collector.cron=-", "estate.collector.base-url=http://localhost/fake",
                        "estate.collector.daily-call-limit=10000", "estate.collector.self-stop-margin=500",
                        "estate.collector.connect-timeout=5s", "estate.collector.read-timeout=30s");

        runner.run(context -> assertThat(context).doesNotHaveBean(TradeApiClient.class)
                .doesNotHaveBean(RetryPolicy.class));
        runner.withPropertyValues("estate.collector.regions=99999", "estate.collector.service-key=" + SERVICE_KEY,
                "estate.collector.raw.endpoint=http://localhost:3900", "estate.collector.raw.region=garage",
                "estate.collector.raw.bucket=raw-fake", "estate.collector.raw.access-key=fake-access",
                "estate.collector.raw.secret-key=fake-secret").run(context -> {
                    assertThat(context).hasSingleBean(TradeApiClient.class).hasSingleBean(RetryPolicy.class);
                    assertThat(context.getBean(RetryPolicy.class).getBackOff().start().nextBackOff())
                            .isEqualTo(2000);
                });
    }

    @EnableConfigurationProperties(CollectorProperties.class)
    static class EnableProperties {
    }
}
