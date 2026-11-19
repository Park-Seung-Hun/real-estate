package estate.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import estate.support.Households;
import estate.support.Households.Person;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T036 US2: 초대 발급, 취소, 보낸 초대 목록의 동작(FR-006, FR-009, FR-010, FR-011, research R11).
 * 오류 본문은 계약 예시와 키까지 같은지 본다. 수락과 확인은 InvitationAcceptTest가 맡는다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class InvitationIssueTest {

    private static final String ORIGIN = "https://localhost";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/api/v1/household/invitations";

    private static final String PENDING = """
            {"type": "https://example.com/problems/invitation-pending", "title": "수락 대기 중인 초대가 있습니다", "status": 409,
             "detail": "수락 대기 중인 초대를 취소한 뒤 다시 시도해 주세요.", "code": "INVITATION_PENDING"}""";
    private static final String FULL = """
            {"type": "https://example.com/problems/household-full", "title": "구성원이 이미 둘입니다", "status": 409,
             "detail": "가구 구성원은 둘까지입니다.", "code": "HOUSEHOLD_FULL"}""";
    private static final String NOT_PENDING = """
            {"type": "https://example.com/problems/invitation-not-pending", "title": "취소할 수 없는 초대입니다", "status": 409,
             "detail": "이미 수락되었거나 취소되었거나 만료된 초대입니다.", "code": "INVITATION_NOT_PENDING"}""";

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
    }

    @Test
    @DisplayName("T036 US2-2 R11: 발급은 201이고 id, token, createdAt, expiresAt만 있으며 시각은 +09:00 오프셋 문자열이고 DB의 created_at, expires_at과 같은 순간이다")
    void issueReturnsKstTimes() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(654_321_000);
        clock.freezeAt(now);
        Person owner = households.newPerson();
        households.createHousehold(owner);

        String text = issue(owner, 3).andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode body = JSON.readTree(text);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "token", "createdAt", "expiresAt");
        assertThat(body.path("id").isIntegralNumber()).isTrue();
        assertThat(body.path("token").stringValue()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(body.path("createdAt").stringValue()).endsWith("+09:00");
        assertThat(body.path("expiresAt").stringValue()).endsWith("+09:00");
        Map<String, Object> row = jdbc.sql("select id, status, created_at, expires_at from invitation").query().singleRow();
        assertThat(body.path("id").longValue()).isEqualTo((Long) row.get("id"));
        assertThat(row.get("status")).isEqualTo("대기");
        assertThat(OffsetDateTime.parse(body.path("createdAt").stringValue()).toInstant())
                .isEqualTo(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(now);
        assertThat(OffsetDateTime.parse(body.path("expiresAt").stringValue()).toInstant())
                .isEqualTo(((Timestamp) row.get("expires_at")).toInstant()).isEqualTo(now.plus(Duration.ofHours(72)));
    }

    @Test
    @DisplayName("T036 US2-5 FR-009: 수락 대기 중이고 만료되지 않은 초대가 있으면 두 번째 발급은 409 INVITATION_PENDING이고 초대 수가 그대로다")
    void pendingInvitationBlocksIssue() throws Exception {
        Person owner = households.newPerson();
        households.createHousehold(owner);
        issue(owner, 3).andExpect(status().isCreated());
        List<Map<String, Object>> before = invitationRows();

        expectProblem(issue(owner, 5), 409, PENDING);

        assertThat(invitationRows()).isEqualTo(before).hasSize(1);
    }

    @Test
    @DisplayName("T036 US2-5 FR-009: 만료된 초대, 취소한 초대, 수락한 초대는 수락 대기 중인 초대로 세지 않아 발급이 201이다")
    void notPendingInvitationsDoNotBlock() throws Exception {
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        Instant now = Instant.now();
        households.insertInvitation(household, owner.userId(), "대기", now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(1)));
        households.insertInvitation(household, owner.userId(), "취소", now.minus(Duration.ofHours(2)), now.plus(Duration.ofDays(2)));
        households.insertInvitation(household, owner.userId(), "수락", now.minus(Duration.ofHours(3)), now.plus(Duration.ofDays(2)));

        issue(owner, 3).andExpect(status().isCreated());

        assertThat(count("select count(*) from invitation")).isEqualTo(4);
    }

    @Test
    @DisplayName("T036 US2-5 FR-009: 구성원이 둘이면 발급은 409 HOUSEHOLD_FULL이고 초대 수가 그대로다")
    void fullHouseholdBlocksIssue() throws Exception {
        Person owner = households.newPerson();
        households.createHousehold(owner);
        households.addMember(owner);
        List<Map<String, Object>> before = invitationRows();

        expectProblem(issue(owner, 3), 409, FULL);

        assertThat(invitationRows()).isEqualTo(before).hasSize(1);
    }

    @Test
    @DisplayName("T036 FR-009 끝 문장: 구성원이 둘이면서 수락 대기 중이고 만료되지 않은 초대가 있는 상태(SQL)에서도 발급은 409 HOUSEHOLD_FULL이다")
    void fullAndPendingReportsFull() throws Exception {
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        households.addMember(owner);
        Instant now = Instant.now();
        households.insertInvitation(household, owner.userId(), "대기", now, now.plus(Duration.ofDays(3)));
        List<Map<String, Object>> before = invitationRows();

        expectProblem(issue(owner, 3), 409, FULL);

        assertThat(invitationRows()).isEqualTo(before).hasSize(2);
    }

    @Test
    @DisplayName("T036 US2-7 FR-011: 대기 중인 초대를 취소하면 204이고 본문이 없으며 상태가 취소로 바뀌고 행은 남고, 새 발급은 201이다. 같은 초대를 다시 취소하면 409")
    void revokePendingAllowsNewIssue() throws Exception {
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        long id = issuedId(owner, 3);
        Map<String, Object> before = jdbc.sql("select household_id, created_by, expires_at, created_at from invitation where id = :i")
                .param("i", id).query().singleRow();

        byte[] body = revoke(owner, String.valueOf(id)).andExpect(status().isNoContent()).andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEmpty();
        assertThat(jdbc.sql("select status from invitation where id = :i").param("i", id).query(String.class).single()).isEqualTo("취소");
        assertThat(jdbc.sql("select household_id, created_by, expires_at, created_at from invitation where id = :i")
                .param("i", id).query().singleRow()).as("상태 말고는 그대로").isEqualTo(before);
        assertThat(count("select count(*) from invitation")).as("행은 삭제하지 않는다").isEqualTo(1);

        expectProblem(revoke(owner, String.valueOf(id)), 409, NOT_PENDING);
        issue(owner, 3).andExpect(status().isCreated());
        assertThat(count("select count(*) from invitation where household_id = " + household)).isEqualTo(2);
    }

    @Test
    @DisplayName("T036 US2-7 FR-011: 수락되었거나 취소되었거나 만료된 초대를 취소하면 409 INVITATION_NOT_PENDING이고 행이 그대로다")
    void revokeNotPendingConflicts() throws Exception {
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        long accepted = issuedIdAndAccept(owner);
        Instant now = Instant.now();
        long revoked = households.insertInvitation(household, owner.userId(), "취소", now.minus(Duration.ofHours(2)), now.plus(Duration.ofDays(2))).id();
        long expired = households.insertInvitation(household, owner.userId(), "대기", now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(1))).id();
        List<Map<String, Object>> before = invitationRows();
        assertThat(before).hasSize(3);

        for (long id : new long[] {accepted, revoked, expired}) {
            expectProblem(revoke(owner, String.valueOf(id)), 409, NOT_PENDING);
        }

        assertThat(invitationRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("T036 US2-4 FR-010: 수락 대기 중인 초대가 없으면 보낸 초대 목록은 키 invitations만 있는 빈 배열이다")
    void listIsEmptyWithoutPending() throws Exception {
        Person owner = households.newPerson();
        households.createHousehold(owner);

        JsonNode root = list(owner);

        assertThat(root.propertyNames()).containsExactly("invitations");
        assertThat(root.path("invitations").isArray()).isTrue();
        assertThat(root.path("invitations").size()).isZero();
    }

    @Test
    @DisplayName("T036 US2-4 FR-010: 발급한 초대는 목록에 id, createdAt, expiresAt, remainingDays만 담기고 토큰이 없으며 시각은 +09:00이고 3일 정각이면 remainingDays 3이다")
    void listShowsIssuedInvitation() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(654_321_000);
        clock.freezeAt(now);
        Person owner = households.newPerson();
        households.createHousehold(owner);
        long id = issuedId(owner, 3);

        JsonNode items = list(owner).path("invitations");

        assertThat(items.size()).isEqualTo(1);
        JsonNode item = items.path(0);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "createdAt", "expiresAt", "remainingDays");
        assertThat(item.path("id").longValue()).isEqualTo(id);
        assertThat(item.path("remainingDays").isIntegralNumber()).isTrue();
        assertThat(item.path("remainingDays").intValue()).isEqualTo(3);
        assertThat(item.path("createdAt").stringValue()).endsWith("+09:00");
        assertThat(item.path("expiresAt").stringValue()).endsWith("+09:00");
        assertThat(OffsetDateTime.parse(item.path("createdAt").stringValue()).toInstant()).isEqualTo(now);
        assertThat(OffsetDateTime.parse(item.path("expiresAt").stringValue()).toInstant()).isEqualTo(now.plus(Duration.ofDays(3)));
    }

    @Test
    @DisplayName("T036 US2-4 FR-010: 목록은 수락 대기 중이면서 만료되지 않은 초대만이다(수락, 취소, 만료된 초대와 다른 가구의 초대는 없음)")
    void listHidesNotPendingInvitations() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.freezeAt(now);
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        Person otherOwner = households.newPerson();
        long otherHousehold = households.createHousehold(otherOwner);
        households.insertInvitation(household, owner.userId(), "수락", now.minus(Duration.ofHours(3)), now.plus(Duration.ofDays(2)));
        households.insertInvitation(household, owner.userId(), "취소", now.minus(Duration.ofHours(2)), now.plus(Duration.ofDays(2)));
        households.insertInvitation(household, owner.userId(), "대기", now.minus(Duration.ofDays(3)), now.minus(Duration.ofSeconds(1)));
        households.insertInvitation(otherHousehold, otherOwner.userId(), "대기", now, now.plus(Duration.ofDays(5)));
        long pending = households.insertInvitation(household, owner.userId(), "대기", now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(2))).id();

        JsonNode items = list(owner).path("invitations");

        assertThat(items.size()).isEqualTo(1);
        assertThat(items.path(0).path("id").longValue()).isEqualTo(pending);
        assertThat(items.path(0).path("remainingDays").intValue()).isEqualTo(2);
    }

    private long issuedId(Person owner, int days) throws Exception {
        String text = issue(owner, days).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(text).path("id").longValue();
    }

    /** 초대를 API로 발급하고 새 사람이 수락하게 한 뒤(구성원이 둘이 됨) 그 초대 번호를 준다. */
    private long issuedIdAndAccept(Person owner) throws Exception {
        households.addMember(owner);
        return jdbc.sql("select id from invitation where status = '수락'").query(Long.class).single();
    }

    private ResultActions issue(Person owner, int days) throws Exception {
        return mvc.perform(write(post(BASE), owner).contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\": " + days + "}"));
    }

    private ResultActions revoke(Person owner, String id) throws Exception {
        return mvc.perform(write(delete(BASE + "/" + id), owner));
    }

    private JsonNode list(Person person) throws Exception {
        String text = mvc.perform(https(get(BASE)).cookie(person.cookie())).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(text);
    }

    private List<Map<String, Object>> invitationRows() {
        return jdbc.sql("select id, household_id, created_by, status, expires_at, created_at from invitation order by id")
                .query().listOfRows();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    /** 상태 코드, application/problem+json, 본문이 계약 예시와 키까지 같다. */
    private static void expectProblem(ResultActions actions, int status, String expectedJson) throws Exception {
        var response = actions.andExpect(status().is(status)).andReturn().getResponse();
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8))).isEqualTo(JSON.readTree(expectedJson));
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, Person person) {
        return https(builder).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie());
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
