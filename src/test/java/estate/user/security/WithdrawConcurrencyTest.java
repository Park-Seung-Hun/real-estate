package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import estate.support.FakeGoogleServer;
import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T044 보안 경계: 같은 사용자의 탈퇴와 로그인 콜백, 같은 구글 계정의 처음 로그인 콜백 둘이 동시에 와도 교착 없이 끝나고
 * 사용자와 세션 행이 어긋나지 않는다(research R5, R9).
 * 두 요청은 실제 스레드 둘에서 CyclicBarrier로 같은 순간에 출발한다. PostgreSQL은 교착을 찾으면 한쪽을 오류로 끝내므로
 * (deadlock_timeout 기본 1초) 교착은 응답 상태(오류 응답이나 실패 화면 이동)로 드러나고, 애플리케이션 쪽에서 멈추면 제한 시간으로 드러난다.
 * 탈퇴는 로그인 직후(마지막 구글 인증 5분 안)에 보내므로 재인증 판정(US2)이 붙어도 처리된다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class WithdrawConcurrencyTest {

    static final String ORIGIN = "https://localhost";
    static final long TIMEOUT_SECONDS = 30;

    /**
     * 30회 반복하고 탈퇴 스레드만 출발 뒤 0~350ms(25ms 간격, 15단계를 두 바퀴) 늦춘다.
     * 콜백은 트랜잭션 전에 가짜 구글 서버와 토큰 교환을 하므로, 늦추지 않으면 거의 늘 탈퇴가 먼저 사용자 행을 잠근다.
     * 늦춤을 넓게 훑어야 "탈퇴가 먼저"와 "콜백이 먼저"가 모두 나오고, 둘이 겹치는 경계 근처에서 잠금 경합이 생긴다.
     * 한 순서만으로 통과하지 않게 두 결과가 모두 나왔는지 마지막에 확인한다.
     */
    static final int RACE_ITERATIONS = 30;
    static final int DELAY_STEPS = 15;
    static final long DELAY_STEP_MS = 25;

    /** 처음 로그인 둘은 같은 경로를 같은 순간에 출발하므로 늦춤 없이 반복한다. 유니크 제약 충돌(R5의 다시 실행)이 생길 기회를 늘리려고 10회. */
    static final int FIRST_LOGIN_ITERATIONS = 10;

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionRows sessions;
    @Autowired
    JdbcClient jdbc;
    LoginClient login;
    ExecutorService pool;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
        pool = Executors.newFixedThreadPool(2);
    }

    /** 시간 초과나 검증 실패로 끝나도 남은 요청이 다음 테스트의 DB 초기화(Reset)와 겹치지 않게 끝날 때까지 기다린다. */
    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("남은 요청 스레드가 " + TIMEOUT_SECONDS + "초 안에 끝나지 않았다").isTrue();
    }

    @Test
    @DisplayName("T044 research R5 R9: 같은 사용자의 탈퇴와 로그인 콜백을 동시에 실행해도 교착 없이 끝나고, 남는 세션은 없거나 새로 만든 사용자 행의 세션뿐")
    void withdrawAndLoginCallbackAtTheSameTime() throws Exception {
        int withdrawFirst = 0;
        int callbackFirst = 0;
        for (int i = 0; i < RACE_ITERATIONS; i++) {
            String round = "round " + i;
            GoogleAccount account = account("race");
            Cookie old = login.login(account).requireSessionCookie();
            long oldId = userIdOf(account.sub());
            assertThat(sessions.rowsOfUser(oldId)).as(round).hasSize(1);

            // 다른 기기의 일반 로그인을 콜백 직전까지 진행해 둔다(동시 콜백은 예약을 직접 쌓는다)
            FakeGoogleServer.reset();
            FakeGoogleServer.willReturn(account);
            MvcResult start = login.start("/", null, null);
            Map<String, String> query = login.authorize(start, account);

            long withdrawDelay = (i % DELAY_STEPS) * DELAY_STEP_MS;
            CyclicBarrier go = new CyclicBarrier(2);
            Future<MvcResult> withdraw = pool.submit(at(go, withdrawDelay,
                    () -> mvc.perform(https(delete("/api/v1/me")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(old)).andReturn()));
            Future<MvcResult> callback = pool.submit(at(go, 0, () -> login.callback(start, query)));
            MvcResult w = withdraw.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            MvcResult c = callback.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 교착으로 한쪽이 중단되지 않았다: 탈퇴는 204, 콜백은 성공 이동과 새 세션 쿠키
            assertThat(w.getResponse().getStatus()).as(round + " 탈퇴 응답").isEqualTo(204);
            assertThat(c.getResponse().getStatus()).as(round + " 콜백 응답").isEqualTo(302);
            assertThat(c.getResponse().getHeader(HttpHeaders.LOCATION)).as(round + " 콜백 이동").isEqualTo("/");
            Cookie fresh = LoginClient.cookie(c, LoginClient.SESSION_COOKIE);
            assertThat(fresh).as(round + " 콜백의 세션 쿠키").isNotNull();
            String freshId = SessionRows.idOfCookie(fresh.getValue());

            assertThat(sessions.rowsOfUser(oldId)).as(round + " 옛 사용자 번호의 세션은 남지 않는다").isEmpty();
            assertThat(jdbc.sql("select count(*) from \"user\" where id = :id").param("id", oldId).query(Long.class).single())
                    .as(round + " 옛 사용자 행은 남지 않는다").isZero();
            assertThat(mvc.perform(https(get("/api/v1/me")).cookie(old)).andReturn().getResponse().getStatus()).as(round).isEqualTo(401);

            long users = userCount(account.sub());
            if (users == 0) {
                // 콜백이 먼저: 탈퇴가 콜백의 새 세션까지 삭제했다
                callbackFirst++;
                assertThat(sessions.exists(freshId)).as(round + " 콜백이 먼저면 새 세션도 삭제된다").isFalse();
                assertThat(mvc.perform(https(get("/api/v1/me")).cookie(fresh)).andReturn().getResponse().getStatus()).as(round).isEqualTo(401);
            } else {
                // 탈퇴가 먼저: 콜백이 새 번호의 사용자 행을 만들고 그 세션 하나만 남는다
                withdrawFirst++;
                assertThat(users).as(round).isEqualTo(1);
                long newId = userIdOf(account.sub());
                assertThat(newId).as(round + " 새로 만든 사용자 행").isNotEqualTo(oldId);
                assertThat(sessions.rowsOfUser(newId)).extracting(SessionRows.Row::sessionId).as(round).containsExactly(freshId);
                assertThat(mvc.perform(https(get("/api/v1/me")).cookie(fresh)).andReturn().getResponse().getStatus()).as(round).isEqualTo(200);
            }
            // 이 테스트에서 남을 수 있는 세션은 "탈퇴가 먼저"였던 회차의 새 사용자 세션뿐이다(Reset이 시작 때 모두 지움)
            assertThat(sessions.count()).as(round + " 다른 주체의 세션이 남지 않는다").isEqualTo(withdrawFirst);
        }
        assertThat(withdrawFirst).as("탈퇴가 먼저인 순서가 한 번도 나오지 않았다(늦춤 범위를 다시 본다)").isPositive();
        assertThat(callbackFirst).as("콜백이 먼저인 순서가 한 번도 나오지 않았다(늦춤 범위를 다시 본다)").isPositive();
    }

    @Test
    @DisplayName("T044 research R5: 같은 구글 계정의 처음 로그인 콜백 둘을 동시에 실행하면 사용자 행 하나와 세션 행 둘이 남는다")
    void twoFirstLoginCallbacksAtTheSameTime() throws Exception {
        for (int i = 0; i < FIRST_LOGIN_ITERATIONS; i++) {
            String round = "round " + i;
            GoogleAccount account = account("first");
            assertThat(userCount(account.sub())).as(round).isZero();

            FakeGoogleServer.reset();
            FakeGoogleServer.willReturn(account);
            FakeGoogleServer.willReturn(account);
            MvcResult startA = login.start("/", null, null);
            MvcResult startB = login.start("/", null, null);
            Map<String, String> queryA = login.authorize(startA, account);
            Map<String, String> queryB = login.authorize(startB, account);

            CyclicBarrier go = new CyclicBarrier(2);
            Future<MvcResult> a = pool.submit(at(go, 0, () -> login.callback(startA, queryA)));
            Future<MvcResult> b = pool.submit(at(go, 0, () -> login.callback(startB, queryB)));
            MvcResult ra = a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            MvcResult rb = b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            for (MvcResult r : new MvcResult[] {ra, rb}) {
                assertThat(r.getResponse().getStatus()).as(round + " 콜백 응답").isEqualTo(302);
                assertThat(r.getResponse().getHeader(HttpHeaders.LOCATION)).as(round + " 콜백 이동").isEqualTo("/");
            }
            Cookie cookieA = LoginClient.cookie(ra, LoginClient.SESSION_COOKIE);
            Cookie cookieB = LoginClient.cookie(rb, LoginClient.SESSION_COOKIE);
            assertThat(cookieA).as(round).isNotNull();
            assertThat(cookieB).as(round).isNotNull();

            assertThat(userCount(account.sub())).as(round + " 사용자 행 하나").isEqualTo(1);
            long id = userIdOf(account.sub());
            assertThat(sessions.rowsOfUser(id)).extracting(SessionRows.Row::sessionId).as(round + " 세션 행 둘")
                    .containsExactlyInAnyOrder(SessionRows.idOfCookie(cookieA.getValue()), SessionRows.idOfCookie(cookieB.getValue()));
            assertThat(mvc.perform(https(get("/api/v1/me")).cookie(cookieA)).andReturn().getResponse().getStatus()).as(round).isEqualTo(200);
            assertThat(mvc.perform(https(get("/api/v1/me")).cookie(cookieB)).andReturn().getResponse().getStatus()).as(round).isEqualTo(200);
        }
        assertThat(sessions.count()).isEqualTo(2L * FIRST_LOGIN_ITERATIONS);
    }

    /** 두 스레드가 모두 도착하면 같은 순간에 출발하고, delayMs만큼 늦춘 뒤 요청한다. */
    private static Callable<MvcResult> at(CyclicBarrier go, long delayMs, Callable<MvcResult> request) {
        return () -> {
            go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            return request.call();
        };
    }

    private long userIdOf(String sub) {
        return jdbc.sql("select id from \"user\" where provider = 'google' and provider_subject = :sub")
                .param("sub", sub).query(Long.class).single();
    }

    private long userCount(String sub) {
        return jdbc.sql("select count(*) from \"user\" where provider = 'google' and provider_subject = :sub")
                .param("sub", sub).query(Long.class).single();
    }

    private static GoogleAccount account(String tag) {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("withdraw-race-" + tag + "-" + id, "withdraw-race-" + tag + "-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
