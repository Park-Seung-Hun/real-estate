package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.ORIGIN;
import static estate.household.security.AccessInterceptorSecurityTest.expectProblem;
import static estate.household.security.AccessInterceptorSecurityTest.https;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import estate.household.domain.HouseholdRole;
import estate.support.Households;
import estate.support.Households.Person;
import estate.support.Households.World;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T010 B4(공통): 역할을 세션에 담지 않고 요청마다 멤버십을 다시 읽는다(FR-019, research R2, API 공통 규칙).
 * T009의 테스트 전용 컨트롤러를 가져와 쓰고, 멤버십은 SQL로 넣고 바꾼다. 가구 하나에 owner와 member.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
// T009의 설정을 가져온 이 클래스도 자기 컨텍스트를 따로 쓰므로 T008의 컨텍스트에는 테스트 전용 컨트롤러가 없다
@Import(AccessInterceptorSecurityTest.TestOnlyApi.class)
class MembershipRecheckSecurityTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;

    long household;
    Person owner;
    Person member;

    @BeforeEach
    void setUp() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        owner = households.newPerson();
        member = households.newPerson();
        household = jdbc.sql("select nextval('household_id_seq')").query(Long.class).single();
        jdbc.sql("insert into household (id) values (:id)").param("id", household).update();
        insertMember(owner, HouseholdRole.OWNER);
        insertMember(member, HouseholdRole.MEMBER);
    }

    @Test
    @DisplayName("T010 B4 FR-019 SC-004: 같은 쿠키로 가구 요청이 200인 뒤 SQL로 멤버십 행을 지우면 다음 요청이 403 NO_HOUSEHOLD이고 세션은 남는다")
    void deletedMembershipIsRejectedOnNextRequest() throws Exception {
        mvc.perform(getAs("/api/test-only/required", member)).andExpect(status().isOk());

        jdbc.sql("delete from household_member where user_id = :u").param("u", member.userId()).update();

        expectProblem(mvc.perform(getAs("/api/test-only/required", member)), 403, "NO_HOUSEHOLD");
        assertThat(sessions.exists(SessionRows.idOfCookie(member.cookie().getValue()))).as("세션 행은 남는다").isTrue();
        mvc.perform(getAs("/api/test-only/optional", member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false));
        mvc.perform(getAs("/api/test-only/required", owner)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T010 B4 FR-019 FR-025: SQL로 owner와 member의 역할을 맞바꾸면 같은 쿠키의 다음 요청부터 바뀐 역할의 권한으로 확인한다")
    void changedRoleIsCheckedOnNextRequest() throws Exception {
        mvc.perform(getAs("/api/test-only/owner-key", owner)).andExpect(status().isOk());
        expectProblem(mvc.perform(getAs("/api/test-only/owner-key", member)), 403, "PERMISSION_DENIED");
        mvc.perform(getAs("/api/test-only/member-key", member)).andExpect(status().isOk());
        expectProblem(mvc.perform(getAs("/api/test-only/member-key", owner)), 403, "PERMISSION_DENIED");

        jdbc.sql("update household_member set role = case role when 'owner' then 'member' else 'owner' end where household_id = :h")
                .param("h", household).update();

        mvc.perform(getAs("/api/test-only/owner-key", member)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("owner"));
        expectProblem(mvc.perform(getAs("/api/test-only/owner-key", owner)), 403, "PERMISSION_DENIED");
        mvc.perform(getAs("/api/test-only/member-key", owner)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("member"));
        expectProblem(mvc.perform(getAs("/api/test-only/member-key", member)), 403, "PERMISSION_DENIED");
    }

    @Test
    @DisplayName("T051 B4 US3-3 FR-019 SC-004: owner가 member를 제거하면 그 사람이 같은 쿠키로 보낸 다음 GET /api/v1/household가 403 NO_HOUSEHOLD이고 세션 행이 남는다")
    void removedMemberIsRejectedOnNextRequest() throws Exception {
        World world = new Households(mvc, jdbc, sessions).world();
        mvc.perform(getAs("/api/v1/household", world.memberA())).andExpect(status().isOk());

        mvc.perform(deleteAs("/api/v1/household/members/" + world.memberA().userId(), world.ownerA()))
                .andExpect(status().isNoContent());

        expectNoHouseholdWithSession(world.memberA());
        mvc.perform(getAs("/api/v1/household", world.ownerA())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T051 B4 US3-3 US3-6 FR-019 SC-004: member가 가구를 나가면 같은 쿠키로 보낸 다음 GET /api/v1/household가 403 NO_HOUSEHOLD이고 세션 행이 남는다")
    void leftMemberIsRejectedOnNextRequest() throws Exception {
        World world = new Households(mvc, jdbc, sessions).world();
        mvc.perform(getAs("/api/v1/household", world.memberA())).andExpect(status().isOk());

        mvc.perform(deleteAs("/api/v1/household/membership", world.memberA())).andExpect(status().isNoContent());

        expectNoHouseholdWithSession(world.memberA());
        mvc.perform(getAs("/api/v1/household", world.ownerA())).andExpect(status().isOk());
    }

    /** 같은 쿠키의 다음 가구 요청이 401이 아니라 403 NO_HOUSEHOLD이고, 그 쿠키의 세션 행이 그 사람의 것으로 남아 있다. */
    private void expectNoHouseholdWithSession(Person person) throws Exception {
        String sessionId = SessionRows.idOfCookie(person.cookie().getValue());
        expectProblem(mvc.perform(getAs("/api/v1/household", person)), 403, "NO_HOUSEHOLD");
        assertThat(sessions.exists(sessionId)).as("세션 행은 남는다").isTrue();
        assertThat(sessions.rowOf(sessionId).principalName()).isEqualTo(Long.toString(person.userId()));
    }

    private void insertMember(Person person, HouseholdRole role) {
        jdbc.sql("insert into household_member (household_id, user_id, role, joined_at) values (:h, :u, :role, now())")
                .param("h", household).param("u", person.userId()).param("role", role.dbValue()).update();
    }

    private static MockHttpServletRequestBuilder getAs(String path, Person person) {
        return https(get(path)).cookie(person.cookie());
    }

    private static MockHttpServletRequestBuilder deleteAs(String path, Person person) {
        return https(delete(path)).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie());
    }
}
