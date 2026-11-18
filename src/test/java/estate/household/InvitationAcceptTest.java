package estate.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import estate.support.GoogleAccount;
import estate.support.Households;
import estate.support.Households.Person;
import estate.support.Households.World;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T037 US2: 초대 확인(POST /api/v1/invitations/preview)과 수락(POST /api/v1/invitations/accept)의 동작(FR-001, FR-012~FR-015, research R8).
 * 사유 순서는 R8 순서도를 따르고, 거절은 초대와 멤버십을 바꾸지 않아야 한다. 나가기와 가구 삭제 뒤의 수락(US2-13 둘째 문장)은 T055, T069가 맡는다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class InvitationAcceptTest {

    private static final String ORIGIN = "https://localhost";
    private static final String PREVIEW = "/api/v1/invitations/preview";
    private static final String ACCEPT = "/api/v1/invitations/accept";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;
    @Autowired
    MutableClock clock;

    LoginClient login;
    Households households;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
        households = new Households(mvc, jdbc, sessions);
    }

    @Test
    @DisplayName("T037 US2-9 US2-10 FR-012: 초대 확인은 발급한 owner의 현재 표시 이름 하나만 반환하고, 두 번 호출해도 결과가 같고 초대와 멤버십이 바뀌지 않는다")
    void previewReturnsCurrentInviterName() throws Exception {
        Person owner = loginAs(account().withName("가입 때 이름"));
        households.createHousehold(owner);
        String token = households.invite(owner, 3);
        jdbc.sql("update \"user\" set display_name = :n where id = :u").param("n", "바뀐 이름").param("u", owner.userId()).update();
        Person loner = households.newPerson();
        List<Map<String, Object>> invitations = invitationRows();
        List<Map<String, Object>> members = memberRows();

        String first = preview(loner, token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second = preview(loner, token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode body = JSON.readTree(first);
        assertThat(body.propertyNames()).as("owner의 이메일과 구성원 목록이 없다").containsExactly("inviterDisplayName");
        assertThat(body.path("inviterDisplayName").stringValue()).isEqualTo("바뀐 이름");
        assertThat(JSON.readTree(second)).isEqualTo(body);
        assertThat(invitationRows()).isEqualTo(invitations);
        assertThat(memberRows()).isEqualTo(members);
    }

    @Test
    @DisplayName("T037 US2-9 FR-012: owner의 표시 이름이 비어 있으면 초대 확인의 inviterDisplayName은 키는 있고 값이 null이다")
    void previewNameIsNullWhenEmpty() throws Exception {
        Person owner = loginAs(account().withoutName());
        households.createHousehold(owner);
        String token = households.invite(owner, 3);
        Person loner = households.newPerson();

        String text = preview(loner, token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode body = JSON.readTree(text);
        assertThat(body.propertyNames()).containsExactly("inviterDisplayName");
        assertThat(body.path("inviterDisplayName").isNull()).isTrue();
    }

    @Test
    @DisplayName("T037 US2-10 US2-17 SC-002 FR-001: 수락하면 204이고 member 멤버십이 생기며 joined_at은 Clock 현재 시각이고 초대가 수락이며 양쪽 목록에 수락한 사람의 표시 이름과 이메일이 보인다")
    void acceptCreatesMember() throws Exception {
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(654_321_000);
        clock.freezeAt(start);
        GoogleAccount ownerAccount = account().withName("가구장");
        GoogleAccount joinerAccount = account().withName("새 가족");
        Person owner = loginAs(ownerAccount);
        long household = households.createHousehold(owner);
        String token = households.invite(owner, 3);
        Person joiner = loginAs(joinerAccount);
        clock.advance(Duration.ofMinutes(2));

        byte[] body = accept(joiner, token).andExpect(status().isNoContent()).andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEmpty();
        Map<String, Object> row = jdbc.sql("select household_id, role, joined_at from household_member where user_id = :u")
                .param("u", joiner.userId()).query().singleRow();
        assertThat(row.get("household_id")).isEqualTo(household);
        assertThat(row.get("role")).isEqualTo("member");
        assertThat(((Timestamp) row.get("joined_at")).toInstant()).isEqualTo(start.plus(Duration.ofMinutes(2)));
        assertThat(jdbc.sql("select status from invitation").query(String.class).single()).isEqualTo("수락");
        assertThat(jdbc.sql("select role from household_member where user_id = :u").param("u", owner.userId()).query(String.class).single())
                .isEqualTo("owner");
        for (Person requester : new Person[] {owner, joiner}) {
            String text = mvc.perform(https(get("/api/v1/household")).cookie(requester.cookie())).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode members = JSON.readTree(text).path("members");
            assertThat(members.size()).isEqualTo(2);
            assertThat(members.path(0).path("role").stringValue()).isEqualTo("owner");
            assertThat(members.path(1).path("userId").longValue()).isEqualTo(joiner.userId());
            assertThat(members.path(1).path("role").stringValue()).isEqualTo("member");
            assertThat(members.path(1).path("displayName").stringValue()).isEqualTo("새 가족");
            assertThat(members.path(1).path("loginEmail").stringValue()).isEqualTo(joinerAccount.email());
        }
    }

    @Test
    @DisplayName("T037 spec Edge Cases: email_verified=false 계정도 수락되고 owner의 목록에 emailVerified false로 나온다")
    void unverifiedEmailCanAccept() throws Exception {
        Person owner = households.newPerson();
        households.createHousehold(owner);
        String token = households.invite(owner, 3);
        Person joiner = loginAs(account().withEmailVerified(false));

        accept(joiner, token).andExpect(status().isNoContent());

        String text = mvc.perform(https(get("/api/v1/household")).cookie(owner.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode member = JSON.readTree(text).path("members").path(1);
        assertThat(member.path("userId").longValue()).isEqualTo(joiner.userId());
        assertThat(member.path("emailVerified").booleanValue()).isFalse();
    }

    @Test
    @DisplayName("T037 US2-12 R8: 없는 토큰은 확인과 수락 모두 404 NOT_FOUND이고 아무것도 바뀌지 않는다")
    void unknownTokenIsNotFound() throws Exception {
        World world = households.world();
        households.invite(world.ownerB(), 3);
        List<Map<String, Object>> invitations = invitationRows();
        List<Map<String, Object>> members = memberRows();

        for (String path : new String[] {PREVIEW, ACCEPT}) {
            MvcResult result = send(path, world.loner(), randomToken()).andExpect(status().isNotFound()).andReturn();
            assertThat(JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("code").stringValue())
                    .isEqualTo("NOT_FOUND");
        }

        assertThat(invitationRows()).isEqualTo(invitations);
        assertThat(memberRows()).isEqualTo(members);
    }

    @Test
    @DisplayName("T037 US2-12 US2-14 FR-013 R8: 사유는 취소, 사용됨, 만료, 구성원 둘의 순서이고 여러 사유에 걸리면 앞의 것이다. 다른 가구에 속한 사람에게도 같다")
    void linkReasonsFollowOrder() throws Exception {
        World world = households.world();
        long household = world.householdA();
        long creator = world.ownerA().userId();
        Instant now = Instant.now();
        Instant past = now.minus(Duration.ofDays(2));
        Instant expired = now.minus(Duration.ofDays(1));
        Instant later = now.plus(Duration.ofDays(2));
        String revoked = households.insertInvitation(household, creator, "취소", past, expired).token();
        String used = households.insertInvitation(household, creator, "수락", past, expired).token();
        String expiredPending = households.insertInvitation(household, creator, "대기", past, expired).token();
        String full = households.insertInvitation(household, creator, "대기", now, later).token();

        for (Person opener : new Person[] {world.ownerB(), world.loner()}) {
            assertRejected(opener, revoked, "INVITATION_REVOKED");
            assertRejected(opener, used, "INVITATION_USED");
            assertRejected(opener, expiredPending, "INVITATION_EXPIRED");
            assertRejected(opener, full, "HOUSEHOLD_FULL");
        }
        assertRejected(world.memberA(), full, "HOUSEHOLD_FULL");
        assertRejected(world.ownerA(), full, "HOUSEHOLD_FULL");
    }

    @Test
    @DisplayName("T037 US2-13 FR-014: 다른 가구에 속한 사람이 유효한 링크를 열면 확인과 수락 모두 409 HAS_HOUSEHOLD이고 초대는 대기로 남고 멤버십이 그대로다")
    void otherHouseholdKeepsInvitationPending() throws Exception {
        Person ownerA = households.newPerson();
        households.createHousehold(ownerA);
        Person ownerB = households.newPerson();
        households.createHousehold(ownerB);
        String token = households.invite(ownerA, 3);

        assertRejected(ownerB, token, "HAS_HOUSEHOLD");

        assertThat(jdbc.sql("select status from invitation").query(String.class).single()).isEqualTo("대기");
        assertThat(memberRows()).hasSize(2);
    }

    @Test
    @DisplayName("T037 US2-14 FR-013: 다른 가구에 속한 사람이 만료되었거나 취소된 링크를 열면 HAS_HOUSEHOLD가 아니라 INVITATION_EXPIRED나 INVITATION_REVOKED이다")
    void expiredOrRevokedBeatsHasHousehold() throws Exception {
        Person ownerA = households.newPerson();
        long household = households.createHousehold(ownerA);
        Person ownerB = households.newPerson();
        households.createHousehold(ownerB);
        Instant now = Instant.now();
        String expired = households.insertInvitation(household, ownerA.userId(), "대기", now.minus(Duration.ofDays(2)), now.minus(Duration.ofSeconds(1))).token();
        String revoked = households.insertInvitation(household, ownerA.userId(), "취소", now, now.plus(Duration.ofDays(2))).token();

        assertRejected(ownerB, expired, "INVITATION_EXPIRED");
        assertRejected(ownerB, revoked, "INVITATION_REVOKED");
    }

    @Test
    @DisplayName("T037 US2-15: owner가 자기 가구의 유효한 링크를 열면 확인과 수락 모두 409 ALREADY_IN_THIS_HOUSEHOLD이고 초대는 대기로 남는다")
    void ownerOpeningOwnLinkIsRejected() throws Exception {
        Person owner = households.newPerson();
        households.createHousehold(owner);
        String token = households.invite(owner, 3);

        assertRejected(owner, token, "ALREADY_IN_THIS_HOUSEHOLD");

        assertThat(jdbc.sql("select status from invitation").query(String.class).single()).isEqualTo("대기");
    }

    @Test
    @DisplayName("T037 US2-12: 확인 화면을 연 뒤 초대가 취소되면 수락은 409 INVITATION_REVOKED이고 멤버십이 없다")
    void revokedAfterPreviewIsRejected() throws Exception {
        Person owner = households.newPerson();
        households.createHousehold(owner);
        String token = households.invite(owner, 3);
        Person loner = households.newPerson();
        preview(loner, token).andExpect(status().isOk());
        long id = jdbc.sql("select id from invitation").query(Long.class).single();
        mvc.perform(write(delete("/api/v1/household/invitations/" + id), owner)).andExpect(status().isNoContent());

        assertRejected(loner, token, "INVITATION_REVOKED");

        assertThat(jdbc.sql("select count(*) from household_member where user_id = :u").param("u", loner.userId()).query(Long.class).single())
                .isZero();
    }

    @Test
    @DisplayName("T037 US2-12: 두 사람이 확인 화면을 연 뒤 한 사람이 수락하면 다른 사람의 수락은 409 INVITATION_USED이고 구성원이 둘이다")
    void usedAfterPreviewIsRejected() throws Exception {
        Person owner = households.newPerson();
        households.createHousehold(owner);
        String token = households.invite(owner, 3);
        Person first = households.newPerson();
        Person second = households.newPerson();
        preview(first, token).andExpect(status().isOk());
        preview(second, token).andExpect(status().isOk());
        accept(first, token).andExpect(status().isNoContent());

        assertRejected(second, token, "INVITATION_USED");

        assertThat(memberRows()).hasSize(2);
    }

    /** 확인과 수락이 모두 409와 같은 code이고, 본문 키가 Problem의 다섯 개뿐(그 가구의 구성원 정보가 없음)이며, 초대와 멤버십이 바뀌지 않는다. */
    private void assertRejected(Person person, String token, String code) throws Exception {
        List<Map<String, Object>> invitations = invitationRows();
        List<Map<String, Object>> members = memberRows();
        for (String path : new String[] {PREVIEW, ACCEPT}) {
            MvcResult result = send(path, person, token).andExpect(status().isConflict()).andReturn();
            JsonNode body = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(body.path("code").stringValue()).as(path + " " + code).isEqualTo(code);
            assertThat(body.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "detail", "code");
        }
        assertThat(invitationRows()).as(code + " 뒤 초대").isEqualTo(invitations);
        assertThat(memberRows()).as(code + " 뒤 멤버십").isEqualTo(members);
    }

    private ResultActions preview(Person person, String token) throws Exception {
        return send(PREVIEW, person, token);
    }

    private ResultActions accept(Person person, String token) throws Exception {
        return send(ACCEPT, person, token);
    }

    private ResultActions send(String path, Person person, String token) throws Exception {
        return mvc.perform(write(post(path), person).contentType(MediaType.APPLICATION_JSON).content("{\"token\": \"" + token + "\"}"));
    }

    private List<Map<String, Object>> invitationRows() {
        return jdbc.sql("select id, household_id, created_by, status, expires_at, created_at from invitation order by id")
                .query().listOfRows();
    }

    private List<Map<String, Object>> memberRows() {
        return jdbc.sql("select user_id, household_id, role, joined_at from household_member order by user_id").query().listOfRows();
    }

    private Person loginAs(GoogleAccount account) throws Exception {
        Cookie cookie = login.login(account).requireSessionCookie();
        return new Person(cookie, Long.parseLong(sessions.rowOf(SessionRows.idOfCookie(cookie.getValue())).principalName()));
    }

    private static String randomToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static GoogleAccount account() {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("invitation-accept-" + id, "invitation-accept-" + id + "@example.com");
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
