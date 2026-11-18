package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.https;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

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
import tools.jackson.databind.ObjectMapper;

import estate.household.domain.HouseholdRole;
import estate.support.Households;
import estate.support.Households.Person;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T017 B2(US1 몫): 가구 만들기와 함께 보는 사람 목록의 응답 순서(FR-005, FR-023, FR-026, research R3, R5).
 * 오류 본문은 계약 예시의 type, title, status, detail, code와 키까지 같아야 한다(T022가 실제 서버 본문과 비교하는 기준).
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class ResponseOrderSecurityTest {

    static final String ORIGIN = "https://localhost";
    static final String FOREIGN_ORIGIN = "https://evil.example.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String UNAUTHENTICATED = """
            {"type": "https://example.com/problems/unauthenticated", "title": "로그인이 필요합니다", "status": 401,
             "detail": "다시 로그인해 주세요.", "code": "UNAUTHENTICATED"}""";
    static final String ORIGIN_REJECTED = """
            {"type": "https://example.com/problems/origin-rejected", "title": "허용하지 않은 요청입니다", "status": 403,
             "detail": "서비스 화면에서 다시 시도해 주세요.", "code": "ORIGIN_REJECTED"}""";
    static final String NO_HOUSEHOLD = """
            {"type": "https://example.com/problems/no-household", "title": "가구가 없습니다", "status": 403,
             "detail": "가구를 만들거나 초대 링크로 들어온 뒤 다시 시도해 주세요.", "code": "NO_HOUSEHOLD"}""";
    static final String HAS_HOUSEHOLD = """
            {"type": "https://example.com/problems/has-household", "title": "이미 가구가 있습니다", "status": 409,
             "detail": "가구를 나가거나 삭제한 뒤 다시 시도해 주세요.", "code": "HAS_HOUSEHOLD"}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;

    Households households;

    @BeforeEach
    void setUp() {
        households = new Households(mvc, jdbc, sessions);
    }

    @Test
    @DisplayName("T017 B2 FR-023 US1: 로그인 없는 POST /api/v1/household는 Origin이 맞든 틀리든 401 UNAUTHENTICATED이고 가구가 생기지 않는다")
    void createWithoutLoginIsUnauthenticated() throws Exception {
        expectExactProblem(mvc.perform(https(post("/api/v1/household")).header(HttpHeaders.ORIGIN, ORIGIN)), 401, UNAUTHENTICATED);
        expectExactProblem(mvc.perform(https(post("/api/v1/household")).header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)), 401, UNAUTHENTICATED);
        assertThat(householdCount()).isZero();

        // 짝: 로그인하면 같은 요청이 201
        Person person = households.newPerson();
        create(person, ORIGIN).andExpect(status().isCreated());
        assertThat(householdCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T017 B2 FR-023 US1: 허용 밖 Origin의 POST /api/v1/household는 403 ORIGIN_REJECTED이고 가구와 멤버십이 생기지 않으며, 허용 Origin이면 201")
    void foreignOriginIsRejectedWithoutCreating() throws Exception {
        Person person = households.newPerson();
        assertThat(userCount(person)).as("사용자 행이 있어야 가구 없음이 의미가 있다").isEqualTo(1);

        expectExactProblem(create(person, FOREIGN_ORIGIN), 403, ORIGIN_REJECTED);

        assertThat(householdCount()).isZero();
        assertThat(membershipCount(person)).isZero();

        create(person, ORIGIN).andExpect(status().isCreated());
        assertThat(householdCount()).isEqualTo(1);
        assertThat(membershipCount(person)).isEqualTo(1);
    }

    @Test
    @DisplayName("T017 B2 US1-3 FR-005: 가구가 있는 owner의 POST /api/v1/household는 409 HAS_HOUSEHOLD이고 가구 수와 멤버십이 그대로다(첫 요청은 201)")
    void ownerWithHouseholdGetsConflict() throws Exception {
        Person owner = households.newPerson();
        create(owner, ORIGIN).andExpect(status().isCreated());
        String before = memberships();
        assertThat(householdCount()).isEqualTo(1);

        expectExactProblem(create(owner, ORIGIN), 409, HAS_HOUSEHOLD);

        assertThat(householdCount()).isEqualTo(1);
        assertThat(memberships()).isEqualTo(before);
    }

    @Test
    @DisplayName("T017 B2 US1-3 FR-005: 가구가 있는 member의 POST /api/v1/household도 409 HAS_HOUSEHOLD이고 가구 수와 멤버십이 그대로다")
    void memberWithHouseholdGetsConflict() throws Exception {
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        Person member = households.newPerson();
        // 초대 API(T042) 없이 member 상태를 만든다
        jdbc.sql("insert into household_member (household_id, user_id, role, joined_at) values (:h, :u, :role, now())")
                .param("h", household).param("u", member.userId()).param("role", HouseholdRole.MEMBER.dbValue()).update();
        String before = memberships();

        expectExactProblem(create(member, ORIGIN), 409, HAS_HOUSEHOLD);

        assertThat(householdCount()).isEqualTo(1);
        assertThat(memberships()).isEqualTo(before);
    }

    @Test
    @DisplayName("T017 B2 US1-6 FR-026: 멤버십 없는 사람의 GET /api/v1/household는 403 NO_HOUSEHOLD이고, 가구를 만든 뒤에는 200")
    void listWithoutMembershipIsNoHousehold() throws Exception {
        Person person = households.newPerson();

        expectExactProblem(list(person), 403, NO_HOUSEHOLD);

        households.createHousehold(person);
        list(person).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    /** 상태 코드, problem+json, 본문 JSON이 계약 예시와 키까지 같다. */
    static void expectExactProblem(ResultActions actions, int status, String expectedJson) throws Exception {
        actions.andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        String body = actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JSON.readTree(body)).isEqualTo(JSON.readTree(expectedJson));
    }

    private ResultActions create(Person person, String origin) throws Exception {
        return mvc.perform(https(post("/api/v1/household")).header(HttpHeaders.ORIGIN, origin).cookie(person.cookie()));
    }

    private ResultActions list(Person person) throws Exception {
        return mvc.perform(https(get("/api/v1/household")).cookie(person.cookie()));
    }

    private long householdCount() {
        return jdbc.sql("select count(*) from household").query(Long.class).single();
    }

    private long membershipCount(Person person) {
        return jdbc.sql("select count(*) from household_member where user_id = :u").param("u", person.userId()).query(Long.class).single();
    }

    private long userCount(Person person) {
        return jdbc.sql("select count(*) from \"user\" where id = :u").param("u", person.userId()).query(Long.class).single();
    }

    /** 모든 멤버십 행(가구 번호, 사용자 번호, 역할, 들어온 시각). */
    private String memberships() {
        return String.join(",", jdbc.sql("""
                select household_id || '|' || user_id || '|' || role || '|' || joined_at
                from household_member order by household_id, user_id""").query(String.class).list());
    }
}
