package estate.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MvcResult;

@LoginIntegrationTest
class TestSupportSelfTest {

    @Autowired
    ClientRegistrationRepository registrations;
    @Autowired
    Clock clock;
    @Autowired
    MutableClock mutableClock;
    @Autowired
    SessionRows sessions;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    javax.sql.DataSource dataSource;

    @Test
    void 가짜_서버가_discovery_문서를_돌려준다() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(FakeGoogleServer.issuer() + "/.well-known/openid-configuration")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().replaceAll("\\s", "")).contains("\"issuer\":\"" + FakeGoogleServer.issuer() + "\"");
    }

    @Test
    void google_등록이_가짜_서버를_가리킨다() {
        ClientRegistration google = registrations.findByRegistrationId("google");
        String issuer = FakeGoogleServer.issuer();

        assertThat(google.getProviderDetails().getIssuerUri()).isEqualTo(issuer);
        assertThat(google.getProviderDetails().getAuthorizationUri()).startsWith(issuer);
        assertThat(google.getProviderDetails().getTokenUri()).startsWith(issuer);
        assertThat(google.getProviderDetails().getJwkSetUri()).startsWith(issuer);
        assertThat(google.getProviderDetails().getUserInfoEndpoint().getUri()).startsWith(issuer);
    }

    @Test
    void Clock_빈이_옮길_수_있는_시계다() {
        assertThat(clock).isSameAs(mutableClock);
        Instant before = clock.instant();

        mutableClock.advance(Duration.ofDays(31));
        assertThat(Duration.between(before, clock.instant())).isBetween(Duration.ofDays(31), Duration.ofDays(31).plusSeconds(5));

        Instant fixed = Instant.parse("2030-01-01T00:00:00Z");
        mutableClock.freezeAt(fixed);
        assertThat(clock.instant()).isEqualTo(fixed);
    }

    @Test
    void 세션_도우미가_행의_시각을_과거로_옮긴다() {
        String id = SessionRows.idOfCookie("cookie-original-1");
        jdbc.sql("insert into spring_session values ('11111111-1111-1111-1111-111111111111', :id, 1000000000000, 1000000100000,"
                + " 604800, 1000604800000, '42')").param("id", id).update();
        jdbc.sql("insert into spring_session_attributes values ('11111111-1111-1111-1111-111111111111', 'a', :b)")
                .param("b", new byte[] {1, 2}).update();

        assertThat(sessions.count()).isEqualTo(1);
        assertThat(sessions.attributes(id)).containsOnlyKeys("a");

        sessions.shiftBackByUser(42, Duration.ofDays(31), Duration.ofDays(1), Duration.ofDays(1));
        SessionRows.Row byUser = sessions.rowsOfUser(42).getFirst();
        assertThat(byUser.creationTime()).isEqualTo(1000000000000L - Duration.ofDays(31).toMillis());
        assertThat(byUser.lastAccessTime()).isEqualTo(1000000100000L - Duration.ofDays(1).toMillis());
        assertThat(byUser.expiryTime()).isEqualTo(1000604800000L - Duration.ofDays(1).toMillis());

        sessions.shiftBackBySessionId(id, Duration.ofMillis(5), Duration.ZERO, Duration.ZERO);
        assertThat(sessions.rowOf(id).creationTime()).isEqualTo(byUser.creationTime() - 5);
    }

    @Test
    void 위조_토큰은_가짜_서버_키로_서명되고_aud가_등록_clientId와_같다() throws Exception {
        ClientRegistration google = registrations.findByRegistrationId("google");
        GoogleAccount account = GoogleAccount.of("self-sub-forged", "user1@example.com");

        String token = FakeGoogleServer.forgedIdToken(account, "n-1").build();

        var jwt = NimbusJwtDecoder.withJwkSetUri(google.getProviderDetails().getJwkSetUri()).build().decode(token);
        assertThat(jwt.getAudience()).containsExactly(google.getClientId());
        assertThat(jwt.getIssuer().toString()).isEqualTo(google.getProviderDetails().getIssuerUri());
        assertThat(SignedJWT.parse(token).getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(SignedJWT.parse(FakeGoogleServer.forgedIdToken(account, "n-1").alg(JWSAlgorithm.RS384).build()).getHeader().getAlgorithm())
                .isEqualTo(JWSAlgorithm.RS384);
    }

    @Test
    void 가짜_서버_CLIENT_ID가_등록_clientId와_같다() {
        assertThat(FakeGoogleServer.CLIENT_ID).isEqualTo(registrations.findByRegistrationId("google").getClientId());
    }

    @Test
    void 예약은_토큰_요청_하나에_계정_하나씩_쓰이고_reset이_비운다() throws Exception {
        FakeGoogleServer.willReturn(GoogleAccount.of("self-sub-1", "user1@example.com"));
        FakeGoogleServer.willReturn(GoogleAccount.of("self-sub-2", "user2@example.com").withoutName());

        var first = SignedJWT.parse(exchangeForIdToken()).getJWTClaimsSet();
        assertThat(first.getSubject()).isEqualTo("self-sub-1");
        assertThat(first.getStringClaim("email")).isEqualTo("user1@example.com");
        assertThat(first.getClaim("email_verified")).isEqualTo(true);
        assertThat(first.getStringClaim("nonce")).isEqualTo("self-nonce");
        assertThat(FakeGoogleServer.pendingCount()).isEqualTo(1);

        var second = SignedJWT.parse(exchangeForIdToken()).getJWTClaimsSet();
        assertThat(second.getSubject()).isEqualTo("self-sub-2");
        assertThat(second.getClaim("name")).isNull();

        FakeGoogleServer.willReturn(GoogleAccount.of("self-sub-3", "user3@example.com"));
        FakeGoogleServer.enqueueTokenResponse(GoogleAccount.of("self-sub-3", "user3@example.com"), "x");
        FakeGoogleServer.reset();
        assertThat(FakeGoogleServer.pendingCount()).isZero();
    }

    /** 앱 없이 가짜 서버에만 인가와 토큰 교환을 해서 ID 토큰을 받는다. */
    private String exchangeForIdToken() throws Exception {
        String authorizeUrl = FakeGoogleServer.server().authorizationEndpointUrl(FakeGoogleServer.ISSUER_ID) + "?response_type=code&client_id="
                + FakeGoogleServer.CLIENT_ID + "&redirect_uri=" + URLEncoder.encode("https://localhost/cb", StandardCharsets.UTF_8)
                + "&state=s&nonce=self-nonce&scope=openid";
        String location = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(HttpRequest.newBuilder(URI.create(authorizeUrl)).build(), HttpResponse.BodyHandlers.discarding())
                .headers().firstValue("Location").orElseThrow();
        return exchangeCodeForIdToken(location.replaceAll(".*[?&]code=([^&]+).*", "$1"));
    }

    private String exchangeCodeForIdToken(String code) throws Exception {
        String form = "grant_type=authorization_code&code=" + code + "&client_id=" + FakeGoogleServer.CLIENT_ID
                + "&redirect_uri=" + URLEncoder.encode("https://localhost/cb", StandardCharsets.UTF_8);
        String body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(FakeGoogleServer.server().tokenEndpointUrl(FakeGoogleServer.ISSUER_ID).uri())
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return body.replaceAll("(?s).*\"id_token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    void prompt가_붙은_인가_요청도_authorize가_code와_state를_받고_토큰의_sub는_예약_계정이다() throws Exception {
        String authorizeUrl = FakeGoogleServer.server().authorizationEndpointUrl(FakeGoogleServer.ISSUER_ID) + "?response_type=code&client_id="
                + FakeGoogleServer.CLIENT_ID + "&redirect_uri=" + URLEncoder.encode("https://localhost/cb", StandardCharsets.UTF_8)
                + "&state=s-prompt&nonce=self-nonce&scope=openid&prompt=select_account";
        MvcResult start = mock(MvcResult.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("Location", authorizeUrl);
        when(start.getResponse()).thenReturn(response);
        FakeGoogleServer.willReturn(GoogleAccount.of("self-sub-prompt", "user1@example.com"));

        var authorized = new LoginClient(null).authorize(start);

        assertThat(authorized.get("state")).isEqualTo("s-prompt");
        assertThat(FakeGoogleServer.pendingCount()).isEqualTo(1);
        var claims = SignedJWT.parse(exchangeCodeForIdToken(authorized.get("code"))).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("self-sub-prompt");
        assertThat(claims.getStringClaim("email")).isEqualTo("user1@example.com");
        assertThat(claims.getStringClaim("name")).isEqualTo("테스트 사용자");
        assertThat(claims.getClaim("email_verified")).isEqualTo(true);
        assertThat(claims.getStringClaim("nonce")).isEqualTo("self-nonce");
    }

    @Test
    void 쿠키_도우미는_삭제_쿠키를_없는_것으로_읽고_속성_원문을_준다() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.addHeader("Set-Cookie", "__Host-Http-session=abc; Path=/; Max-Age=0; Secure");
        response.addHeader("Set-Cookie", "__Host-Http-login=def; Path=/; Max-Age=600; HttpOnly; Secure; SameSite=Lax");
        response.addHeader("Set-Cookie", "empty=; Path=/");
        MvcResult result = mock(MvcResult.class);
        when(result.getResponse()).thenReturn(response);

        assertThat(LoginClient.cookie(result, "__Host-Http-session")).isNull();
        assertThat(LoginClient.cookie(result, "empty")).isNull();
        assertThat(LoginClient.cookie(result, "__Host-Http-login").getValue()).isEqualTo("def");
        assertThat(LoginClient.setCookieHeaders(result, "__Host-Http-login")).singleElement().asString().contains("SameSite=Lax");
        assertThat(LoginClient.startCookies(result)).extracting(c -> c.getName()).containsExactly("__Host-Http-login");
    }

    @Test
    void 세션이_없으면_조회_도우미가_예외를_던진다() {
        String missing = SessionRows.idOfCookie("never-created");

        assertThat(sessions.exists(missing)).isFalse();
        assertThatThrownBy(() -> sessions.rowOf(missing)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sessions.attributes(missing)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 삭제_실패_트리거가_그_세션만_막고_지우면_풀린다() {
        String blocked = SessionRows.idOfCookie("cookie-blocked");
        String free = SessionRows.idOfCookie("cookie-free");
        jdbc.sql("insert into spring_session values ('22222222-2222-2222-2222-222222222222', :id, 1, 1, 604800, 2, '7')").param("id", blocked).update();
        jdbc.sql("insert into spring_session values ('33333333-3333-3333-3333-333333333333', :id, 1, 1, 604800, 2, '7')").param("id", free).update();

        sessions.failDeleteOf(blocked);
        assertThatThrownBy(() -> jdbc.sql("delete from spring_session where principal_name = '7'").update()).hasMessageContaining("delete blocked");
        assertThat(sessions.count()).isEqualTo(2);
        assertThat(jdbc.sql("delete from spring_session where session_id = :id").param("id", free).update()).isEqualTo(1);

        sessions.clearDeleteFailures();
        assertThat(jdbc.sql("delete from spring_session where session_id = :id").param("id", blocked).update()).isEqualTo(1);
    }

    @Test
    void 열_검색_도우미가_text와_bytea_열에서_문자열을_찾는다() {
        jdbc.sql("insert into spring_session values ('44444444-4444-4444-4444-444444444444', :id, 1, 1, 604800, 2, 'leak-principal-xyz')")
                .param("id", SessionRows.idOfCookie("cookie-leak")).update();
        jdbc.sql("insert into spring_session_attributes values ('44444444-4444-4444-4444-444444444444', 'a', :b)")
                .param("b", "prefix leak-bytes-xyz suffix".getBytes(StandardCharsets.UTF_8)).update();

        assertThat(sessions.columnsContaining("leak-principal-xyz")).containsExactly("spring_session.principal_name");
        assertThat(sessions.columnsContaining("leak-bytes-xyz")).containsExactly("spring_session_attributes.attribute_bytes");
        assertThat(sessions.columnsContaining("absent-string-xyz")).isEmpty();
    }

    @Test
    void 인가가_error로_돌아오면_의미_있는_예외를_내고_없는_값은_null이다() throws Exception {
        var stub = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/authorize", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://localhost/cb?error=access_denied&state=s");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        stub.start();
        try {
            MvcResult start = mock(MvcResult.class);
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setHeader("Location", "http://localhost:" + stub.getAddress().getPort() + "/authorize?state=s");
            when(start.getResponse()).thenReturn(response);

            assertThat(LoginClient.locationParam(start, "absent")).isNull();
            assertThat(LoginClient.locationParam(start, "state")).isEqualTo("s");
            assertThatThrownBy(() -> new LoginClient(null).authorize(start)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("error=access_denied");
        } finally {
            stub.stop(0);
        }
    }

    @Test
    void 세션_쿠키가_없으면_requireSessionCookie가_예외를_던지고_옮길_행이_없으면_shiftBack이_예외를_던진다() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.addHeader("Set-Cookie", "__Host-Http-session=; Path=/; Max-Age=0");
        MvcResult callback = mock(MvcResult.class);
        when(callback.getResponse()).thenReturn(response);

        assertThatThrownBy(() -> new LoginClient.Result(null, callback).requireSessionCookie()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sessions.shiftBackByUser(999999, Duration.ofDays(1), Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sessions.shiftBackBySessionId(SessionRows.idOfCookie("none"), Duration.ZERO, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void awaitWaiting은_기다리는_쪽이_있으면_돌아오고_없으면_상한_뒤_예외를_던진다() throws Exception {
        long id = jdbc.sql("select nextval('household_id_seq')").query(Long.class).single();
        jdbc.sql("insert into household (id) values (:id)").param("id", id).update();
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, id)) {
            assertThatThrownBy(() -> lock.awaitWaiting(1, Duration.ofMillis(300))).isInstanceOf(IllegalStateException.class);

            // 둘째 대기자는 첫 대기자에게 막히므로 둘 이상이어야 대기 수 조회의 조건을 검증한다
            Runnable wait = () -> jdbc.sql("select id from household where id = :id for update").param("id", id).query(Long.class).single();
            var first = java.util.concurrent.CompletableFuture.runAsync(wait);
            var second = java.util.concurrent.CompletableFuture.runAsync(wait);
            lock.awaitWaiting(2);
            assertThat(first).isNotDone();
            assertThat(second).isNotDone();

            lock.rollback();
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            second.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
