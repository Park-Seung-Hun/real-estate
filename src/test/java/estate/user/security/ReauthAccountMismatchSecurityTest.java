package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T050 보안 경계: 재인증에서 다른 구글 계정(다른 sub)을 고른 경우(US2-4, FR-013, FR-019, SC-003, 결정 기록 0013, research R4, R5).
 * 재인증 시작 요청에 옛 세션 쿠키를 싣고, 가짜 구글 서버에서는 다른 sub의 계정을 고른다. 콜백에는 Strict 세션 쿠키가 실리지 않는다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class ReauthAccountMismatchSecurityTest {

    static final String MISMATCH = "/reauth-failed?reason=account-mismatch";

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionRows sessions;
    @Autowired
    JdbcClient jdbc;
    LoginClient login;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    @Test
    @DisplayName("T050 US2-4 FR-013 SC-003: 다른 계정(사용자 행 없음)으로 재인증하면 account-mismatch로 가고 옛 세션 행이 삭제되며 새 세션 행과 쿠키가 없다")
    void mismatchWithUnknownAccountDeletesOldSessionAndCreatesNothing() throws Exception {
        GoogleAccount current = account("current");
        GoogleAccount other = account("other");
        Cookie oldCookie = login.login(current).requireSessionCookie();
        String oldId = idOf(oldCookie);
        long userId = Long.parseLong(sessions.rowOf(oldId).principalName());
        String currentRowBefore = userRow(current);
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(userCount(other)).isZero();

        LoginClient.Result r = login.login(other, "/withdraw", "reauth", oldCookie);

        assertThat(r.location()).isEqualTo(MISMATCH);
        assertThat(LoginClient.cookie(r.callback(), LoginClient.SESSION_COOKIE)).as("새 세션 쿠키가 없어야 한다").isNull();
        assertThat(sessions.exists(oldId)).isFalse();
        assertThat(sessions.rowsOfUser(userId)).isEmpty();
        assertThat(sessions.count()).as("새 세션 행이 없어야 한다").isZero();
        assertThat(userCount(other)).as("다른 계정의 사용자 행을 만들지 않는다").isZero();
        assertThat(userRow(current)).as("현재 사용자 행은 바뀌지 않는다").isEqualTo(currentRowBefore);
        expectUnauthenticated(oldCookie);
    }

    @Test
    @DisplayName("T050 US2-4 FR-013 SC-003: 다른 계정(사용자 행 있음)으로 재인증하면 그 계정의 이메일, 표시 이름, 검증 여부와 세션이 그대로다")
    void mismatchWithExistingAccountChangesNothingOfThatAccount() throws Exception {
        GoogleAccount current = account("current");
        GoogleAccount other = account("other");
        Cookie otherCookie = login.login(other).requireSessionCookie();
        String otherRowBefore = userRow(other);
        long otherUserId = Long.parseLong(sessions.rowOf(idOf(otherCookie)).principalName());
        List<String> otherSessionsBefore = sessionIdsOf(otherUserId);
        assertThat(otherSessionsBefore).containsExactly(idOf(otherCookie));

        Cookie oldCookie = login.login(current).requireSessionCookie();
        String oldId = idOf(oldCookie);
        long userId = Long.parseLong(sessions.rowOf(oldId).principalName());
        String currentRowBefore = userRow(current);
        assertThat(sessions.count()).isEqualTo(2);

        // 구글이 다른 이메일, 이름, 검증 여부를 보내도 다른 계정의 행에 반영하면 안 된다
        GoogleAccount otherChanged = other.withEmail("changed-" + other.email()).withEmailVerified(false).withName("바뀐 이름");
        LoginClient.Result r = login.login(otherChanged, "/withdraw", "reauth", oldCookie);

        assertThat(r.location()).isEqualTo(MISMATCH);
        assertThat(LoginClient.cookie(r.callback(), LoginClient.SESSION_COOKIE)).as("새 세션 쿠키가 없어야 한다").isNull();
        assertThat(sessions.exists(oldId)).isFalse();
        assertThat(sessions.rowsOfUser(userId)).isEmpty();
        assertThat(sessionIdsOf(otherUserId)).as("다른 계정에 새 세션이 생기거나 옛 세션이 지워지지 않는다").isEqualTo(otherSessionsBefore);
        assertThat(sessions.count()).as("남는 것은 다른 계정이 미리 가진 세션뿐이다").isEqualTo(1);
        assertThat(userCount(other)).isEqualTo(1);
        assertThat(userRow(other)).as("다른 계정의 이메일, 검증 여부, 표시 이름이 그대로다").isEqualTo(otherRowBefore);
        assertThat(userRow(current)).as("현재 사용자 행은 바뀌지 않는다").isEqualTo(currentRowBefore);
        expectUnauthenticated(oldCookie);
        mvc.perform(https(get("/api/v1/me")).cookie(otherCookie)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T050 T053 FR-013: sub 비교가 사용자 행 갱신보다 먼저라, 현재 사용자 행의 UPDATE를 막아도 다른 계정 재인증은 account-mismatch와 옛 세션 삭제로 끝난다")
    void subIsComparedBeforeUserRowUpdate() throws Exception {
        GoogleAccount current = account("current");
        GoogleAccount other = account("other");
        Cookie oldCookie = login.login(current).requireSessionCookie();
        String oldId = idOf(oldCookie);
        long userId = Long.parseLong(sessions.rowOf(oldId).principalName());
        String currentRowBefore = userRow(current);

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String function = "test_block_user_update_fn_" + suffix;
        String trigger = "test_block_user_update_" + suffix;
        try {
            jdbc.sql("create function " + function + "() returns trigger language plpgsql as $$\n"
                    + "begin\n  raise exception 'test: user update blocked';\nend $$").update();
            jdbc.sql("create trigger " + trigger + " before update on \"user\" for each row when (old.id = " + userId + ")"
                    + " execute function " + function + "()").update();

            // 트리거가 실제로 갱신을 막는지 먼저 본다: 같은 계정 재인증은 이메일을 갱신하므로(research R5 4번) 실패하고 옛 세션이 남는다
            GoogleAccount sameChanged = current.withEmail("changed-" + current.email());
            LoginClient.Result control = login.login(sameChanged, "/withdraw", "reauth", oldCookie);
            assertThat(control.location()).as("대조: 갱신이 막히면 같은 계정 재인증은 성공하지 못한다").isEqualTo("/reauth-failed");
            assertThat(LoginClient.cookie(control.callback(), LoginClient.SESSION_COOKIE)).isNull();
            assertThat(sessions.exists(oldId)).as("대조: 롤백되어 옛 세션이 남는다").isTrue();

            LoginClient.Result r = login.login(other, "/withdraw", "reauth", oldCookie);

            assertThat(r.location()).isEqualTo(MISMATCH);
            assertThat(LoginClient.cookie(r.callback(), LoginClient.SESSION_COOKIE)).as("새 세션 쿠키가 없어야 한다").isNull();
            assertThat(sessions.exists(oldId)).as("옛 세션 행이 삭제된다").isFalse();
            assertThat(sessions.count()).as("새 세션 행이 없어야 한다").isZero();
            assertThat(userCount(other)).isZero();
            assertThat(userRow(current)).isEqualTo(currentRowBefore);
        } finally {
            jdbc.sql("drop trigger if exists " + trigger + " on \"user\"").update();
            jdbc.sql("drop function if exists " + function + "()").update();
        }
    }

    private void expectUnauthenticated(Cookie cookie) throws Exception {
        mvc.perform(https(get("/api/v1/me")).cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
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

    private static GoogleAccount account(String role) {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("reauth-mismatch-" + role + "-" + id, "reauth-mismatch-" + role + "-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
