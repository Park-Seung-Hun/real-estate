package estate.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

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

import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T045 탈퇴 동작: 응답이 세션 쿠키를 삭제하고, 탈퇴한 구글 계정은 다시 로그인하면 처음 로그인하는 계정으로 취급된다(US3-5).
 * 쿠키 기대값은 결정 기록 0013(__Host-Http-session, HttpOnly, Secure, SameSite=Strict, Path=/, Domain 없음)과
 * research R3(삭제는 빈 값과 Max-Age=0), R9(탈퇴 뒤 쿠키 삭제와 204)에서 가져왔다.
 * 탈퇴는 로그인 직후(마지막 구글 인증 5분 안)에 보낸다. 상태를 바꾸는 요청이라 허용 출처 Origin을 붙인다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class WithdrawFlowTest {

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
    @DisplayName("T045 FR-016 research R9: 탈퇴 응답은 204이고 __Host-Http-session을 빈 값, Max-Age=0, 0013의 속성으로 삭제한다")
    void withdrawResponseDeletesSessionCookie() throws Exception {
        Cookie cookie = login.login(account("cookie")).requireSessionCookie();
        assertThat(sessions.exists(SessionRows.idOfCookie(cookie.getValue()))).isTrue();

        MvcResult withdraw = mvc.perform(https(delete("/api/v1/me")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie))
                .andExpect(status().isNoContent()).andReturn();

        List<String> lines = LoginClient.setCookieHeaders(withdraw, LoginClient.SESSION_COOKIE);
        assertThat(lines).as("세션 쿠키를 삭제하는 Set-Cookie 줄이 있어야 한다").isNotEmpty();
        for (String line : lines) {
            assertThat(line).as("빈 값: " + line).startsWith(LoginClient.SESSION_COOKIE + "=;");
            List<String> attributes = attributes(line);
            assertThat(attributes).as(line).contains("max-age=0", "path=/", "secure", "httponly", "samesite=strict");
            assertThat(attributes).as("Domain 없음(__Host- 접두사): " + line).noneMatch(a -> a.startsWith("domain"));
        }
        assertThat(LoginClient.cookie(withdraw, LoginClient.SESSION_COOKIE)).as("응답이 끝났을 때 값 있는 세션 쿠키가 없어야 한다: " + lines).isNull();
    }

    @Test
    @DisplayName("T045 US3-5: 탈퇴한 구글 계정으로 다시 로그인하면 새 번호의 사용자 행이 생기고 그때의 구글 이름이 표시 이름이다")
    void reloginAfterWithdrawIsFirstLogin() throws Exception {
        GoogleAccount before = account("relogin").withName("탈퇴 전 이름");
        Cookie cookie = login.login(before).requireSessionCookie();
        long oldId = userIdOf(before.sub());
        assertThat(displayNameOf(oldId)).isEqualTo("탈퇴 전 이름");

        mvc.perform(https(delete("/api/v1/me")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie))
                .andExpect(status().isNoContent());
        assertThat(userCount(before.sub())).isZero();

        // 이미 있는 사용자면 표시 이름을 바꾸지 않으므로(FR-004) 구글 이름을 바꿔 "처음 로그인"과 구분한다
        GoogleAccount after = before.withName("다시 온 이름");
        LoginClient.Result relogin = login.login(after);
        Cookie fresh = relogin.requireSessionCookie();

        assertThat(relogin.location()).isEqualTo("/");
        assertThat(userCount(after.sub())).isEqualTo(1);
        long newId = userIdOf(after.sub());
        assertThat(newId).as("새 번호의 사용자 행").isNotEqualTo(oldId);
        assertThat(displayNameOf(newId)).isEqualTo("다시 온 이름");
        assertThat(sessions.rowsOfUser(oldId)).isEmpty();
        assertThat(sessions.rowsOfUser(newId)).extracting(SessionRows.Row::sessionId)
                .containsExactly(SessionRows.idOfCookie(fresh.getValue()));
        mvc.perform(https(get("/api/v1/me")).cookie(fresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("다시 온 이름"));
    }

    private long userIdOf(String sub) {
        return jdbc.sql("select id from \"user\" where provider = 'google' and provider_subject = :sub")
                .param("sub", sub).query(Long.class).single();
    }

    private long userCount(String sub) {
        return jdbc.sql("select count(*) from \"user\" where provider = 'google' and provider_subject = :sub")
                .param("sub", sub).query(Long.class).single();
    }

    private String displayNameOf(long id) {
        return jdbc.sql("select display_name from \"user\" where id = :id").param("id", id).query(String.class).single();
    }

    /** Set-Cookie 줄의 이름=값 뒤 속성들(소문자, 공백 제거). */
    private static List<String> attributes(String setCookie) {
        List<String> result = new ArrayList<>();
        String[] parts = setCookie.split(";");
        for (int i = 1; i < parts.length; i++) {
            result.add(parts[i].trim().toLowerCase(Locale.ROOT).replace(" ", ""));
        }
        return result;
    }

    private static GoogleAccount account(String tag) {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("withdraw-flow-" + tag + "-" + id, "withdraw-flow-" + tag + "-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
