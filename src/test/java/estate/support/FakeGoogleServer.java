package estate.support;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.TokenRequest;
import no.nav.security.mock.oauth2.MockOAuth2Server;
import no.nav.security.mock.oauth2.OAuth2Config;
import no.nav.security.mock.oauth2.http.OAuth2HttpRequest;
import no.nav.security.mock.oauth2.http.OAuth2HttpResponse;
import no.nav.security.mock.oauth2.http.Route;
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback;
import no.nav.security.mock.oauth2.token.KeyProvider;
import no.nav.security.mock.oauth2.token.OAuth2TokenCallback;
import no.nav.security.mock.oauth2.token.OAuth2TokenProvider;
import okhttp3.Headers;

/**
 * 가짜 구글 서버(JVM당 하나). 정상 로그인: {@code FakeGoogleServer.willReturn(GoogleAccount.of("sub-1", "user1@example.com"))} 뒤 로그인 도우미 실행.
 * 잘못된 ID 토큰: {@code forgedIdToken(account, nonce).alg(RS384).audience("x").build()}를 {@code enqueueTokenResponse}에 준다(LoginClient가 이어 준다).
 * 테스트마다 고유 sub를 쓴다(사용자 행은 Reset이 지우지 않음). HS256 위조는 무작위 키라 알고리즘이 아니라 서명 검증을 시험한다(알고리즘 거절 변별은 RS384).
 */
public final class FakeGoogleServer {

    public static final String ISSUER_ID = "google";
    /** IntegrationTest가 GOOGLE_CLIENT_ID로 넣는 값과 같아야 한다. */
    public static final String CLIENT_ID = "test-client-id";

    private static final KeyProvider KEYS = new KeyProvider();
    private static final Queue<GoogleAccount> ACCOUNTS = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<String> PENDING_TOKEN_RESPONSE = new AtomicReference<>();
    private static final MockOAuth2Server SERVER = start();

    private FakeGoogleServer() {
    }

    private static MockOAuth2Server start() {
        MockOAuth2Server server = new MockOAuth2Server(new OAuth2Config(false, null, null, false, new OAuth2TokenProvider(KEYS),
                Set.of(new QueuedCallback())), new ForgedTokenRoute());
        server.start(InetAddress.getLoopbackAddress(), 0);
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        return server;
    }

    public static MockOAuth2Server server() {
        return SERVER;
    }

    public static String issuer() {
        return SERVER.issuerUrl(ISSUER_ID).toString();
    }

    /** 다음 토큰 요청 하나에 이 계정의 정상 ID 토큰을 준다(호출한 순서대로 한 요청에 하나씩 쓰인다). */
    public static void willReturn(GoogleAccount a) {
        ACCOUNTS.add(a);
    }

    /** 예약된 계정과 위조 토큰 응답을 비운다(서버는 JVM 정적이라 콜백이 토큰 교환 전에 실패하면 남는다. Reset이 테스트마다 부른다). */
    public static void reset() {
        ACCOUNTS.clear();
        PENDING_TOKEN_RESPONSE.set(null);
    }

    /** 예약이 남았는지(테스트가 소비되지 않은 예약을 확인할 때). */
    public static int pendingCount() {
        return ACCOUNTS.size() + (PENDING_TOKEN_RESPONSE.get() != null ? 1 : 0);
    }

    /**
     * mock-oauth2-server의 enqueueCallback은 대기열을 비울 방법이 없어, 상시 콜백 하나가 요청마다 우리 대기열에서 계정을 꺼낸다.
     * 한 토큰 요청의 ID 토큰과 access token 만들기가 같은 계정을 쓰도록 요청 객체별로 기억한다.
     */
    private static final class QueuedCallback implements OAuth2TokenCallback {
        private final Map<TokenRequest, DefaultOAuth2TokenCallback> perRequest = Collections.synchronizedMap(new WeakHashMap<>());

        private DefaultOAuth2TokenCallback current(TokenRequest request) {
            return perRequest.computeIfAbsent(request, r -> {
                GoogleAccount a = ACCOUNTS.poll();
                if (a == null) {
                    throw new IllegalStateException("FakeGoogleServer.willReturn 없이 토큰 요청이 왔다");
                }
                Map<String, Object> claims = new HashMap<>();
                claims.put("email", a.email());
                claims.put("email_verified", a.emailVerified());
                if (a.name() != null) {
                    claims.put("name", a.name());
                }
                return new DefaultOAuth2TokenCallback(ISSUER_ID, a.sub(), "JWT", null, claims, 3600L);
            });
        }

