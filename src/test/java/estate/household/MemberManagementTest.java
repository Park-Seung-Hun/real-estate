package estate.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import estate.support.SessionRows;

/**
 * T055 US3: 함께 보는 사람 목록(GET /api/v1/household), 구성원 제거(DELETE /api/v1/household/members/{userId}),
 * 가구 나가기(DELETE /api/v1/household/membership)의 동작(FR-016~FR-018). member는 초대 발급과 수락 API로 만든다.
 * 권한, 응답 순서, 재인증, 다른 가구 404는 security 패키지의 B1~B5, B11 테스트가 맡는다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class MemberManagementTest {

    private static final String ORIGIN = "https://localhost";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;

    LoginClient login;
    Households households;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
        households = new Households(mvc, jdbc, sessions);
    }

    @Test
    @DisplayName("T055 US3-1 FR-016: 목록의 사람마다 userId, 표시 이름, 역할, 로그인 이메일, 검증 여부가 있고 요청한 사람만 me true이며 표시 이름이 없는 사람은 displayName이 null이다")
    void listShowsEachMember() throws Exception {
        GoogleAccount ownerAccount = account().withName("가구장");
        GoogleAccount memberAccount = account().withoutName().withEmailVerified(false);
        Person owner = loginAs(ownerAccount);
        households.createHousehold(owner);
        Person member = loginAs(memberAccount);
        households.accept(member, households.invite(owner, 3));

        for (Person requester : new Person[] {owner, member}) {
            JsonNode members = members(requester);
            assertThat(members.size()).isEqualTo(2);
            JsonNode ownerItem = itemOf(members, owner);
            JsonNode memberItem = itemOf(members, member);
            for (JsonNode item : new JsonNode[] {ownerItem, memberItem}) {
                assertThat(item.propertyNames()).containsAll(List.of("userId", "displayName", "role", "loginEmail", "emailVerified", "me"));
            }
            assertThat(ownerItem.path("displayName").stringValue()).isEqualTo("가구장");
            assertThat(ownerItem.path("role").stringValue()).isEqualTo("owner");
            assertThat(ownerItem.path("loginEmail").stringValue()).isEqualTo(ownerAccount.email());
            assertThat(ownerItem.path("emailVerified").isBoolean()).isTrue();
            assertThat(ownerItem.path("emailVerified").booleanValue()).isTrue();
            assertThat(ownerItem.path("me").booleanValue()).as("요청한 사람만 me").isEqualTo(requester == owner);

            assertThat(memberItem.path("displayName").isNull()).as("표시 이름이 없으면 키는 있고 값이 null").isTrue();
            assertThat(memberItem.path("role").stringValue()).isEqualTo("member");
            assertThat(memberItem.path("loginEmail").stringValue()).isEqualTo(memberAccount.email());
            assertThat(memberItem.path("emailVerified").isBoolean()).isTrue();
            assertThat(memberItem.path("emailVerified").booleanValue()).isFalse();
            assertThat(memberItem.path("me").booleanValue()).as("요청한 사람만 me").isEqualTo(requester == member);
        }
    }

    @Test
    @DisplayName("T055 US3-2 FR-017: owner가 member를 제거하면 확인 단계 없이 요청 한 번에 204이고 본문이 없으며 owner의 목록에 owner만 남는다")
    void ownerRemovesMember() throws Exception {
        World world = households.world();

        byte[] body = remove(world.ownerA(), world.memberA()).andExpect(status().isNoContent()).andReturn().getResponse()
                .getContentAsByteArray();

        assertThat(body).isEmpty();
        assertThat(membershipCount(world.memberA())).isZero();
        assertThat(roleOf(world.ownerA())).isEqualTo("owner");
        JsonNode members = members(world.ownerA());
        assertThat(members.size()).isEqualTo(1);
        assertThat(members.path(0).path("userId").longValue()).isEqualTo(world.ownerA().userId());
    }

    @Test
    @DisplayName("T055 US3-6 FR-018: member가 가구를 나가면 204이고 본문이 없으며 그 사람의 멤버십만 삭제되고 사용자 행과 세션, 가구, owner의 멤버십은 그대로다")
    void memberLeaves() throws Exception {
        World world = households.world();
        List<Map<String, Object>> ownerRow = memberRowsOf(world.ownerA());

        byte[] body = leave(world.memberA()).andExpect(status().isNoContent()).andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEmpty();
        assertThat(membershipCount(world.memberA())).isZero();
        assertThat(memberRowsOf(world.ownerA())).as("owner의 멤버십이 그대로").isEqualTo(ownerRow);
        assertThat(jdbc.sql("select count(*) from household where id = :h").param("h", world.householdA()).query(Long.class).single())
                .as("가구가 남는다").isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from \"user\" where id = :u").param("u", world.memberA().userId()).query(Long.class).single())
                .as("사용자 행이 남는다").isEqualTo(1);
        assertThat(sessions.exists(SessionRows.idOfCookie(world.memberA().cookie().getValue()))).as("세션 행이 남는다").isTrue();
    }

    @Test
    @DisplayName("T055 US3-9: 제거된 사람이 새 초대 링크를 수락하면 204이고 같은 가구의 member가 된다")
    void removedPersonRejoins() throws Exception {
        World world = households.world();
        remove(world.ownerA(), world.memberA()).andExpect(status().isNoContent());

        households.accept(world.memberA(), households.invite(world.ownerA(), 3));

        assertMemberOf(world.memberA(), world.householdA());
    }

    @Test
    @DisplayName("T055 US3-9: 가구를 나간 사람이 새 초대 링크를 수락하면 204이고 같은 가구의 member가 된다")
    void leftPersonRejoins() throws Exception {
        World world = households.world();
        leave(world.memberA()).andExpect(status().isNoContent());

        households.accept(world.memberA(), households.invite(world.ownerA(), 3));

        assertMemberOf(world.memberA(), world.householdA());
    }

    @Test
    @DisplayName("T055 US3-10 spec Edge Cases: 구성원이 둘일 때 409 HOUSEHOLD_FULL이던 owner의 초대 발급이 member를 제거한 직후 201이다")
    void issueAfterRemoval() throws Exception {
        World world = households.world();
        expectIssueFull(world.ownerA());

        remove(world.ownerA(), world.memberA()).andExpect(status().isNoContent());

        issue(world.ownerA()).andExpect(status().isCreated());
        assertThat(pendingCount(world.householdA())).isEqualTo(1);
    }

    @Test
    @DisplayName("T055 US3-10: 구성원이 둘일 때 409 HOUSEHOLD_FULL이던 owner의 초대 발급이 member가 나간 직후 201이다")
    void issueAfterLeave() throws Exception {
        World world = households.world();
        expectIssueFull(world.ownerA());

        leave(world.memberA()).andExpect(status().isNoContent());

        issue(world.ownerA()).andExpect(status().isCreated());
        assertThat(pendingCount(world.householdA())).isEqualTo(1);
    }

    @Test
    @DisplayName("T055 US2-13 둘째 문장: 가구 A의 member에게 409 HAS_HOUSEHOLD이던 가구 B의 대기 링크가 가구 A를 나간 뒤 수락되어 204이고 가구 B의 member가 된다")
    void leaverAcceptsOtherLink() throws Exception {
        World world = households.world();
        String token = households.invite(world.ownerB(), 3);
        String code = JSON.readTree(acceptRaw(world.memberA(), token).andExpect(status().isConflict()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("code").stringValue();
        assertThat(code).isEqualTo("HAS_HOUSEHOLD");
        assertThat(invitationStatusOf(world.householdB())).isEqualTo("대기");

        leave(world.memberA()).andExpect(status().isNoContent());
        acceptRaw(world.memberA(), token).andExpect(status().isNoContent());

        assertMemberOf(world.memberA(), world.householdB());
        assertThat(invitationStatusOf(world.householdB())).isEqualTo("수락");
    }

    private void assertMemberOf(Person person, long household) {
        Map<String, Object> row = jdbc.sql("select household_id, role from household_member where user_id = :u")
                .param("u", person.userId()).query().singleRow();
        assertThat(row.get("household_id")).isEqualTo(household);
        assertThat(row.get("role")).isEqualTo("member");
    }

    private void expectIssueFull(Person owner) throws Exception {
        String text = issue(owner).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JSON.readTree(text).path("code").stringValue()).isEqualTo("HOUSEHOLD_FULL");
    }

    private JsonNode members(Person requester) throws Exception {
        String text = mvc.perform(https(get("/api/v1/household")).cookie(requester.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(text).path("members");
    }

    private static JsonNode itemOf(JsonNode members, Person person) {
        for (JsonNode item : members) {
            if (item.path("userId").longValue() == person.userId()) {
                return item;
            }
        }
        throw new AssertionError("목록에 " + person.userId() + "가 없다: " + members);
    }

    private ResultActions remove(Person owner, Person target) throws Exception {
        return mvc.perform(write(delete("/api/v1/household/members/" + target.userId()), owner));
    }

    private ResultActions leave(Person member) throws Exception {
        return mvc.perform(write(delete("/api/v1/household/membership"), member));
    }

    private ResultActions issue(Person owner) throws Exception {
        return mvc.perform(write(post("/api/v1/household/invitations"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInDays\": 3}"));
    }

    private ResultActions acceptRaw(Person person, String token) throws Exception {
        return mvc.perform(write(post("/api/v1/invitations/accept"), person).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\"}"));
    }

    private long membershipCount(Person person) {
        return jdbc.sql("select count(*) from household_member where user_id = :u").param("u", person.userId()).query(Long.class).single();
    }

    private String roleOf(Person person) {
        return jdbc.sql("select role from household_member where user_id = :u").param("u", person.userId()).query(String.class).single();
    }

    private List<Map<String, Object>> memberRowsOf(Person person) {
        return jdbc.sql("select household_id, user_id, role, joined_at from household_member where user_id = :u")
                .param("u", person.userId()).query().listOfRows();
    }

    private long pendingCount(long household) {
        return jdbc.sql("select count(*) from invitation where household_id = :h and status = '대기'").param("h", household)
                .query(Long.class).single();
    }

    private String invitationStatusOf(long household) {
        return jdbc.sql("select status from invitation where household_id = :h").param("h", household).query(String.class).single();
    }

    private Person loginAs(GoogleAccount account) throws Exception {
        Cookie cookie = login.login(account).requireSessionCookie();
        return new Person(cookie, Long.parseLong(sessions.rowOf(SessionRows.idOfCookie(cookie.getValue())).principalName()));
    }

    private static GoogleAccount account() {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("member-management-" + id, "member-management-" + id + "@example.com");
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
