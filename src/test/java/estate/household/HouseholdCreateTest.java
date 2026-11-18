package estate.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import estate.support.GoogleAccount;
import estate.support.Households;
import estate.support.Households.Person;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T019 US1: 가구 만들기(POST /api/v1/household)와 함께 보는 사람 목록(GET /api/v1/household)의 동작(FR-001, US1-2, US1-7, spec Edge Cases).
 * 목록의 구성원은 계약의 Member 스키마대로 키 여섯 개와 JSON 타입까지 확인한다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class HouseholdCreateTest {

    static final String ORIGIN = "https://localhost";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> MEMBER_KEYS = Set.of("userId", "displayName", "role", "loginEmail", "emailVerified", "me");

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
    @DisplayName("T019 US1-2 FR-001: 가구 만들기는 201이고 본문이 없으며, 가구 하나와 owner 멤버십이 생기고 joined_at은 Clock 현재 시각이다")
    void createMakesOwnerMembershipAtClockTime() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.freezeAt(now);
        GoogleAccount account = account();
        Person person = loginAs(account);
        assertThat(householdCount()).isZero();

        MvcResult result = mvc.perform(https(post("/api/v1/household")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie()))
                .andExpect(status().isCreated()).andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).as("201 본문이 없다").isEmpty();
        assertThat(householdCount()).isEqualTo(1);
        var row = jdbc.sql("select household_id, role, joined_at from household_member where user_id = :u")
                .param("u", person.userId()).query().singleRow();
        assertThat(row.get("household_id")).isEqualTo(jdbc.sql("select id from household").query(Long.class).single());
        assertThat(row.get("role")).isEqualTo("owner");
        assertThat(((Timestamp) row.get("joined_at")).toInstant()).isEqualTo(now);
    }

    @Test
    @DisplayName("T019 US1-2 FR-001: 가구를 만든 뒤 함께 보는 사람 목록에 본인 한 명이 owner, me true, 로그인 이메일, emailVerified, 표시 이름으로 나오고 키와 타입이 계약대로다")
    void listShowsOnlyMeAsOwner() throws Exception {
        GoogleAccount account = account().withName("가구 만든 사람");
        Person person = loginAs(account);
        households.createHousehold(person);

        JsonNode member = onlyMember(person);

        assertThat(member.path("userId").longValue()).isEqualTo(person.userId());
        assertThat(member.path("displayName").isString()).isTrue();
        assertThat(member.path("displayName").stringValue()).isEqualTo("가구 만든 사람");
        assertThat(member.path("role").stringValue()).isEqualTo("owner");
        assertThat(member.path("loginEmail").stringValue()).as("복호화한 로그인 이메일").isEqualTo(account.email());
        assertThat(member.path("emailVerified").booleanValue()).isTrue();
        assertThat(member.path("me").booleanValue()).isTrue();
    }

    @Test
    @DisplayName("T019 US1-2 FR-016(목록) contracts Member.me: 구성원이 둘이면 owner, member 순서이고 항목마다 그 사람의 프로필이며 me는 요청한 사람의 항목에서만 true다")
    void listOrdersOwnerFirstWithOwnProfilesAndMe() throws Exception {
        GoogleAccount ownerAccount = account().withName("가구장");
        GoogleAccount memberAccount = account().withName("구성원");
        // member가 사용자 번호도 작고 행도 먼저 들어가, 기본 키 순서로 읽든 저장 순서로 읽든 정렬이 없으면 member가 먼저 나온다
        Person member = loginAs(memberAccount);
        Person owner = loginAs(ownerAccount);
        long household = jdbc.sql("select nextval('household_id_seq')").query(Long.class).single();
        jdbc.sql("insert into household (id) values (:h)").param("h", household).update();
        jdbc.sql("insert into household_member (household_id, user_id, role, joined_at) values (:h, :u, 'member', now())")
                .param("h", household).param("u", member.userId()).update();
        jdbc.sql("insert into household_member (household_id, user_id, role, joined_at) values (:h, :u, 'owner', now())")
                .param("h", household).param("u", owner.userId()).update();

        for (Person requester : new Person[] {owner, member}) {
            String body = mvc.perform(https(get("/api/v1/household")).cookie(requester.cookie()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode members = JSON.readTree(body).path("members");
            assertThat(members.size()).isEqualTo(2);
            assertMember(members.path(0), owner.userId(), "가구장", "owner", ownerAccount.email(), requester == owner);
            assertMember(members.path(1), member.userId(), "구성원", "member", memberAccount.email(), requester == member);
        }
    }

    private static void assertMember(JsonNode node, long userId, String name, String role, String email, boolean me) {
        assertThat(node.path("userId").longValue()).isEqualTo(userId);
        assertThat(node.path("displayName").stringValue()).isEqualTo(name);
        assertThat(node.path("role").stringValue()).isEqualTo(role);
        assertThat(node.path("loginEmail").stringValue()).isEqualTo(email);
        assertThat(node.path("me").booleanValue()).isEqualTo(me);
    }

    @Test
    @DisplayName("T019 US1-7 FR-001: 가구를 만들지 않고 로그아웃과 로그인을 세 번 반복해도 가구 행과 멤버십 행이 없고, 그 뒤 가구 만들기를 하면 생긴다")
    void repeatedLoginDoesNotCreateHousehold() throws Exception {
        GoogleAccount account = account();
        long userId = 0;
        for (int i = 0; i < 3; i++) {
            Person person = loginAs(account);
            userId = person.userId();
            mvc.perform(https(post("/api/v1/logout")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie()))
                    .andExpect(status().isNoContent());
        }

        assertThat(jdbc.sql("select count(*) from \"user\" where provider = 'google' and provider_subject = :s")
                .param("s", account.sub()).query(Long.class).single()).as("사용자 행은 하나 있다").isEqualTo(1);
        assertThat(householdCount()).isZero();
        assertThat(jdbc.sql("select count(*) from household_member").query(Long.class).single()).isZero();

        // 짝: 같은 사람이 가구 만들기를 하면 가구와 멤버십이 생긴다
        Person person = loginAs(account);
        assertThat(person.userId()).isEqualTo(userId);
        households.createHousehold(person);
        assertThat(householdCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T019 spec Edge Cases FR-001: email_verified=false 계정도 가구를 만들고 목록에 emailVerified false로 나온다")
    void unverifiedEmailCanCreate() throws Exception {
        GoogleAccount account = account().withEmailVerified(false);
        Person person = loginAs(account);
        assertThat(jdbc.sql("select email_verified from \"user\" where id = :u").param("u", person.userId()).query(Boolean.class).single())
                .isFalse();

        households.createHousehold(person);

        JsonNode member = onlyMember(person);
        assertThat(member.path("emailVerified").booleanValue()).isFalse();
        assertThat(member.path("loginEmail").stringValue()).isEqualTo(account.email());
    }

    @Test
    @DisplayName("T019 US1-2: 구글이 이름을 보내지 않은 계정은 목록의 displayName이 null이다(키는 있음)")
    void missingGoogleNameIsNull() throws Exception {
        Person person = loginAs(account().withoutName());

        households.createHousehold(person);

        JsonNode member = onlyMember(person);
        assertThat(member.has("displayName")).isTrue();
        assertThat(member.path("displayName").isNull()).isTrue();
    }

    /** 200 application/json이고 members가 정확히 한 명이며 그 구성원의 키와 JSON 타입이 계약의 Member와 같다. */
    private JsonNode onlyMember(Person person) throws Exception {
        String body = mvc.perform(https(get("/api/v1/household")).cookie(person.cookie()))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = JSON.readTree(body);
        assertThat(root.propertyNames()).containsExactly("members");
        assertThat(root.path("members").isArray()).isTrue();
        assertThat(root.path("members").size()).isEqualTo(1);
        JsonNode member = root.path("members").path(0);
        assertThat(member.propertyNames()).as("구글 sub, 해시 같은 다른 키가 없다").containsExactlyInAnyOrderElementsOf(MEMBER_KEYS);
        assertThat(member.path("userId").isIntegralNumber()).as("userId는 정수").isTrue();
        assertThat(member.path("displayName").isString() || member.path("displayName").isNull()).as("displayName은 문자열 또는 null").isTrue();
        assertThat(member.path("role").isString()).as("role은 문자열").isTrue();
        assertThat(member.path("loginEmail").isString()).as("loginEmail은 문자열").isTrue();
        assertThat(member.path("emailVerified").isBoolean()).as("emailVerified는 불리언").isTrue();
        assertThat(member.path("me").isBoolean()).as("me는 불리언").isTrue();
        return member;
    }

    private Person loginAs(GoogleAccount account) throws Exception {
        Cookie cookie = login.login(account).requireSessionCookie();
        return new Person(cookie, Long.parseLong(sessions.rowOf(SessionRows.idOfCookie(cookie.getValue())).principalName()));
    }

    private long householdCount() {
        return jdbc.sql("select count(*) from household").query(Long.class).single();
    }

    private static GoogleAccount account() {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("household-create-" + id, "household-create-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
