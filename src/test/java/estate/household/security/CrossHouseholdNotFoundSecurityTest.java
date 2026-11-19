package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.https;
import static estate.household.security.ResponseOrderSecurityTest.NOT_FOUND;
import static estate.household.security.ResponseOrderSecurityTest.expectExactProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import estate.support.Households;
import estate.support.Households.Person;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T033 B1(초대 몫): 다른 가구의 초대를 가리키는 요청은 없는 초대를 가리킨 요청과 구분되지 않는 404이고, 다른 가구의 초대를 보거나 바꾸지 못한다
 * (US3-12, FR-024, SC-006, research R3의 4단계).
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class CrossHouseholdNotFoundSecurityTest {

    static final String ORIGIN = "https://localhost";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;

    Households households;
    Person ownerA;
    Person ownerB;
    long householdA;
    long householdB;

    @BeforeEach
    void setUp() throws Exception {
        households = new Households(mvc, jdbc, sessions);
        ownerA = households.newPerson();
        householdA = households.createHousehold(ownerA);
        ownerB = households.newPerson();
        householdB = households.createHousehold(ownerB);
    }

    @Test
    @DisplayName("T033 B1 US3-12 FR-024 SC-006: 가구 A owner의 가구 B 초대 취소는 404이고 없는 번호, abc, long 범위를 넘는 번호와 상태 코드와 본문이 같으며 가구 B의 초대가 그대로다")
    void otherHouseholdInvitationIsNotFound() throws Exception {
        households.invite(ownerB, 3);
        long invitationB = jdbc.sql("select id from invitation where household_id = :h").param("h", householdB).query(Long.class).single();
        // 어느 초대에도 없는 번호: 시퀀스가 지금까지 준 값보다 크다
        long unused = jdbc.sql("select last_value from invitation_id_seq").query(Long.class).single() + 1000;
        String before = invitationsOf(householdB);

        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (String id : new String[] {String.valueOf(invitationB), String.valueOf(unused), "abc", "9223372036854775808"}) {
            var actions = mvc.perform(https(delete("/api/v1/household/invitations/" + id)).header(HttpHeaders.ORIGIN, ORIGIN)
                    .cookie(ownerA.cookie()));
            expectExactProblem(actions, 404, NOT_FOUND);
            responses.add(actions.andReturn().getResponse());
        }

        MockHttpServletResponse other = responses.getFirst();
        for (MockHttpServletResponse r : responses.subList(1, responses.size())) {
            assertThat(r.getStatus()).isEqualTo(other.getStatus());
            assertThat(r.getContentType()).isEqualTo(other.getContentType());
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).as("다른 가구의 초대와 없는 초대의 본문이 글자까지 같다")
                    .isEqualTo(other.getContentAsString(StandardCharsets.UTF_8));
        }
        assertThat(invitationsOf(householdB)).as("가구 B의 초대가 그대로(대기)").isEqualTo(before).contains("|대기|");

        // 짝: 가구 B의 owner에게는 같은 번호가 취소할 수 있는 초대다
        mvc.perform(https(delete("/api/v1/household/invitations/" + invitationB)).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(ownerB.cookie())).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("T033 B1 FR-024: 가구 A의 보낸 초대 목록에 가구 B의 초대가 없고, 가구 A의 발급이 가구 B의 초대 수를 바꾸지 않는다")
    void sentListAndIssueStayInHousehold() throws Exception {
        households.invite(ownerB, 3);
        String beforeB = invitationsOf(householdB);
        assertThat(beforeB).as("가구 B에 대기 초대가 있어야 목록에 없음이 의미가 있다").contains("|대기|");

        households.invite(ownerA, 5);
        long invitationA = jdbc.sql("select id from invitation where household_id = :h").param("h", householdA).query(Long.class).single();

        assertThat(invitationsOf(householdB)).as("가구 A의 발급이 가구 B의 초대를 바꾸지 않는다").isEqualTo(beforeB);
        String body = mvc.perform(https(get("/api/v1/household/invitations")).cookie(ownerA.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode list = JSON.readTree(body).path("invitations");
        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("id").longValue()).as("가구 A의 초대만 보인다").isEqualTo(invitationA);
    }

    @Test
    @DisplayName("T048 B1 US3-12 FR-024 SC-006: 가구 A owner의 가구 B owner 제거는 404이고 없는 번호, abc, long 범위를 넘는 번호와 상태 코드와 본문이 같으며 가구 B의 멤버십이 그대로다")
    void otherHouseholdMemberIsNotFound() throws Exception {
        Person memberA = households.addMember(ownerA);
        // 어느 사용자에도 없는 번호: 시퀀스가 지금까지 준 값보다 크다
        long unused = jdbc.sql("select last_value from user_id_seq").query(Long.class).single() + 1000;
        String beforeB = membershipsOf(householdB);
        String beforeA = membershipsOf(householdA);

        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (String id : new String[] {String.valueOf(ownerB.userId()), String.valueOf(unused), "abc", "9223372036854775808"}) {
            var actions = mvc.perform(https(delete("/api/v1/household/members/" + id)).header(HttpHeaders.ORIGIN, ORIGIN)
                    .cookie(ownerA.cookie()));
            expectExactProblem(actions, 404, NOT_FOUND);
            responses.add(actions.andReturn().getResponse());
        }

        MockHttpServletResponse other = responses.getFirst();
        for (MockHttpServletResponse r : responses.subList(1, responses.size())) {
            assertThat(r.getStatus()).isEqualTo(other.getStatus());
            assertThat(r.getContentType()).isEqualTo(other.getContentType());
            assertThat(r.getContentAsString(StandardCharsets.UTF_8)).as("다른 가구의 구성원과 없는 구성원의 본문이 글자까지 같다")
                    .isEqualTo(other.getContentAsString(StandardCharsets.UTF_8));
        }
        assertThat(membershipsOf(householdB)).as("가구 B의 멤버십이 그대로(owner)").isEqualTo(beforeB)
                .startsWith(ownerB.userId() + "|owner|");
        assertThat(membershipsOf(householdA)).isEqualTo(beforeA);

        // 짝: 같은 owner가 자기 가구의 member를 가리키면 제거된다(인증 5분 안)
        mvc.perform(https(delete("/api/v1/household/members/" + memberA.userId())).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(ownerA.cookie())).andExpect(status().isNoContent());
        assertThat(membershipsOf(householdA)).as("member 행만 없어진다").isEqualTo(beforeA.split(",")[0])
                .startsWith(ownerA.userId() + "|owner|");
    }

    @Test
    @DisplayName("T048 B1 FR-024: 가구 A의 함께 보는 사람 목록에 가구 B의 구성원이 없다")
    void memberListStaysInHousehold() throws Exception {
        Person memberA = households.addMember(ownerA);
        households.addMember(ownerB);
        assertThat(membershipsOf(householdB).split(",")).as("가구 B에 구성원이 둘 있어야 목록에 없음이 의미가 있다").hasSize(2);

        String body = mvc.perform(https(get("/api/v1/household")).cookie(ownerA.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode members = JSON.readTree(body).path("members");
        assertThat(members.isArray()).isTrue();
        List<Long> ids = new ArrayList<>();
        members.forEach(m -> ids.add(m.path("userId").longValue()));
        assertThat(ids).as("가구 A의 구성원만 보인다").containsExactlyInAnyOrder(ownerA.userId(), memberA.userId());
    }

    @Test
    @DisplayName("T048 B1 FR-024: 가구 A의 member가 나가면 가구 A의 그 멤버십만 없어지고 가구 B의 멤버십이 그대로다")
    void leaveStaysInHousehold() throws Exception {
        Person memberA = households.addMember(ownerA);
        households.addMember(ownerB);
        String beforeA = membershipsOf(householdA);
        String beforeB = membershipsOf(householdB);
        assertThat(beforeB.split(",")).as("가구 B에 구성원이 둘 있어야 그대로임이 의미가 있다").hasSize(2);

        mvc.perform(https(delete("/api/v1/household/membership")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(memberA.cookie()))
                .andExpect(status().isNoContent());

        assertThat(membershipsOf(householdB)).isEqualTo(beforeB);
        assertThat(membershipsOf(householdA)).as("가구 A에서는 member 행만 없어진다").isEqualTo(beforeA.split(",")[0])
                .startsWith(ownerA.userId() + "|owner|");
    }

    /** 이 가구의 멤버십 행(사용자 번호, 역할, 들어온 시각). owner가 먼저 들어왔으므로 첫 항목이다. */
    private String membershipsOf(long household) {
        return String.join(",", jdbc.sql("""
                select user_id || '|' || role || '|' || joined_at from household_member
                where household_id = :h order by joined_at, user_id""").param("h", household).query(String.class).list());
    }

    /** 이 가구의 초대 행(번호, 상태, 만든 시각, 만료 시각). */
    private String invitationsOf(long household) {
        return String.join(",", jdbc.sql("""
                select id || '|' || status || '|' || created_at || '|' || expires_at from invitation
                where household_id = :h order by id""").param("h", household).query(String.class).list());
    }
}
