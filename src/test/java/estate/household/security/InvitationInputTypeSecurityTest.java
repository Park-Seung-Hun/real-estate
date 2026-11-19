package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.https;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import estate.support.Households;
import estate.support.Households.Person;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T027 B9: 초대 발급, 확인, 수락 본문의 타입 제약(contracts CreateInvitation, InvitationToken, research R12, FR-006).
 * 숫자 문자열(2147483648, 1e0, 3.0)이 직렬화 도구를 거치며 바뀌지 않게 본문은 JSON 문자열을 직접 쓴다.
 * 재인증 판정이 앞서므로(R3) 시계를 로그인 시각에 멈춰 두고 보낸다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class InvitationInputTypeSecurityTest {

    static final String ORIGIN = "https://localhost";
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String EXPIRES_IN_DAYS_INVALID = """
            {"type": "https://example.com/problems/validation-failed", "title": "입력값이 올바르지 않습니다", "status": 400,
             "detail": "입력값 하나를 확인해 주세요.", "code": "VALIDATION_FAILED",
             "errors": [{"pointer": "#/expiresInDays", "detail": "만료 기간은 1일부터 7일까지 고를 수 있습니다."}]}""";
    static final String TOKEN_INVALID = """
            {"type": "https://example.com/problems/validation-failed", "title": "입력값이 올바르지 않습니다", "status": 400,
             "detail": "입력값 하나를 확인해 주세요.", "code": "VALIDATION_FAILED",
             "errors": [{"pointer": "#/token", "detail": "초대 링크를 확인해 주세요."}]}""";
    static final String NOT_FOUND = """
            {"type": "https://example.com/problems/not-found", "title": "찾을 수 없습니다", "status": 404,
             "detail": "주소를 확인해 주세요.", "code": "NOT_FOUND"}""";

    /** 본문과, 응답에 되돌아오면 안 되는 보낸 값(없으면 null). */
    record Case(String body, String sent) {
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;
    @Autowired
    MutableClock clock;

    Households households;

    @BeforeEach
    void setUp() {
        households = new Households(mvc, jdbc, sessions);
        // 재인증 5분 안을 유지한다(로그인과 발급이 같은 시각)
        clock.freezeAt(Instant.now());
    }

    @Test
    @DisplayName("T027 B9 US2-3 FR-006: 만료 기간이 1~7의 JSON 정수가 아니면 400 #/expiresInDays이고 초대 행이 생기지 않으며, 1과 7은 201")
    void issueRejectsNonIntegerDays() throws Exception {
        Person owner = households.newPerson();
        households.createHousehold(owner);
        List<String> bodies = List.of(
                days("0"), days("8"), days("1.5"), days("3.0"), days("1e0"), days("\"3\""), days("true"), days("null"),
                days("[3]"), days("{\"days\": 3}"), days("2147483648"), "{}");

        for (String body : bodies) {
            expectExact(issue(owner, body), "발급 " + body, 400, EXPIRES_IN_DAYS_INVALID);
            assertThat(invitationCount()).as("발급 " + body + " 뒤 초대 행").isZero();
        }

        // 짝: 같은 owner가 1을 보내면 201(위 400이 입력 때문임), 다른 가구의 owner가 7을 보내면 201
        assertThat(issue(owner, "{\"expiresInDays\": 1}").getResponse().getStatus()).isEqualTo(201);
        Person other = households.newPerson();
        households.createHousehold(other);
        assertThat(issue(other, "{\"expiresInDays\": 7}").getResponse().getStatus()).isEqualTo(201);
        assertThat(invitationCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("T027 B9 US2-3 FR-006: 다른 키, 깨진 JSON, 빈 본문, 객체가 아닌 본문의 발급은 400 #이고 초대 행이 생기지 않는다")
    void issueRejectsNonObjectBody() throws Exception {
        Person owner = households.newPerson();
        households.createHousehold(owner);
        List<Case> cases = List.of(
                new Case("{\"expiresInDays\": 3, \"extraMarker\": 1}", "extraMarker"),
                new Case("{\"expiresInDays\": 3, \"brokenMarker", "brokenMarker"),
                new Case("", null),
                new Case("[{\"expiresInDays\": 3}]", null),
                new Case("\"plainMarker\"", "plainMarker"),
                new Case("3", null),
                new Case("null", null));

        for (Case c : cases) {
            MvcResult r = issue(owner, c.body());
            expectBodyProblem(r, "발급 [" + c.body() + "]", c.sent());
            assertThat(invitationCount()).as("발급 [" + c.body() + "] 뒤 초대 행").isZero();
        }

        // 짝: 올바른 본문이면 201
        assertThat(issue(owner, "{\"expiresInDays\": 3}").getResponse().getStatus()).isEqualTo(201);
        assertThat(invitationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T027 B9 FR-012 research R12: token이 없거나 문자열이 아니면 초대 확인과 수락이 400 #/token이고 초대와 멤버십이 그대로다")
    void tokenMustBeString() throws Exception {
        Fixture f = fixture();
        List<String> bodies = List.of(
                "{}",
                "{\"token\": null}",
                "{\"token\": 987654321}",
                "{\"token\": true}",
                "{\"token\": [\"" + f.token() + "\"]}",
                "{\"token\": {\"value\": \"" + f.token() + "\"}}");

        for (String body : bodies) {
            for (String path : List.of("preview", "accept")) {
                expectExact(token(path, f.guest(), body), path + " " + body, 400, TOKEN_INVALID);
            }
        }

        expectUnchanged(f);
        expectAcceptable(f);
    }

    @Test
    @DisplayName("T027 B9 research R12: 다른 키, 깨진 JSON, 빈 본문, 객체가 아닌 본문의 초대 확인과 수락은 400 #이고 초대와 멤버십이 그대로다")
    void tokenBodyMustBeObject() throws Exception {
        Fixture f = fixture();
        List<Case> cases = List.of(
                new Case("{\"token\": \"" + f.token() + "\", \"extraMarker\": 1}", f.token()),
                new Case("{\"token\": \"" + f.token() + "\"", f.token()),
                new Case("", null),
                new Case("\"" + f.token() + "\"", f.token()),
                new Case("[\"" + f.token() + "\"]", f.token()),
                new Case("null", null));

        for (Case c : cases) {
            for (String path : List.of("preview", "accept")) {
                MvcResult r = token(path, f.guest(), c.body());
                expectBodyProblem(r, path + " [" + c.body() + "]", c.sent());
                assertThat(body(r)).as(path + " 응답에 다른 키 이름").doesNotContain("extraMarker");
            }
        }

        expectUnchanged(f);
        expectAcceptable(f);
    }

    @Test
    @DisplayName("T027 B9 research R12: token이 문자열이지만 43자 base64url이 아니면 초대 확인과 수락이 400이 아니라 404 NOT_FOUND이고 초대가 그대로다")
    void malformedTokenIsNotFound() throws Exception {
        Fixture f = fixture();
        String t = f.token();
        List<String> malformed = List.of(
                "",
                t.substring(1),
                t + "A",
                " " + t,
                t + "\\n",
                "+" + t.substring(1),
                "/" + t.substring(1),
                t.substring(1) + "=",
                "." + t.substring(1),
                "가" + t.substring(1));

        for (String m : malformed) {
            for (String path : List.of("preview", "accept")) {
                expectExact(token(path, f.guest(), "{\"token\": \"" + m + "\"}"), path + " [" + m + "]", 404, NOT_FOUND);
            }
        }

        expectUnchanged(f);
        expectAcceptable(f);
    }

    /** owner가 발급한 대기 중인 초대 하나와 가구 없는 사람. */
    record Fixture(long invitationId, String token, Person guest) {
    }

    private Fixture fixture() throws Exception {
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        String token = households.invite(owner, 3);
        long id = jdbc.sql("select id from invitation where household_id = :h").param("h", household).query(Long.class).single();
        return new Fixture(id, token, households.newPerson());
    }

    /** 거절 뒤: 초대가 대기이고 그 사람에게 멤버십이 없다. */
    private void expectUnchanged(Fixture f) {
        assertThat(jdbc.sql("select status from invitation where id = :id").param("id", f.invitationId()).query(String.class).single())
                .isEqualTo("대기");
        assertThat(membershipCount(f.guest())).isZero();
    }

    /** 짝: 같은 사람이 원래 토큰으로 확인하면 200, 수락하면 204(위 거절이 입력 때문임). */
    private void expectAcceptable(Fixture f) throws Exception {
        String valid = "{\"token\": \"" + f.token() + "\"}";
        assertThat(token("preview", f.guest(), valid).getResponse().getStatus()).isEqualTo(200);
        assertThat(token("accept", f.guest(), valid).getResponse().getStatus()).isEqualTo(204);
        assertThat(membershipCount(f.guest())).isEqualTo(1);
    }

    private static String days(String value) {
        return "{\"expiresInDays\": " + value + "}";
    }

    private MvcResult issue(Person owner, String body) throws Exception {
        return mvc.perform(https(post("/api/v1/household/invitations")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult token(String path, Person person, String body) throws Exception {
        return mvc.perform(https(post("/api/v1/invitations/" + path)).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    /** 상태 코드, problem+json, 본문이 계약 예시와 키까지 같다(보낸 값이 들어갈 자리가 없다). */
    private static void expectExact(MvcResult r, String label, int status, String expected) throws Exception {
        assertThat(r.getResponse().getStatus()).as(label + " 상태").isEqualTo(status);
        assertThat(r.getResponse().getContentType()).as(label + " Content-Type").startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(JSON.readTree(body(r))).as(label + " 본문").isEqualTo(JSON.readTree(expected));
    }

    /** 400 VALIDATION_FAILED, 오류 하나, pointer #, 보낸 값이 본문에 없다. */
    private static void expectBodyProblem(MvcResult r, String label, String sent) throws Exception {
        assertThat(r.getResponse().getStatus()).as(label + " 상태").isEqualTo(400);
        assertThat(r.getResponse().getContentType()).as(label + " Content-Type").startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode node = JSON.readTree(body(r));
        assertThat(node.path("code").asString()).as(label + " code").isEqualTo("VALIDATION_FAILED");
        assertThat(node.path("errors").size()).as(label + " errors 수").isEqualTo(1);
        assertThat(node.path("errors").get(0).path("pointer").asString()).as(label + " pointer").isEqualTo("#");
        if (sent != null) {
            assertThat(body(r)).as(label + " 본문에 보낸 값").doesNotContain(sent);
        }
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long invitationCount() {
        return jdbc.sql("select count(*) from invitation").query(Long.class).single();
    }

    private long membershipCount(Person person) {
        return jdbc.sql("select count(*) from household_member where user_id = :u").param("u", person.userId()).query(Long.class).single();
    }
}
