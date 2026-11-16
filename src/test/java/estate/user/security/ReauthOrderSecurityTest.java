package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T049 보안 경계: 탈퇴(DELETE /api/v1/me)의 재인증 판정과 확인 순서(US2-1, US2-2, US2-6, FR-011, FR-015, SC-002, 결정 기록 0013, research R8).
 * 마지막 구글 인증 시각은 로그인 콜백을 처리한 Clock 시각이므로, 초 단위로 자른 시각에 Clock을 멈춘 채 로그인하고 Clock을 옮겨 판정한다.
 * 경계: 0013은 "차이가 5분보다 크면", "60초를 넘게 미래이면" 재인증을 요구하므로 정확히 5분과 정확히 60초 미래는 처리된다.
 * "기록 없음"(마지막 구글 인증 시각이 없는 세션)은 테스트 지원으로 만들 수 없어(세션 직렬화 내부를 써야 함) 여기서 다루지 않는다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class ReauthOrderSecurityTest {

    static final String ORIGIN = "https://localhost";

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionRows sessions;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    DataSource dataSource;
    LoginClient login;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    @Test
    @DisplayName("T049 US2-1 FR-011 SC-002: 마지막 구글 인증이 정확히 5분 전이면 탈퇴가 처리된다(경계는 허용 쪽)")
    void exactlyFiveMinutesIsProcessed() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie cookie = loginAt(account, authAt);

        clock.freezeAt(authAt.plus(Duration.ofMinutes(5)));

        expectWithdrawn(account, cookie);
    }

    @Test
    @DisplayName("T049 US2-1 FR-011: 로그인 직후(5분 안)면 탈퇴가 재인증 없이 처리된다")
    void rightAfterLoginIsProcessed() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie cookie = loginAt(account, authAt);

        clock.freezeAt(authAt.plus(Duration.ofMinutes(4)));

        expectWithdrawn(account, cookie);
    }

    @Test
    @DisplayName("T049 US2-2 FR-011 SC-002: 마지막 구글 인증이 5분 1초 전이면 401 REAUTH_REQUIRED이고 사용자와 세션이 그대로다")
    void fiveMinutesOneSecondIsReauthRequired() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie cookie = loginAt(account, authAt);

        clock.freezeAt(authAt.plus(Duration.ofMinutes(5)).plusSeconds(1));

        expectReauthRequiredAndNothingChanged(account, cookie);
    }

    @Test
    @DisplayName("T049 US2-2 FR-011 SC-002: 마지막 구글 인증이 한 시간 전이면 401 REAUTH_REQUIRED이고 사용자와 세션이 그대로다")
    void oneHourAgoIsReauthRequired() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie cookie = loginAt(account, authAt);

        clock.freezeAt(authAt.plus(Duration.ofHours(1)));

        expectReauthRequiredAndNothingChanged(account, cookie);
    }

    @Test
    @DisplayName("T049 US2-2 FR-011 짝: 마지막 구글 인증이 현재 시각보다 정확히 60초 미래면 탈퇴가 처리된다(경계는 허용 쪽)")
    void exactlySixtySecondsInFutureIsProcessed() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie cookie = loginAt(account, authAt);

        clock.freezeAt(authAt.minusSeconds(60));

        expectWithdrawn(account, cookie);
    }

    @Test
    @DisplayName("T049 US2-2 FR-011: 마지막 구글 인증이 현재 시각보다 61초 미래면 401 REAUTH_REQUIRED이고 사용자와 세션이 그대로다")
    void sixtyOneSecondsInFutureIsReauthRequired() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie cookie = loginAt(account, authAt);

        clock.freezeAt(authAt.minusSeconds(61));

        expectReauthRequiredAndNothingChanged(account, cookie);
    }

    @Test
    @DisplayName("T049 US2-6 FR-015: 절대 30일이 지난 세션은 마지막 인증이 오래됐어도 REAUTH_REQUIRED가 아니라 401 UNAUTHENTICATED")
    void absoluteExpiredSessionIsUnauthenticatedNotReauth() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie cookie = loginAt(account, authAt);
        clock.freezeAt(authAt.plus(Duration.ofMinutes(10)));
        assertThat(sessions.shiftBackBySessionId(idOf(cookie), Duration.ofDays(31), Duration.ofDays(1), Duration.ofDays(1))).isEqualTo(1);

        expectUnauthenticated(withdraw(cookie));

        assertThat(userCount(account)).as("거절된 탈퇴는 사용자를 지우지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("T049 US2-6 FR-015: 유휴 7일이 지난 세션은 마지막 인증이 오래됐어도 REAUTH_REQUIRED가 아니라 401 UNAUTHENTICATED")
    void idleExpiredSessionIsUnauthenticatedNotReauth() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie cookie = loginAt(account, authAt);
        clock.freezeAt(authAt.plus(Duration.ofMinutes(10)));
        assertThat(sessions.shiftBackBySessionId(idOf(cookie), Duration.ofDays(10), Duration.ofDays(8), Duration.ofDays(8))).isEqualTo(1);

        expectUnauthenticated(withdraw(cookie));

        assertThat(userCount(account)).as("거절된 탈퇴는 사용자를 지우지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("T049 US2-6 FR-015: 로그아웃으로 삭제된 세션은 마지막 인증이 오래됐어도 REAUTH_REQUIRED가 아니라 401 UNAUTHENTICATED")
    void deletedSessionIsUnauthenticatedNotReauth() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie cookie = loginAt(account, authAt);
        clock.freezeAt(authAt.plus(Duration.ofMinutes(10)));
        assertThat(sessions.exists(idOf(cookie))).isTrue();
        mvc.perform(https(post("/api/v1/logout")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie)).andExpect(status().isNoContent());
        assertThat(sessions.exists(idOf(cookie))).isFalse();

        expectUnauthenticated(withdraw(cookie));

        assertThat(userCount(account)).as("거절된 탈퇴는 사용자를 지우지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("T049 spec Edge Cases US2-5: REAUTH_REQUIRED를 받고 재인증한 뒤 5분 안의 두 번째 시도는 재인증 없이 처리된다")
    void secondAttemptAfterReauthIsProcessed() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie oldCookie = loginAt(account, authAt);
        Instant reauthAt = authAt.plus(Duration.ofMinutes(10));
        clock.freezeAt(reauthAt);
        expectReauthRequired(withdraw(oldCookie));

        LoginClient.Result reauth = login.login(account, "/withdraw", "reauth", oldCookie);
        Cookie newCookie = reauth.requireSessionCookie();
        assertThat(newCookie.getValue()).isNotEqualTo(oldCookie.getValue());
        clock.freezeAt(reauthAt.plus(Duration.ofMinutes(2)));

        expectWithdrawn(account, newCookie);
    }

    @Test
    @DisplayName("T049 T052 FR-015: 다른 트랜잭션이 사용자 행을 잠근 동안에도 5분 넘은 탈퇴는 잠금을 기다리지 않고 바로 401 REAUTH_REQUIRED다(판정이 잠금보다 먼저)")
    void reauthIsJudgedBeforeUserRowLock() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie cookie = loginAt(account, authAt);
        clock.freezeAt(authAt.plus(Duration.ofMinutes(10)));
        long userId = userIdOf(cookie);
        String userBefore = userRow(account);
        List<String> sessionsBefore = sessionIdsOf(userId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Connection lock = lockUserRow(userId);
        Future<MvcResult> pending = null;
        try {
            pending = executor.submit(() -> withdraw(cookie).andReturn());
            MvcResult result = pending.get(5, TimeUnit.SECONDS);
            status().isUnauthorized().match(result);
            jsonPath("$.code").value("REAUTH_REQUIRED").match(result);
        } finally {
            cleanUp(lock, false, pending, executor);
        }

        assertThat(userRow(account)).isEqualTo(userBefore);
        assertThat(sessionIdsOf(userId)).isEqualTo(sessionsBefore);
    }

    @Test
    @DisplayName("T049 T052 대조: 5분 안의 탈퇴는 다른 트랜잭션이 사용자 행을 잠근 동안 끝나지 않고, 잠금을 풀면 204로 끝난다")
    void withdrawWithinFiveMinutesWaitsForUserRowLock() throws Exception {
        GoogleAccount account = account();
        Instant authAt = frozenNow();
        Cookie cookie = loginAt(account, authAt);
        clock.freezeAt(authAt.plus(Duration.ofMinutes(1)));
        long userId = userIdOf(cookie);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Connection lock = lockUserRow(userId);
        boolean released = false;
        Future<MvcResult> pending = null;
        try {
            int holderPid = backendPid(lock);
            pending = executor.submit(() -> withdraw(cookie).andReturn());
            assertThat(waitsForLockOf(holderPid, Duration.ofSeconds(5)))
                    .as("탈퇴 요청이 잠금을 잡은 트랜잭션을 기다리는 것이 pg_stat_activity에 보여야 한다").isTrue();
            assertThat(pending.isDone()).as("잠금이 있는 동안 탈퇴가 끝나면 안 된다").isFalse();

            release(lock);
            released = true;
            MvcResult result = pending.get(10, TimeUnit.SECONDS);
            status().isNoContent().match(result);
        } finally {
            cleanUp(lock, released, pending, executor);
        }

        assertThat(userCount(account)).isZero();
        assertThat(sessions.rowsOfUser(userId)).isEmpty();
    }

    /** 별도 연결의 열린 트랜잭션에서 사용자 행을 FOR UPDATE로 잡는다. release로 롤백하고 닫는다. */
    private Connection lockUserRow(long userId) throws Exception {
        Connection c = dataSource.getConnection();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("select id from \"user\" where id = ? for update")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("잠글 사용자 행이 있어야 한다").isTrue();
                }
            }
            return c;
        } catch (Exception | AssertionError e) {
            release(c);
            throw e;
        }
    }

    private static void release(Connection lock) throws Exception {
        try (lock) {
            lock.rollback();
        }
    }

    private static int backendPid(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("select pg_backend_pid()"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** holderPid가 막고 있어 Lock 대기 중인 다른 백엔드가 제한 시간 안에 보이면 true. */
    private boolean waitsForLockOf(int holderPid, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("""
                    select count(*) from pg_stat_activity
                    where wait_event_type = 'Lock' and :holder = any(pg_blocking_pids(pid))""")
                    .param("holder", holderPid).query(Long.class).single();
            if (waiting > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /**
     * 다음 테스트로 스레드나 연결이 새지 않게 정리한다. 잠금을 먼저 풀어 기다리던 요청이 끝날 수 있게 하고,
     * 그다음 남은 작업을 취소하고 실행기를 끝낸다.
     */
    private static void cleanUp(Connection lock, boolean released, Future<?> pending, ExecutorService executor) throws Exception {
        try {
            if (!released) {
                release(lock);
            }
        } finally {
            if (pending != null) {
                pending.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("탈퇴 요청 스레드가 끝나야 한다").isTrue();
        }
    }

    /** 초 단위로 자른 지금. 마지막 구글 인증 시각을 초 단위로 다뤄도 경계가 흔들리지 않게 한다. */
    private static Instant frozenNow() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** Clock을 at에 멈춘 채 로그인한다. 콜백 처리 시각, 곧 마지막 구글 인증 시각이 at이 된다. */
    private Cookie loginAt(GoogleAccount account, Instant at) throws Exception {
        clock.freezeAt(at);
        Cookie cookie = login.login(account).requireSessionCookie();
        assertThat(sessions.exists(idOf(cookie))).isTrue();
        assertThat(userCount(account)).isEqualTo(1);
        return cookie;
    }

    private ResultActions withdraw(Cookie cookie) throws Exception {
        return mvc.perform(https(delete("/api/v1/me")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie));
    }

    /** 204이고 사용자 행과 그 사용자의 세션 행이 없어진다(탈퇴가 실제로 처리됨). */
    private void expectWithdrawn(GoogleAccount account, Cookie cookie) throws Exception {
        long userId = userIdOf(cookie);
        assertThat(sessions.rowsOfUser(userId)).isNotEmpty();

        withdraw(cookie).andExpect(status().isNoContent());

        assertThat(userCount(account)).isZero();
        assertThat(sessions.rowsOfUser(userId)).isEmpty();
    }

    /** 401 REAUTH_REQUIRED이고 사용자 행, 세션 행이 그대로이며 같은 쿠키로 내 정보가 계속 조회된다. */
    private void expectReauthRequiredAndNothingChanged(GoogleAccount account, Cookie cookie) throws Exception {
        String userBefore = userRow(account);
        long userId = userIdOf(cookie);
        List<String> sessionsBefore = sessionIdsOf(userId);
        assertThat(sessionsBefore).contains(idOf(cookie));

        expectReauthRequired(withdraw(cookie));

        assertThat(userRow(account)).isEqualTo(userBefore);
        assertThat(sessionIdsOf(userId)).isEqualTo(sessionsBefore);
        mvc.perform(https(get("/api/v1/me")).cookie(cookie)).andExpect(status().isOk());
    }

    private static void expectReauthRequired(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    }

    private static void expectUnauthenticated(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private long userIdOf(Cookie cookie) {
        return Long.parseLong(sessions.rowOf(idOf(cookie)).principalName());
    }

    private List<String> sessionIdsOf(long userId) {
        return sessions.rowsOfUser(userId).stream().map(SessionRows.Row::sessionId).toList();
    }

    private long userCount(GoogleAccount account) {
        return jdbc.sql("select count(*) from \"user\" where provider = 'google' and provider_subject = :s")
                .param("s", account.sub()).query(Long.class).single();
    }

    /** 사용자 행 전체(번호, 이메일 암호문, 검증 여부, 표시 이름). 없으면 예외. */
    private String userRow(GoogleAccount account) {
        return jdbc.sql("""
                select id || '|' || encode(login_email, 'hex') || '|' || email_verified || '|' || coalesce(display_name, '<null>')
                from "user" where provider = 'google' and provider_subject = :s""").param("s", account.sub()).query(String.class).single();
    }

    private static String idOf(Cookie cookie) {
        return SessionRows.idOfCookie(cookie.getValue());
    }

    private static GoogleAccount account() {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("reauth-order-" + id, "reauth-order-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
