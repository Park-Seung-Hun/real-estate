package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T024: 상태를 바꾸는 요청의 Origin 검사(결정 기록 0013, research R9). 검사는 로그인 상태 확인(401) 뒤이고, 허용 밖 출처는 403 ORIGIN_REJECTED.
 * 허용 출처는 설정값 estate.web.allowed-origins(R9)이고, IntegrationTest가 테스트 요청의 서비스 주소(https://localhost)로 둔다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class OriginCheckSecurityTest {

    static final String SERVICE_ORIGIN = "https://localhost";
    static final String OTHER_ORIGIN = "https://evil.example.com";

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

    private static GoogleAccount newAccount() {
        String id = "t024-" + UUID.randomUUID();
        return GoogleAccount.of("sub-" + id, id + "@example.com");
    }

    /** 사용자 행 전체(번호, 이메일 암호문, 검증 여부, 표시 이름). 없으면 예외. */
    private String userRow(GoogleAccount account) {
        return jdbc.sql("""
                select id || '|' || encode(login_email, 'hex') || '|' || email_verified || '|' || coalesce(display_name, '<null>')
                from "user" where provider_subject = :s""").param("s", account.sub()).query(String.class).single();
    }

    private String displayName(GoogleAccount account) {
        return jdbc.sql("select coalesce(display_name, '<null>') from \"user\" where provider_subject = :s")
                .param("s", account.sub()).query(String.class).single();
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }

    private static MockHttpServletRequestBuilder request(HttpMethod method, String path, String body) {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(method, path);
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return https(b);
    }

    private ResultActions perform(MockHttpServletRequestBuilder b, Cookie session, String origin, String referer) throws Exception {
        if (session != null) {
            b.cookie(session);
        }
        if (origin != null) {
            b.header(HttpHeaders.ORIGIN, origin);
        }
        if (referer != null) {
            b.header(HttpHeaders.REFERER, referer);
        }
        return mvc.perform(b);
    }

    private static void expectOriginRejected(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));
    }

    private Cookie loggedIn(GoogleAccount account) throws Exception {
        Cookie cookie = login.login(account).sessionCookie();
        assertThat(cookie).isNotNull();
        assertThat(sessions.exists(SessionRows.idOfCookie(cookie.getValue()))).isTrue();
        return cookie;
    }

    static Stream<Arguments> stateChangingRequests() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/logout", null),
                Arguments.of(HttpMethod.PATCH, "/api/v1/me", "{\"displayName\":\"바꾼이름\"}"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/me", null));
    }

    @ParameterizedTest(name = "T024 R9: 로그인 상태에서 허용 밖 Origin의 {0} {1}은 403 ORIGIN_REJECTED이고 아무것도 바뀌지 않는다")
    @MethodSource("stateChangingRequests")
    void otherOriginIsRejectedAndChangesNothing(HttpMethod method, String path, String body) throws Exception {
        GoogleAccount account = newAccount();
        Cookie session = loggedIn(account);
        long sessionCount = sessions.count();
        String user = userRow(account);
        String name = displayName(account);

        expectOriginRejected(perform(request(method, path, body), session, OTHER_ORIGIN, null));

        assertThat(sessions.count()).isEqualTo(sessionCount);
        assertThat(sessions.exists(SessionRows.idOfCookie(session.getValue()))).isTrue();
        assertThat(userRow(account)).isEqualTo(user);
        assertThat(displayName(account)).isEqualTo(name);
    }

    @Test
    @DisplayName("T024 R9: 허용 밖 Origin에 잘못된 본문(빈 표시 이름)을 보낸 PATCH도 400이 아니라 403이다")
    void otherOriginWithInvalidBodyIs403Not400() throws Exception {
        GoogleAccount account = newAccount();
        Cookie session = loggedIn(account);
        String name = displayName(account);

        expectOriginRejected(perform(request(HttpMethod.PATCH, "/api/v1/me", "{\"displayName\":\"\"}"), session, OTHER_ORIGIN, null));

        assertThat(displayName(account)).isEqualTo(name);
    }

    @Test
    @DisplayName("T024 대조: 허용 출처 Origin의 로그아웃은 통과해 204다")
    void serviceOriginPasses() throws Exception {
        Cookie session = loggedIn(newAccount());

        perform(request(HttpMethod.POST, "/api/v1/logout", null), session, SERVICE_ORIGIN, null).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("T024 R9: Origin이 없고 Referer가 허용 출처면 통과해 로그아웃이 204다")
    void serviceRefererWithoutOriginPasses() throws Exception {
        Cookie session = loggedIn(newAccount());

        perform(request(HttpMethod.POST, "/api/v1/logout", null), session, null, SERVICE_ORIGIN + "/no-household")
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("T024 R9: Origin이 없고 Referer가 허용 밖 출처면 403 ORIGIN_REJECTED이고 세션이 남는다")
    void otherRefererWithoutOriginIsRejected() throws Exception {
        Cookie session = loggedIn(newAccount());

        expectOriginRejected(perform(request(HttpMethod.POST, "/api/v1/logout", null), session, null, OTHER_ORIGIN + "/page"));

        assertThat(sessions.exists(SessionRows.idOfCookie(session.getValue()))).isTrue();
    }

    @Test
    @DisplayName("T024 R9: Origin과 Referer가 둘 다 없으면 403 ORIGIN_REJECTED이고 세션이 남는다")
    void noOriginNoRefererIsRejected() throws Exception {
        Cookie session = loggedIn(newAccount());

        expectOriginRejected(perform(request(HttpMethod.POST, "/api/v1/logout", null), session, null, null));

        assertThat(sessions.exists(SessionRows.idOfCookie(session.getValue()))).isTrue();
    }

    static Stream<Arguments> unauthenticatedRequests() {
        return stateChangingRequests().flatMap(a -> Stream.of(
                Arguments.of(a.get()[0], a.get()[1], a.get()[2], OTHER_ORIGIN),
                Arguments.of(a.get()[0], a.get()[1], a.get()[2], SERVICE_ORIGIN),
                Arguments.of(a.get()[0], a.get()[1], a.get()[2], null)));
    }

    @ParameterizedTest(name = "T024 R9: 로그인 상태 없이 {0} {1}(Origin {3})은 401 UNAUTHENTICATED")
    @MethodSource("unauthenticatedRequests")
    void withoutLoginIs401RegardlessOfOrigin(HttpMethod method, String path, String body, String origin) throws Exception {
        perform(request(method, path, body), null, origin, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
