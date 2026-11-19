package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.https;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import estate.support.GoogleAccount;
import estate.support.Households.Person;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T029 B7: 초대 토큰과 개인정보가 응답과 로그에 새지 않는다(FR-008, FR-012, FR-028, SC-009, research R6, R7).
 * 토큰 원문은 초대 발급 응답에만 있다. 표시 이름과 로그인 이메일은 함께 보는 사람 목록과 내 정보의 그 필드, 초대 확인의 inviterDisplayName에만 있다.
 * 가짜 계정마다 고유한 이메일과 표시 이름을 써서 다른 테스트의 값과 섞이지 않게 한다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class InvitationTokenLeakSecurityTest {

    static final String ORIGIN = "https://localhost";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;
    @Autowired
    MutableClock clock;

    /** 토큰 원문, 해시의 16진수와 base64, sub. 발급 응답의 token 말고는 어디에도 없어야 한다. */
    final List<String> secrets = new ArrayList<>();
    /** 가짜 이메일과 표시 이름. 로그에 없고 응답에는 허용한 필드에만 있다. */
    final List<String> personal = new ArrayList<>();
    final List<MvcResult> responses = new ArrayList<>();

    /** 초대 발급 응답(201). token만 허용한다. */
    final List<MvcResult> issued = new ArrayList<>();

    /** 로그인한 가짜 계정과 그 이름, 이메일. */
    record Account(Person person, String email, String name) {
        Cookie cookie() {
            return person.cookie();
        }
    }

    /** 발급한 초대: 응답의 id와 원문 토큰, DB의 token_hash. */
    record Issued(long id, String token, byte[] hash) {
    }

    @BeforeEach
    void setUp() {
        // 재인증 5분 안을 유지한다
        clock.freezeAt(Instant.now());
    }

    @Test
    @DisplayName("T029 B7 FR-008 research R6: 토큰은 발급 응답에만 있고 43자 base64url이며 token_hash는 토큰 ASCII 바이트의 SHA-256 32바이트이고, 목록, 확인, 오류 응답에 토큰도 해시도 없다")
    void tokenOnlyInIssueResponse() throws Exception {
        Account owner = account("owner");
        createHousehold(owner);
        Issued invitation = issue(owner);

        assertThat(invitation.token()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(invitation.hash()).hasSize(32);
        assertThat(invitation.hash()).isEqualTo(sha256(invitation.token()));
        assertThat(invitation.hash()).isNotEqualTo(invitation.token().getBytes(StandardCharsets.US_ASCII));
        assertThat(body(issued.get(0))).contains(invitation.token());

        runRejectionsAndAccept(owner, invitation);

        assertThat(responses.size()).as("검사할 응답").isGreaterThan(5);
        for (MvcResult r : responses) {
            String visible = visible(r);
            for (String s : secrets) {
                if (!(issued.contains(r) && s.equals(invitation.token()))) {
                    assertThat(visible).as(describe(r)).doesNotContain(s);
                }
            }
        }
    }

    @Test
    @DisplayName("T029 B7 US2-9 FR-012: 초대 확인 응답의 키는 inviterDisplayName 하나이고 owner의 이메일과 구성원 목록이 없다")
    void previewHasOnlyInviterName() throws Exception {
        Account owner = account("owner");
        createHousehold(owner);
        Issued invitation = issue(owner);
        Account guest = account("guest");

        MvcResult preview = token(post("/api/v1/invitations/preview"), guest, "{\"token\": \"" + invitation.token() + "\"}");

        assertThat(preview.getResponse().getStatus()).isEqualTo(200);
        JsonNode node = JSON.readTree(body(preview));
        assertThat(node.propertyNames()).containsExactly("inviterDisplayName");
        assertThat(node.path("inviterDisplayName").asString()).isEqualTo(owner.name());
        assertThat(body(preview)).doesNotContain(owner.email()).doesNotContain(guest.email()).doesNotContain(guest.name());
    }

    @Test
    @DisplayName("T029 B7 FR-028 SC-009: 발급, 목록, 확인, 수락과 거절(토큰이 든 깨진 JSON 포함) 흐름의 로그와 응답에 토큰, 해시, 가짜 이메일, 가짜 표시 이름이 없다")
    void noLeakInInvitationFlow(CapturedOutput output) throws Exception {
        Account owner = account("owner");
        createHousehold(owner);
        Issued invitation = issue(owner);

        runRejectionsAndAccept(owner, invitation);

        assertNoLeak(output);
    }

    @Test
    @DisplayName("T029 B7 FR-028 SC-009: 구성원 제거, 가구 나가기, 가구 삭제, 표시 이름 400 흐름의 로그와 응답에 토큰, 해시, 가짜 이메일, 가짜 표시 이름이 없다")
    void noLeakInMemberExitFlows(CapturedOutput output) throws Exception {
        Account owner = account("owner");
        createHousehold(owner);
        Account member = account("member");

        // 구성원 제거
        accept(member, issue(owner));
        MvcResult remove = record(mvc.perform(write(delete("/api/v1/household/members/" + member.person().userId()), owner)).andReturn());
        assertThat(remove.getResponse().getStatus()).isEqualTo(204);

        // 표시 이름 400(다시 들어온 member가 제어 문자가 든 이름을 보냄)
        accept(member, issue(owner));
        MvcResult badName = record(mvc.perform(write(patch("/api/v1/me"), member).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"" + member.name() + "\\u0007\"}")).andReturn());
        assertThat(badName.getResponse().getStatus()).isEqualTo(400);

        // 가구 나가기
        MvcResult leave = record(mvc.perform(write(delete("/api/v1/household/membership"), member)).andReturn());
        assertThat(leave.getResponse().getStatus()).isEqualTo(204);

        // 가구 삭제(수락 대기 중인 초대가 남은 채)
        issue(owner);
        MvcResult deleteHousehold = record(mvc.perform(write(delete("/api/v1/household"), owner)).andReturn());
        assertThat(deleteHousehold.getResponse().getStatus()).isEqualTo(204);

        // 삭제 뒤 남은 사람들의 가구 요청(403 NO_HOUSEHOLD)
        assertThat(record(mvc.perform(https(get("/api/v1/household")).cookie(owner.cookie())).andReturn()).getResponse().getStatus())
                .isEqualTo(403);

        assertNoLeak(output);
    }

    /**
     * 목록, 확인, 거절 응답 여러 가지, 수락, 수락 뒤 거절, 함께 보는 사람 목록과 내 정보를 차례로 보낸다.
     * 거절은 발급한 owner, 다른 가구의 owner, 가구 없는 사람의 요청으로 만든다.
     */
    private void runRejectionsAndAccept(Account owner, Issued invitation) throws Exception {
        String t = invitation.token();
        Account other = account("other");
        createHousehold(other);
        Account guest = account("guest");
        Account late = account("late");

        expectStatus(record(mvc.perform(https(get("/api/v1/household/invitations")).cookie(owner.cookie())).andReturn()), 200);
        expectStatus(token(post("/api/v1/invitations/preview"), guest, "{\"token\": \"" + t + "\"}"), 200);
        // 거절: 자기 가구의 링크(409), 다른 가구(409), 형식이 틀린 토큰(404), 토큰이 든 깨진 JSON과 다른 키(400), 수락 대기 중 발급(409)
        expectStatus(token(post("/api/v1/invitations/preview"), owner, "{\"token\": \"" + t + "\"}"), 409);
        expectStatus(token(post("/api/v1/invitations/accept"), other, "{\"token\": \"" + t + "\"}"), 409);
        expectStatus(token(post("/api/v1/invitations/preview"), guest, "{\"token\": \"" + t + "A\"}"), 404);
        expectStatus(token(post("/api/v1/invitations/preview"), guest, "{\"token\": \"" + t + "\""), 400);
        expectStatus(token(post("/api/v1/invitations/accept"), guest, "{\"token\": \"" + t), 400);
        expectStatus(token(post("/api/v1/invitations/accept"), guest, "{\"token\": \"" + t + "\", \"extra\": \"" + t + "\"}"), 400);
        expectStatus(record(mvc.perform(write(post("/api/v1/household/invitations"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInDays\": 3")).andReturn()), 400);
        expectStatus(record(mvc.perform(write(post("/api/v1/household/invitations"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInDays\": 3}")).andReturn()), 409);

        // 수락과 그 뒤의 거절: 사용됨(409), 구성원 둘인 가구의 발급(409), 수락된 초대 취소(409)
        accept(guest, invitation);
        expectStatus(token(post("/api/v1/invitations/preview"), late, "{\"token\": \"" + t + "\"}"), 409);
        expectStatus(token(post("/api/v1/invitations/accept"), late, "{\"token\": \"" + t + "\"}"), 409);
        expectStatus(record(mvc.perform(write(post("/api/v1/household/invitations"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInDays\": 3}")).andReturn()), 409);
        expectStatus(record(mvc.perform(write(delete("/api/v1/household/invitations/" + invitation.id()), owner)).andReturn()), 409);
        expectStatus(record(mvc.perform(https(get("/api/v1/household/invitations")).cookie(owner.cookie())).andReturn()), 200);

        // 이름과 이메일을 정당하게 담는 응답(허용한 필드만 빼고 검사한다)
        expectStatus(record(mvc.perform(https(get("/api/v1/household")).cookie(guest.cookie())).andReturn()), 200);
        expectStatus(record(mvc.perform(https(get("/api/v1/me")).cookie(guest.cookie())).andReturn()), 200);
    }

    /** 고유한 가짜 계정으로 로그인한다. 이메일과 이름은 personal, sub는 secrets에 넣는다. */
    private Account account(String tag) throws Exception {
        String id = UUID.randomUUID().toString();
        GoogleAccount google = GoogleAccount.of("token-leak-sub-" + tag + "-" + id, "token-leak-" + tag + "-" + id + "@example.com")
                .withName("초대누출" + id.substring(0, 8));
        Cookie cookie = new LoginClient(mvc).login(google).requireSessionCookie();
        long userId = Long.parseLong(sessions.rowOf(SessionRows.idOfCookie(cookie.getValue())).principalName());
        secrets.add(google.sub());
        personal.add(google.email());
        personal.add(google.name());
        return new Account(new Person(cookie, userId), google.email(), google.name());
    }

    private void createHousehold(Account owner) throws Exception {
        expectStatus(record(mvc.perform(write(post("/api/v1/household"), owner)).andReturn()), 201);
    }

    /** 3일 초대를 발급한다(201). 토큰과 해시의 여러 표기를 secrets에 넣는다. */
    private Issued issue(Account owner) throws Exception {
        MvcResult r = record(mvc.perform(write(post("/api/v1/household/invitations"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInDays\": 3}")).andReturn());
        expectStatus(r, 201);
        issued.add(r);
        JsonNode node = JSON.readTree(body(r));
        assertThat(node.path("token").isString()).as("발급 응답의 token").isTrue();
        String token = node.path("token").asString();
        long id = node.path("id").asLong();
        byte[] hash = jdbc.sql("select token_hash from invitation where id = :id").param("id", id).query(byte[].class).single();
        secrets.add(token);
        secrets.add(HexFormat.of().formatHex(hash));
        secrets.add(HexFormat.of().withUpperCase().formatHex(hash));
        secrets.add(Base64.getEncoder().withoutPadding().encodeToString(hash));
        secrets.add(Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
        return new Issued(id, token, hash);
    }

    private void accept(Account person, Issued invitation) throws Exception {
        expectStatus(token(post("/api/v1/invitations/accept"), person, "{\"token\": \"" + invitation.token() + "\"}"), 204);
    }

    private MvcResult token(MockHttpServletRequestBuilder builder, Account person, String body) throws Exception {
        return record(mvc.perform(write(builder, person).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());
    }

    private MvcResult record(MvcResult result) {
        responses.add(result);
        return result;
    }

    private static void expectStatus(MvcResult r, int status) {
        assertThat(r.getResponse().getStatus()).as(describe(r)).isEqualTo(status);
    }

    /**
     * 로그 출력: secrets와 personal이 모두 없다. 응답: secrets가 없고(발급 응답의 token 값만 허용),
     * personal은 허용한 필드(함께 보는 사람 목록의 displayName과 loginEmail, 내 정보의 displayName과 loginEmail, 초대 확인의 inviterDisplayName)를 뺀 나머지에 없다.
     */
    private void assertNoLeak(CapturedOutput output) throws Exception {
        String marker = "token-leak-marker-" + UUID.randomUUID();
        LoggerFactory.getLogger(InvitationTokenLeakSecurityTest.class).warn(marker);
        assertThat(output.getAll()).contains(marker);
        assertThat(secrets).isNotEmpty();
        assertThat(personal).isNotEmpty();
        assertThat(issued).isNotEmpty();

        String all = output.getAll();
        for (String s : secrets) {
            assertThat(all).as("로그 출력").doesNotContain(s);
        }
        for (String s : personal) {
            assertThat(all).as("로그 출력").doesNotContain(s);
        }
        for (MvcResult r : responses) {
            String visible = withoutAllowedFields(r);
            for (String s : secrets) {
                assertThat(visible).as(describe(r)).doesNotContain(s);
            }
            for (String s : personal) {
                assertThat(visible).as(describe(r)).doesNotContain(s);
            }
        }
    }

    /** 응답에서 허용한 필드를 뺀 나머지(본문, 오류 메시지, 이동 주소). */
    private static String withoutAllowedFields(MvcResult r) throws Exception {
        String uri = r.getRequest().getRequestURI();
        String method = r.getRequest().getMethod();
        String body = body(r);
        int status = r.getResponse().getStatus();
        if (status == 201 && uri.equals("/api/v1/household/invitations")) {
            ObjectNode node = (ObjectNode) JSON.readTree(body);
            node.remove("token");
            body = node.toString();
        } else if (status == 200 && method.equals("GET") && uri.equals("/api/v1/household")) {
            JsonNode node = JSON.readTree(body);
            for (JsonNode m : node.path("members")) {
                ((ObjectNode) m).remove("displayName");
                ((ObjectNode) m).remove("loginEmail");
            }
            body = node.toString();
        } else if (status == 200 && method.equals("GET") && uri.equals("/api/v1/me")) {
            ObjectNode node = (ObjectNode) JSON.readTree(body);
            node.remove("displayName");
            node.remove("loginEmail");
            body = node.toString();
        } else if (status == 200 && uri.equals("/api/v1/invitations/preview")) {
            ObjectNode node = (ObjectNode) JSON.readTree(body);
            node.remove("inviterDisplayName");
            body = node.toString();
        }
        return body + "\n" + r.getResponse().getErrorMessage() + "\n" + r.getResponse().getHeader(HttpHeaders.LOCATION);
    }

    /** 응답 본문, 오류 메시지, 이동 주소. */
    private static String visible(MvcResult r) throws Exception {
        return body(r) + "\n" + r.getResponse().getErrorMessage() + "\n" + r.getResponse().getHeader(HttpHeaders.LOCATION);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String describe(MvcResult r) {
        return r.getRequest().getMethod() + " " + r.getRequest().getRequestURI() + " 응답(" + r.getResponse().getStatus() + ")";
    }

    private static byte[] sha256(String token) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, Account person) {
        return https(builder).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie());
    }
}
