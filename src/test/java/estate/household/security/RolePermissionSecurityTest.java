package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.https;
import static estate.household.security.ResponseOrderSecurityTest.PERMISSION_DENIED;
import static estate.household.security.ResponseOrderSecurityTest.expectExactProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T034 B11(US2 몫): 초대 목록, 발급, 취소는 owner만 한다(US2-6, FR-025, 결정 기록 0011의 권한 키 표).
 * member의 마지막 구글 인증을 5분 안으로 두어, 발급의 403이 재인증이 아니라 권한에서 걸린 것임을 보인다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class RolePermissionSecurityTest {

    static final String ORIGIN = "https://localhost";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;
    @Autowired
    MutableClock clock;

    @Test
    @DisplayName("T034 B11 US2-6 FR-025: member의 보낸 초대 목록, 초대 발급(인증 5분 안), 초대 취소는 403 PERMISSION_DENIED이고 초대가 바뀌지 않으며, 같은 가구 owner는 통과한다")
    void memberCannotManageInvitations() throws Exception {
        Instant authAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.freezeAt(authAt);
        Households households = new Households(mvc, jdbc, sessions);
        Households.World world = households.world();
        // 구성원 둘이면서 대기 초대가 있는 상태는 API로 만들 수 없어 SQL로 넣는다
        long pending = households.insertInvitation(world.householdA(), world.ownerA().userId(), "대기",
                authAt, authAt.plus(Duration.ofDays(3))).id();
        clock.freezeAt(authAt.plus(Duration.ofMinutes(1)));
        String before = invitations();
        Person member = world.memberA();

        expectExactProblem(mvc.perform(https(get("/api/v1/household/invitations")).cookie(member.cookie())), 403, PERMISSION_DENIED);
        expectExactProblem(issue(member), 403, PERMISSION_DENIED);
        expectExactProblem(revoke(member, pending), 403, PERMISSION_DENIED);

        assertThat(invitations()).as("초대가 그대로(대기 초대 포함)").isEqualTo(before).contains(pending + "|대기|");

        // 짝: 같은 가구 owner는 권한 단계를 통과한다(발급은 구성원 둘이라 409 HOUSEHOLD_FULL)
        mvc.perform(https(get("/api/v1/household/invitations")).cookie(world.ownerA().cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invitations[0].id").value(pending));
        issue(world.ownerA()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOUSEHOLD_FULL"));
        revoke(world.ownerA(), pending).andExpect(status().isNoContent());
    }

    private ResultActions issue(Person person) throws Exception {
        return mvc.perform(https(post("/api/v1/household/invitations")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\": 3}"));
    }

    private ResultActions revoke(Person person, long invitationId) throws Exception {
        return mvc.perform(https(delete("/api/v1/household/invitations/" + invitationId)).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(person.cookie()));
    }

    /** 모든 초대 행(번호, 상태, 가구, 만료 시각). */
    private String invitations() {
        return String.join(",", jdbc.sql("select id || '|' || status || '|' || household_id || '|' || expires_at from invitation order by id")
                .query(String.class).list());
    }
}
