package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.ORIGIN;
import static estate.household.security.AccessInterceptorSecurityTest.https;
import static estate.household.security.ResponseOrderSecurityTest.NOT_FOUND;
import static estate.household.security.ResponseOrderSecurityTest.expectExactProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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

    @Test
    @DisplayName("T064 B5 US4-4 FR-020: 가구 A를 삭제하면 그 가구의 초대(수락, 취소, 대기 모두), 멤버십, 가구 행만 없어지고 사용자, 세션, 가구 B의 행이 그대로다")
    void deleteRemovesOnlyThatHousehold() throws Exception {
        prepareAllInvitationStates();
        Snapshot before = snapshot();
        String a = Long.toString(world.householdA());
        assertThat(before.invitations()).as("가구 A에 수락, 취소, 대기 초대가 있었다")
                .filteredOn(row -> row.get("household_id").toString().equals(a))
                .extracting(row -> row.get("status")).containsExactlyInAnyOrder("수락", "취소", "대기");
        assertThat(before.members()).as("가구 A에 owner와 member의 멤버십이 있었다")
                .filteredOn(row -> row.get("household_id").toString().equals(a)).hasSize(2);

        deleteHousehold(world.ownerA()).andExpect(status().isNoContent());

        Snapshot after = snapshot();
        assertThat(after.invitations()).as("가구 A의 초대만 없어지고 가구 B의 초대는 상태까지 그대로")
                .isEqualTo(before.invitations().stream().filter(row -> !row.get("household_id").toString().equals(a)).toList())
                .isNotEmpty();
        assertThat(after.members()).as("가구 A의 멤버십만 없어지고 가구 B의 멤버십은 그대로")
                .isEqualTo(before.members().stream().filter(row -> !row.get("household_id").toString().equals(a)).toList())
                .isNotEmpty();
        assertThat(after.households()).as("가구 A의 행만 없어진다")
                .isEqualTo(before.households().stream().filter(row -> !row.get("id").toString().equals(a)).toList())
                .isNotEmpty();
        assertThat(after.users()).as("사용자 행(가구 A의 owner와 member 포함)이 그대로").isEqualTo(before.users());
        assertThat(after.sessions()).as("세션 행이 그대로").isEqualTo(before.sessions());
        for (Person person : List.of(world.ownerA(), world.memberA())) {
            assertThat(after.sessions()).as("가구 A 구성원이었던 사람의 세션 행이 있다")
                    .anyMatch(row -> Long.toString(person.userId()).equals(row.get("principal_name")));
        }
    }

    @Test
    @DisplayName("T064 B5 US4-5: 삭제 전 409 HOUSEHOLD_FULL이던 대기 링크의 초대 확인과 수락이 가구 삭제 뒤 404 NOT_FOUND이고 멤버십과 다른 행이 생기거나 바뀌지 않는다")
    void deletedHouseholdLinkIsNotFound() throws Exception {
        String body = "{\"token\": \"" + prepareAllInvitationStates() + "\"}";
        Person loner = world.loner();
        // 짝: 삭제 전에는 같은 링크가 있는 초대다. 가구 A는 구성원이 둘이라 확인이 409 HOUSEHOLD_FULL이다(FR-009, 확인은 상태를 바꾸지 않는다)
        tokenRequest(loner, "/api/v1/invitations/preview", body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOUSEHOLD_FULL"));

        deleteHousehold(world.ownerA()).andExpect(status().isNoContent());
        Snapshot before = snapshot();

        expectExactProblem(tokenRequest(loner, "/api/v1/invitations/preview", body), 404, NOT_FOUND);
        expectExactProblem(tokenRequest(loner, "/api/v1/invitations/accept", body), 404, NOT_FOUND);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(before.members()).noneMatch(row -> row.get("user_id").toString().equals(Long.toString(loner.userId())));
    }

    @Test
    @DisplayName("T064 B5 R10: 가구 삭제는 그 가구의 초대를 멤버십보다 먼저 지운다(테스트 동안만 둔 트리거가, 초대가 남은 가구의 멤버십 삭제를 실패시킨다)")
    void deleteRemovesInvitationsBeforeMemberships() throws Exception {
        assertThat(jdbc.sql("select count(*) from invitation where household_id = :h").param("h", world.householdA()).query(Long.class).single())
                .as("가구 A에 초대가 있어야 순서가 결과로 드러난다").isPositive();
        // 두 테이블 사이에 외래 키가 없어 한 트랜잭션 안의 삭제 순서는 결과 상태로 보이지 않는다. 트리거로 순서를 결과로 드러낸다(리뷰 기준)
        jdbc.sql("""
                create or replace function test_member_delete_guard() returns trigger language plpgsql as $$
                begin
                    if exists (select 1 from invitation where household_id = old.household_id) then
                        raise exception 'invitation remains for household %', old.household_id;
                    end if;
                    return old;
                end $$""").update();
        try {
            jdbc.sql("create trigger test_member_delete_guard before delete on household_member for each row "
                    + "execute function test_member_delete_guard()").update();
            // 대조: 초대가 남은 가구의 멤버십 삭제를 트리거가 실제로 막는다(실패한 문장은 행을 바꾸지 않는다)
            assertThatThrownBy(() -> jdbc.sql("delete from household_member where household_id = :h").param("h", world.householdA()).update())
                    .hasMessageContaining("invitation remains");

            deleteHousehold(world.ownerA()).andExpect(status().isNoContent());
        } finally {
            jdbc.sql("drop trigger if exists test_member_delete_guard on household_member").update();
            jdbc.sql("drop function if exists test_member_delete_guard()").update();
        }
        assertThat(jdbc.sql("select count(*) from household where id = :h").param("h", world.householdA()).query(Long.class).single()).isZero();
    }

    /**
     * 가구 A에 취소 초대를 더한다: setUp의 대기 초대를 owner가 API로 취소하고, 구성원이 둘이면서 대기 중인 초대는 API로 만들 수 없어 SQL로 다시 넣는다.
     * 가구 A의 초대는 수락, 취소, 대기가 되고 새 대기 초대의 토큰 원문을 준다.
     */
    private String prepareAllInvitationStates() throws Exception {
        long pending = jdbc.sql("select id from invitation where household_id = :h and status = '대기'").param("h", world.householdA())
                .query(Long.class).single();
        mvc.perform(https(delete("/api/v1/household/invitations/" + pending)).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(world.ownerA().cookie())).andExpect(status().isNoContent());
        Instant now = Instant.now();
        return new Households(mvc, jdbc, sessions)
                .insertInvitation(world.householdA(), world.ownerA().userId(), "대기", now, now.plus(Duration.ofDays(3))).token();
    }

    private ResultActions deleteHousehold(Person person) throws Exception {
        return mvc.perform(https(delete("/api/v1/household")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie()));
    }

    private ResultActions tokenRequest(Person person, String path, String body) throws Exception {
        return mvc.perform(https(post(path)).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(body));
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
