package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import estate.support.SessionRows;

/**
 * T057 보안 경계: 내 정보 조회와 표시 이름 수정은 로그인한 본인 것만 다루고, 표시 이름 밖의 필드는 바꿀 수 없다
 * (US4-1, US4-2, FR-017, FR-018, API 공통 규칙 권한 확인 1단계와 5단계, research R11, R12).
 * 확인 순서는 로그인 상태(401), Origin(403), 입력 검증(400)이다. PATCH는 허용 출처 https://localhost 의 Origin을 붙인다(research R9).
 * login_email은 암호문이라 바뀌지 않았는지는 열의 바이트로 비교한다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class MeAccessSecurityTest {

    static final String ORIGIN = "https://localhost";
    static final String OTHER_ORIGIN = "https://evil.example";
    static final String EMPTY_NAME_BODY = "{\"displayName\":\"\"}";

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionRows sessions;
    @Autowired
    JdbcClient jdbc;
    LoginClient login;

    /** "user" 행에서 사용자가 바꿀 수 있는지 볼 열 셋. */
    record UserColumns(String displayName, byte[] loginEmail, boolean emailVerified) {
    }

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    @Test
    @DisplayName("T057 US4-1 FR-018 권한 확인 1단계: 세션 쿠키 없이 보낸 올바른 PATCH /api/v1/me 는 401 UNAUTHENTICATED이고 어느 이름도 바뀌지 않는다")
    void patchWithoutSessionIs401AndChangesNothing() throws Exception {
        GoogleAccount account = account("bystander");
        login.login(account).requireSessionCookie();
        UserColumns before = columnsOf(account.sub());
        String newName = uniqueName("무세션");

        expectUnauthenticated(mvc.perform(patchMe(nameBody(newName))));

        assertUnchanged(account.sub(), before);
        assertThat(rowsNamed(newName)).as("어느 사용자의 display_name도 새 값이 되지 않는다").isZero();
    }

    @Test
    @DisplayName("T057 US1-6 FR-008 FR-018: 유휴 7일이 지난 세션(로그인 10일, 마지막 요청 8일 전)의 PATCH /api/v1/me 는 401이고 이름이 바뀌지 않는다")
    void patchWithIdleExpiredSessionIs401() throws Exception {
        expectExpiredPatchRejected(Duration.ofDays(10), Duration.ofDays(8));
    }

    @Test
    @DisplayName("T057 US1-6 FR-008 FR-018: 절대 30일이 지난 세션(로그인 31일, 마지막 요청 어제)의 PATCH /api/v1/me 는 401이고 이름이 바뀌지 않는다")
    void patchWithAbsoluteExpiredSessionIs401() throws Exception {
        expectExpiredPatchRejected(Duration.ofDays(31), Duration.ofDays(1));
    }

    @ParameterizedTest(name = "T057 API 공통 규칙 권한 확인: 로그인 없이 틀린 본문 {0} 은 400이 아니라 401")
    @ValueSource(strings = {"{\"displayName\":\"\"}", "{\"displayName\":\"새이름\",\"foo\":1}", "{\"loginEmail\":\"other@example.com\"}"})
    @DisplayName("T057 API 공통 규칙 권한 확인: 로그인 없이 틀린 본문(빈 이름, 알 수 없는 필드)을 보내면 400이 아니라 401")
    void invalidBodyWithoutSessionIs401(String body) throws Exception {
        expectUnauthenticated(mvc.perform(patchMe(body)));
    }

    @Test
    @DisplayName("T057 US4-2 FR-018 권한 확인 5단계: A와 B가 로그인한 상태에서 B의 쿠키로 PATCH하면 B의 이름만 바뀌고 A는 그대로다")
    void patchChangesOnlySessionUser() throws Exception {
        GoogleAccount a = account("a").withName("에이이름");
        GoogleAccount b = account("b").withName("비이름");
        Cookie cookieA = login.login(a).requireSessionCookie();
        Cookie cookieB = login.login(b).requireSessionCookie();
        UserColumns beforeA = columnsOf(a.sub());
        UserColumns beforeB = columnsOf(b.sub());
        assertThat(beforeA.displayName()).isEqualTo("에이이름");
        assertThat(beforeB.displayName()).isEqualTo("비이름");
        String newName = uniqueName("비새");

        mvc.perform(patchMe(nameBody(newName)).cookie(cookieB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value(newName))
                .andExpect(jsonPath("$.loginEmail").value(b.email()));

        UserColumns afterB = columnsOf(b.sub());
        assertThat(afterB.displayName()).as("B의 display_name은 새 값").isEqualTo(newName);
        assertThat(afterB.loginEmail()).as("B의 login_email은 그대로").isEqualTo(beforeB.loginEmail());
        assertThat(afterB.emailVerified()).isEqualTo(beforeB.emailVerified());
        assertUnchanged(a.sub(), beforeA);
        assertThat(rowsNamed(newName)).as("새 이름은 B의 행 하나에만").isEqualTo(1);
        mvc.perform(https(get("/api/v1/me")).cookie(cookieA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("에이이름"));
    }

    @ParameterizedTest(name = "T057 FR-017 FR-018 R12: 본문 {0} 은 400 VALIDATION_FAILED, pointer #, 아무 열도 바뀌지 않음")
    @ValueSource(strings = {"{\"displayName\":\"새이름\",\"loginEmail\":\"other@example.com\"}", "{\"displayName\":\"새이름\",\"emailVerified\":false}"})
    @DisplayName("T057 FR-017 FR-018: loginEmail이나 emailVerified를 본문에 넣으면 400 VALIDATION_FAILED이고 아무것도 바뀌지 않는다")
    void unknownFieldIs400AndChangesNothing(String body) throws Exception {
        GoogleAccount account = account("immutable").withName("원래이름");
        Cookie cookie = login.login(account).requireSessionCookie();
        UserColumns before = columnsOf(account.sub());

        MvcResult result = mvc.perform(patchMe(body).cookie(cookie))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#"))
                .andReturn();

        assertUnchanged(account.sub(), before);
        String response = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(response).as("오류 본문은 요청 값을 담지 않는다")
                .doesNotContain("other@example.com")
                .doesNotContain("새이름")
                .doesNotContain("loginEmail")
                .doesNotContain("emailVerified");
    }

    @Test
    @DisplayName("T057 US4-1 FR-017: A와 B가 로그인한 상태에서 B의 쿠키로 GET /api/v1/me 하면 B의 값만 오고 A의 이메일은 본문에 없다")
    void getReturnsOnlySessionUser() throws Exception {
        GoogleAccount a = account("get-a").withName("에이조회");
        GoogleAccount b = account("get-b").withName("비조회").withEmailVerified(false);
        login.login(a).requireSessionCookie();
        Cookie cookieB = login.login(b).requireSessionCookie();

        MvcResult result = mvc.perform(https(get("/api/v1/me")).cookie(cookieB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginEmail").value(b.email()))
                .andExpect(jsonPath("$.displayName").value("비조회"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andReturn();

        String response = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(response).doesNotContain(a.email()).doesNotContain("에이조회");
    }

    @Test
    @DisplayName("T057 결정 기록 0013 research R9: 로그인 상태에서 Origin과 Referer 없이 보낸 PATCH /api/v1/me 는 403 ORIGIN_REJECTED이고 이름이 바뀌지 않는다")
    void patchWithoutOriginIs403AndChangesNothing() throws Exception {
        GoogleAccount account = account("no-origin");
        Cookie cookie = login.login(account).requireSessionCookie();
        UserColumns before = columnsOf(account.sub());
        String newName = uniqueName("무출처");

        mvc.perform(https(patch("/api/v1/me")).contentType(MediaType.APPLICATION_JSON).content(nameBody(newName)).cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));

        assertUnchanged(account.sub(), before);
        assertThat(rowsNamed(newName)).isZero();
    }

    @Test
    @DisplayName("T057 API 공통 규칙 권한 확인 순서: 세션 없음, 다른 출처 Origin, 빈 이름이 겹치면 401 UNAUTHENTICATED이고 아무 열도 바뀌지 않는다")
    void noSessionOtherOriginInvalidBodyIs401() throws Exception {
        GoogleAccount account = account("order-no-session-other");
        login.login(account).requireSessionCookie();
        UserColumns before = columnsOf(account.sub());

        expectUnauthenticated(mvc.perform(patchMe(EMPTY_NAME_BODY, OTHER_ORIGIN)));

        assertUnchanged(account.sub(), before);
    }

    @Test
    @DisplayName("T057 API 공통 규칙 권한 확인 순서: 세션 없음, Origin과 Referer 없음, 빈 이름이 겹치면 401 UNAUTHENTICATED이고 아무 열도 바뀌지 않는다")
    void noSessionNoOriginInvalidBodyIs401() throws Exception {
        GoogleAccount account = account("order-no-session-none");
        login.login(account).requireSessionCookie();
        UserColumns before = columnsOf(account.sub());

        expectUnauthenticated(mvc.perform(patchMe(EMPTY_NAME_BODY, null)));

        assertUnchanged(account.sub(), before);
    }

    @ParameterizedTest(name = "T057 API 공통 규칙 권한 확인 순서: 로그인, 다른 출처 Origin, 틀린 본문 {0} 은 403 ORIGIN_REJECTED")
    @ValueSource(strings = {EMPTY_NAME_BODY, "{\"displayName\":\"새이름\",\"loginEmail\":\"other@example.com\"}"})
    @DisplayName("T057 API 공통 규칙 권한 확인 순서: 로그인 상태에서 다른 출처 Origin과 틀린 본문(빈 이름, 알 수 없는 필드)이 겹치면 400이 아니라 403 ORIGIN_REJECTED이고 아무 열도 바뀌지 않는다")
    void loggedInOtherOriginInvalidBodyIs403(String body) throws Exception {
        GoogleAccount account = account("order-other-origin");
        Cookie cookie = login.login(account).requireSessionCookie();
        UserColumns before = columnsOf(account.sub());

        mvc.perform(patchMe(body, OTHER_ORIGIN).cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));

        assertUnchanged(account.sub(), before);
    }

    private void expectExpiredPatchRejected(Duration sinceLogin, Duration sinceLastRequest) throws Exception {
        GoogleAccount account = account("expired");
        Cookie cookie = login.login(account).requireSessionCookie();
        // expiry_time은 last_access_time과 같은 만큼 옮겨 유휴 7일 기준을 유지한다(SessionExpirySecurityTest와 같은 방식)
        assertThat(sessions.shiftBackBySessionId(SessionRows.idOfCookie(cookie.getValue()), sinceLogin, sinceLastRequest, sinceLastRequest))
                .isEqualTo(1);
        UserColumns before = columnsOf(account.sub());
        String newName = uniqueName("만료");

        expectUnauthenticated(mvc.perform(patchMe(nameBody(newName)).cookie(cookie)));

        assertUnchanged(account.sub(), before);
        assertThat(rowsNamed(newName)).isZero();
    }

    private UserColumns columnsOf(String sub) {
        return jdbc.sql("select display_name, login_email, email_verified from \"user\" where provider = 'google' and provider_subject = :sub")
                .param("sub", sub)
                .query((rs, n) -> new UserColumns(rs.getString(1), rs.getBytes(2), rs.getBoolean(3))).single();
    }

    private void assertUnchanged(String sub, UserColumns before) {
        UserColumns after = columnsOf(sub);
        assertThat(after.displayName()).as("display_name은 요청 전과 같다").isEqualTo(before.displayName());
        assertThat(after.loginEmail()).as("login_email 바이트는 요청 전과 같다").isEqualTo(before.loginEmail());
        assertThat(after.emailVerified()).as("email_verified는 요청 전과 같다").isEqualTo(before.emailVerified());
    }

    private long rowsNamed(String displayName) {
        return jdbc.sql("select count(*) from \"user\" where display_name = :n").param("n", displayName).query(Long.class).single();
    }

    private static MockHttpServletRequestBuilder patchMe(String body) {
        return patchMe(body, ORIGIN);
    }

    /** origin이 null이면 Origin 헤더를 붙이지 않는다(MockMvc는 Referer도 붙이지 않는다). */
    private static MockHttpServletRequestBuilder patchMe(String body, String origin) {
        MockHttpServletRequestBuilder request = https(patch("/api/v1/me")).contentType(MediaType.APPLICATION_JSON).content(body);
        return origin == null ? request : request.header(HttpHeaders.ORIGIN, origin);
    }

    private static String nameBody(String name) {
        return "{\"displayName\":\"" + name + "\"}";
    }

    /** 테스트끼리 이름이 겹치지 않게 짧은 무작위 꼬리를 붙인다(20자 한도 안). */
    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void expectUnauthenticated(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private static GoogleAccount account(String tag) {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("me-access-" + tag + "-" + id, "me-access-" + tag + "-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
