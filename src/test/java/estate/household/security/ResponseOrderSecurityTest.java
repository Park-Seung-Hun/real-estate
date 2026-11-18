package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.https;
import static org.assertj.core.api.Assertions.assertThat;
import static estate.household.security.HouseholdReauthSecurityTest.REAUTH_REQUIRED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
import estate.support.MutableClock;
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
    static final String PERMISSION_DENIED = """
            {"type": "https://example.com/problems/permission-denied", "title": "할 수 없는 동작입니다", "status": 403,
             "detail": "이 가구에서 할 수 없는 동작입니다.", "code": "PERMISSION_DENIED"}""";
    static final String NOT_FOUND = """
            {"type": "https://example.com/problems/not-found", "title": "찾을 수 없습니다", "status": 404,
             "detail": "주소를 확인해 주세요.", "code": "NOT_FOUND"}""";
    static final String INVITATION_PENDING = """
            {"type": "https://example.com/problems/invitation-pending", "title": "수락 대기 중인 초대가 있습니다", "status": 409,
             "detail": "수락 대기 중인 초대를 취소한 뒤 다시 시도해 주세요.", "code": "INVITATION_PENDING"}""";
    static final String INVITATION_NOT_PENDING = """
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

    @Test
    @DisplayName("T032 B2 US3-11 SC-007 FR-023: 마지막 구글 인증이 오래된 member의 초대 발급은 401이 아니라 403 PERMISSION_DENIED이고 초대가 바뀌지 않는다")
    void staleMemberIssueIsPermissionDenied() throws Exception {
        Instant authAt = frozenNow();
        Households.World world = households.world();
        String before = invitations();
        clock.freezeAt(authAt.plus(Duration.ofMinutes(10)));

        expectExactProblem(issue(world.memberA(), ORIGIN, "{\"expiresInDays\": 3}"), 403, PERMISSION_DENIED);

        assertThat(invitations()).isEqualTo(before);
        // 이 시각에 인증이 실제로 오래되었음을 같은 가구 owner의 발급으로 확인한다(구성원 둘보다 재인증이 먼저)
        expectExactProblem(issue(world.ownerA(), ORIGIN, "{\"expiresInDays\": 3}"), 401, REAUTH_REQUIRED);
    }

    @Test
    @DisplayName("T032 B2 FR-023 R3 R12: 초대 발급은 재인증, 입력 검증, 상태 충돌 순서다. 인증이 오래되면 깨진 JSON도 401, 새로우면 같은 본문이 400, 올바른 본문이면서 대기 초대가 있으면 409")
    void issueChecksReauthThenBodyThenState() throws Exception {
        Instant authAt = frozenNow();
        Person owner = households.newPerson();
        households.createHousehold(owner);
        households.invite(owner, 3);
        String before = invitations();
        String broken = "{\"expiresInDays\": ";

        clock.freezeAt(authAt.plus(Duration.ofMinutes(5)).plusSeconds(1));
        expectExactProblem(issue(owner, ORIGIN, broken), 401, REAUTH_REQUIRED);
        expectExactProblem(issue(owner, ORIGIN, "{\"expiresInDays\": 2}"), 401, REAUTH_REQUIRED);

        clock.freezeAt(authAt.plus(Duration.ofMinutes(4)));
        issue(owner, ORIGIN, broken).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#"));
        expectExactProblem(issue(owner, ORIGIN, "{\"expiresInDays\": 2}"), 409, INVITATION_PENDING);

        assertThat(invitations()).isEqualTo(before);
    }

    @Test
    @DisplayName("T032 B2 US1-6 FR-026: 멤버십 없는 사람의 보낸 초대 목록, 초대 발급, 초대 취소는 403 NO_HOUSEHOLD이고 다른 가구의 초대가 바뀌지 않는다")
    void invitationEndpointsNeedHousehold() throws Exception {
        Person owner = households.newPerson();
        households.createHousehold(owner);
        households.invite(owner, 3);
        long invitation = jdbc.sql("select id from invitation").query(Long.class).single();
        Person loner = households.newPerson();
        String before = invitations();

        expectExactProblem(mvc.perform(https(get("/api/v1/household/invitations")).cookie(loner.cookie())), 403, NO_HOUSEHOLD);
        expectExactProblem(issue(loner, ORIGIN, "{\"expiresInDays\": 3}"), 403, NO_HOUSEHOLD);
        expectExactProblem(revoke(loner, ORIGIN, invitation), 403, NO_HOUSEHOLD);

        assertThat(invitations()).isEqualTo(before);
        assertThat(membershipCount(loner)).isZero();
    }

    @Test
    @DisplayName("T032 B2 FR-012 FR-026: 초대 확인과 수락은 로그인 없으면 401 UNAUTHENTICATED이고, 멤버십 없는 사람은 확인 200과 수락 204로 통과한다")
    void previewAndAcceptNeedLoginOnly() throws Exception {
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        String token = households.invite(owner, 3);
        String body = "{\"token\": \"" + token + "\"}";
        String before = invitations();

        for (String origin : new String[] {ORIGIN, FOREIGN_ORIGIN}) {
            for (String path : new String[] {"/api/v1/invitations/preview", "/api/v1/invitations/accept"}) {
                expectExactProblem(mvc.perform(https(post(path)).header(HttpHeaders.ORIGIN, origin)
                        .contentType(MediaType.APPLICATION_JSON).content(body)), 401, UNAUTHENTICATED);
            }
        }
        assertThat(invitations()).isEqualTo(before);

        Person loner = households.newPerson();
        tokenRequest(loner, ORIGIN, "/api/v1/invitations/preview", body).andExpect(status().isOk());
        tokenRequest(loner, ORIGIN, "/api/v1/invitations/accept", body).andExpect(status().isNoContent());
        assertThat(households.householdIdOf(loner)).isEqualTo(household);
    }

    @Test
    @DisplayName("T032 B2 FR-023: 허용 밖 Origin의 초대 발급, 취소, 확인, 수락은 403 ORIGIN_REJECTED이고 초대와 멤버십이 바뀌지 않으며, 허용 Origin이면 확인 200과 수락 204")
    void foreignOriginInvitationRejected() throws Exception {
        Person owner = households.newPerson();
        long household = households.createHousehold(owner);
        String token = households.invite(owner, 3);
        long invitation = jdbc.sql("select id from invitation").query(Long.class).single();
        Person loner = households.newPerson();
        String body = "{\"token\": \"" + token + "\"}";
        String before = invitations();
        String members = memberships();

        expectExactProblem(issue(owner, FOREIGN_ORIGIN, "{\"expiresInDays\": 3}"), 403, ORIGIN_REJECTED);
        expectExactProblem(revoke(owner, FOREIGN_ORIGIN, invitation), 403, ORIGIN_REJECTED);
        expectExactProblem(tokenRequest(loner, FOREIGN_ORIGIN, "/api/v1/invitations/preview", body), 403, ORIGIN_REJECTED);
        expectExactProblem(tokenRequest(loner, FOREIGN_ORIGIN, "/api/v1/invitations/accept", body), 403, ORIGIN_REJECTED);

        assertThat(invitations()).as("초대 행이 그대로(대기 한 건)").isEqualTo(before).contains("|대기|");
        assertThat(memberships()).isEqualTo(members);
        assertThat(membershipCount(loner)).isZero();

        // 짝: 허용 Origin이면 같은 토큰으로 확인과 수락이 통과한다
        tokenRequest(loner, ORIGIN, "/api/v1/invitations/preview", body).andExpect(status().isOk());
        tokenRequest(loner, ORIGIN, "/api/v1/invitations/accept", body).andExpect(status().isNoContent());
        assertThat(households.householdIdOf(loner)).isEqualTo(household);
    }

    @Test
    @DisplayName("T032 B2 FR-023 FR-024: 다른 가구의 초대 취소는 그 초대가 취소나 수락 상태여도 409가 아니라 404 NOT_FOUND이고, 자기 가구의 같은 초대는 409 INVITATION_NOT_PENDING")
    void otherHouseholdRevokeIsNotFound() throws Exception {
        Person ownerA = households.newPerson();
        households.createHousehold(ownerA);
        Person ownerB = households.newPerson();
        long householdB = households.createHousehold(ownerB);
        Instant now = Instant.now();
        for (String state : new String[] {"취소", "수락"}) {
            long invitation = households.insertInvitation(householdB, ownerB.userId(), state,
                    now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(2))).id();
            String before = invitations();

            expectExactProblem(revoke(ownerA, ORIGIN, invitation), 404, NOT_FOUND);
            assertThat(invitations()).isEqualTo(before);

            expectExactProblem(revoke(ownerB, ORIGIN, invitation), 409, INVITATION_NOT_PENDING);
            assertThat(invitations()).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("T049 B2 US3-11 SC-007: 모든 인증이 오래되면 member 발급 403, 다른 가구 owner 제거 404, 자기 가구 member 제거 401 REAUTH_REQUIRED이고 멤버십과 초대가 그대로다")
    void us3ElevenFirstFailingStepWins() throws Exception {
        Instant authAt = frozenNow();
        Households.World world = households.world();
        households.invite(world.ownerB(), 3);
        String beforeInvitations = invitations();
        String beforeMembers = memberships();
        assertThat(beforeInvitations).as("가구 B에 수락 대기 초대 하나").contains("|" + world.householdB() + "|대기|");
        clock.freezeAt(authAt.plus(Duration.ofMinutes(10)));

        expectExactProblem(issue(world.memberA(), ORIGIN, "{\"expiresInDays\": 3}"), 403, PERMISSION_DENIED);
        expectExactProblem(removeMember(world.ownerA(), ORIGIN, String.valueOf(world.ownerB().userId())), 404, NOT_FOUND);
        expectExactProblem(removeMember(world.ownerA(), ORIGIN, String.valueOf(world.memberA().userId())), 401, REAUTH_REQUIRED);

        assertThat(invitations()).isEqualTo(beforeInvitations);
        assertThat(memberships()).isEqualTo(beforeMembers);
    }

    @Test
    @DisplayName("T049 B2 US3-4 FR-023: owner의 자기 자신 제거는 인증이 5분 안이든 오래되었든 403 PERMISSION_DENIED이고 멤버십이 그대로다")
    void ownerSelfRemovalIsPermissionDenied() throws Exception {
        Instant authAt = frozenNow();
        Households.World world = households.world();
        String before = memberships();
        String self = String.valueOf(world.ownerA().userId());

        expectExactProblem(removeMember(world.ownerA(), ORIGIN, self), 403, PERMISSION_DENIED);
        assertThat(memberships()).isEqualTo(before);

        clock.freezeAt(authAt.plus(Duration.ofMinutes(10)));
        expectExactProblem(removeMember(world.ownerA(), ORIGIN, self), 403, PERMISSION_DENIED);
        assertThat(memberships()).isEqualTo(before).contains(world.householdA() + "|" + self + "|owner|");
        // 이 시각에 인증이 실제로 오래되었음을 같은 owner의 member 제거로 확인한다(자기 제거 확인이 재인증보다 먼저)
        expectExactProblem(removeMember(world.ownerA(), ORIGIN, String.valueOf(world.memberA().userId())), 401, REAUTH_REQUIRED);
        assertThat(memberships()).isEqualTo(before);
    }

    @Test
    @DisplayName("T049 B2 US3-8 FR-023: 마지막 구글 인증이 오래된 member의 구성원 제거는 401이 아니라 403 PERMISSION_DENIED이고 멤버십이 그대로다")
    void staleMemberRemoveIsPermissionDenied() throws Exception {
        Instant authAt = frozenNow();
        Households.World world = households.world();
        String before = memberships();
        clock.freezeAt(authAt.plus(Duration.ofMinutes(10)));

        expectExactProblem(removeMember(world.memberA(), ORIGIN, String.valueOf(world.ownerA().userId())), 403, PERMISSION_DENIED);
        // 권한 확인(3단계)이 경로 번호 확인(4단계)보다 먼저다
        expectExactProblem(removeMember(world.memberA(), ORIGIN, "abc"), 403, PERMISSION_DENIED);

        assertThat(memberships()).isEqualTo(before);
        // 이 시각에 인증이 실제로 오래되었음을 같은 가구 owner의 제거로 확인한다
        expectExactProblem(removeMember(world.ownerA(), ORIGIN, String.valueOf(world.memberA().userId())), 401, REAUTH_REQUIRED);
        assertThat(memberships()).isEqualTo(before);
    }

    @Test
    @DisplayName("T049 B2 US1-6 FR-026: 멤버십 없는 사람의 구성원 제거와 가구 나가기는 403 NO_HOUSEHOLD이고 다른 가구의 멤버십이 바뀌지 않는다")
    void removeAndLeaveNeedHousehold() throws Exception {
        Households.World world = households.world();
        Person loner = world.loner();
        String before = memberships();

        expectExactProblem(removeMember(loner, ORIGIN, String.valueOf(world.ownerA().userId())), 403, NO_HOUSEHOLD);
        expectExactProblem(removeMember(loner, ORIGIN, "abc"), 403, NO_HOUSEHOLD);
        expectExactProblem(leave(loner, ORIGIN), 403, NO_HOUSEHOLD);

        assertThat(memberships()).isEqualTo(before);
        assertThat(membershipCount(loner)).isZero();
    }

    @Test
    @DisplayName("T049 B2 FR-023: 허용 밖 Origin의 구성원 제거와 가구 나가기는 403 ORIGIN_REJECTED이고 멤버십이 그대로이며, 허용 Origin이면 나가기 204")
    void foreignOriginRemoveAndLeaveRejected() throws Exception {
        Households.World world = households.world();
        String member = String.valueOf(world.memberA().userId());
        String before = memberships();

        expectExactProblem(removeMember(world.ownerA(), FOREIGN_ORIGIN, member), 403, ORIGIN_REJECTED);
        expectExactProblem(leave(world.memberA(), FOREIGN_ORIGIN), 403, ORIGIN_REJECTED);
        // Origin 확인이 멤버십 확인보다 먼저다
        expectExactProblem(leave(world.loner(), FOREIGN_ORIGIN), 403, ORIGIN_REJECTED);

        assertThat(memberships()).isEqualTo(before).contains(world.householdA() + "|" + member + "|member|");

        // 짝: 허용 Origin이면 같은 member가 나간다
        leave(world.memberA(), ORIGIN).andExpect(status().isNoContent());
        assertThat(membershipCount(world.memberA())).isZero();
    }

    private ResultActions removeMember(Person person, String origin, String userId) throws Exception {
        return mvc.perform(https(delete("/api/v1/household/members/" + userId)).header(HttpHeaders.ORIGIN, origin)
                .cookie(person.cookie()));
    }

    private ResultActions leave(Person person, String origin) throws Exception {
        return mvc.perform(https(delete("/api/v1/household/membership")).header(HttpHeaders.ORIGIN, origin).cookie(person.cookie()));
    }

    /** 초 단위로 자른 지금 시각에 Clock을 멈춘다. 그 뒤 로그인한 사람의 마지막 구글 인증 시각이 이 값이다. */
    private Instant frozenNow() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.freezeAt(at);
        return at;
    }

    private ResultActions issue(Person person, String origin, String body) throws Exception {
        return mvc.perform(https(post("/api/v1/household/invitations")).header(HttpHeaders.ORIGIN, origin).cookie(person.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions revoke(Person person, String origin, long invitationId) throws Exception {
        return mvc.perform(https(delete("/api/v1/household/invitations/" + invitationId)).header(HttpHeaders.ORIGIN, origin)
                .cookie(person.cookie()));
    }

    private ResultActions tokenRequest(Person person, String origin, String path, String body) throws Exception {
        return mvc.perform(https(post(path)).header(HttpHeaders.ORIGIN, origin).cookie(person.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 모든 초대 행(번호, 가구, 상태, 만료 시각). */
    private String invitations() {
        return String.join(",", jdbc.sql("select id || '|' || household_id || '|' || status || '|' || expires_at from invitation order by id")
                .query(String.class).list());
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
