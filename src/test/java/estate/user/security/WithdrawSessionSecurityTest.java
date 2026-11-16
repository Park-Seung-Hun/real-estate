package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T043 보안 경계: 탈퇴하면 그 사용자의 모든 기기 세션이 삭제된다(US3-3, US3-4, FR-016, FR-019, SC-004, research R9).
 * 탈퇴는 로그인 직후(마지막 구글 인증이 5분 안, Clock은 Reset이 시스템 시각으로 되돌림)에 보내므로 재인증 판정(US2)이 붙어도 처리된다.
 * 상태를 바꾸는 요청이라 허용 출처 https://localhost 의 Origin을 붙인다(research R9).
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class WithdrawSessionSecurityTest {

    static final String ORIGIN = "https://localhost";

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
    @DisplayName("T043 US3-3 US3-4 FR-016 FR-019 SC-004: 두 기기 중 한쪽이 탈퇴하면 204, 사용자 행과 그 사용자의 세션 행이 모두 없고 두 쿠키 모두 401, 다른 사용자 세션은 남음")
    void withdrawDeletesUserAndAllSessionsOfThatUser() throws Exception {
        GoogleAccount account = account("me");
        Cookie phone = login.login(account).requireSessionCookie();
        Cookie computer = login.login(account).requireSessionCookie();
        GoogleAccount otherAccount = account("other");
        Cookie other = login.login(otherAccount).requireSessionCookie();

        long userId = userIdOf(account.sub());
        long otherId = userIdOf(otherAccount.sub());
        String phoneId = SessionRows.idOfCookie(phone.getValue());
        String computerId = SessionRows.idOfCookie(computer.getValue());
        String otherSessionId = SessionRows.idOfCookie(other.getValue());
        // 없어짐을 보기 전에 있음을 먼저 확인한다
        assertThat(userCount(account.sub())).isEqualTo(1);
        assertThat(sessions.rowsOfUser(userId)).extracting(SessionRows.Row::sessionId).containsExactlyInAnyOrder(phoneId, computerId);
        assertThat(sessions.exists(otherSessionId)).isTrue();
        mvc.perform(https(get("/api/v1/me")).cookie(computer)).andExpect(status().isOk());

        mvc.perform(https(delete("/api/v1/me")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(phone))
                .andExpect(status().isNoContent());

        assertThat(userCount(account.sub())).as("사용자 행이 실제로 삭제된다(FR-016)").isZero();
        assertThat(jdbc.sql("select count(*) from \"user\" where id = :id").param("id", userId).query(Long.class).single()).isZero();
        assertThat(sessions.rowsOfUser(userId)).as("그 사용자의 모든 기기 세션 행이 삭제된다(FR-016, FR-019)").isEmpty();
        assertThat(sessions.exists(phoneId)).isFalse();
        assertThat(sessions.exists(computerId)).isFalse();

        // US3-3: 탈퇴한 기기, US3-4: 다른 기기 모두 다음 요청이 401
        expectUnauthenticated(mvc.perform(https(get("/api/v1/me")).cookie(phone)));
        expectUnauthenticated(mvc.perform(https(get("/api/v1/me")).cookie(computer)));

        assertThat(sessions.exists(otherSessionId)).as("다른 사용자의 세션은 남는다").isTrue();
        assertThat(sessions.rowsOfUser(otherId)).hasSize(1);
        assertThat(userCount(otherAccount.sub())).isEqualTo(1);
        mvc.perform(https(get("/api/v1/me")).cookie(other)).andExpect(status().isOk());
    }

    private long userIdOf(String sub) {
        return jdbc.sql("select id from \"user\" where provider = 'google' and provider_subject = :sub")
                .param("sub", sub).query(Long.class).single();
    }

    private long userCount(String sub) {
        return jdbc.sql("select count(*) from \"user\" where provider = 'google' and provider_subject = :sub")
                .param("sub", sub).query(Long.class).single();
    }

    private static void expectUnauthenticated(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private static GoogleAccount account(String tag) {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("withdraw-session-" + tag + "-" + id, "withdraw-session-" + tag + "-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
