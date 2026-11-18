package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.https;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
import estate.support.Households.InsertedInvitation;
import estate.support.Households.Person;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T028 B8: 초대 만료 계산과 경계(research R11, spec US2-4, US2-11, FR-007, FR-010, SC-003).
 * 시계는 마이크로초 아래 자리(789나노초)가 있는 시각에 멈춘다. PostgreSQL은 이 자리를 반올림하므로, 서버가 자르지 않고 저장하면 created_at이 1마이크로초 달라진다.
 * 경계 시각은 저장된 created_at과 expires_at을 기준으로 옮긴다(R11: 저장한 값과 계산한 값이 같아야 경계가 정확하다).
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class InvitationExpirySecurityTest {

    static final String ORIGIN = "https://localhost";
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String INVITATION_EXPIRED = """
            {"type": "https://example.com/problems/invitation-expired", "title": "초대 링크가 만료되었습니다", "status": 409,
             "detail": "가족에게 새 초대 링크를 받아 주세요.", "code": "INVITATION_EXPIRED"}""";
    static final String INVITATION_NOT_PENDING = """
            {"type": "https://example.com/problems/invitation-not-pending", "title": "취소할 수 없는 초대입니다", "status": 409,
             "detail": "이미 수락되었거나 취소되었거나 만료된 초대입니다.", "code": "INVITATION_NOT_PENDING"}""";
    static final String INVITATION_PENDING = """
            {"type": "https://example.com/problems/invitation-pending", "title": "수락 대기 중인 초대가 있습니다", "status": 409,
             "detail": "수락 대기 중인 초대를 취소한 뒤 다시 시도해 주세요.", "code": "INVITATION_PENDING"}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;
    @Autowired
    MutableClock clock;

    Households households;
    /** 로그인과 발급 시각. 초 아래 자리가 있고 마이크로초 아래 자리(789나노초)가 반올림 쪽이다. */
    Instant issuedAt;
    /** issue로 만든 가구의 owner. */
    final Map<Long, Person> owners = new HashMap<>();

    @BeforeEach
    void setUp() {
        households = new Households(mvc, jdbc, sessions);
        issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789);
    }

    /** 저장된 초대 한 건. */
    record Row(long id, long householdId, String token, Instant createdAt, Instant expiresAt) {
    }

    @Test
    @DisplayName("T028 B8 US2-2 FR-007: 만료 기간 1~7 각각 created_at은 마이크로초로 자른 Clock 시각이고 expires_at은 created_at 더하기 N 곱하기 24시간이다")
    void expiryIsDaysTimesTwentyFourHours() throws Exception {
        clock.freezeAt(issuedAt);
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);

        for (int days = 1; days <= 7; days++) {
            // 발급 시각마다 초를 달리해 다른 발급의 값을 읽은 것과 구별한다(재인증 5분 안)
            Instant at = issuedAt.plusSeconds(days);
            clock.freezeAt(at);
            String token = households.invite(owner, days);
            Row row = pendingRow(household, token);

            assertThat(row.createdAt()).as(days + "일 초대의 created_at").isEqualTo(at.truncatedTo(ChronoUnit.MICROS));
            assertThat(Duration.between(row.createdAt(), row.expiresAt())).as(days + "일 초대의 기간").isEqualTo(Duration.ofHours(24L * days));

            // 다음 발급이 수락 대기 중인 초대에 막히지 않게 SQL로 치운다
            jdbc.sql("update invitation set status = '취소' where id = :id").param("id", row.id()).update();
        }
    }

    @Test
    @DisplayName("T028 B8 US2-11 SC-003: 1일 초대는 23시간 59분 59초와 정확히 24시간에 수락된다")
    void oneDayAcceptedUntilExpiry() throws Exception {
        for (Duration after : new Duration[] {Duration.ofHours(24).minusSeconds(1), Duration.ofHours(24)}) {
            Row row = issue(1);
            Person guest = households.newPerson();
            clock.freezeAt(row.createdAt().plus(after));

            accept(guest, row.token()).andExpect(r -> assertThat(r.getResponse().getStatus()).as(after + " 뒤 수락").isEqualTo(204));
            assertThat(memberRole(guest, row.householdId())).as(after + " 뒤 멤버십").isEqualTo("member");
            assertThat(status(row)).isEqualTo("수락");
        }
    }

    @Test
    @DisplayName("T028 B8 US2-11 SC-003: 1일 초대를 24시간 1초에 수락하면 409 INVITATION_EXPIRED이고 멤버십이 없으며 초대는 대기로 남는다")
    void oneDayRejectedAfterExpiry() throws Exception {
        Row row = issue(1);
        Person guest = households.newPerson();
        clock.freezeAt(row.createdAt().plus(Duration.ofHours(24)).plusSeconds(1));

        ResponseOrderSecurityTest.expectExactProblem(accept(guest, row.token()), 409, INVITATION_EXPIRED);

        assertThat(membershipCount(guest)).isZero();
        assertThat(status(row)).isEqualTo("대기");
    }

    @Test
    @DisplayName("T028 B8 US2-11 SC-003: 7일 초대는 6일 23시간 59분 59초에 수락되고 7일 1초에 409 INVITATION_EXPIRED")
    void sevenDayBoundary() throws Exception {
        Row accepted = issue(7);
        Person early = households.newPerson();
        Row rejected = issue(7);
        Person late = households.newPerson();

        clock.freezeAt(accepted.createdAt().plus(Duration.ofDays(7)).minusSeconds(1));
        accept(early, accepted.token()).andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(204));
        assertThat(memberRole(early, accepted.householdId())).isEqualTo("member");

        clock.freezeAt(rejected.createdAt().plus(Duration.ofDays(7)).plusSeconds(1));
        ResponseOrderSecurityTest.expectExactProblem(accept(late, rejected.token()), 409, INVITATION_EXPIRED);
        assertThat(membershipCount(late)).isZero();
        assertThat(status(rejected)).isEqualTo("대기");
    }

    @Test
    @DisplayName("T028 B8 US2-11: 만료 시각 정각에 초대 확인이 200이어도, 1초 뒤 수락하면 409 INVITATION_EXPIRED이고 멤버십이 없다")
    void expiresBetweenPreviewAndAccept() throws Exception {
        Row row = issue(1);
        Person guest = households.newPerson();

        clock.freezeAt(row.expiresAt());
        mvc.perform(write(post("/api/v1/invitations/preview"), guest).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"" + row.token() + "\"}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).as("만료 시각 정각의 초대 확인").isEqualTo(200));

        clock.advance(Duration.ofSeconds(1));
        ResponseOrderSecurityTest.expectExactProblem(accept(guest, row.token()), 409, INVITATION_EXPIRED);

        assertThat(membershipCount(guest)).isZero();
        assertThat(status(row)).isEqualTo("대기");
    }

    @Test
    @DisplayName("T028 B8 US2-4 FR-010: 3일 초대의 remainingDays는 3일 정각 3, 2일 5시간 3, 1일 1초 2, 1일 정각 1, 5시간 1, 만료 정각 1이고 만료 뒤에는 목록에 없다")
    void remainingDaysRoundsUp() throws Exception {
        Row row = issue(3);
        Person owner = ownerOf(row);
        Object[][] points = {
                {Duration.ofDays(3), 3},
                {Duration.ofDays(2).plusHours(5), 3},
                {Duration.ofDays(1).plusSeconds(1), 2},
                {Duration.ofDays(1), 1},
                {Duration.ofHours(5), 1},
                {Duration.ZERO, 1}};

        for (Object[] p : points) {
            Duration remaining = (Duration) p[0];
            clock.freezeAt(row.expiresAt().minus(remaining));
            JsonNode invitations = list(owner);
            assertThat(invitations.size()).as("남은 " + remaining + "의 목록 수").isEqualTo(1);
            assertThat(invitations.get(0).path("id").asLong()).as("남은 " + remaining + "의 id").isEqualTo(row.id());
            assertThat(invitations.get(0).path("remainingDays").isInt()).as("남은 " + remaining + "의 remainingDays 정수").isTrue();
            assertThat(invitations.get(0).path("remainingDays").asInt()).as("남은 " + remaining + "의 remainingDays").isEqualTo(p[1]);
        }

        clock.freezeAt(row.expiresAt().plusSeconds(1));
        assertThat(list(owner).size()).as("만료 1초 뒤 목록").isZero();
    }

    @Test
    @DisplayName("T028 B8 spec Edge Cases data-model: 만료된 초대는 목록에 없고 수락 대기로 세지 않아 새 발급이 201이며, 취소하면 409 INVITATION_NOT_PENDING이고 행이 그대로다")
    void expiredInvitationIsNotPending() throws Exception {
        clock.freezeAt(issuedAt);
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        InsertedInvitation expired = households.insertInvitation(household, owner.userId(), "대기",
                issuedAt.minus(Duration.ofDays(1)).minusSeconds(1), issuedAt.minusSeconds(1));
        String before = invitationRow(expired.id());
        assertThat(before).as("만료된 초대 행이 있어야 없음 확인이 의미 있다").isNotNull();

        assertThat(list(owner).size()).as("만료된 초대만 있는 목록").isZero();

        ResponseOrderSecurityTest.expectExactProblem(revoke(owner, expired.id()), 409, INVITATION_NOT_PENDING);
        assertThat(invitationRow(expired.id())).isEqualTo(before);

        String token = households.invite(owner, 3);
        Row issued = pendingRow(household, token);
        assertThat(issued.id()).isNotEqualTo(expired.id());
        JsonNode invitations = list(owner);
        assertThat(invitations.size()).isEqualTo(1);
        assertThat(invitations.get(0).path("id").asLong()).isEqualTo(issued.id());
    }

    @Test
    @DisplayName("T028 B8 FR-007 경계 짝: 만료 시각 정각인 초대는 아직 대기라 목록에 있고 새 발급이 409 INVITATION_PENDING이며 취소가 204")
    void invitationAtExpiryIsStillPending() throws Exception {
        // SQL로 넣는 expires_at이 반올림되지 않게 마이크로초 단위 시각을 쓴다(현재 시각과 정확히 같아야 정각이다)
        Instant now = issuedAt.truncatedTo(ChronoUnit.MICROS);
        clock.freezeAt(now);
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        InsertedInvitation edge = households.insertInvitation(household, owner.userId(), "대기", now.minus(Duration.ofDays(1)), now);

        JsonNode invitations = list(owner);
        assertThat(invitations.size()).isEqualTo(1);
        assertThat(invitations.get(0).path("id").asLong()).isEqualTo(edge.id());

        ResponseOrderSecurityTest.expectExactProblem(
                mvc.perform(write(post("/api/v1/household/invitations"), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInDays\": 3}")), 409, INVITATION_PENDING);
        assertThat(invitationCount(household)).isEqualTo(1);

        revoke(owner, edge.id()).andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(204));
        assertThat(jdbc.sql("select status from invitation where id = :id").param("id", edge.id()).query(String.class).single())
                .isEqualTo("취소");
    }

    @Test
    @DisplayName("T028 B8 R11 FR-010: 만료 시각 100나노초 뒤에는 목록이 빈 배열이고 새 발급이 201이다(드라이버가 반올림한 :now로 대기를 판정하지 않음)")
    void justAfterExpiryListEmptyIssueOk() throws Exception {
        JustExpired fixture = justAfterExpiry();
        Person owner = fixture.owner();
        long household = fixture.household();
        InsertedInvitation edge = fixture.invitation();
        String before = invitationRow(edge.id());

        assertThat(list(owner).size()).as("만료 100나노초 뒤 목록").isZero();

        // 목록(읽기만 함) 뒤에 발급한다. 발급이 행을 더하므로 목록보다 먼저 두지 않는다
        mvc.perform(write(post("/api/v1/household/invitations"), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInDays\": 2}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).as("만료 100나노초 뒤 새 발급").isEqualTo(201));
        assertThat(invitationCount(household)).isEqualTo(2);
        assertThat(invitationRow(edge.id())).as("만료된 초대 행은 그대로").isEqualTo(before);
    }

    @Test
    @DisplayName("T028 B8 R11: 만료 시각 100나노초 뒤에는 취소가 409 INVITATION_NOT_PENDING, 수락이 409 INVITATION_EXPIRED이고 초대 행과 멤버십이 그대로다")
    void justAfterExpiryRevokeAcceptRejected() throws Exception {
        JustExpired fixture = justAfterExpiry();
        Person owner = fixture.owner();
        InsertedInvitation edge = fixture.invitation();
        Person guest = households.newPerson();
        String before = invitationRow(edge.id());

        // 취소를 먼저 둔다. 수락이 잘못 성공하면 초대가 수락이 되어 취소가 원래 이유와 상관없이 409가 되기 때문이다
        ResponseOrderSecurityTest.expectExactProblem(revoke(owner, edge.id()), 409, INVITATION_NOT_PENDING);
        assertThat(invitationRow(edge.id())).isEqualTo(before);

        ResponseOrderSecurityTest.expectExactProblem(accept(guest, edge.token()), 409, INVITATION_EXPIRED);
        assertThat(invitationRow(edge.id())).isEqualTo(before);
        assertThat(membershipCount(guest)).isZero();
    }

    /** justAfterExpiry가 만든 owner, 가구, 초대. */
    record JustExpired(Person owner, long household, InsertedInvitation invitation) {
    }

    /**
     * 마이크로초에 맞춘 시각을 expires_at으로 하는 대기 초대를 넣고, 시계를 DB의 expires_at 더하기 100나노초에 멈춘다.
     * 로그인도 그 시각에 해서 발급의 재인증(5분)을 통과한다. 드라이버는 100나노초를 버리고 보내므로 SQL로만 판정하면 대기로 보인다.
     */
    private JustExpired justAfterExpiry() throws Exception {
        Instant expiresAt = issuedAt.truncatedTo(ChronoUnit.MICROS);
        clock.freezeAt(expiresAt.plusNanos(100));
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        InsertedInvitation invitation = households.insertInvitation(household, owner.userId(), "대기", expiresAt.minus(Duration.ofDays(1)), expiresAt);
        Instant stored = jdbc.sql("select expires_at from invitation where id = :id").param("id", invitation.id())
                .query((rs, i) -> rs.getTimestamp("expires_at").toInstant()).single();
        assertThat(stored).as("DB의 expires_at이 넣은 값과 같아야 100나노초 뒤가 정확하다").isEqualTo(expiresAt);
        clock.freezeAt(stored.plusNanos(100));
        return new JustExpired(owner, household, invitation);
    }

    /** issuedAt에 멈춘 채 새 owner가 가구를 만들고 N일 초대를 발급한다. 저장된 행을 돌려준다. */
    private Row issue(int days) throws Exception {
        clock.freezeAt(issuedAt);
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        owners.put(household, owner);
        return pendingRow(household, households.invite(owner, days));
    }

    private Person ownerOf(Row row) {
        return owners.get(row.householdId());
    }

    private Row pendingRow(long household, String token) {
        // 만료된 초대도 상태는 대기로 남으므로(research R9, R11) 토큰 해시로 고른다
        return jdbc.sql("select id, created_at, expires_at from invitation where household_id = :h and status = '대기' and token_hash = sha256(convert_to(:t, 'UTF8'))")
                .param("h", household).param("t", token)
                .query((rs, i) -> new Row(rs.getLong("id"), household, token,
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant()))
                .single();
    }

    private String status(Row row) {
        return jdbc.sql("select status from invitation where id = :id").param("id", row.id()).query(String.class).single();
    }

    /** 초대 행 전체(상태, 시각). 없으면 null. */
    private String invitationRow(long id) {
        return jdbc.sql("select status || '|' || created_at || '|' || expires_at || '|' || created_by from invitation where id = :id")
                .param("id", id).query(String.class).optional().orElse(null);
    }

    private long invitationCount(long household) {
        return jdbc.sql("select count(*) from invitation where household_id = :h").param("h", household).query(Long.class).single();
    }

    private String memberRole(Person person, long household) {
        return jdbc.sql("select role from household_member where user_id = :u and household_id = :h")
                .param("u", person.userId()).param("h", household).query(String.class).optional().orElse(null);
    }

    private long membershipCount(Person person) {
        return jdbc.sql("select count(*) from household_member where user_id = :u").param("u", person.userId()).query(Long.class).single();
    }

    private ResultActions accept(Person person, String token) throws Exception {
        return mvc.perform(write(post("/api/v1/invitations/accept"), person).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\"}"));
    }

    private ResultActions revoke(Person owner, long id) throws Exception {
        return mvc.perform(write(delete("/api/v1/household/invitations/" + id), owner));
    }

    /** 보낸 초대 목록의 invitations 배열(200 기대). */
    private JsonNode list(Person owner) throws Exception {
        var response = mvc.perform(https(get("/api/v1/household/invitations")).cookie(owner.cookie())).andReturn().getResponse();
        assertThat(response.getStatus()).as("보낸 초대 목록").isEqualTo(200);
        JsonNode invitations = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("invitations");
        assertThat(invitations.isArray()).as("invitations 배열").isTrue();
        return invitations;
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, Person person) {
        return https(builder).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie());
    }
}
