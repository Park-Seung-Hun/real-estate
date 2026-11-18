package estate.support;

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

import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code @SpringBootTest(RANDOM_PORT)}의 실제 서버로 요청한다: {@code new RealServerClient(port)}.
 * MockMvc LoginClient를 쓸 수 없어 실제 서버 주소로 가짜 구글 흐름을 직접 돌고 세션 쿠키 값을 준다({@code login}).
 */
public final class RealServerClient {

    public static final String ORIGIN = "https://localhost";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final int port;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    public RealServerClient(int port) {
        this.port = port;
    }

    /** 실제 서버 주소로 로그인 시작, 가짜 구글 인가, 콜백을 돌고 세션 쿠키 값을 준다. 그 쿠키로 /api/v1/me 200까지 확인한다. */
    public String login() throws Exception {
        String id = UUID.randomUUID().toString();
        GoogleAccount account = GoogleAccount.of("leak-" + id, "leak-" + id + "@example.com");
        FakeGoogleServer.willReturn(account);

        HttpResponse<String> start = send(HttpRequest.newBuilder(url("/oauth2/authorization/google")).GET());
        assertThat(start.statusCode()).as("로그인 시작은 구글로 보낸다").isEqualTo(302);
        Map<String, String> loginCookies = setCookies(start);

        String authorize = start.headers().firstValue("Location").orElseThrow();
        HttpResponse<String> first = send(HttpRequest.newBuilder(URI.create(authorize)).GET());
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

    public HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public URI url(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
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
}