        @Override
        public String issuerId() {
            return ISSUER_ID;
        }

        @Override
        public String subject(TokenRequest request) {
            return current(request).subject(request);
        }

        @Override
        public String typeHeader(TokenRequest request) {
            return current(request).typeHeader(request);
        }

        @Override
        public java.util.List<String> audience(TokenRequest request) {
            return current(request).audience(request);
        }

        @Override
        public Map<String, Object> addClaims(TokenRequest request) {
            return current(request).addClaims(request);
        }

        @Override
        public long tokenExpiry() {
            return 3600L;
        }
    }

    /**
     * 다음 토큰 요청 하나에 callback 대신 이 ID 토큰을 그대로 준다(가짜 서버의 기본 처리보다 먼저 가로챈다).
     */
    public static void enqueueTokenResponse(GoogleAccount a, String idToken) {
        String accessToken = SERVER.anyToken(SERVER.issuerUrl(ISSUER_ID),
                Map.of("sub", a.sub(), "email", a.email(), "aud", CLIENT_ID)).serialize();
        PENDING_TOKEN_RESPONSE.set("{\"access_token\":\"" + accessToken + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"id_token\":\"" + idToken + "\"}");
    }

    /** mock-oauth2-server 7.0.0은 응답 대기열(enqueueResponse)을 지원하지 않아 사용자 지정 경로로 가로챈다. */
    private static final class ForgedTokenRoute implements Route {
        @Override
        public boolean match(OAuth2HttpRequest request) {
            return PENDING_TOKEN_RESPONSE.get() != null && "POST".equals(request.getMethod())
                    && request.getUrl().encodedPath().equals("/" + ISSUER_ID + "/token");
        }

        @Override
        public OAuth2HttpResponse invoke(OAuth2HttpRequest request) {
            return new OAuth2HttpResponse(Headers.of("Content-Type", "application/json"), 200, PENDING_TOKEN_RESPONSE.getAndSet(null), null);
        }
    }

    public static ForgedIdToken forgedIdToken(GoogleAccount a, String nonce) {
        return new ForgedIdToken(a, nonce);
    }

    /** 기본값은 정상 토큰과 같고, 바꾼 항목만 잘못된다. 가짜 서버 키로 서명한다(HS256은 무작위 비밀키). */
    public static final class ForgedIdToken {
        private final GoogleAccount account;
        private String nonce;
        private JWSAlgorithm alg = JWSAlgorithm.RS256;
        private String issuer = FakeGoogleServer.issuer();
        private String audience = CLIENT_ID;
        private Duration expiresIn = Duration.ofMinutes(5);

        private ForgedIdToken(GoogleAccount account, String nonce) {
            this.account = account;
            this.nonce = nonce;
        }

        public ForgedIdToken alg(JWSAlgorithm alg) {
            this.alg = alg;
            return this;
        }

        public ForgedIdToken issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public ForgedIdToken audience(String audience) {
            this.audience = audience;
            return this;
        }

        public ForgedIdToken nonce(String nonce) {
            this.nonce = nonce;
            return this;
        }

        public ForgedIdToken expired() {
            this.expiresIn = Duration.ofHours(-1);
            return this;
        }

        public String build() {
            Instant now = Instant.now();
            JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().issuer(issuer).subject(account.sub()).audience(audience)
                    .issueTime(Date.from(now.minus(Duration.ofHours(2))))
                    .expirationTime(Date.from(now.plus(expiresIn)))
                    .claim("email", account.email()).claim("email_verified", account.emailVerified());
            if (nonce != null) {
                c.claim("nonce", nonce);
            }
            if (account.name() != null) {
                c.claim("name", account.name());
            }
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(ISSUER_ID).build(), c.build());
                if (alg.getName().startsWith("HS")) {
                    byte[] secret = new byte[64];
                    new SecureRandom().nextBytes(secret);
                    jwt.sign(new MACSigner(secret));
                } else {
                    jwt.sign(new RSASSASigner((RSAKey) KEYS.signingKey(ISSUER_ID)));
                }
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
