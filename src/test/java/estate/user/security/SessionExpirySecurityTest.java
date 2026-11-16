package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import estate.support.FakeGoogleServer;
import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T021 보안 경계: 유휴 7일과 절대 30일 만료(US1-6, FR-008, SC-004, 결정 기록 0013, research R8).
 * 시각은 세션 행의 creation_time, last_access_time, expiry_time을 과거로 옮겨 만든다.
 * /withdraw 가 index.html 인지는 클래스패스의 static/index.html(Gradle이 web/dist에서 복사)과 응답 본문을 비교해 본다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class SessionExpirySecurityTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionRows sessions;
    @Autowired
    MutableClock clock;
    LoginClient login;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    @Test
    @DisplayName("T021 US1-6 FR-008 SC-004: 로그인 31일, 마지막 요청 어제인 세션은 /api/v1/me 401")
    void absoluteExpiryIs401() throws Exception {
        Cookie cookie = loggedInShiftedBack(Duration.ofDays(31), Duration.ofDays(1));

        expectUnauthenticated(cookie);
    }

    @Test
    @DisplayName("T021 US1-6 FR-008 짝: 로그인 29일, 마지막 요청 어제인 세션은 200")
    void beforeAbsoluteExpiryIs200() throws Exception {
        Cookie cookie = loggedInShiftedBack(Duration.ofDays(29), Duration.ofDays(1));

        mvc.perform(https(get("/api/v1/me")).cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T021 FR-008 결정 기록 0013 경계: 로그인한 지 정확히 30일(마지막 요청 어제)인 세션은 아직 지나지 않았으므로 200")
    void exactlyThirtyDaysIs200() throws Exception {
        Cookie cookie = loggedInWithClockAfterLogin(Duration.ofDays(30));

        mvc.perform(https(get("/api/v1/me")).cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T021 FR-008 결정 기록 0013 경계: 로그인한 지 30일 1초(마지막 요청 어제)인 세션은 /api/v1/me 401")
    void thirtyDaysAndOneSecondIs401() throws Exception {
        Cookie cookie = loggedInWithClockAfterLogin(Duration.ofDays(30).plusSeconds(1));

        expectUnauthenticated(cookie);
    }

    @Test
    @DisplayName("T021 US1-6 FR-008 SC-004: 로그인 10일, 마지막 요청 8일 전인 세션은 /api/v1/me 401")
    void idleExpiryIs401() throws Exception {
        Cookie cookie = loggedInShiftedBack(Duration.ofDays(10), Duration.ofDays(8));

        expectUnauthenticated(cookie);
    }

    @Test
    @DisplayName("T021 US1-6 FR-008 짝: 로그인 10일, 마지막 요청 6일 전인 세션은 200")
    void beforeIdleExpiryIs200() throws Exception {
        Cookie cookie = loggedInShiftedBack(Duration.ofDays(10), Duration.ofDays(6));

        mvc.perform(https(get("/api/v1/me")).cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T021 FR-008 research R8: 절대 만료(31일, 어제) 쿠키의 /api/v1/me 401 응답은 세션 쿠키를 지운다")
    void absoluteExpiry401DeletesCookie() throws Exception {
        Cookie cookie = loggedInShiftedBack(Duration.ofDays(31), Duration.ofDays(1));

        assertDeletesSessionCookie(expectUnauthenticated(cookie));
    }

    @Test
    @DisplayName("T021 FR-008 research R3: 유휴 만료(10일, 8일 전) 쿠키의 /api/v1/me 401 응답은 세션 쿠키를 지운다")
    void idleExpiry401DeletesCookie() throws Exception {
        Cookie cookie = loggedInShiftedBack(Duration.ofDays(10), Duration.ofDays(8));

        assertDeletesSessionCookie(expectUnauthenticated(cookie));
    }

    @Test
    @DisplayName("T021 결정 기록 0013 쿠키 표, research R4: 절대 만료 쿠키를 가진 채 로그인하면 콜백의 세션 쿠키 줄은 새 값 하나뿐이다")
    void loginWithAbsoluteExpiredCookieEndsWithFreshCookie() throws Exception {
        GoogleAccount account = account();
        Cookie expired = loggedInShiftedBack(account, Duration.ofDays(31), Duration.ofDays(1));

        FakeGoogleServer.willReturn(account);
        MvcResult start = login.start(null, null, expired);
        // MockMvc는 SameSite를 흉내 내지 않으므로 옛 쿠키가 콜백에 실리는 경우까지 덮으려고 콜백에도 싣는다
        MvcResult callback = login.callback(start, login.authorize(start), expired);

        List<String> lines = LoginClient.setCookieHeaders(callback, LoginClient.SESSION_COOKIE);
        assertThat(lines).as("콜백은 세션 쿠키를 발급해야 한다").isNotEmpty();
        Cookie fresh = LoginClient.cookie(callback, LoginClient.SESSION_COOKIE);
        assertThat(fresh).as("브라우저는 같은 이름이면 마지막 줄을 따르므로 마지막 줄이 삭제 쿠키이면 안 된다: " + lines).isNotNull();
        assertThat(fresh.getValue()).isNotEqualTo(expired.getValue());
        assertThat(lines).as("커밋한 뒤 쿠키를 한 번만 쓴다(R4)").hasSize(1);
        mvc.perform(https(get("/api/v1/me")).cookie(fresh)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T021 FR-008 research R8: 절대 30일이 지난 쿠키로 /withdraw 를 열면 index.html 이고 세션 행이 삭제된다")
    void absoluteExpiryOnScreenPathServesIndexAndDeletesSession() throws Exception {
        Cookie cookie = loggedInShiftedBack(Duration.ofDays(31), Duration.ofDays(1));
        String id = SessionRows.idOfCookie(cookie.getValue());
        assertThat(sessions.exists(id)).isTrue();

        assertIndexHtml(mvc.perform(https(get("/withdraw")).accept(MediaType.TEXT_HTML).cookie(cookie)).andReturn());

        assertThat(sessions.exists(id)).isFalse();
    }

    @Test
    @DisplayName("T021 research R8 짝: 만료 전 쿠키로 /withdraw 를 열면 index.html 이고 세션 행은 남는다")
    void validSessionOnScreenPathKeepsSession() throws Exception {
        Cookie cookie = loggedInShiftedBack(Duration.ofDays(29), Duration.ofDays(1));
        String id = SessionRows.idOfCookie(cookie.getValue());

        assertIndexHtml(mvc.perform(https(get("/withdraw")).accept(MediaType.TEXT_HTML).cookie(cookie)).andReturn());

        assertThat(sessions.exists(id)).isTrue();
    }

    @Test
    @DisplayName("T021 research R8: 절대 30일이 지난 쿠키로도 로그인 시작은 구글로 보내고, 새 로그인이 된다")
    void absoluteExpiryLoginStartGoesToGoogle() throws Exception {
        GoogleAccount account = account();
        Cookie expired = loggedInShiftedBack(account, Duration.ofDays(31), Duration.ofDays(1));

        FakeGoogleServer.willReturn(account);
        MvcResult start = login.start(null, null, expired);
        assertThat(start.getResponse().getStatus()).isEqualTo(302);
        assertThat(start.getResponse().getHeader(HttpHeaders.LOCATION)).startsWith(FakeGoogleServer.issuer() + "/");

        Cookie fresh = LoginClient.cookie(login.callback(start, login.authorize(start)), LoginClient.SESSION_COOKIE);
        assertThat(fresh).as("구글에서 돌아오면 새 세션 쿠키가 생긴다").isNotNull();
        mvc.perform(https(get("/api/v1/me")).cookie(fresh)).andExpect(status().isOk());
    }

    private Cookie loggedInShiftedBack(Duration sinceLogin, Duration sinceLastRequest) throws Exception {
        return loggedInShiftedBack(account(), sinceLogin, sinceLastRequest);
    }

    /** 로그인한 뒤 세션 행을 옮긴다. expiry_time은 last_access_time과 같은 만큼 옮겨 유휴 7일 기준을 유지한다. */
    private Cookie loggedInShiftedBack(GoogleAccount account, Duration sinceLogin, Duration sinceLastRequest) throws Exception {
        Cookie cookie = login.login(account).sessionCookie();
        assertThat(cookie).as("로그인 콜백이 세션 쿠키를 발급해야 한다").isNotNull();
        int moved = sessions.shiftBackBySessionId(SessionRows.idOfCookie(cookie.getValue()), sinceLogin, sinceLastRequest, sinceLastRequest);
        assertThat(moved).isEqualTo(1);
        return cookie;
    }

    /**
     * 로그인한 뒤 creation_time을 30일, last_access_time과 expiry_time을 1일 과거로 옮기고, 우리 필터가 읽는 Clock을
     * (옮긴 creation_time + sinceLogin)에 멈춘다. 행의 밀리초 값에서 바로 계산하므로 요청 사이에 흐른 시간이 경과에 섞이지 않는다.
     * 유휴 만료는 Spring Session이 시스템 시각으로 보므로 last_access를 어제로 두어 걸리지 않게 한다(research R8).
     */
    private Cookie loggedInWithClockAfterLogin(Duration sinceLogin) throws Exception {
        Cookie cookie = login.login(account()).requireSessionCookie();
        String id = SessionRows.idOfCookie(cookie.getValue());
        assertThat(sessions.shiftBackBySessionId(id, Duration.ofDays(30), Duration.ofDays(1), Duration.ofDays(1))).isEqualTo(1);
        Instant created = Instant.ofEpochMilli(sessions.rowOf(id).creationTime());
        clock.freezeAt(created.plus(sinceLogin));
        return cookie;
    }

    private MvcResult expectUnauthenticated(Cookie cookie) throws Exception {
        return mvc.perform(https(get("/api/v1/me")).cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn();
    }

    /** 세션 쿠키를 지우는 줄(빈 값 또는 Max-Age=0, Path=/)이 있고, 응답이 끝났을 때 값 있는 세션 쿠키가 남지 않는다. */
    private static void assertDeletesSessionCookie(MvcResult result) {
        List<String> lines = LoginClient.setCookieHeaders(result, LoginClient.SESSION_COOKIE);
        assertThat(lines).as("세션 쿠키를 지우는 Set-Cookie 줄이 있어야 한다").anySatisfy(line -> {
            List<String> parts = Arrays.stream(line.split(";")).map(p -> p.trim().toLowerCase(Locale.ROOT)).toList();
            assertThat(parts.getFirst().equals(LoginClient.SESSION_COOKIE.toLowerCase(Locale.ROOT) + "=")
                    || parts.contains("max-age=0")).as("빈 값 또는 Max-Age=0: " + line).isTrue();
            assertThat(parts).as("Path=/: " + line).contains("path=/");
        });
        assertThat(LoginClient.cookie(result, LoginClient.SESSION_COOKIE)).as("마지막 줄이 삭제 쿠키여야 한다: " + lines).isNull();
    }

    /** 화면 경로가 index.html 을 돌려줬는지 본다. 본문으로 주면 파일 내용과 같고, forward면 그 대상이 index.html 이다. */
    private static void assertIndexHtml(MvcResult result) throws Exception {
        ClassPathResource index = new ClassPathResource("static/index.html");
        assertThat(index.exists()).as("static/index.html 이 클래스패스에 있어야 한다(web/dist 빌드 필요)").isTrue();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String forwarded = result.getResponse().getForwardedUrl();
        if (forwarded != null) {
            assertThat(forwarded).endsWith("index.html");
        } else {
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo(index.getContentAsString(StandardCharsets.UTF_8));
        }
    }

    private static GoogleAccount account() {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("expiry-" + id, "expiry-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
