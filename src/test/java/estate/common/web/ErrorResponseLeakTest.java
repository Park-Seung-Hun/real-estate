package estate.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import estate.support.FakeGoogleServer;
import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;

/**
 * 오류 본문의 요청 값 비반사(FR-020, tasks T012 "오류 본문과 로그에 요청 값을 그대로 넣지 않는다", API 공통 규칙 오류 응답 절의 application/problem+json).
 * 우리 advice가 받지 않는 오류(405, 방화벽 거절 400, 본문 파싱 400과 415)는 sendError로 Boot 기본 오류 처리에 가므로,
 * error dispatch를 하지 않는 MockMvc 대신 실제 서버를 띄워 java.net.http로 요청한다.
 * 직접 붙인 @SpringBootTest(RANDOM_PORT)가 @LoginIntegrationTest 안의 @SpringBootTest보다 가깝게 선언돼 이긴다
 * (두 선언의 @BootstrapWith 값이 같아 충돌하지 않음). 그 밖의 @IntegrationTest 설정(가짜 키, 가짜 구글, 허용 출처, PostgreSQL)은 그대로 쓴다.
 * 로그인은 MockMvc LoginClient를 쓸 수 없어 실제 서버 주소로 가짜 구글 흐름을 직접 돈다.
 */
@LoginIntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorResponseLeakTest {

    static final String ORIGIN = "https://localhost";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    @DisplayName("FR-020: 로그인 상태의 GET /api/v1/logout(POST만 받음) 오류 본문에 경로와 표식이 없다")
    void methodNotAllowedDoesNotEcho() throws Exception {
        String session = loginOnRealServer();
        String marker = marker();

        HttpResponse<String> response = send(HttpRequest.newBuilder(url("/api/v1/logout?" + marker)).GET()
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));

        assertThat(response.statusCode()).isEqualTo(405);
        assertNoEcho(response, "/api/v1/logout", marker);
    }

    @Test
    @DisplayName("FR-020: 로그인 상태에서 방화벽이 거절한 경로(세미콜론)의 400 본문에 경로와 표식이 없다")
    void firewallRejectionDoesNotEchoWhenLoggedIn() throws Exception {
        String session = loginOnRealServer();
        String marker = marker();

        HttpResponse<String> response = send(HttpRequest.newBuilder(url("/api/v1/x;" + marker)).GET()
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));

        assertThat(response.statusCode()).isEqualTo(400);
        assertNoEcho(response, "/api/v1/x", marker);
    }

    @Test
    @DisplayName("FR-020: 로그인 없이도 방화벽이 거절한 경로(세미콜론)의 400 본문에 경로와 표식이 없다")
    void firewallRejectionDoesNotEchoWithoutLogin() throws Exception {
        String marker = marker();

        HttpResponse<String> response = send(HttpRequest.newBuilder(url("/api/v1/x;" + marker)).GET());

        assertThat(response.statusCode()).isEqualTo(400);
        assertNoEcho(response, "/api/v1/x", marker);
    }

    @Test
    @DisplayName("FR-020: 로그인 상태의 POST /api/v1/logout 에 깨진 JSON 본문을 보내도 오류 본문에 경로와 표식이 없다(상태 코드는 정하지 않음)")
    void brokenJsonBodyDoesNotEcho() throws Exception {
        String session = loginOnRealServer();
        String marker = marker();

        HttpResponse<String> response = send(HttpRequest.newBuilder(url("/api/v1/logout?" + marker))
                .POST(HttpRequest.BodyPublishers.ofString("{\"" + marker + "\": "))
                .header("Content-Type", "application/json")
                .header("Origin", ORIGIN)
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));

        assertNoEcho(response, "/api/v1/logout", marker);
    }

    /** 본문에 경로와 표식이 없고, 본문이 있으면 application/problem+json 이다. */
    private static void assertNoEcho(HttpResponse<String> response, String path, String marker) {
        String body = response.body();
        assertThat(body).as("오류 본문에 표식이 없어야 한다").doesNotContain(marker);
        assertThat(body).as("오류 본문에 요청 경로가 없어야 한다").doesNotContain(path);
        if (!body.isEmpty()) {
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            assertThat(contentType).as("본문이 있으면 Content-Type이 있어야 한다").isNotNull();
            assertThat(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .as("오류 본문은 application/problem+json: " + contentType).isTrue();
        }
    }

    /** 실제 서버 주소로 로그인 시작, 가짜 구글 인가, 콜백을 돌고 세션 쿠키 값을 준다. 그 쿠키로 /api/v1/me 200까지 확인한다. */
    private String loginOnRealServer() throws Exception {
        String id = UUID.randomUUID().toString();
        GoogleAccount account = GoogleAccount.of("leak-" + id, "leak-" + id + "@example.com");
        FakeGoogleServer.willReturn(account);

        HttpResponse<String> start = send(HttpRequest.newBuilder(url("/oauth2/authorization/google")).GET());
        assertThat(start.statusCode()).as("로그인 시작은 구글로 보낸다").isEqualTo(302);
        Map<String, String> loginCookies = setCookies(start);

        String authorize = start.headers().firstValue("Location").orElseThrow();
        HttpResponse<String> first = send(HttpRequest.newBuilder(URI.create(authorize)).GET());
        // prompt 때문에 가짜 서버가 로그인 폼(200)을 주면 예약한 계정으로 폼을 보낸다(LoginClient.authorize와 같은 방식)
        HttpResponse<String> authorized = first.statusCode() != 200 ? first : send(HttpRequest.newBuilder(URI.create(authorize))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + URLEncoder.encode(account.sub(), StandardCharsets.UTF_8)
                        + "&claims=" + URLEncoder.encode(JSON.writeValueAsString(account.claims()), StandardCharsets.UTF_8))));
        String callbackUrl = authorized.headers().firstValue("Location").orElseThrow(
                () -> new IllegalStateException("가짜 서버 인가가 리디렉션하지 않음: " + authorized.statusCode()));
        assertThat(UriComponentsBuilder.fromUriString(callbackUrl).build().getQueryParams()).containsKey("code");

        HttpResponse<String> callback = send(HttpRequest.newBuilder(URI.create(callbackUrl)).GET()
                .header("Cookie", loginCookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; "))));
        String session = setCookies(callback).get(LoginClient.SESSION_COOKIE);
        assertThat(session).as("콜백이 세션 쿠키를 발급해야 한다").isNotNull();

        HttpResponse<String> me = send(HttpRequest.newBuilder(url("/api/v1/me")).GET()
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));
        assertThat(me.statusCode()).as("실제 서버에서 로그인 상태가 통해야 오류 경우가 401로 새지 않는다").isEqualTo(200);
        return session;
    }

    /** 응답의 Set-Cookie 가운데 값이 있고 Max-Age=0이 아닌 것(같은 이름이면 마지막 줄). */
    private static Map<String, String> setCookies(HttpResponse<?> response) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String header : response.headers().allValues("Set-Cookie")) {
            String[] parts = header.split(";");
            int eq = parts[0].indexOf('=');
            String name = parts[0].substring(0, eq).trim();
            String value = parts[0].substring(eq + 1).trim();
            boolean deletion = value.isEmpty();
            for (int i = 1; i < parts.length; i++) {
                String a = parts[i].trim().toLowerCase(Locale.ROOT);
                deletion |= a.startsWith("max-age=") && a.substring(8).trim().matches("-?0+|-\\d+");
            }
            if (deletion) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
        return cookies;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI url(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    private static String marker() {
        return "marker-" + UUID.randomUUID();
    }
}
