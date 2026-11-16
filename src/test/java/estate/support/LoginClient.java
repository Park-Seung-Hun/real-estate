package estate.support;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import jakarta.servlet.http.Cookie;

import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * 로그인 한 바퀴: {@code new LoginClient(mockMvc).login(GoogleAccount.of("sub-1", "user1@example.com")).requireSessionCookie()}.
 * 단계별 {@code start}, {@code authorize}, {@code callback}과 쿠키 도우미({@code cookie}, {@code setCookieHeaders}), 잘못된 토큰 {@code loginWithIdToken}.
 * {@code login}류는 시작 때 남은 가짜 서버 예약을 비운다(동시 콜백은 {@code FakeGoogleServer.willReturn}을 직접 쌓고 단계별로 부른다).
 */
public final class LoginClient {

    public static final String SESSION_COOKIE = "__Host-Http-session";
    public static final String LOGIN_COOKIE = "__Host-Http-login";

    public record Result(MvcResult start, MvcResult callback) {
        public Cookie sessionCookie() {
            return cookie(callback, SESSION_COOKIE);
        }

        /** 성공을 전제하는 테스트용: 세션 쿠키가 없으면(또는 삭제 쿠키면) 예외. */
        public Cookie requireSessionCookie() {
            Cookie c = sessionCookie();
            if (c == null) {
                throw new IllegalStateException("세션 쿠키가 없다. 콜백 응답: " + callback.getResponse().getStatus() + " "
                        + callback.getResponse().getHeader(HttpHeaders.LOCATION));
            }
            return c;
        }

