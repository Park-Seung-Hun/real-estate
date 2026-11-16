package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T020 보안 경계: 로그아웃(US1-5, FR-009, FR-019, SC-004, 결정 기록 0013, research R9).
 * 상태를 바꾸는 요청에는 서비스 출처의 Origin을 붙인다. MockMvc 요청의 출처는 https://localhost 이고,
 * 테스트 컨텍스트의 estate.web.allowed-origins에 이 값이 있어야 한다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class LogoutSessionSecurityTest {

    static final String ORIGIN = "https://localhost";

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionRows sessions;
    LoginClient login;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    @Test
    @DisplayName("T020 US1-5 FR-009 FR-019 SC-004: 두 기기 중 한쪽이 로그아웃하면 그 세션 행만 삭제되고 그 쿠키는 401, 다른 기기는 200")
    void logoutDeletesOnlyThisDevice() throws Exception {
        GoogleAccount account = account();
        Cookie phone = login.login(account).sessionCookie();
        Cookie computer = login.login(account).sessionCookie();
        assertThat(phone).isNotNull();
        assertThat(computer).isNotNull();
        String phoneId = SessionRows.idOfCookie(phone.getValue());
        String computerId = SessionRows.idOfCookie(computer.getValue());
        assertThat(sessions.exists(phoneId)).isTrue();
        assertThat(sessions.exists(computerId)).isTrue();
        assertThat(sessions.count()).isEqualTo(2);

        MvcResult logout = mvc.perform(https(post("/api/v1/logout")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(phone))
                .andExpect(status().isNoContent()).andReturn();

        assertThat(sessions.exists(phoneId)).isFalse();
        assertThat(sessions.exists(computerId)).isTrue();
        assertThat(sessions.count()).isEqualTo(1);
        // 계약: 로그아웃은 세션 쿠키를 삭제한다(삭제 쿠키 줄이 있고 값 있는 쿠키는 없음)
        assertThat(LoginClient.setCookieHeaders(logout, LoginClient.SESSION_COOKIE)).isNotEmpty();
        assertThat(LoginClient.cookie(logout, LoginClient.SESSION_COOKIE)).isNull();

        expectUnauthenticated(mvc.perform(https(get("/api/v1/me")).cookie(phone)));
        mvc.perform(https(get("/api/v1/me")).cookie(computer)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T020 FR-009 FR-019: 로그인 상태 없이 하는 로그아웃은 401")
    void logoutWithoutLoginIs401() throws Exception {
        expectUnauthenticated(mvc.perform(https(post("/api/v1/logout")).header(HttpHeaders.ORIGIN, ORIGIN)));
    }

    @Test
    @DisplayName("T020 FR-019 SC-004: 이미 로그아웃한 쿠키로 다시 로그아웃하면 401")
    void logoutWithDeletedSessionIs401() throws Exception {
        Cookie cookie = login.login(account()).sessionCookie();
        assertThat(cookie).isNotNull();
        mvc.perform(https(post("/api/v1/logout")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie))
                .andExpect(status().isNoContent());

        expectUnauthenticated(mvc.perform(https(post("/api/v1/logout")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie)));
    }

    @Test
    @DisplayName("T020 FR-009 research R9: GET /api/v1/logout 은 로그아웃하지 않는다")
    void getDoesNotLogout() throws Exception {
        Cookie cookie = login.login(account()).sessionCookie();
        assertThat(cookie).isNotNull();
        String id = SessionRows.idOfCookie(cookie.getValue());
        assertThat(sessions.exists(id)).isTrue();

        MvcResult result = mvc.perform(https(get("/api/v1/logout")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie)).andReturn();

        assertThat(result.getResponse().getStatus()).as("GET은 로그아웃 성공(204)으로 응답하지 않는다").isNotEqualTo(204);
        assertThat(sessions.exists(id)).isTrue();
        // 세션 쿠키를 삭제하는 줄도 없어야 한다(줄이 없거나 값 있는 쿠키)
        assertThat(LoginClient.setCookieHeaders(result, LoginClient.SESSION_COOKIE).isEmpty()
                || LoginClient.cookie(result, LoginClient.SESSION_COOKIE) != null).isTrue();
        mvc.perform(https(get("/api/v1/me")).cookie(cookie)).andExpect(status().isOk());
    }

    private static void expectUnauthenticated(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private static GoogleAccount account() {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("logout-" + id, "logout-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
