package estate.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import estate.support.Households;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.RealServerClient;
import estate.support.SessionRows;

/**
 * T022 실제 서버 확인(US1-3, US1-6, research R5, R16): 403 NO_HOUSEHOLD와 409 HAS_HOUSEHOLD의 본문을 실제 서버(RANDOM_PORT)에서 받아
 * 계약 예시와 키까지 같은지, 같은 세션 쿠키로 MockMvc가 받은 본문(T017)과 같은지 본다. 설정 방식은 ErrorResponseLeakTest와 같다.
 */
@LoginIntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class HouseholdErrorBodyServerTest {

    static final String ORIGIN = RealServerClient.ORIGIN;
    private static final ObjectMapper JSON = new ObjectMapper();

    // contracts/openapi.yaml components.responses의 NoHousehold, HasHousehold 예시
    static final String NO_HOUSEHOLD = """
            {"type": "https://example.com/problems/no-household", "title": "가구가 없습니다", "status": 403,
             "detail": "가구를 만들거나 초대 링크로 들어온 뒤 다시 시도해 주세요.", "code": "NO_HOUSEHOLD"}""";
    static final String HAS_HOUSEHOLD = """
            {"type": "https://example.com/problems/has-household", "title": "이미 가구가 있습니다", "status": 409,
             "detail": "가구를 나가거나 삭제한 뒤 다시 시도해 주세요.", "code": "HAS_HOUSEHOLD"}""";

    // T038(US2 몫): contracts/openapi.yaml의 예시
    static final String PERMISSION_DENIED = """
            {"type": "https://example.com/problems/permission-denied", "title": "할 수 없는 동작입니다", "status": 403,
             "detail": "이 가구에서 할 수 없는 동작입니다.", "code": "PERMISSION_DENIED"}""";
    static final String REAUTH_REQUIRED = """
            {"type": "https://example.com/problems/reauth-required", "title": "구글 계정 확인이 필요합니다", "status": 401,
             "detail": "구글 계정을 한 번 더 확인한 뒤 다시 시도해 주세요.", "code": "REAUTH_REQUIRED"}""";

    static final String INVITATION_REVOKED = """
            {"type": "https://example.com/problems/invitation-revoked", "title": "취소된 초대 링크입니다", "status": 409,
             "detail": "가족에게 새 초대 링크를 받아 주세요.", "code": "INVITATION_REVOKED"}""";
    static final String INVITATION_USED = """
            {"type": "https://example.com/problems/invitation-used", "title": "이미 사용된 초대 링크입니다", "status": 409,
             "detail": "가족에게 새 초대 링크를 받아 주세요.", "code": "INVITATION_USED"}""";
    static final String INVITATION_EXPIRED = """
            {"type": "https://example.com/problems/invitation-expired", "title": "초대 링크가 만료되었습니다", "status": 409,
             "detail": "가족에게 새 초대 링크를 받아 주세요.", "code": "INVITATION_EXPIRED"}""";
    static final String HOUSEHOLD_FULL = """
            {"type": "https://example.com/problems/household-full", "title": "구성원이 이미 둘입니다", "status": 409,
             "detail": "가구 구성원은 둘까지입니다.", "code": "HOUSEHOLD_FULL"}""";
    static final String ALREADY_IN_THIS_HOUSEHOLD = """
            {"type": "https://example.com/problems/already-in-this-household", "title": "이미 이 가구의 구성원입니다", "status": 409,
             "detail": "링크는 가족에게 보내세요.", "code": "ALREADY_IN_THIS_HOUSEHOLD"}""";
    static final String NOT_FOUND = """
            {"type": "https://example.com/problems/not-found", "title": "찾을 수 없습니다", "status": 404,
             "detail": "주소를 확인해 주세요.", "code": "NOT_FOUND"}""";
    static final String EXPIRES_IN_DAYS_INVALID = """
            {"type": "https://example.com/problems/validation-failed", "title": "입력값이 올바르지 않습니다", "status": 400,
             "detail": "입력값 하나를 확인해 주세요.", "code": "VALIDATION_FAILED",
             "errors": [{"pointer": "#/expiresInDays", "detail": "만료 기간은 1일부터 7일까지 고를 수 있습니다."}]}""";
    static final String BODY_INVALID = """
            {"type": "https://example.com/problems/validation-failed", "title": "입력값이 올바르지 않습니다", "status": 400,
             "detail": "입력값 하나를 확인해 주세요.", "code": "VALIDATION_FAILED",
             "errors": [{"pointer": "#", "detail": "알 수 없는 필드가 있습니다."}]}""";
    // 계약 예시(body)는 다른 키 경우다. 깨진 JSON, 객체가 아닌 본문, 빈 본문은 ProblemAdvice와 같은 문구다
    static final String BODY_UNREADABLE = """
            {"type": "https://example.com/problems/validation-failed", "title": "입력값이 올바르지 않습니다", "status": 400,
             "detail": "입력값 하나를 확인해 주세요.", "code": "VALIDATION_FAILED",
             "errors": [{"pointer": "#", "detail": "요청 본문을 읽을 수 없습니다."}]}""";

    @LocalServerPort
    int port;
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;
    @Autowired
    MutableClock clock;

    private RealServerClient server;
    private Households households;

    @BeforeEach
    void setUp() {
        server = new RealServerClient(port);
        households = new Households(mvc, jdbc, sessions);
    }

    @Test
    @DisplayName("T022 US1-6 FR-026 실서버: 멤버십 없는 사람의 GET /api/v1/household는 403 NO_HOUSEHOLD problem+json이고 계약 예시, MockMvc 본문과 같으며, 가구를 만든 뒤에는 200")
    void noHouseholdBodyOnRealServer() throws Exception {
        String session = server.login();

        HttpResponse<String> response = server.send(HttpRequest.newBuilder(server.url("/api/v1/household")).GET()
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));

        JsonNode real = expectExactProblem(response, 403, NO_HOUSEHOLD);
        MvcResult mock = mvc.perform(https(get("/api/v1/household")).cookie(cookie(session))).andReturn();
        assertThat(mock.getResponse().getStatus()).isEqualTo(403);
        assertThat(JSON.readTree(mock.getResponse().getContentAsString(StandardCharsets.UTF_8))).as("MockMvc 본문과 같다").isEqualTo(real);

        // 짝: 가구를 만들면 같은 요청이 200
        assertThat(create(session).statusCode()).isEqualTo(201);
        assertThat(server.send(HttpRequest.newBuilder(server.url("/api/v1/household")).GET()
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("T022 US1-3 FR-005 실서버: 가구가 있는 사람의 POST /api/v1/household는 409 HAS_HOUSEHOLD problem+json이고 계약 예시, MockMvc 본문과 같다(첫 요청은 201)")
    void hasHouseholdBodyOnRealServer() throws Exception {
        String session = server.login();
        HttpResponse<String> first = create(session);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.body()).as("201 본문이 없다").isEmpty();

        JsonNode real = expectExactProblem(create(session), 409, HAS_HOUSEHOLD);

        MvcResult mock = mvc.perform(https(post("/api/v1/household")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie(session)))
                .andReturn();
        assertThat(mock.getResponse().getStatus()).isEqualTo(409);
        assertThat(JSON.readTree(mock.getResponse().getContentAsString(StandardCharsets.UTF_8))).as("MockMvc 본문과 같다").isEqualTo(real);
    }

    @Test
    @DisplayName("T038 US2-12 US2-13 US2-14 US2-15 실서버: 초대 확인과 수락의 409 여섯 코드가 계약 예시와 같고 MockMvc 본문과 같다")
    void invitationRejectionsOnRealServer() throws Exception {
        String ownerA = server.login();
        String ownerB = server.login();
        String joiner = server.login();
        String opener = server.login();
        assertThat(create(ownerA).statusCode()).isEqualTo(201);
        assertThat(create(ownerB).statusCode()).isEqualTo(201);
        long creatorA = userIdOf(ownerA);
        long householdA = households.householdIdOf(new Households.Person(cookie(ownerA), creatorA));

        JsonNode revokedInvitation = issue(ownerA);
        assertThat(sendJson("DELETE", ownerA, "/api/v1/household/invitations/" + revokedInvitation.path("id").longValue(), null).statusCode())
                .isEqualTo(204);
        assertSameRejection(opener, "/api/v1/invitations/preview", revokedInvitation.path("token").stringValue(), INVITATION_REVOKED);
        assertSameRejection(opener, "/api/v1/invitations/accept", revokedInvitation.path("token").stringValue(), INVITATION_REVOKED);

        String usedToken = issue(ownerA).path("token").stringValue();
        assertThat(postToken(joiner, "/api/v1/invitations/accept", usedToken).statusCode()).isEqualTo(204);
        assertSameRejection(opener, "/api/v1/invitations/preview", usedToken, INVITATION_USED);
        assertSameRejection(opener, "/api/v1/invitations/accept", usedToken, INVITATION_USED);

        Instant now = Instant.now();
        String expired = households.insertInvitation(householdA, creatorA, "대기", now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1))).token();
        assertSameRejection(opener, "/api/v1/invitations/preview", expired, INVITATION_EXPIRED);
        assertSameRejection(opener, "/api/v1/invitations/accept", expired, INVITATION_EXPIRED);

        String full = households.insertInvitation(householdA, creatorA, "대기", now, now.plus(Duration.ofDays(3))).token();
        assertSameRejection(opener, "/api/v1/invitations/preview", full, HOUSEHOLD_FULL);
        assertSameRejection(opener, "/api/v1/invitations/accept", full, HOUSEHOLD_FULL);

        String tokenB = issue(ownerB).path("token").stringValue();
        assertSameRejection(ownerB, "/api/v1/invitations/preview", tokenB, ALREADY_IN_THIS_HOUSEHOLD);
        assertSameRejection(ownerB, "/api/v1/invitations/accept", tokenB, ALREADY_IN_THIS_HOUSEHOLD);
        assertSameRejection(ownerA, "/api/v1/invitations/preview", tokenB, HAS_HOUSEHOLD);
        assertSameRejection(ownerA, "/api/v1/invitations/accept", tokenB, HAS_HOUSEHOLD);
    }

    @Test
    @DisplayName("T038 US2-12 실서버: 형식이 틀린 토큰은 확인과 수락 모두 404 NOT_FOUND이고 계약 예시, MockMvc 본문과 같다")
    void malformedTokenOnRealServer() throws Exception {
        String session = server.login();
        for (String token : new String[] {"abc", "", "A".repeat(42), "A".repeat(44), "!".repeat(43), "A".repeat(42) + "+"}) {
            assertSameRejection(session, "/api/v1/invitations/preview", token, 404, NOT_FOUND);
            assertSameRejection(session, "/api/v1/invitations/accept", token, 404, NOT_FOUND);
        }
    }

    @Test
    @DisplayName("T038 US2-3 실서버: 초대 발급의 만료 기간 오류는 400 #/expiresInDays, 알 수 없는 키와 깨진 JSON은 400 #이고 계약 예시, MockMvc 본문과 같다")
    void issueValidationOnRealServer() throws Exception {
        String session = server.login();
        assertThat(create(session).statusCode()).isEqualTo(201);
        String path = "/api/v1/household/invitations";

        for (String body : new String[] {"{\"expiresInDays\": 0}", "{\"expiresInDays\": 8}", "{\"expiresInDays\": \"3\"}", "{\"expiresInDays\": null}", "{}"}) {
            assertSameBody(session, path, body, 400, EXPIRES_IN_DAYS_INVALID);
        }
        assertSameBody(session, path, "{\"expiresInDays\": 3, \"extra\": 1}", 400, BODY_INVALID);
        for (String body : new String[] {"{\"expiresInDays\": ", "[3]", ""}) {
            assertSameBody(session, path, body, 400, BODY_UNREADABLE);
        }
        assertThat(jdbcCount("invitation")).as("거절된 요청은 초대를 만들지 않는다").isZero();
    }

    @Test
    @DisplayName("T056 US3-12 FR-024 실서버: 다른 가구 구성원, 없는 번호, 숫자가 아닌 값의 구성원 제거 404 본문 셋이 글자까지 같고 계약 예시, MockMvc 본문과 같다")
    void removeNotFoundOnRealServer() throws Exception {
        String ownerA = server.login();
        String ownerB = server.login();
        assertThat(create(ownerA).statusCode()).isEqualTo(201);
        assertThat(create(ownerB).statusCode()).isEqualTo(201);
        // 어느 사용자에도 없는 번호: 시퀀스가 지금까지 준 값보다 크다
        long unused = jdbc.sql("select last_value from user_id_seq").query(Long.class).single() + 1000;

        List<String> bodies = new ArrayList<>();
        for (String id : new String[] {String.valueOf(userIdOf(ownerB)), String.valueOf(unused), "abc"}) {
            String path = "/api/v1/household/members/" + id;
            HttpResponse<String> response = sendJson("DELETE", ownerA, path, null);
            JsonNode real = expectExactProblem(response, 404, NOT_FOUND);
            bodies.add(response.body());
            MvcResult mock = mvc.perform(https(delete(path)).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie(ownerA))).andReturn();
            assertThat(mock.getResponse().getStatus()).as(path).isEqualTo(404);
            assertThat(JSON.readTree(mock.getResponse().getContentAsString(StandardCharsets.UTF_8))).as("MockMvc 본문과 같다").isEqualTo(real);
        }
        assertThat(bodies).as("다른 가구 구성원과 없는 구성원의 본문이 글자까지 같다").containsOnly(bodies.getFirst());
        assertThat(jdbcCount("household_member")).as("가구 B의 멤버십이 그대로").isEqualTo(2);
    }

    @Test
    @DisplayName("T033 US3-12 FR-024 R16 실서버: 다른 가구 초대, 없는 번호, abc, long 범위를 넘는 번호의 초대 취소 404가 상태, 미디어 타입, 본문까지 같고 계약 예시, MockMvc 본문과 같다")
    void revokeNotFoundOnRealServer() throws Exception {
        String ownerA = server.login();
        String ownerB = server.login();
        assertThat(create(ownerA).statusCode()).isEqualTo(201);
        assertThat(create(ownerB).statusCode()).isEqualTo(201);
        long invitationB = issue(ownerB).path("id").longValue();
        // 어느 초대에도 없는 번호: 시퀀스가 지금까지 준 값보다 크다
        long unused = jdbc.sql("select last_value from invitation_id_seq").query(Long.class).single() + 1000;

        List<String> bodies = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (String id : new String[] {String.valueOf(invitationB), String.valueOf(unused), "abc", "9223372036854775808"}) {
            String path = "/api/v1/household/invitations/" + id;
            HttpResponse<String> response = sendJson("DELETE", ownerA, path, null);
            JsonNode real = expectExactProblem(response, 404, NOT_FOUND);
            bodies.add(response.body());
            types.add(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow());
            MvcResult mock = mvc.perform(https(delete(path)).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie(ownerA))).andReturn();
            assertThat(mock.getResponse().getStatus()).as(path).isEqualTo(404);
            assertThat(JSON.readTree(mock.getResponse().getContentAsString(StandardCharsets.UTF_8))).as("MockMvc 본문과 같다").isEqualTo(real);
        }
        assertThat(bodies).as("다른 가구 초대와 없는 초대의 본문이 글자까지 같다").containsOnly(bodies.getFirst());
        assertThat(types).as("미디어 타입이 같다").containsOnly(types.getFirst());
        assertThat(jdbc.sql("select status from invitation where id = :id").param("id", invitationB).query(String.class).single())
                .as("가구 B의 초대가 대기로 남는다").isEqualTo("대기");

        // 짝: 가구 B의 owner에게는 같은 번호가 취소할 수 있는 초대다
        assertThat(sendJson("DELETE", ownerB, "/api/v1/household/invitations/" + invitationB, null).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("T056 US3-3 FR-019 실서버: 제거된 사람이 같은 세션 쿠키로 보낸 다음 GET /api/v1/household는 403 NO_HOUSEHOLD이고 계약 예시, MockMvc 본문과 같다")
    void removedMemberBodyOnRealServer() throws Exception {
        String owner = server.login();
        assertThat(create(owner).statusCode()).isEqualTo(201);
        String joiner = server.login();
        assertThat(postToken(joiner, "/api/v1/invitations/accept", issue(owner).path("token").stringValue()).statusCode()).isEqualTo(204);
        long joinerId = userIdOf(joiner);

        assertThat(sendJson("DELETE", owner, "/api/v1/household/members/" + joinerId, null).statusCode()).isEqualTo(204);

        HttpResponse<String> response = server.send(HttpRequest.newBuilder(server.url("/api/v1/household")).GET()
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + joiner));
        JsonNode real = expectExactProblem(response, 403, NO_HOUSEHOLD);
        MvcResult mock = mvc.perform(https(get("/api/v1/household")).cookie(cookie(joiner))).andReturn();
        assertThat(mock.getResponse().getStatus()).isEqualTo(403);
        assertThat(JSON.readTree(mock.getResponse().getContentAsString(StandardCharsets.UTF_8))).as("MockMvc 본문과 같다").isEqualTo(real);
    }

    @Test
    @DisplayName("T070 US4-7 SC-008 실서버: 가구가 있는 owner와 member의 DELETE /api/v1/me는 409 HAS_HOUSEHOLD problem+json이고 계약 예시, MockMvc 본문과 같으며 멤버십은 그대로다")
    void withdrawHasHouseholdOnRealServer() throws Exception {
        String owner = server.login();
        assertThat(create(owner).statusCode()).isEqualTo(201);
        String member = server.login();
        assertThat(postToken(member, "/api/v1/invitations/accept", issue(owner).path("token").stringValue()).statusCode()).isEqualTo(204);

        for (String session : new String[] {owner, member}) {
            assertSameDelete(session, "/api/v1/me", 409, HAS_HOUSEHOLD);
        }
        assertThat(jdbcCount("household_member")).as("멤버십이 그대로").isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from \"user\" where id in (:ids)").param("ids", List.of(userIdOf(owner), userIdOf(member)))
                .query(Long.class).single()).as("사용자 행이 그대로").isEqualTo(2);
    }

    @Test
    @DisplayName("T070 US4-6 FR-020 실서버: member의 DELETE /api/v1/household는 403 PERMISSION_DENIED problem+json이고 계약 예시, MockMvc 본문과 같으며 가구는 그대로다")
    void memberDeleteDeniedOnRealServer() throws Exception {
        String owner = server.login();
        assertThat(create(owner).statusCode()).isEqualTo(201);
        String member = server.login();
        assertThat(postToken(member, "/api/v1/invitations/accept", issue(owner).path("token").stringValue()).statusCode()).isEqualTo(204);

        assertSameDelete(member, "/api/v1/household", 403, PERMISSION_DENIED);

        assertThat(jdbcCount("household")).as("가구가 그대로").isEqualTo(1);
        assertThat(jdbcCount("household_member")).as("멤버십이 그대로").isEqualTo(2);
    }

    @Test
    @DisplayName("T070 US4-3 FR-022 실서버: 마지막 구글 인증이 5분 1초 전인 owner의 DELETE /api/v1/household는 401 REAUTH_REQUIRED problem+json이고 계약 예시, MockMvc 본문과 같으며 가구는 그대로다")
    void staleOwnerDeleteReauthOnRealServer() throws Exception {
        String owner = server.login();
        assertThat(create(owner).statusCode()).isEqualTo(201);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertSameDelete(owner, "/api/v1/household", 401, REAUTH_REQUIRED);

        assertThat(jdbcCount("household")).as("가구가 그대로").isEqualTo(1);
        assertThat(jdbcCount("household_member")).as("멤버십이 그대로").isEqualTo(1);
    }

    /** 실제 서버의 DELETE 응답이 계약 예시와 같고, 같은 세션 쿠키의 MockMvc 응답 본문과 같다. */
    private void assertSameDelete(String session, String path, int status, String expectedJson) throws Exception {
        JsonNode real = expectExactProblem(sendJson("DELETE", session, path, null), status, expectedJson);
        MvcResult mock = mvc.perform(https(delete(path)).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie(session))).andReturn();
        assertThat(mock.getResponse().getStatus()).as(path).isEqualTo(status);
        assertThat(JSON.readTree(mock.getResponse().getContentAsString(StandardCharsets.UTF_8))).as("MockMvc 본문과 같다").isEqualTo(real);
    }

    private void assertSameRejection(String session, String path, String token, String expectedJson) throws Exception {
        assertSameRejection(session, path, token, 409, expectedJson);
    }

    private void assertSameRejection(String session, String path, String token, int status, String expectedJson) throws Exception {
        assertSameBody(session, path, "{\"token\": \"" + token + "\"}", status, expectedJson);
    }

    /** 실제 서버의 응답이 계약 예시와 같고, 같은 세션 쿠키의 MockMvc 응답 본문과 같다. */
    private void assertSameBody(String session, String path, String body, int status, String expectedJson) throws Exception {
        JsonNode real = expectExactProblem(sendJson("POST", session, path, body), status, expectedJson);
        MvcResult mock = mvc.perform(https(post(path)).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie(session))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(mock.getResponse().getStatus()).as(path + " " + body).isEqualTo(status);
        assertThat(JSON.readTree(mock.getResponse().getContentAsString(StandardCharsets.UTF_8))).as("MockMvc 본문과 같다").isEqualTo(real);
    }

    private HttpResponse<String> postToken(String session, String path, String token) throws Exception {
        return sendJson("POST", session, path, "{\"token\": \"" + token + "\"}");
    }

    private JsonNode issue(String session) throws Exception {
        HttpResponse<String> response = sendJson("POST", session, "/api/v1/household/invitations", "{\"expiresInDays\": 3}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JSON.readTree(response.body());
    }

    /** 가구가 있는 세션의 사용자 번호(함께 보는 사람 목록에서 me가 true인 항목). */
    private long userIdOf(String session) throws Exception {
        HttpResponse<String> response = server.send(HttpRequest.newBuilder(server.url("/api/v1/household")).GET()
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));
        for (JsonNode member : JSON.readTree(response.body()).path("members")) {
            if (member.path("me").booleanValue()) {
                return member.path("userId").longValue();
            }
        }
        throw new IllegalStateException("me 항목이 없다: " + response.body());
    }

    private HttpResponse<String> sendJson(String method, String session, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(server.url(path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Origin", ORIGIN)
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return server.send(request);
    }

    private long jdbcCount(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private HttpResponse<String> create(String session) throws Exception {
        return server.send(HttpRequest.newBuilder(server.url("/api/v1/household"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Origin", ORIGIN)
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));
    }

    /** 상태 코드, application/problem+json, 본문 JSON이 계약 예시와 키까지 같다. 본문 JSON을 준다. */
    private static JsonNode expectExactProblem(HttpResponse<String> response, int status, String expectedJson) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).as("Content-Type이 application/problem+json").isTrue();
        JsonNode body = JSON.readTree(response.body());
        assertThat(body).isEqualTo(JSON.readTree(expectedJson));
        return body;
    }

    private static Cookie cookie(String session) {
        return new Cookie(LoginClient.SESSION_COOKIE, session);
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
