package estate.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import estate.common.crypto.ColumnCipher;
import estate.support.FakeGoogleServer;
import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 로그인 동작(tasks T025). 기대값은 spec, contracts/openapi.yaml, research R2, R6, R11, 결정 기록 0013에서 가져왔다. */
@LoginIntegrationTest
@AutoConfigureMockMvc
class LoginFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionRows sessions;
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
    void 로그인_시작은_계정_선택과_PKCE와_state와_nonce를_담아_구글로_보내고_세션은_만들지_않는다_FR_012_R6() throws Exception {
        FakeGoogleServer.willReturn(account("start"));

        MvcResult start = login.start(null, null, null);

        assertThat(start.getResponse().getStatus()).isEqualTo(302);
        String location = start.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith(FakeGoogleServer.server().authorizationEndpointUrl(FakeGoogleServer.ISSUER_ID).toString());
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(query.getFirst("prompt")).isEqualTo("select_account");
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(query.getFirst("code_challenge")).isNotBlank();
        assertThat(query.getFirst("state")).isNotBlank();
        assertThat(query.getFirst("nonce")).isNotBlank();

        assertThat(LoginClient.setCookieHeaders(start, LoginClient.LOGIN_COOKIE)).singleElement().satisfies(line -> {
            assertThat(LoginClient.cookie(start, LoginClient.LOGIN_COOKIE)).isNotNull();
            Map<String, String> attributes = cookieAttributes(line);
            assertThat(attributes).containsEntry("max-age", "600").containsEntry("path", "/")
                    .containsKey("httponly").containsKey("secure").doesNotContainKey("domain");
            assertThat(attributes.get("samesite")).isEqualToIgnoringCase("Lax");
        });

        // 요청 캐시와 기본 인가 요청 저장소는 세션을 만든다(research R6). 시작 요청 뒤 세션 행도 세션 쿠키도 없어야 한다
        assertThat(LoginClient.setCookieHeaders(start, LoginClient.SESSION_COOKIE)).isEmpty();
        assertThat(sessions.count()).isZero();

        // 대조: 같은 흐름을 끝내면 세션 행이 생긴다(위의 0이 세지 못해서 나온 값이 아님)
        MvcResult callback = login.callback(start, login.authorize(start));
        assertThat(LoginClient.cookie(callback, LoginClient.SESSION_COOKIE)).isNotNull();
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void 재인증_목적의_로그인_시작에도_계정_선택이_붙는다_FR_012() throws Exception {
        Cookie session = login.login(account("reauth-start")).sessionCookie();
        assertThat(session).isNotNull();

        MvcResult start = login.start("/withdraw", "reauth", session);

        assertThat(start.getResponse().getStatus()).isEqualTo(302);
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(start.getResponse().getHeader(HttpHeaders.LOCATION))
                .build().getQueryParams();
        assertThat(query.getFirst("prompt")).isEqualTo("select_account");
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
    }

    @Test
    void 처음_로그인하면_사용자가_생기고_구글_이름과_이메일과_검증_여부가_저장되며_첫_화면으로_간다_US1_2_FR_003_FR_005() throws Exception {
        GoogleAccount google = account("first").withName("처음 사용자");

        LoginClient.Result result = login.login(google);

        assertThat(result.callback().getResponse().getStatus()).isEqualTo(302);
        assertThat(result.location()).isEqualTo("/");
        assertThat(result.sessionCookie()).isNotNull();

        UserRow row = userOf(google.sub());
        assertThat(row.provider()).isEqualTo("google");
        assertThat(row.displayName()).isEqualTo("처음 사용자");
        assertThat(cipher.decrypt(row.loginEmail(), "user.login_email:" + row.id())).isEqualTo(google.email());
        assertThat(row.emailVerified()).isTrue();
    }

    @Test
    void 다시_로그인하면_이메일과_검증_여부는_최신_값으로_바뀌고_표시_이름은_그대로다_US1_3_FR_004() throws Exception {
        GoogleAccount before = account("again").withName("원래 이름");
        login.login(before);
        long id = userOf(before.sub()).id();

        GoogleAccount after = before.withEmail("changed-" + UUID.randomUUID() + "@example.com").withEmailVerified(false).withName("바뀐 구글 이름");
        LoginClient.Result result = login.login(after);

        assertThat(result.location()).isEqualTo("/");
        assertThat(jdbc.sql("select count(*) from \"user\" where provider = 'google' and provider_subject = :sub")
                .param("sub", before.sub()).query(Long.class).single()).isEqualTo(1);
        UserRow row = userOf(before.sub());
        assertThat(row.id()).isEqualTo(id);
        assertThat(cipher.decrypt(row.loginEmail(), "user.login_email:" + row.id())).isEqualTo(after.email());
        assertThat(row.emailVerified()).isFalse();
        assertThat(row.displayName()).isEqualTo("원래 이름");
    }

    @Test
    void 로그인하지_않은_내_정보_요청은_401이고_세션을_만들지_않는다_US1_1_FR_002() throws Exception {
        MvcResult me = mvc.perform(https(MockMvcRequestBuilders.get("/api/v1/me"))).andReturn();

        assertThat(me.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(me).get("code").asString()).isEqualTo("UNAUTHENTICATED");
        // 요청 캐시를 켜 두면 401 진입점이 요청을 세션에 저장한다(research R6)
        assertThat(LoginClient.setCookieHeaders(me, LoginClient.SESSION_COOKIE)).isEmpty();
        assertThat(sessions.count()).isZero();
    }

    @Test
    void 이메일_검증이_안_된_계정도_로그인되고_내_정보에_거짓으로_담긴다_EdgeCase() throws Exception {
        GoogleAccount google = account("unverified").withEmailVerified(false);

        LoginClient.Result result = login.login(google);

        assertThat(result.location()).isEqualTo("/");
        assertThat(result.sessionCookie()).isNotNull();
        JsonNode me = json(mvc.perform(https(MockMvcRequestBuilders.get("/api/v1/me")).cookie(result.sessionCookie())).andReturn());
        assertThat(me.get("emailVerified").asBoolean()).isFalse();
        assertThat(me.get("emailVerified").isBoolean()).isTrue();
        assertThat(me.get("loginEmail").asString()).isEqualTo(google.email());
        assertThat(userOf(google.sub()).emailVerified()).isFalse();
    }

    @Test
    void 로그인_방법은_구글_하나뿐이다_FR_001() throws Exception {
        // research R2: /login은 화면 경로라 index.html이다. Spring Security가 만드는 기본 로그인 페이지가 아니어야 한다
        String indexHtml = indexHtml();
        MvcResult loginPage = mvc.perform(https(MockMvcRequestBuilders.get("/login"))).andReturn();
        assertThat(loginPage.getResponse().getStatus()).isEqualTo(200);
        assertThat(loginPage.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(indexHtml);

        // 폼 로그인이 있으면 자격 증명이 틀려도 302로 보낸다. 문서가 상태 코드를 정하지 않아(정적 파일 처리는 GET만 받고,
        // Origin 검사가 먼저 막을 수도 있음) 4xx이고, 이동이 없고, 세션이 생기지 않는 것만 본다
        MvcResult formLogin = mvc.perform(https(MockMvcRequestBuilders.post("/login"))
                .header(HttpHeaders.ORIGIN, "https://localhost")
                .param("username", "form-user@example.com").param("password", "form-password")).andReturn();
        assertThat(formLogin.getResponse().getStatus()).isBetween(400, 499);
        assertThat(formLogin.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(LoginClient.setCookieHeaders(formLogin, LoginClient.SESSION_COOKIE)).isEmpty();

        // research R2: oauth2/로 시작하는 경로는 index.html로 가지 않는다. 등록이 없는 제공자는 로그인 시작이 아니다
        // (구글로 보내지 않고 임시 값 쿠키도 없음). 상태 코드는 문서에 없어 오류(4xx, 5xx)인 것만 본다
        for (String provider : new String[] {"github", "kakao"}) {
            MvcResult other = mvc.perform(https(MockMvcRequestBuilders.get("/oauth2/authorization/" + provider))).andReturn();
            assertThat(other.getResponse().getStatus()).as(provider).isGreaterThanOrEqualTo(400);
            assertThat(other.getResponse().getHeader(HttpHeaders.LOCATION)).as(provider).isNull();
            assertThat(LoginClient.setCookieHeaders(other, LoginClient.LOGIN_COOKIE)).as(provider).isEmpty();
            assertThat(other.getResponse().getContentAsString(StandardCharsets.UTF_8)).as(provider).isNotEqualTo(indexHtml);
        }
        assertThat(sessions.count()).isZero();
    }

    @Test
    void 구글_이름이_21자면_앞_20자를_쓰고_방향_제어_문자는_지운다_R11() throws Exception {
        String twentyOne = "가나다라마바사아자차카타파하거너더러머버서";
        assertThat(twentyOne.codePointCount(0, twentyOne.length())).isEqualTo(21);
        GoogleAccount longName = account("long-name").withName(twentyOne);
        GoogleAccount bidiName = account("bidi-name").withName("‮엄마⁦");

        login.login(longName);
        login.login(bidiName);

        assertThat(userOf(longName.sub()).displayName()).isEqualTo(twentyOne.substring(0, 20));
        assertThat(userOf(bidiName.sub()).displayName()).isEqualTo("엄마");
    }

    record UserRow(long id, String provider, byte[] loginEmail, boolean emailVerified, String displayName) {
    }

    private UserRow userOf(String sub) {
        return jdbc.sql("select id, provider, login_email, email_verified, display_name from \"user\" where provider = 'google' and provider_subject = :sub")
                .param("sub", sub).query(UserRow.class).optional()
                .orElseThrow(() -> new AssertionError("사용자 행이 없다: " + sub));
    }

    /** 테스트마다 새 사용자 행이 되도록 sub와 이메일을 새로 만든다(사용자 행은 테스트 사이에 남음). */
    private static GoogleAccount account(String tag) {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("login-flow-" + tag + "-" + id, tag + "-" + id + "@example.com");
    }

    private static Map<String, String> cookieAttributes(String setCookie) {
        Map<String, String> attributes = new LinkedHashMap<>();
        String[] parts = setCookie.split(";");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            int eq = part.indexOf('=');
            attributes.put((eq < 0 ? part : part.substring(0, eq)).trim().toLowerCase(Locale.ROOT), eq < 0 ? "" : part.substring(eq + 1).trim());
        }
        return attributes;
    }

    private static String indexHtml() throws Exception {
        ClassPathResource index = new ClassPathResource("static/index.html");
        assertThat(index.exists()).as("웹 화면 빌드 결과(static/index.html)가 테스트 클래스패스에 있어야 한다").isTrue();
        return index.getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** LoginClient와 같은 https 요청(Secure 쿠키를 주고받으려면 https여야 함). */
    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
