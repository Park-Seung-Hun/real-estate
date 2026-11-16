package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import jakarta.servlet.http.Cookie;

import estate.support.FakeGoogleServer;
import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 보안 경계 테스트(tasks T060, FR-020, SC-006). 로그 출력을 캡처한 채 흐름을 실행하고, 출력과 응답 본문에
 * 가짜 이메일, 가짜 sub, 세션 쿠키 원문 토큰, ID 토큰 문자열이 없고 가짜 표시 이름은 내 정보 응답 본문에만 있는지 본다.
 * 내 정보 응답(GET /api/v1/me)은 계약상 loginEmail과 displayName을 담으므로 그 둘만 검사에서 뺀다(sub, 토큰은 검사).
 * ID 토큰 문자열을 알려고 모든 로그인을 정상 서명 토큰을 직접 넣는 경로(FakeGoogleServer.forgedIdToken을 바꾸지 않고 build)로 한다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
// 상태를 바꾸는 요청(PATCH, DELETE)의 Origin 검사를 통과시키려고 MockMvc 요청의 출처를 허용 출처로 둔다(research R9의 설정 이름)
class PersonalDataLeakSecurityTest {

    static final String ORIGIN = "https://localhost";

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionRows sessions;

    LoginClient login;

    /** 출력과 응답 어디에도 없어야 하는 값. */
    final List<String> secrets = new ArrayList<>();
    /** 내 정보 응답 본문 말고는 없어야 하는 값(표시 이름, 로그인 이메일). */
    final List<String> meOnly = new ArrayList<>();
    final List<MvcResult> responses = new ArrayList<>();
    final List<MvcResult> meResponses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    @Test
    void 로그인_성공_FR_020(CapturedOutput output) throws Exception {
        GoogleAccount a = account("success");

        Cookie session = loginWithKnownToken(a);
        MvcResult me = me(session);

        assertThat(me.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains(a.name());
        assertNoLeak(output);
    }

    @Test
    void 로그인_실패_FR_020(CapturedOutput output) throws Exception {
        GoogleAccount a = account("failure");

        // 구글 오류
        MvcResult start = record(login.start("/", null, null));
        MvcResult denied = record(login.callback(start, Map.of("error", "access_denied", "state", LoginClient.locationParam(start, "state"))));
        assertThat(denied.getResponse().getHeader(HttpHeaders.LOCATION)).startsWith("/login-failed");

        // ID 토큰 검증 실패(다른 aud). 실패 처리 로그에 토큰이나 클레임이 실리지 않아야 한다
        MvcResult badToken = callbackWithKnownToken(a, record(login.start("/", null, null)), t -> t.audience("other-client-id"));
        assertThat(badToken.getResponse().getHeader(HttpHeaders.LOCATION)).startsWith("/login-failed");

        assertNoLeak(output);
    }

    @Test
    void 재인증_실패_FR_020(CapturedOutput output) throws Exception {
        GoogleAccount a = account("reauth-failure");
        Cookie session = loginWithKnownToken(a);
        MvcResult start = record(login.start("/withdraw", "reauth", session));
        // 옛 세션이 유휴 만료되면 재인증은 실패한다(research R5)
        assertThat(sessions.shiftBackBySessionId(SessionRows.idOfCookie(session.getValue()),
                Duration.ofDays(8), Duration.ofDays(8), Duration.ofDays(8))).isEqualTo(1);

        MvcResult callback = callbackWithKnownToken(a, start, UnaryOperator.identity());

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION)).startsWith("/reauth-failed");
        assertNoLeak(output);
    }

    @Test
    void 다른_계정_재인증_FR_020(CapturedOutput output) throws Exception {
        GoogleAccount a = account("mismatch-a");
        GoogleAccount b = account("mismatch-b");
        Cookie session = loginWithKnownToken(a);
        MvcResult start = record(login.start("/withdraw", "reauth", session));

        MvcResult callback = callbackWithKnownToken(b, start, UnaryOperator.identity());

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/reauth-failed?reason=account-mismatch");
        assertNoLeak(output);
    }

    @Test
    void 롤백_FR_020(CapturedOutput output) throws Exception {
        GoogleAccount a = account("rollback");
        Cookie old = loginWithKnownToken(a);
        // 옛 세션 참조를 담은 일반 로그인에서 옛 세션 삭제를 실패시킨다(tasks T023과 같은 방식)
        MvcResult start = record(login.start("/", null, old));
        sessions.failDeleteOf(SessionRows.idOfCookie(old.getValue()));

        MvcResult callback = callbackWithKnownToken(a, start, UnaryOperator.identity());

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION)).startsWith("/login-failed");
        assertNoLeak(output);
    }

    @Test
    void 표시_이름_400_FR_020(CapturedOutput output) throws Exception {
        GoogleAccount a = account("bad-name");
        Cookie session = loginWithKnownToken(a);
        // 거부된 입력값(가짜 표시 이름에 제어 문자를 붙인 값)이 오류 응답이나 로그에 되풀이되면 안 된다
        String rejected = a.name() + "\\u0007";

        MvcResult patch = record(mvc.perform(api(MockMvcRequestBuilders.patch("/api/v1/me"), session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"" + rejected + "\"}")).andReturn());

        assertThat(patch.getResponse().getStatus()).isEqualTo(400);
        assertNoLeak(output);
    }

    @Test
    void 탈퇴_FR_020(CapturedOutput output) throws Exception {
        GoogleAccount a = account("withdraw");
        Cookie session = loginWithKnownToken(a);

        // 로그인 직후라 마지막 구글 인증이 5분 안이다
        MvcResult withdraw = record(mvc.perform(api(MockMvcRequestBuilders.delete("/api/v1/me"), session)).andReturn());

        assertThat(withdraw.getResponse().getStatus()).isEqualTo(204);
        assertNoLeak(output);
    }

    @Test
    void 등록_없는_로그인_제공자_FR_020(CapturedOutput output) throws Exception {
        // 경로의 제공자 이름 자리에 넣은 값(이메일 모양, 등록하지 않은 제공자)이 응답이나 로그에 되풀이되면 안 된다
        String email = "leak-" + UUID.randomUUID() + "@example.com";
        for (String provider : new String[] {email, "github"}) {
            secrets.add(provider);
            MvcResult result = record(mvc.perform(https(MockMvcRequestBuilders.get("/oauth2/authorization/" + provider))).andReturn());

            assertThat(result.getResponse().getStatus()).as(provider + " 응답").isEqualTo(404);
            String contentType = result.getResponse().getContentType();
            assertThat(contentType).as(provider + " 응답의 Content-Type").isNotNull();
            assertThat(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .as("404 본문은 application/problem+json: " + contentType).isTrue();
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .as("404 본문이 있어야 비반사 검사가 의미 있다").isNotBlank();
        }

        // 대조: 등록한 제공자(google)는 지금처럼 구글로 보낸다
        MvcResult google = record(login.start("/", null, null));
        assertThat(google.getResponse().getStatus()).isEqualTo(302);
        assertThat(google.getResponse().getHeader(HttpHeaders.LOCATION)).startsWith(FakeGoogleServer.issuer() + "/");

        assertNoLeak(output);
    }

    /** 정상 로그인 한 바퀴. 이메일, sub, 표시 이름, ID 토큰, 세션 쿠키 원문을 검사 대상에 넣는다. */
    private Cookie loginWithKnownToken(GoogleAccount a) throws Exception {
        MvcResult callback = callbackWithKnownToken(a, record(login.start("/", null, null)), UnaryOperator.identity());
        Cookie session = LoginClient.cookie(callback, LoginClient.SESSION_COOKIE);
        assertThat(session).as("로그인 성공 쿠키").isNotNull();
        secrets.add(session.getValue());
        return session;
    }

    /** 가짜 서버 인가 뒤, 테스트가 문자열을 아는 ID 토큰으로 토큰 응답을 예약하고 콜백한다. */
    private MvcResult callbackWithKnownToken(GoogleAccount a, MvcResult start,
            UnaryOperator<FakeGoogleServer.ForgedIdToken> change) throws Exception {
        remember(a);
        Map<String, String> authorized = login.authorize(start);
        String idToken = change.apply(FakeGoogleServer.forgedIdToken(a, LoginClient.locationParam(start, "nonce"))).build();
        secrets.add(idToken);
        FakeGoogleServer.enqueueTokenResponse(a, idToken);
        return record(login.callback(start, authorized));
    }

    private MvcResult me(Cookie session) throws Exception {
        MvcResult me = mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/me"), session)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        meResponses.add(me);
        return me;
    }

    private void remember(GoogleAccount a) {
        if (!secrets.contains(a.sub())) {
            secrets.add(a.sub());
            meOnly.add(a.email());
            meOnly.add(a.name());
        }
    }

    private MvcResult record(MvcResult result) {
        responses.add(result);
        return result;
    }

    private void assertNoLeak(CapturedOutput output) throws Exception {
        // 대조: 앱이 쓰는 로깅 시스템의 출력을 캡처가 실제로 잡는다(없음 검사가 빈 캡처로 통과하지 않게)
        String marker = "leak-check-marker-" + UUID.randomUUID();
        LoggerFactory.getLogger(PersonalDataLeakSecurityTest.class).warn(marker);
        assertThat(output.getAll()).contains(marker);
        assertThat(secrets).isNotEmpty();
        assertThat(responses).isNotEmpty();

        String all = output.getAll();
        for (String s : secrets) {
            assertThat(all).as("로그 출력").doesNotContain(s);
        }
        for (String s : meOnly) {
            assertThat(all).as("로그 출력").doesNotContain(s);
        }
        for (MvcResult r : responses) {
            String visible = visible(r);
            for (String s : secrets) {
                assertThat(visible).as(describe(r)).doesNotContain(s);
            }
            for (String s : meOnly) {
                assertThat(visible).as(describe(r)).doesNotContain(s);
            }
        }
        for (MvcResult r : meResponses) {
            for (String s : secrets) {
                assertThat(visible(r)).as(describe(r)).doesNotContain(s);
            }
        }
    }

    /** 응답 본문, 오류 메시지(sendError), 이동 주소. Set-Cookie는 세션 원문을 정당하게 담으므로 뺀다. */
    private static String visible(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8) + "\n" + r.getResponse().getErrorMessage()
                + "\n" + r.getResponse().getHeader(HttpHeaders.LOCATION);
    }

    private static String describe(MvcResult r) {
        return r.getRequest().getMethod() + " " + r.getRequest().getRequestURI() + " 응답";
    }

    /** 고유한 가짜 계정. 표시 이름은 20자 안이라 다듬어도 그대로 남는다. */
    private static GoogleAccount account(String tag) {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("leak-sub-" + tag + "-" + id, "leak-" + tag + "-" + id + "@example.com")
                .withName("누출확인" + id.substring(0, 8));
    }

    private static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder builder, Cookie session) {
        return https(builder.cookie(session).header(HttpHeaders.ORIGIN, ORIGIN));
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
