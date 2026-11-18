package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.ORIGIN;
import static estate.household.security.AccessInterceptorSecurityTest.https;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import estate.support.Households;
import estate.support.Households.Person;
import estate.support.Households.World;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T052 B5(US3 몫): 구성원 제거와 가구 나가기는 그 사람의 household_member 행 하나만 삭제하고 사용자 행, 세션 행, 초대 행, 가구 행,
 * 다른 구성원의 멤버십은 그대로 둔다(US3-2, US3-6, FR-017, FR-018, data-model.md 삭제와 보존).
 * 가구 A에는 member를 들인 수락 초대(API)와, 구성원이 둘이면서 대기 중인 초대(API로 만들 수 없어 SQL)가 있고, 가구 B에는 대기 초대(API)가 있다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class HouseholdDeletionCascadeSecurityTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;

    World world;

    @BeforeEach
    void setUp() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        world = households.world();
        households.invite(world.ownerB(), 3);
        Instant now = Instant.now();
        households.insertInvitation(world.householdA(), world.ownerA().userId(), "대기", now, now.plus(Duration.ofDays(3)));
        assertThat(jdbc.sql("select status from invitation order by id").query(String.class).list())
                .as("가구 A의 수락, 가구 B의 대기, 가구 A의 대기").containsExactly("수락", "대기", "대기");
    }

    @Test
    @DisplayName("T052 B5 US3-2 FR-017: owner가 member를 제거하면 그 member의 멤버십 행 하나만 없어지고 사용자, 세션, 초대(상태 포함), 가구, 다른 멤버십 행이 그대로다")
    void removeDeletesOnlyThatMembership() throws Exception {
        Snapshot before = snapshot();

        mvc.perform(https(delete("/api/v1/household/members/" + world.memberA().userId())).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(world.ownerA().cookie())).andExpect(status().isNoContent());

        assertOnlyMembershipDeleted(before, world.memberA());
    }

    @Test
    @DisplayName("T052 B5 US3-6 FR-018: member가 가구를 나가면 자기 멤버십 행 하나만 없어지고 사용자, 세션, 초대(상태 포함), 가구, 다른 멤버십 행이 그대로다")
    void leaveDeletesOnlyThatMembership() throws Exception {
        Snapshot before = snapshot();

        mvc.perform(https(delete("/api/v1/household/membership")).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(world.memberA().cookie())).andExpect(status().isNoContent());

        assertOnlyMembershipDeleted(before, world.memberA());
    }

    private record Snapshot(List<Map<String, Object>> users, List<Map<String, Object>> sessions, List<Map<String, Object>> invitations,
                            List<Map<String, Object>> households, List<Map<String, Object>> members) {
    }

    private void assertOnlyMembershipDeleted(Snapshot before, Person target) {
        Snapshot after = snapshot();
        String id = Long.toString(target.userId());
        assertThat(before.members()).as("대상의 멤버십 행이 처음에 있었다")
                .anyMatch(row -> row.get("user_id").toString().equals(id));
        assertThat(after.members()).as("대상의 멤버십 행만 없어지고 다른 구성원의 멤버십은 역할과 들어온 시각까지 그대로")
                .isEqualTo(before.members().stream().filter(row -> !row.get("user_id").toString().equals(id)).toList());
        assertThat(after.users()).as("사용자 행(대상 포함)이 그대로").isEqualTo(before.users());
        assertThat(after.sessions()).as("세션 행(대상 포함)이 그대로").isEqualTo(before.sessions());
        assertThat(after.sessions()).as("대상의 세션 행이 있다").anyMatch(row -> id.equals(row.get("principal_name")));
        assertThat(after.invitations()).as("초대 행과 상태가 그대로").isEqualTo(before.invitations());
        assertThat(after.households()).as("가구 행이 그대로").isEqualTo(before.households());
    }

    private Snapshot snapshot() {
        List<Long> people = List.of(world.ownerA().userId(), world.memberA().userId(), world.ownerB().userId(), world.loner().userId());
        return new Snapshot(
                jdbc.sql("""
                        select id, provider, provider_subject, encode(login_email, 'hex') as login_email, email_verified, display_name
                        from "user" where id in (:ids) order by id""").param("ids", people).query().listOfRows(),
                // 요청이 마지막 접근 시각과 만료 시각을 옮기므로 행의 정체를 정하는 열만 본다
                jdbc.sql("select primary_id, session_id, principal_name, creation_time from spring_session order by session_id")
                        .query().listOfRows(),
                jdbc.sql("""
                        select id, household_id, created_by, encode(token_hash, 'hex') as token_hash, status, expires_at, created_at
                        from invitation order by id""").query().listOfRows(),
                jdbc.sql("select id from household order by id").query().listOfRows(),
                jdbc.sql("select household_id, user_id, role, joined_at from household_member order by user_id").query().listOfRows());
    }
}