        public String location() {
            return callback.getResponse().getHeader(HttpHeaders.LOCATION);
        }
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private final MockMvc mockMvc;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    public LoginClient(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** 정상 로그인 한 바퀴. 세션 쿠키가 없는 상태에서 시작한다. */
    public Result login(GoogleAccount account) throws Exception {
        return login(account, "/", null, null);
    }

    public Result login(GoogleAccount account, String returnTo, String purpose, Cookie oldSessionCookie) throws Exception {
        FakeGoogleServer.reset();
        FakeGoogleServer.willReturn(account);
        MvcResult start = start(returnTo, purpose, oldSessionCookie);
        return new Result(start, callback(start, authorize(start, account)));
    }

    /** 잘못된 ID 토큰으로 한 바퀴. nonce를 받아 토큰을 만드는 함수를 준다: {@code n -> forgedIdToken(a, n).audience("x").build()}. */
    public Result loginWithIdToken(GoogleAccount account, Function<String, String> idTokenForNonce) throws Exception {
        FakeGoogleServer.reset();
        MvcResult start = start("/", null, null);
        Map<String, String> authorized = authorize(start, account);
        String nonce = locationParam(start, "nonce");
        FakeGoogleServer.enqueueTokenResponse(account, idTokenForNonce.apply(nonce));
        return new Result(start, callback(start, authorized));
    }

    /** GET /oauth2/authorization/google. 응답은 구글로 가는 리디렉션이다. */
    public MvcResult start(String returnTo, String purpose, Cookie sessionCookie) throws Exception {
        MockHttpServletRequestBuilder get = MockMvcRequestBuilders.get("/oauth2/authorization/google");
        if (returnTo != null) {
            get.queryParam("returnTo", returnTo);
        }
        if (purpose != null) {
            get.queryParam("purpose", purpose);
        }
        if (sessionCookie != null) {
            get.cookie(sessionCookie);
        }
        return mockMvc.perform(https(get)).andReturn();
    }

    /**
     * 시작 응답의 Location으로 가짜 서버 인가를 HTTP로 실행하고 돌아온 code와 state를 준다. prompt 때문에 뜨는 로그인 폼은 자동으로 넘기며,
     * 폼에 넣는 계정은 예약 대기열의 맨 앞 계정이다(소비하지 않음, 예약이 없으면 고정 계정). 계정을 직접 정하려면 {@code authorize(start, account)}.
     */
    public Map<String, String> authorize(MvcResult start) throws Exception {
        GoogleAccount next = FakeGoogleServer.peekNext();
        return authorize(start, next != null ? next : GoogleAccount.of("form-login-user", "form-login-user@example.com"));
    }

    /** prompt 때문에 가짜 서버가 로그인 폼(200)을 주면 이 계정의 sub와 claims로 폼을 보낸다. 폼이 없으면 계정은 쓰이지 않는다. */
    public Map<String, String> authorize(MvcResult start, GoogleAccount account) throws Exception {
        HttpResponse<Void> first = http.send(HttpRequest.newBuilder(URI.create(location(start))).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        // 가짜 서버는 폼의 username과 claims를 토큰의 sub와 claims로 쓰므로 예약한 계정과 같은 값을 보낸다(예약 대기열은 토큰 요청이 소비한다).
        HttpResponse<Void> response = first.statusCode() != 200 ? first : http.send(HttpRequest.newBuilder(URI.create(location(start)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + URLEncoder.encode(account.sub(), StandardCharsets.UTF_8)
                        + "&claims=" + URLEncoder.encode(JSON.writeValueAsString(account.claims()), StandardCharsets.UTF_8))).build(),
                HttpResponse.BodyHandlers.discarding());
        String back = response.headers().firstValue("Location").orElseThrow(
                () -> new IllegalStateException("가짜 서버 인가가 리디렉션하지 않음: " + response.statusCode()));
        String error = param(back, "error");
        if (error != null) {
            throw new IllegalStateException("가짜 서버 인가가 error로 돌아옴: error=" + error + ", " + back);
        }
        String code = param(back, "code");
        String state = param(back, "state");
        if (code == null || state == null) {
            throw new IllegalStateException("가짜 서버 인가 응답에 code 또는 state가 없음: " + back);
        }
        return Map.of("code", code, "state", state);
    }

    /**
     * GET /login/oauth2/code/google. 쿼리값은 {@code authorize}의 결과나 {@code Map.of("error", "access_denied", "state", s)} 같은 직접 만든 값.
     * 시작 응답의 쿠키에 추가 쿠키(옛 세션 쿠키 등)를 더해 보낸다(같은 이름이면 추가 쿠키가 이긴다).
     */
    public MvcResult callback(MvcResult start, Map<String, String> query, Cookie... extra) throws Exception {
        Map<String, Cookie> cookies = new LinkedHashMap<>();
        startCookies(start).forEach(c -> cookies.put(c.getName(), c));
        for (Cookie c : extra) {
            cookies.put(c.getName(), c);
        }
        return callbackWithCookies(query, cookies.values().toArray(new Cookie[0]));
    }

    /** 실을 쿠키를 정확히 이것만 보낸다(임시 값 쿠키 없음이나 변조 경우). */
    public MvcResult callbackWithCookies(Map<String, String> query, Cookie... cookies) throws Exception {
        MockHttpServletRequestBuilder get = MockMvcRequestBuilders.get("/login/oauth2/code/google");
        query.forEach(get::queryParam);
        if (cookies.length > 0) {
            get.cookie(cookies);
        }
        return mockMvc.perform(https(get)).andReturn();
    }

    /** 시작 응답이 설정한 쿠키(삭제 쿠키 제외). 콜백에 자동으로 실리는 것. */
    public static List<Cookie> startCookies(MvcResult start) {
        List<Cookie> cookies = new ArrayList<>();
        for (String header : start.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
            if (!isDeletion(header)) {
                cookies.add(parse(header));
            }
        }
        return cookies;
    }

    /** 이 응답이 설정한 쿠키. 없거나 삭제 쿠키(빈 값 또는 Max-Age=0)면 null. 속성은 {@code setCookieHeaders}로 본다. */
    public static Cookie cookie(MvcResult result, String name) {
        Cookie found = null;
        for (String header : setCookieHeaders(result, name)) {
            found = isDeletion(header) ? null : parse(header);
        }
        return found;
    }

    /** 이 이름의 Set-Cookie 원문 줄(속성 포함, 삭제 쿠키도 포함). */
    public static List<String> setCookieHeaders(MvcResult result, String name) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream().filter(h -> parse(h).getName().equals(name)).toList();
    }

    private static boolean isDeletion(String setCookie) {
        if (parse(setCookie).getValue().isEmpty()) {
            return true;
        }
        for (String attribute : setCookie.split(";")) {
            String a = attribute.trim().toLowerCase(Locale.ROOT);
            if (a.startsWith("max-age=") && a.substring(8).trim().matches("-?0+|-\\d+")) {
                return true;
            }
        }
        return false;
    }

    /** 응답 Location 주소의 질의 값(디코딩됨, 없으면 null). 시작 응답에서 state, nonce를 꺼낼 때 쓴다. */
    public static String locationParam(MvcResult result, String name) {
        return param(location(result), name);
    }

    private static String param(String url, String name) {
        String value = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);
        return value == null ? null : UriUtils.decode(value, StandardCharsets.UTF_8);
    }

    private static Cookie parse(String setCookie) {
        String pair = setCookie.split(";", 2)[0];
        int eq = pair.indexOf('=');
        return new Cookie(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
    }

    private static String location(MvcResult result) {
        return result.getResponse().getHeader(HttpHeaders.LOCATION);
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
