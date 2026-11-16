package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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

import estate.common.crypto.ColumnCipher;
import estate.support.FakeGoogleServer;
import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T051 보안 경계: 재인증 성공 때의 세션 교체(US2-5, FR-014, 결정 기록 0013, research R4)와 옛 세션이 없거나 만료된 재인증 실패(research R5).
 * 마지막 구글 인증 시각은 세션 직렬화를 읽지 않고, 새 세션으로 한 탈퇴가 재인증 시각 기준 5분 안에 처리되고 5분 1초에 REAUTH_REQUIRED인 것으로 본다.
 * 옛 세션 만료는 재인증 시작 요청 뒤, 콜백 전에 세션 행을 옮겨 만든다(콜백이 옛 세션을 확인하는 시점이 콜백이므로).
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class ReauthSessionSecurityTest {

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
    ColumnCipher cipher;
    LoginClient login;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    @Test
    @DisplayName("T051 US2-5 FR-014: 재인증에 성공하면 새 세션 행과 쿠키가 생기고 옛 세션 행이 삭제되며 돌아갈 경로로 간다")
    void reauthReplacesSession() throws Exception {
        GoogleAccount account = account();
        Cookie oldCookie = login.login(account).requireSessionCookie();
        String oldId = idOf(oldCookie);
        long userId = userIdOf(oldId);
        assertThat(sessions.count()).isEqualTo(1);

        LoginClient.Result r = login.login(account, "/withdraw", "reauth", oldCookie);

        assertThat(r.location()).isEqualTo("/withdraw");
        Cookie newCookie = r.requireSessionCookie();
        String newId = idOf(newCookie);
        assertThat(newCookie.getValue()).isNotEqualTo(oldCookie.getValue());
        assertThat(sessions.exists(oldId)).isFalse();
        assertThat(sessions.exists(newId)).isTrue();
        assertThat(sessions.rowsOfUser(userId)).extracting(SessionRows.Row::sessionId).containsExactly(newId);
        assertThat(sessions.count()).isEqualTo(1);
        expectUnauthenticated(mvc.perform(https(get("/api/v1/me")).cookie(oldCookie)));
        mvc.perform(https(get("/api/v1/me")).cookie(newCookie)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T051 US2-5 FR-014: 재인증으로 생긴 세션의 creation_time은 재인증 시각이다(옛 세션의 로그인 시각을 잇지 않고 30일을 새로 셈)")
    void reauthRestartsAbsoluteLifetime() throws Exception {
        GoogleAccount account = account();
        Cookie oldCookie = login.login(account).requireSessionCookie();
        String oldId = idOf(oldCookie);
        // 옛 세션은 20일 전에 로그인한 것으로 둔다(절대 30일 전이라 재인증은 성공해야 한다)
        assertThat(sessions.shiftBackBySessionId(oldId, Duration.ofDays(20), Duration.ZERO, Duration.ZERO)).isEqualTo(1);
        long oldCreation = sessions.rowOf(oldId).creationTime();

        long before = Instant.now().toEpochMilli();
        LoginClient.Result r = login.login(account, "/withdraw", "reauth", oldCookie);
        long after = Instant.now().toEpochMilli();

        Cookie newCookie = r.requireSessionCookie();
        long newCreation = sessions.rowOf(idOf(newCookie)).creationTime();
        assertThat(newCreation).as("새 세션의 creation_time이 재인증 콜백 시각이어야 한다").isBetween(before, after);
        assertThat(newCreation - oldCreation).isGreaterThanOrEqualTo(Duration.ofDays(20).toMillis());
        assertThat(sessions.exists(oldId)).isFalse();
    }

    @Test
    @DisplayName("T051 US2-5 FR-014: 재인증으로 생긴 세션의 마지막 구글 인증 시각은 재인증 시각이다(5분 1초 뒤 REAUTH_REQUIRED, 정확히 5분 뒤 탈퇴 처리)")
    void reauthSetsLastGoogleAuthToReauthTime() throws Exception {
        GoogleAccount account = account();
        Instant loginAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.freezeAt(loginAt);
        Cookie oldCookie = login.login(account).requireSessionCookie();

        Instant reauthAt = loginAt.plus(Duration.ofMinutes(10));
        clock.freezeAt(reauthAt);
        Cookie newCookie = login.login(account, "/withdraw", "reauth", oldCookie).requireSessionCookie();
        String newId = idOf(newCookie);
        long userId = userIdOf(newId);

        // 재인증 시각보다 늦게 잡혔다면 통과하는 쪽: 5분 1초 뒤에는 거절되어야 한다
        clock.freezeAt(reauthAt.plus(Duration.ofMinutes(5)).plusSeconds(1));
        withdraw(newCookie).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        assertThat(userCount(account)).isEqualTo(1);
        assertThat(sessions.exists(newId)).isTrue();

        // 첫 로그인 시각이 남았다면 거절되는 쪽: 재인증 뒤 정확히 5분이면 처리되어야 한다
        clock.freezeAt(reauthAt.plus(Duration.ofMinutes(5)));
        withdraw(newCookie).andExpect(status().isNoContent());
        assertThat(userCount(account)).isZero();
        assertThat(sessions.rowsOfUser(userId)).isEmpty();
    }

    @Test
    @DisplayName("T051 research R5: 재인증 시작 뒤 옛 세션이 없어지면(로그아웃) /reauth-failed이고 새 세션 행과 쿠키가 없다")
    void reauthWithMissingOldSessionFails() throws Exception {
        GoogleAccount account = account();
        Cookie oldCookie = login.login(account).requireSessionCookie();
        String oldId = idOf(oldCookie);
        long userId = userIdOf(oldId);
        FakeGoogleServer.reset();
        FakeGoogleServer.willReturn(account);
        MvcResult start = login.start("/withdraw", "reauth", oldCookie);
        assertThat(sessions.exists(oldId)).isTrue();
        mvc.perform(https(post("/api/v1/logout")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(oldCookie)).andExpect(status().isNoContent());
        assertThat(sessions.exists(oldId)).isFalse();

        MvcResult callback = login.callback(start, login.authorize(start, account));

        assertReauthFailedWithoutNewSession(callback, userId, oldId);
    }

    @Test
    @DisplayName("T051 research R5: 재인증 시작 뒤 옛 세션이 유휴 7일을 넘기면 /reauth-failed이고 새 세션 행과 쿠키가 없다")
    void reauthWithIdleExpiredOldSessionFails() throws Exception {
        GoogleAccount account = account();
        Cookie oldCookie = login.login(account).requireSessionCookie();
        String oldId = idOf(oldCookie);
        long userId = userIdOf(oldId);
        FakeGoogleServer.reset();
        FakeGoogleServer.willReturn(account);
        MvcResult start = login.start("/withdraw", "reauth", oldCookie);
        assertThat(sessions.shiftBackBySessionId(oldId, Duration.ofDays(10), Duration.ofDays(8), Duration.ofDays(8))).isEqualTo(1);

        MvcResult callback = login.callback(start, login.authorize(start, account));

        assertReauthFailedWithoutNewSession(callback, userId, oldId);
    }

    @Test
    @DisplayName("T051 research R5: 재인증 시작 뒤 옛 세션이 절대 30일을 넘기면 /reauth-failed이고 새 세션 행과 쿠키가 없다")
    void reauthWithAbsoluteExpiredOldSessionFails() throws Exception {
        GoogleAccount account = account();
        Cookie oldCookie = login.login(account).requireSessionCookie();
        String oldId = idOf(oldCookie);
        long userId = userIdOf(oldId);
        FakeGoogleServer.reset();
        FakeGoogleServer.willReturn(account);
        MvcResult start = login.start("/withdraw", "reauth", oldCookie);
        assertThat(sessions.shiftBackBySessionId(oldId, Duration.ofDays(31), Duration.ofDays(1), Duration.ofDays(1))).isEqualTo(1);

        MvcResult callback = login.callback(start, login.authorize(start, account));

        assertReauthFailedWithoutNewSession(callback, userId, oldId);
    }

    @Test
    @DisplayName("T051 T053 0013 research R5: 유효한 옛 세션의 삭제만 0건이 되면 롤백되어 /reauth-failed이고 새 세션, 쿠키, 이메일 갱신이 없다")
    void reauthRollsBackWhenOldSessionDeleteAffectsNoRow() throws Exception {
        GoogleAccount account = account();
        Cookie oldCookie = login.login(account).requireSessionCookie();
        String oldId = idOf(oldCookie);
        long userId = userIdOf(oldId);
        GoogleAccount changed = account.withEmail("changed-" + account.email()).withEmailVerified(false);
        FakeGoogleServer.reset();
        FakeGoogleServer.willReturn(changed);
        MvcResult start = login.start("/withdraw", "reauth", oldCookie);
        SessionRows.Row oldRowBefore = sessions.rowOf(oldId);
        assertThat(sessions.count()).isEqualTo(1);

        // 옛 세션 행의 DELETE만 조용히 건너뛰게 해(삭제 0건) 새 세션 저장 뒤 마지막 단계에서 실패하게 만든다
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String function = "test_skip_session_delete_fn_" + suffix;
        String trigger = "test_skip_session_delete_" + suffix;
        try {
            jdbc.sql("create function " + function + "() returns trigger language plpgsql as $$\n"
                    + "begin\n  if old.session_id = '" + oldId + "' then return null; end if;\n  return old;\nend $$").update();
            jdbc.sql("create trigger " + trigger + " before delete on spring_session for each row execute function " + function + "()").update();

            MvcResult callback = login.callback(start, login.authorize(start, changed));

            assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/reauth-failed");
            assertThat(LoginClient.cookie(callback, LoginClient.SESSION_COOKIE)).as("새 세션 쿠키가 없어야 한다").isNull();
            assertThat(sessions.count()).as("새 세션 행이 롤백되어 옛 세션 행 하나만 남아야 한다").isEqualTo(1);
            assertThat(sessions.rowOf(oldId)).as("옛 세션 행이 그대로다").isEqualTo(oldRowBefore);
            var user = jdbc.sql("select login_email, email_verified from \"user\" where id = :id").param("id", userId)
                    .query((rs, n) -> new Object[] {rs.getBytes(1), rs.getBoolean(2)}).single();
            assertThat(cipher.decrypt((byte[]) user[0], "user.login_email:" + userId)).as("바꿔 보낸 이메일로 갱신되지 않는다").isEqualTo(account.email());
            assertThat((Boolean) user[1]).as("검증 여부도 갱신되지 않는다").isTrue();
        } finally {
            jdbc.sql("drop trigger if exists " + trigger + " on spring_session").update();
            jdbc.sql("drop function if exists " + function + "()").update();
        }
    }

    /** /reauth-failed로 가고, 세션 쿠키를 발급하지 않으며, 남은 세션 행은 (있다면) 옛 세션 행뿐이다. */
    private void assertReauthFailedWithoutNewSession(MvcResult callback, long userId, String oldId) {
        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/reauth-failed");
        assertThat(LoginClient.cookie(callback, LoginClient.SESSION_COOKIE)).as("새 세션 쿠키가 없어야 한다").isNull();
        assertThat(sessions.rowsOfUser(userId)).extracting(SessionRows.Row::sessionId).isSubsetOf(oldId);
        assertThat(sessions.count()).as("새 세션 행이 없어야 한다").isEqualTo(sessions.rowsOfUser(userId).size());
    }

    private ResultActions withdraw(Cookie cookie) throws Exception {
        return mvc.perform(https(delete("/api/v1/me")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie));
    }

    private static void expectUnauthenticated(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private long userIdOf(String sessionId) {
        return Long.parseLong(sessions.rowOf(sessionId).principalName());
    }

    private long userCount(GoogleAccount account) {
        return jdbc.sql("select count(*) from \"user\" where provider = 'google' and provider_subject = :s")
                .param("s", account.sub()).query(Long.class).single();
    }

    private static String idOf(Cookie cookie) {
        return SessionRows.idOfCookie(cookie.getValue());
    }

    private static GoogleAccount account() {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("reauth-session-" + id, "reauth-session-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
