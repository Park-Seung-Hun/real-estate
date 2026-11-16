package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import com.nimbusds.jose.JWSAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import estate.support.FakeGoogleServer;
import estate.support.FakeGoogleServer.ForgedIdToken;
import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T022: ID 토큰 확인 표(결정 기록 0013)와 임시 값 쿠키 확인(research R6). 거절 이유가 모두 같은 화면(/login-failed)이라
 * 바꾸지 않은 정상 토큰이나 정상 흐름이 성공하는 대조 테스트를 같은 클래스에 둔다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class IdTokenValidationSecurityTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    SessionRows sessions;

    LoginClient login;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    private static GoogleAccount newAccount() {
        String id = "t022-" + UUID.randomUUID();
        return GoogleAccount.of("sub-" + id, id + "@example.com");
    }

    private void assertLoggedIn(MvcResult callback) {
        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(LoginClient.cookie(callback, LoginClient.SESSION_COOKIE)).isNotNull();
        assertThat(sessions.count()).isEqualTo(1);
    }

    private void assertRejected(MvcResult callback) {
        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/login-failed");
        assertThat(sessions.count()).isZero();
        assertThat(LoginClient.cookie(callback, LoginClient.SESSION_COOKIE)).isNull();
    }

    // ID 토큰 확인 표의 항목마다, 정상 토큰에서 그 항목 하나만 바꾼다.
    static Stream<Arguments> forgedTokens() {
        return Stream.of(
                Arguments.of("서명 알고리즘 RS384(같은 키)", (UnaryOperator<ForgedIdToken>) t -> t.alg(JWSAlgorithm.RS384)),
                Arguments.of("허용 목록 밖 iss", (UnaryOperator<ForgedIdToken>) t -> t.issuer("https://issuer.example.com")),
                Arguments.of("다른 aud", (UnaryOperator<ForgedIdToken>) t -> t.audience("other-client-id")),
                Arguments.of("만료된 토큰", (UnaryOperator<ForgedIdToken>) ForgedIdToken::expired),
                Arguments.of("nonce 불일치", (UnaryOperator<ForgedIdToken>) t -> t.nonce("other-nonce")));
    }

    @Test
    @DisplayName("T022 대조: 아무것도 바꾸지 않은 위조 경로 토큰은 로그인되어 / 로 가고 세션이 하나 생긴다")
    void unchangedTokenLogsIn() throws Exception {
        GoogleAccount account = newAccount();

        LoginClient.Result r = login.loginWithIdToken(account, nonce -> FakeGoogleServer.forgedIdToken(account, nonce).build());

        assertThat(FakeGoogleServer.pendingCount()).isZero();
        assertLoggedIn(r.callback());
    }

    @ParameterizedTest(name = "T022 {0}: /login-failed, 세션 없음")
    @MethodSource("forgedTokens")
    void invalidIdTokenIsRejected(String reason, UnaryOperator<ForgedIdToken> change) throws Exception {
        GoogleAccount account = newAccount();

        LoginClient.Result r = login.loginWithIdToken(account,
                nonce -> change.apply(FakeGoogleServer.forgedIdToken(account, nonce)).build());

        // 위조 토큰이 실제로 토큰 요청에 쓰였어야 토큰 때문에 거절된 것이다
        assertThat(FakeGoogleServer.pendingCount()).isZero();
        assertRejected(r.callback());
    }

    @Test
    @DisplayName("T022 대조: 단계별 정상 흐름(state 그대로, 바로 콜백)은 로그인된다")
    void unchangedFlowLogsIn() throws Exception {
        GoogleAccount account = newAccount();
        FakeGoogleServer.willReturn(account);
        MvcResult start = login.start("/", null, null);
        Map<String, String> query = login.authorize(start);

        assertLoggedIn(login.callback(start, query));
    }

    @Test
    @DisplayName("T022 state 위조: /login-failed, 세션 없음")
    void forgedStateIsRejected() throws Exception {
        GoogleAccount account = newAccount();
        FakeGoogleServer.willReturn(account);
        MvcResult start = login.start("/", null, null);
        Map<String, String> query = new HashMap<>(login.authorize(start));
        query.put("state", "forged-" + query.get("state"));

        assertRejected(login.callback(start, query));
    }

    @Test
    @DisplayName("T022 대조: 시작하고 9분 뒤 콜백은 로그인된다")
    void callbackWithinTenMinutesLogsIn() throws Exception {
        GoogleAccount account = newAccount();
        FakeGoogleServer.willReturn(account);
        MvcResult start = login.start("/", null, null);
        Map<String, String> query = login.authorize(start);
        clock.advance(Duration.ofMinutes(9));

        assertLoggedIn(login.callback(start, query));
    }

    @Test
    @DisplayName("T022 시작 시각이 10분 지난 임시 값 쿠키(11분 뒤 콜백): /login-failed, 세션 없음")
    void callbackAfterTenMinutesIsRejected() throws Exception {
        GoogleAccount account = newAccount();
        FakeGoogleServer.willReturn(account);
        MvcResult start = login.start("/", null, null);
        Map<String, String> query = login.authorize(start);
        clock.advance(Duration.ofMinutes(11));

        assertRejected(login.callback(start, query));
    }
}
