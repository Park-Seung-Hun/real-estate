package estate.household.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import estate.household.authz.Access;
import estate.household.authz.HouseholdMembership;
import estate.household.authz.MembershipRule;
import estate.household.authz.Permission;
import estate.household.domain.HouseholdRole;
import estate.support.Households;
import estate.support.Households.Person;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;
import tools.jackson.databind.JsonNode;

/**
 * T009 B2(공통 규칙): 권한 확인 2, 3단계(research R2 멤버십 규칙 표, API 공통 규칙 인증과 권한 확인, FR-023, FR-026)를
 * 테스트 전용 컨트롤러로 확인한다. 멤버십은 SQL로 넣고, 사용자는 가짜 구글 로그인으로 만든다.
 * 가구 A(ownerA, memberA), 가구 B(ownerB), 가구 없는 사람(loner).
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class AccessInterceptorSecurityTest {

    static final String ORIGIN = "https://localhost";
    static final String BROKEN_JSON = "{\"x\": ";

    /** 인자 해석기가 넘긴 멤버십. 없으면 present=false. */
    record Seen(boolean present, long householdId, long userId, String role) {
        static Seen of(HouseholdMembership m) {
            return m == null ? new Seen(false, 0, 0, null) : new Seen(true, m.householdId(), m.userId(), m.role().dbValue());
        }
    }

    // 중첩 @TestConfiguration은 이 클래스의 컨텍스트에만 더해지고 컨텍스트 캐시 키에 들어가므로 이 설정이 없는 클래스(T008)는 다른 컨텍스트를 쓴다.
    // 아래 컨트롤러는 테스트 클래스 안의 중첩 클래스라 TestTypeExcludeFilter가 모든 테스트 컨텍스트의 컴포넌트 스캔에서 빼고, 이 설정의 @Import로만 등록된다
    @TestConfiguration(proxyBeanMethods = false)
    @Import(TestOnlyController.class)
    static class TestOnlyApi {
    }

    @RestController
    static class TestOnlyController {

        @GetMapping("/api/test-only/required")
        @Access(Permission.HOUSEHOLD_READ)
        Seen required(HouseholdMembership membership) {
            return Seen.of(membership);
        }

        @GetMapping("/api/test-only/optional")
        @Access(value = Permission.NONE, membership = MembershipRule.OPTIONAL)
        Seen optional(HouseholdMembership membership) {
            return Seen.of(membership);
        }

        /** owner만 가진 키이고 멤버십 없이도 허용한다. */
        @GetMapping("/api/test-only/optional-owner-key")
        @Access(value = Permission.INVITATION_READ, membership = MembershipRule.OPTIONAL)
        Seen optionalOwnerKey(HouseholdMembership membership) {
            return Seen.of(membership);
        }

        @PostMapping("/api/test-only/absent")
        @Access(value = Permission.NONE, membership = MembershipRule.ABSENT)
        Seen absent() {
            return Seen.of(null);
        }

        @GetMapping("/api/test-only/none")
        @Access(Permission.NONE)
        Seen none(HouseholdMembership membership) {
            return Seen.of(membership);
        }

        /** owner만 가진 키. */
        @GetMapping("/api/test-only/owner-key")
        @Access(Permission.INVITATION_READ)
        Seen ownerKey(HouseholdMembership membership) {
            return Seen.of(membership);
        }

        /** member만 가진 키. */
        @GetMapping("/api/test-only/member-key")
        @Access(Permission.MEMBER_LEAVE)
        Seen memberKey(HouseholdMembership membership) {
            return Seen.of(membership);
        }

        /** owner만 가진 키이고 본문을 Spring이 읽는다. */
        @PostMapping("/api/test-only/owner-key-body")
        @Access(Permission.INVITATION_CREATE)
        Seen ownerKeyBody(HouseholdMembership membership, @RequestBody JsonNode body) {
            return Seen.of(membership);
        }

        @GetMapping("/api/test-only/unmarked")
        Seen unmarked() {
            return Seen.of(null);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;

    long householdA;
    long householdB;
    Person ownerA;
    Person memberA;
    Person ownerB;
    Person loner;

    @BeforeEach
    void setUp() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        ownerA = households.newPerson();
        memberA = households.newPerson();
        ownerB = households.newPerson();
        loner = households.newPerson();
        householdA = insertHousehold();
        householdB = insertHousehold();
        insertMember(householdA, ownerA, HouseholdRole.OWNER);
        insertMember(householdA, memberA, HouseholdRole.MEMBER);
        insertMember(householdB, ownerB, HouseholdRole.OWNER);
    }

    @Test
    @DisplayName("T009 B2 research R2 REQUIRED FR-026: 멤버십이 없으면 403 NO_HOUSEHOLD, 같은 핸들러를 멤버십이 있는 사람이 부르면 200")
    void requiredRejectsWithoutMembership() throws Exception {
        expectProblem(mvc.perform(getAs("/api/test-only/required", loner)), 403, "NO_HOUSEHOLD");
        mvc.perform(getAs("/api/test-only/required", ownerA)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T009 B2 research R2 OPTIONAL FR-026: 멤버십이 없어도 200이고 인자는 null, 멤버십이 있으면 200이고 인자에 멤버십이 온다")
    void optionalPassesWithoutMembership() throws Exception {
        mvc.perform(getAs("/api/test-only/optional", loner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false));
        mvc.perform(getAs("/api/test-only/optional", memberA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(true))
                .andExpect(jsonPath("$.householdId").value(householdA))
                .andExpect(jsonPath("$.userId").value(memberA.userId()))
                .andExpect(jsonPath("$.role").value("member"));
    }

    @Test
    @DisplayName("T009 B2 research R2 OPTIONAL: 멤버십이 있으면 3단계를 거쳐 역할에 없는 키는 403 PERMISSION_DENIED, 키가 있는 역할과 멤버십이 없는 사람은 200")
    void optionalWithMembershipStillChecksKey() throws Exception {
        expectProblem(mvc.perform(getAs("/api/test-only/optional-owner-key", memberA)), 403, "PERMISSION_DENIED");
        mvc.perform(getAs("/api/test-only/optional-owner-key", ownerA)).andExpect(status().isOk());
        mvc.perform(getAs("/api/test-only/optional-owner-key", loner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false));
    }

    @Test
    @DisplayName("T009 B2 research R2 ABSENT FR-023 (2): owner와 member는 409 HAS_HOUSEHOLD, 멤버십이 없는 사람은 200")
    void absentRejectsWithMembership() throws Exception {
        expectProblem(mvc.perform(postAs("/api/test-only/absent", ownerA)), 409, "HAS_HOUSEHOLD");
        expectProblem(mvc.perform(postAs("/api/test-only/absent", memberA)), 409, "HAS_HOUSEHOLD");
        mvc.perform(postAs("/api/test-only/absent", loner)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T009 B2 research R2 FR-023 (3) FR-025: 역할에 없는 키는 403 PERMISSION_DENIED(member의 owner 키, owner의 member 키), 키가 있는 역할은 200")
    void roleWithoutKeyIsPermissionDenied() throws Exception {
        expectProblem(mvc.perform(getAs("/api/test-only/owner-key", memberA)), 403, "PERMISSION_DENIED");
        mvc.perform(getAs("/api/test-only/owner-key", ownerA)).andExpect(status().isOk());
        expectProblem(mvc.perform(getAs("/api/test-only/member-key", ownerA)), 403, "PERMISSION_DENIED");
        mvc.perform(getAs("/api/test-only/member-key", memberA)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T009 B2 결정 기록 0011 기본 거부 research R2: @Access가 없는 /api/** 핸들러는 멤버십이 있어도 없어도 403 PERMISSION_DENIED, 표시가 있는 핸들러는 같은 사람에게 200")
    void unmarkedHandlerIsDenied() throws Exception {
        expectProblem(mvc.perform(getAs("/api/test-only/unmarked", ownerA)), 403, "PERMISSION_DENIED");
        expectProblem(mvc.perform(getAs("/api/test-only/unmarked", loner)), 403, "PERMISSION_DENIED");
        mvc.perform(getAs("/api/test-only/required", ownerA)).andExpect(status().isOk());
        mvc.perform(getAs("/api/test-only/optional", loner)).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "T009 B2 FR-023 (1): 로그인 없이 GET {0} 은 401 UNAUTHENTICATED")
    @ValueSource(strings = {"/api/test-only/required", "/api/test-only/optional", "/api/test-only/none", "/api/test-only/owner-key",
            "/api/test-only/member-key", "/api/test-only/unmarked"})
    @DisplayName("T009 B2 FR-023 (1): 로그인 상태가 없으면 멤버십 규칙과 표시와 관계없이 401 UNAUTHENTICATED")
    void getWithoutLoginIsUnauthenticated(String path) throws Exception {
        expectProblem(mvc.perform(https(get(path))), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest(name = "T009 B2 FR-023 (1): 로그인 없이 깨진 JSON의 POST {0} 은 401 UNAUTHENTICATED")
    @ValueSource(strings = {"/api/test-only/absent", "/api/test-only/owner-key-body"})
    @DisplayName("T009 B2 FR-023 (1): 로그인 상태가 없으면 본문이 깨져도 400이 아니라 401 UNAUTHENTICATED")
    void postWithoutLoginIsUnauthenticatedRegardlessOfBody(String path) throws Exception {
        expectProblem(mvc.perform(https(post(path)).header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(BROKEN_JSON)), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("T009 B2 FR-023 (1): 멤버십이 있는 owner도 세션이 만료되면 401 UNAUTHENTICATED, 만료 전 같은 쿠키는 200")
    void expiredSessionWithMembershipIsUnauthenticated() throws Exception {
        mvc.perform(getAs("/api/test-only/required", ownerA)).andExpect(status().isOk());
        sessions.shiftBackBySessionId(SessionRows.idOfCookie(ownerA.cookie().getValue()), Duration.ofDays(31), Duration.ofDays(1),
                Duration.ofDays(1));

        expectProblem(mvc.perform(getAs("/api/test-only/required", ownerA)), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("T009 B2 API 공통 규칙 입력 검증은 권한 확인 뒤: 깨진 JSON이어도 멤버십 없음은 403 NO_HOUSEHOLD, member의 owner 키는 403 PERMISSION_DENIED, 키가 있는 owner는 400")
    void brokenBodyIsRejectedByAccessBeforeBodyIsRead() throws Exception {
        expectProblem(mvc.perform(brokenBodyAs("/api/test-only/owner-key-body", loner)), 403, "NO_HOUSEHOLD");
        expectProblem(mvc.perform(brokenBodyAs("/api/test-only/owner-key-body", memberA)), 403, "PERMISSION_DENIED");
        expectProblem(mvc.perform(brokenBodyAs("/api/test-only/owner-key-body", ownerA)), 400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("T009 B2 API 공통 규칙 권한 확인 3단계: NONE 핸들러는 멤버십이 있으면 owner와 member 모두 3단계를 통과해 200, 멤버십이 없으면 2단계에서 403 NO_HOUSEHOLD")
    void noneKeyPassesStage3OnlyWithMembership() throws Exception {
        mvc.perform(getAs("/api/test-only/none", ownerA)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("owner"));
        mvc.perform(getAs("/api/test-only/none", memberA)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("member"));
        expectProblem(mvc.perform(getAs("/api/test-only/none", loner)), 403, "NO_HOUSEHOLD");
    }

    @Test
    @DisplayName("T009 B2 FR-023 (1)~(3) research R2: 인자 해석기가 넘기는 householdId, userId, role이 요청한 사람의 household_member 행과 같다(가구 둘)")
    void resolvedMembershipMatchesDatabaseRow() throws Exception {
        for (Person person : new Person[] {ownerA, memberA, ownerB}) {
            Row row = jdbc.sql("select household_id, user_id, role from household_member where user_id = :u")
                    .param("u", person.userId()).query(Row.class).single();
            mvc.perform(getAs("/api/test-only/required", person))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.present").value(true))
                    .andExpect(jsonPath("$.householdId").value(row.householdId()))
                    .andExpect(jsonPath("$.userId").value(row.userId()))
                    .andExpect(jsonPath("$.role").value(row.role()));
        }
        assertThat(householdA).as("가구 둘의 번호가 달라야 가구 번호를 구분해 확인한다").isNotEqualTo(householdB);
    }

    record Row(long householdId, long userId, String role) {
    }

    private long insertHousehold() {
        long id = jdbc.sql("select nextval('household_id_seq')").query(Long.class).single();
        jdbc.sql("insert into household (id) values (:id)").param("id", id).update();
        return id;
    }

    private void insertMember(long household, Person person, HouseholdRole role) {
        jdbc.sql("insert into household_member (household_id, user_id, role, joined_at) values (:h, :u, :role, now())")
                .param("h", household).param("u", person.userId()).param("role", role.dbValue()).update();
    }

    private static MockHttpServletRequestBuilder getAs(String path, Person person) {
        return https(get(path)).cookie(person.cookie());
    }

    private static MockHttpServletRequestBuilder postAs(String path, Person person) {
        return https(post(path)).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie());
    }

    private static MockHttpServletRequestBuilder brokenBodyAs(String path, Person person) {
        return postAs(path, person).contentType(MediaType.APPLICATION_JSON).content(BROKEN_JSON);
    }

    static void expectProblem(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
