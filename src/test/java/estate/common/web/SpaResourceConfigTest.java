package estate.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;

/**
 * T017: 화면 경로를 index.html 로 돌리는 해석기(research R2). 화면 경로와 정적 파일은 로그인 없이 요청한다.
 * index.html 과 정적 파일은 클래스패스 static/(Gradle이 web/dist에서 복사)에 있어야 한다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class SpaResourceConfigTest {

    @Autowired
    MockMvc mvc;
    LoginClient login;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/no-household", "/withdraw"})
    @DisplayName("T017 research R2: 화면 경로는 로그인 없이 index.html")
    void screenPathServesIndexHtml(String path) throws Exception {
        MvcResult result = mvc.perform(https(get(path)).accept(MediaType.TEXT_HTML)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(isIndexHtml(result)).as("index.html 이어야 한다").isTrue();
    }

    @Test
    @DisplayName("T017 research R2: 있는 정적 파일은 그 파일 그대로 온다")
    void existingStaticFileIsServedAsIs() throws Exception {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:static/assets/*.js");
        assertThat(scripts).as("static/assets 아래 빌드한 스크립트가 있어야 한다(web/dist 빌드 필요)").isNotEmpty();
        Resource script = scripts[0];

        MvcResult result = mvc.perform(https(get("/assets/" + script.getFilename()))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(script.getContentAsByteArray());
        assertThat(isIndexHtml(result)).isFalse();
    }

    @Test
    @DisplayName("T017 research R2: 로그인한 상태의 /api/v1/없는경로 는 index.html 이 아니라 404 Problem Details")
    void unknownApiPathIs404Problem() throws Exception {
        String id = UUID.randomUUID().toString();
        Cookie cookie = login.login(GoogleAccount.of("spa-" + id, "spa-" + id + "@example.com")).sessionCookie();
        assertThat(cookie).as("로그인 콜백이 세션 쿠키를 발급해야 한다").isNotNull();

        MvcResult result = mvc.perform(https(get("/api/v1/no-such-path")).accept(MediaType.TEXT_HTML, MediaType.ALL).cookie(cookie))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentType()).isNotNull();
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as("content type은 application/problem+json").isTrue();
        assertThat(isIndexHtml(result)).isFalse();
    }

    @Test
    @DisplayName("T017 FR-020 API 공통 규칙: 로그인한 상태의 404 본문에 요청 경로가 없다(instance 포함)")
    void unknownApiPathBodyDoesNotEchoPath() throws Exception {
        Cookie cookie = loggedIn();
        String segment = "no-such-path-" + UUID.randomUUID();
        String path = "/api/v1/" + segment;

        MvcResult result = mvc.perform(https(get(path)).cookie(cookie)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).as("404 본문이 있어야 비반사 검사가 의미 있다").isNotBlank();
        assertThat(body).doesNotContain(segment).doesNotContain(path);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/api", "/oauth2"})
    @DisplayName("T017 research R2: 로그인한 상태에서 슬래시 없는 /api, /oauth2 도 index.html 이 아니다")
    void barePrefixesDoNotFallBackToIndex(String path) throws Exception {
        Cookie cookie = loggedIn();

        MvcResult result = mvc.perform(https(get(path)).accept(MediaType.TEXT_HTML).cookie(cookie)).andReturn();

        assertThat(isIndexHtml(result)).as(path + " 응답이 index.html 이면 안 된다").isFalse();
    }

    private Cookie loggedIn() throws Exception {
        String id = UUID.randomUUID().toString();
        Cookie cookie = login.login(GoogleAccount.of("spa-" + id, "spa-" + id + "@example.com")).sessionCookie();
        assertThat(cookie).as("로그인 콜백이 세션 쿠키를 발급해야 한다").isNotNull();
        return cookie;
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/oauth2/no-such-path", "/login/oauth2/no-such-path"})
    @DisplayName("T017 research R2: /oauth2/ 와 /login/oauth2/ 로 시작하는 경로는 index.html 로 가지 않는다")
    void oauthPathsDoNotFallBackToIndex(String path) throws Exception {
        MvcResult result = mvc.perform(https(get(path)).accept(MediaType.TEXT_HTML)).andReturn();

        assertThat(isIndexHtml(result)).as(path + " 응답이 index.html 이면 안 된다").isFalse();
    }

    /** 본문이 index.html 파일과 같거나 forward 대상이 index.html 이면 참. */
    private static boolean isIndexHtml(MvcResult result) throws Exception {
        ClassPathResource index = new ClassPathResource("static/index.html");
        assertThat(index.exists()).as("static/index.html 이 클래스패스에 있어야 한다(web/dist 빌드 필요)").isTrue();
        String forwarded = result.getResponse().getForwardedUrl();
        if (forwarded != null && forwarded.endsWith("index.html")) {
            return true;
        }
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8).equals(index.getContentAsString(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
