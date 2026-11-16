package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import estate.support.FakeGoogleServer;
import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/** T023: 콜백이 만드는 세션(research R4)과 실패, 롤백 뒤 세션이 없음(US1-4, FR-007). */
@LoginIntegrationTest
@AutoConfigureMockMvc
class CallbackSessionSecurityTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SessionRows sessions;

    LoginClient login;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    private static GoogleAccount newAccount() {
        String id = "t023-" + UUID.randomUUID();
        return GoogleAccount.of("sub-" + id, id + "@example.com");
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + n.length <= haystack.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** 세션을 만드는 Set-Cookie가 없음(값 있는 __Host-Http-session이 없음). 삭제 쿠키는 허용한다. */
    private static void assertNoSessionCookieIssued(MvcResult callback) {
        assertThat(LoginClient.cookie(callback, LoginClient.SESSION_COOKIE)).isNull();
    }

    @Test
    @DisplayName("T023 R4: 콜백 뒤 세션 행이 정확히 하나이고 속성에 이메일, ID 토큰, sub 문자열이 없다")
    void callbackCreatesExactlyOneMinimalSession() throws Exception {
        GoogleAccount account = newAccount();
        AtomicReference<String> idToken = new AtomicReference<>();
        assertThat(sessions.count()).isZero();

        LoginClient.Result r = login.loginWithIdToken(account, nonce -> {
            idToken.set(FakeGoogleServer.forgedIdToken(account, nonce).build());
            return idToken.get();
        });

        assertThat(r.location()).isEqualTo("/");
        assertThat(sessions.count()).isEqualTo(1);
        Cookie cookie = r.sessionCookie();
        assertThat(cookie).isNotNull();
        String sessionId = SessionRows.idOfCookie(cookie.getValue());
        assertThat(sessions.exists(sessionId)).isTrue();
        Map<String, byte[]> attributes = sessions.attributes(sessionId);
        // 로그인 정보가 담긴 속성이 있어야 "없음" 검사가 빈 결과로 통과하지 않는다
        assertThat(attributes).isNotEmpty();
        attributes.forEach((name, bytes) -> {
            assertThat(contains(bytes, account.email())).as("속성 %s에 이메일", name).isFalse();
            assertThat(contains(bytes, idToken.get())).as("속성 %s에 ID 토큰", name).isFalse();
            assertThat(contains(bytes, account.sub())).as("속성 %s에 sub", name).isFalse();
        });
    }

    @Test
    @DisplayName("T023 US1-4 FR-007: 구글 오류(error=access_denied) 뒤 새 세션 행과 세션 Set-Cookie가 없다")
    void googleErrorCreatesNoSession() throws Exception {
        MvcResult start = login.start("/", null, null);
        assertThat(sessions.count()).isZero();

        MvcResult callback = login.callback(start, Map.of("error", "access_denied", "state", LoginClient.locationParam(start, "state")));

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/login-failed");
        assertThat(sessions.count()).isZero();
        assertNoSessionCookieIssued(callback);
    }

    @Test
    @DisplayName("T023 대조 R4: 로그인한 쿠키로 다시 로그인하면 옛 세션 행은 지워지고 새 세션 하나가 생긴다")
    void reloginReplacesOldSession() throws Exception {
        GoogleAccount account = newAccount();
        Cookie oldCookie = login.login(account).sessionCookie();
        assertThat(oldCookie).isNotNull();
        String oldId = SessionRows.idOfCookie(oldCookie.getValue());
        assertThat(sessions.exists(oldId)).isTrue();

        LoginClient.Result r = login.login(account, "/", null, oldCookie);

        assertThat(r.location()).isEqualTo("/");
        Cookie newCookie = r.sessionCookie();
        assertThat(newCookie).isNotNull();
        assertThat(sessions.exists(oldId)).isFalse();
        assertThat(sessions.exists(SessionRows.idOfCookie(newCookie.getValue()))).isTrue();
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("T023 FR-007 R4: 옛 세션 삭제를 실패시키면 롤백되어 새 세션 행과 세션 Set-Cookie가 없고 /login-failed로 간다")
    void failedOldSessionDeleteRollsBackNewSession() throws Exception {
        GoogleAccount account = newAccount();
        Cookie oldCookie = login.login(account).sessionCookie();
        assertThat(oldCookie).isNotNull();
        String oldId = SessionRows.idOfCookie(oldCookie.getValue());
        long userId = Long.parseLong(sessions.rowOf(oldId).principalName());
        assertThat(sessions.count()).isEqualTo(1);
        sessions.failDeleteOf(oldId);

        LoginClient.Result r = login.login(account, "/", null, oldCookie);

        assertThat(r.location()).isEqualTo("/login-failed");
        assertNoSessionCookieIssued(r.callback());
        // 남은 것은 옛 세션 행 하나뿐이다
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(sessions.exists(oldId)).isTrue();
        assertThat(sessions.rowsOfUser(userId)).extracting(SessionRows.Row::sessionId).containsExactly(oldId);
    }
}
