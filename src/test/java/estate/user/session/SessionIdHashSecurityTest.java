package estate.user.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/** T015 보안 경계: 세션 쿠키 원문과 저장되는 세션 ID(결정 기록 0012, 0013, research R3). */
@LoginIntegrationTest
@AutoConfigureMockMvc
class SessionIdHashSecurityTest {

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
    @DisplayName("T015 0013: 쿠키 값은 32바이트를 패딩 없는 base64url로 쓴 43자다")
    void cookieValueIsUnpaddedBase64Url43() throws Exception {
        Cookie cookie = login.login(account()).sessionCookie();

        assertThat(cookie).as("로그인 콜백이 세션 쿠키를 발급해야 한다").isNotNull();
        assertThat(cookie.getValue()).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(cookie.getValue())).hasSize(32);
    }

    @Test
    @DisplayName("T015 0013: spring_session.session_id는 쿠키 값의 SHA-256 소문자 16진수 64자다")
    void storedSessionIdIsSha256HexOfCookie() throws Exception {
        Cookie cookie = login.login(account()).sessionCookie();
        assertThat(cookie).isNotNull();
        String id = SessionRows.idOfCookie(cookie.getValue());

        assertThat(sessions.count()).isEqualTo(1);
        assertThat(sessions.exists(id)).isTrue();
        assertThat(sessions.rowOf(id).sessionId()).isEqualTo(id).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("T015 0012: 데이터베이스 어디에도 쿠키 원문이 없다(같은 검색으로 해시는 찾힌다)")
    void cookieValueIsNowhereInDatabase() throws Exception {
        Cookie cookie = login.login(account()).sessionCookie();
        assertThat(cookie).isNotNull();
        String id = SessionRows.idOfCookie(cookie.getValue());

        // 검색이 동작하는지 먼저 확인한다: 저장된 해시는 session_id 열에서 찾혀야 한다
        assertThat(sessions.columnsContaining(id)).contains("spring_session.session_id");
        assertThat(sessions.columnsContaining(cookie.getValue())).isEmpty();
    }

    @Test
    @DisplayName("T015 0013: 쿠키 원문은 로그인 상태로 통하고, 저장된 해시를 쿠키로 보내면 401")
    void onlyRawCookieAuthenticates() throws Exception {
        Cookie cookie = login.login(account()).sessionCookie();
        assertThat(cookie).isNotNull();

        mvc.perform(https(get("/api/v1/me")).cookie(cookie)).andExpect(status().isOk());

        Cookie hashAsCookie = new Cookie(LoginClient.SESSION_COOKIE, SessionRows.idOfCookie(cookie.getValue()));
        mvc.perform(https(get("/api/v1/me")).cookie(hashAsCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("T015 0013: 쿠키 속성은 __Host-Http-session, HttpOnly, Secure, SameSite=Strict, Path=/, Domain 없음, Max-Age 30일")
    void sessionCookieAttributes() throws Exception {
        LoginClient.Result result = login.login(account());
        assertThat(result.sessionCookie()).isNotNull();

        List<String> lines = LoginClient.setCookieHeaders(result.callback(), LoginClient.SESSION_COOKIE);
        assertThat(lines).as("세션 쿠키 Set-Cookie 줄은 하나").hasSize(1);
        List<String> attributes = Arrays.stream(lines.getFirst().split(";")).skip(1)
                .map(a -> a.trim().toLowerCase(Locale.ROOT)).toList();

        assertThat(lines.getFirst()).startsWith(LoginClient.SESSION_COOKIE + "=");
        assertThat(attributes).contains("httponly", "secure", "samesite=strict", "path=/", "max-age=2592000");
        assertThat(attributes).noneMatch(a -> a.startsWith("domain"));
        assertThat(attributes).filteredOn(a -> a.startsWith("max-age=")).hasSize(1);
        assertThat(attributes).filteredOn(a -> a.startsWith("path=")).hasSize(1);
    }

    private static GoogleAccount account() {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("hash-" + id, "hash-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
