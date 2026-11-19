package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.https;
import static estate.household.security.ResponseOrderSecurityTest.expectExactProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

import estate.support.GoogleAccount;
import estate.support.Households;
import estate.support.Households.Person;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T031 B3(US2 몫): 초대 발급의 재인증 판정(FR-022, SC-005, 결정 기록 0013)과 재인증 대상이 아닌 초대 취소.
 * 마지막 구글 인증 시각은 로그인 콜백을 처리한 Clock 시각이므로, 초 단위로 자른 시각에 Clock을 멈춘 채 로그인하고 Clock을 옮겨 판정한다(001 ReauthOrderSecurityTest와 같은 방식).
 * "기록 없음"(마지막 구글 인증 시각이 없는 세션)은 세션 직렬화 내부를 써야 만들 수 있어 001과 같이 여기서 다루지 않는다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class HouseholdReauthSecurityTest {

    static final String ORIGIN = "https://localhost";
    static final String REAUTH_REQUIRED = """
            {"type": "https://example.com/problems/reauth-required", "title": "구글 계정 확인이 필요합니다", "status": 401,
             "detail": "구글 계정을 한 번 더 확인한 뒤 다시 시도해 주세요.", "code": "REAUTH_REQUIRED"}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;
    @Autowired
    MutableClock clock;

    Households households;
    Instant authAt;
    Person owner;

    @BeforeEach
    void setUp() throws Exception {
        households = new Households(mvc, jdbc, sessions);
        authAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.freezeAt(authAt);
        owner = households.newPerson();
        households.createHousehold(owner);
    }

    @Test
    @DisplayName("T031 B3 FR-022 SC-005: 마지막 구글 인증이 정확히 5분 전이면 초대 발급이 201이고 초대 행이 생긴다(경계는 허용 쪽)")
    void exactlyFiveMinutesIssues() throws Exception {
        clock.freezeAt(authAt.plus(Duration.ofMinutes(5)));

        issue().andExpect(status().isCreated());

        assertThat(invitationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T031 B3 FR-022 SC-005: 마지막 구글 인증이 5분 1초 전이면 초대 발급이 401 REAUTH_REQUIRED이고 초대 행이 생기지 않는다")
    void fiveMinutesOneSecondIsReauth() throws Exception {
        clock.freezeAt(authAt.plus(Duration.ofMinutes(5)).plusSeconds(1));

        expectExactProblem(issue(), 401, REAUTH_REQUIRED);
        assertThat(invitationCount()).isZero();

        // 짝: 같은 세션이 5분 안으로 돌아오면 같은 요청이 201
        clock.freezeAt(authAt.plus(Duration.ofMinutes(4)));
        issue().andExpect(status().isCreated());
        assertThat(invitationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T031 B3 FR-022: 마지막 구글 인증이 현재 시각보다 정확히 60초 미래면 초대 발급이 201이다(경계는 허용 쪽)")
    void sixtySecondsInFutureIssues() throws Exception {
        clock.freezeAt(authAt.minusSeconds(60));

        issue().andExpect(status().isCreated());

        assertThat(invitationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T031 B3 FR-022: 마지막 구글 인증이 현재 시각보다 61초 미래면 초대 발급이 401 REAUTH_REQUIRED이고 초대 행이 생기지 않는다")
    void sixtyOneSecondsInFutureIsReauth() throws Exception {
        clock.freezeAt(authAt.minusSeconds(61));

        expectExactProblem(issue(), 401, REAUTH_REQUIRED);

        assertThat(invitationCount()).isZero();
    }

    @Test
    @DisplayName("T031 B3 FR-022 R4: 초대 취소는 민감 동작이 아니라 마지막 구글 인증이 한 시간 전이어도 204이고 초대가 취소 상태가 된다")
    void staleAuthStillRevokes() throws Exception {
        households.invite(owner, 3);
        long invitation = jdbc.sql("select id from invitation").query(Long.class).single();
        clock.freezeAt(authAt.plus(Duration.ofHours(1)));
        // 이 시각에 인증이 실제로 오래되었음을 같은 세션의 초대 발급으로 확인한다
        expectExactProblem(issue(), 401, REAUTH_REQUIRED);

        mvc.perform(https(delete("/api/v1/household/invitations/" + invitation)).header(HttpHeaders.ORIGIN, ORIGIN).cookie(owner.cookie()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.sql("select status from invitation where id = :id").param("id", invitation).query(String.class).single())
                .isEqualTo("취소");
    }

    @Test
    @DisplayName("T050 B3 FR-022 SC-005: 마지막 구글 인증이 정확히 5분 전이면 구성원 제거가 204이고 member의 멤버십만 없어진다(경계는 허용 쪽)")
    void exactlyFiveMinutesRemoves() throws Exception {
        Person member = households.addMember(owner);
        clock.freezeAt(authAt.plus(Duration.ofMinutes(5)));

        remove(member).andExpect(status().isNoContent());

        assertThat(memberships()).isEqualTo(owner.userId() + "|owner");
    }

    @Test
    @DisplayName("T050 B3 FR-022 SC-005: 마지막 구글 인증이 5분 1초 전이면 구성원 제거가 401 REAUTH_REQUIRED이고 멤버십이 그대로다")
    void fiveMinutesOneSecondRemoveIsReauth() throws Exception {
        Person member = households.addMember(owner);
        String before = memberships();
        clock.freezeAt(authAt.plus(Duration.ofMinutes(5)).plusSeconds(1));

        expectExactProblem(remove(member), 401, REAUTH_REQUIRED);
        assertThat(memberships()).isEqualTo(before).contains(member.userId() + "|member");

        // 짝: 같은 세션이 5분 안으로 돌아오면 같은 요청이 204
        clock.freezeAt(authAt.plus(Duration.ofMinutes(4)));
        remove(member).andExpect(status().isNoContent());
        assertThat(memberships()).isEqualTo(owner.userId() + "|owner");
    }

    @Test
    @DisplayName("T050 B3 FR-022: 마지막 구글 인증이 현재 시각보다 61초 미래면 구성원 제거가 401 REAUTH_REQUIRED이고 멤버십이 그대로다")
    void sixtyOneSecondsInFutureRemoveIsReauth() throws Exception {
        Person member = households.addMember(owner);
        String before = memberships();
        clock.freezeAt(authAt.minusSeconds(61));

        expectExactProblem(remove(member), 401, REAUTH_REQUIRED);

        assertThat(memberships()).isEqualTo(before).contains(member.userId() + "|member");
    }

    @Test
    @DisplayName("T050 B3 FR-018: 가구 나가기는 재인증 대상이 아니라 마지막 구글 인증이 한 시간 전이어도 204이고 그 member의 멤버십만 없어진다")
    void staleAuthStillLeaves() throws Exception {
        Person member = households.addMember(owner);
        String before = memberships();
        clock.freezeAt(authAt.plus(Duration.ofHours(1)));
        // 이 시각에 인증이 실제로 오래되었음을 owner의 구성원 제거로 확인한다(멤버십은 그대로)
        expectExactProblem(remove(member), 401, REAUTH_REQUIRED);
        assertThat(memberships()).isEqualTo(before);

        mvc.perform(https(delete("/api/v1/household/membership")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(member.cookie()))
                .andExpect(status().isNoContent());

        assertThat(memberships()).isEqualTo(owner.userId() + "|owner");
    }

    @Test
    @DisplayName("T062 B3 US4-3 FR-022 SC-005: 마지막 구글 인증이 정확히 5분 전이면 가구 삭제가 204이고 가구, 멤버십, 초대 행이 없어진다(경계는 허용 쪽)")
    void exactlyFiveMinutesDeletes() throws Exception {
        households.addMember(owner);
        assertThat(householdRows()).contains("|member", "|수락");
        clock.freezeAt(authAt.plus(Duration.ofMinutes(5)));

        deleteHousehold().andExpect(status().isNoContent());

        assertThat(householdRows()).isEqualTo(";;");
    }

    @Test
    @DisplayName("T062 B3 US4-3 FR-022 SC-005: 마지막 구글 인증이 5분 1초 전이면 가구 삭제가 401 REAUTH_REQUIRED이고 가구, 멤버십, 초대가 그대로다")
    void fiveMinutesOneSecondDeleteIsReauth() throws Exception {
        households.addMember(owner);
        String before = householdRows();
        clock.freezeAt(authAt.plus(Duration.ofMinutes(5)).plusSeconds(1));

        expectExactProblem(deleteHousehold(), 401, REAUTH_REQUIRED);
        assertThat(householdRows()).isEqualTo(before).contains("|member", "|수락");

        // 짝: 같은 세션이 5분 안으로 돌아오면 같은 요청이 204
        clock.freezeAt(authAt.plus(Duration.ofMinutes(4)));
        deleteHousehold().andExpect(status().isNoContent());
        assertThat(householdRows()).isEqualTo(";;");
    }

    @Test
    @DisplayName("T062 B3 FR-022: 마지막 구글 인증이 현재 시각보다 61초 미래면 가구 삭제가 401 REAUTH_REQUIRED이고 가구, 멤버십, 초대가 그대로다")
    void sixtyOneSecondsInFutureDeleteIsReauth() throws Exception {
        households.addMember(owner);
        String before = householdRows();
        clock.freezeAt(authAt.minusSeconds(61));

        expectExactProblem(deleteHousehold(), 401, REAUTH_REQUIRED);

        assertThat(householdRows()).isEqualTo(before).contains("|member", "|수락");
    }

    @Test
    @DisplayName("T062 B3 FR-022 Edge Cases: 재인증 한 번 뒤 5분 안에 구성원 제거, 초대 발급, 가구 삭제가 각각 재인증 없이 처리된다(재인증 전에는 셋 다 401)")
    void oneReauthCoversThreeActions() throws Exception {
        Person member = households.addMember(owner);
        Instant reauthAt = authAt.plus(Duration.ofMinutes(10));
        clock.freezeAt(reauthAt);
        String before = householdRows();
        expectExactProblem(remove(member), 401, REAUTH_REQUIRED);
        expectExactProblem(issue(), 401, REAUTH_REQUIRED);
        expectExactProblem(deleteHousehold(), 401, REAUTH_REQUIRED);
        assertThat(householdRows()).isEqualTo(before);

        String sub = jdbc.sql("select provider_subject from \"user\" where id = :u").param("u", owner.userId())
                .query(String.class).single();
        // Households.newPerson은 이메일을 sub + "@example.com"으로 만든다
        Cookie reauthed = new LoginClient(mvc).login(GoogleAccount.of(sub, sub + "@example.com"), "/household/delete", "reauth",
                owner.cookie()).requireSessionCookie();
        owner = new Person(reauthed, owner.userId());

        clock.freezeAt(reauthAt.plus(Duration.ofMinutes(1)));
        remove(member).andExpect(status().isNoContent());
        assertThat(memberships()).isEqualTo(owner.userId() + "|owner");
        clock.freezeAt(reauthAt.plus(Duration.ofMinutes(2)));
        issue().andExpect(status().isCreated());
        clock.freezeAt(reauthAt.plus(Duration.ofMinutes(3)));
        deleteHousehold().andExpect(status().isNoContent());
        assertThat(householdRows()).isEqualTo(";;");
    }

    private ResultActions deleteHousehold() throws Exception {
        return mvc.perform(https(delete("/api/v1/household")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(owner.cookie()));
    }

    /** 가구 번호, 멤버십(사용자 번호, 역할), 초대(번호, 상태)를 ;로 이은 값. 가구 삭제 뒤에는 ";;"이다. */
    private String householdRows() {
        return String.join(",", jdbc.sql("select id::text from household order by id").query(String.class).list()) + ";"
                + memberships() + ";"
                + String.join(",", jdbc.sql("select id || '|' || status from invitation order by id").query(String.class).list());
    }

    /** owner가 이 사람을 구성원 제거로 요청한다. */
    private ResultActions remove(Person target) throws Exception {
        return mvc.perform(https(delete("/api/v1/household/members/" + target.userId())).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(owner.cookie()));
    }

    /** 모든 멤버십 행(사용자 번호, 역할). 가구는 setUp의 하나뿐이다. */
    private String memberships() {
        return String.join(",", jdbc.sql("select user_id || '|' || role from household_member order by user_id")
                .query(String.class).list());
    }

    private ResultActions issue() throws Exception {
        return mvc.perform(https(post("/api/v1/household/invitations")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\": 3}"));
    }

    private long invitationCount() {
        return jdbc.sql("select count(*) from invitation").query(Long.class).single();
    }
}
