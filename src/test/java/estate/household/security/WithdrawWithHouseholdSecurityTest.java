package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.https;
import static estate.household.security.HouseholdReauthSecurityTest.REAUTH_REQUIRED;
import static estate.household.security.ResponseOrderSecurityTest.expectExactProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultActions;

import estate.household.domain.HouseholdRole;
import estate.support.HouseholdLock;
import estate.support.Households;
import estate.support.Households.Person;
import estate.support.LoginIntegrationTest;
import estate.support.MutableClock;
import estate.support.SessionRows;

/**
 * T066 B10: 가구가 있는 사람의 탈퇴 409(US4-7, US4-8, FR-021, SC-008, research R13).
 * 인증이 오래된 경우는 초 단위로 자른 시각에 Clock을 멈춘 채 로그인하고 Clock을 옮긴다(HouseholdReauthSecurityTest와 같은 방식).
 * 권한 확인기 뒤의 경쟁은 다른 연결이 커밋하지 않은 멤버십을 쥔 채 탈퇴를 보내 사용자 행 잠금을 기다리게 한 뒤 커밋한다(재인증 판정은 잠금 앞이라 5분 안의 세션으로 한다).
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class WithdrawWithHouseholdSecurityTest {

    static final String ORIGIN = "https://localhost";
    static final String HAS_HOUSEHOLD = """
            {"type": "https://example.com/problems/has-household", "title": "이미 가구가 있습니다", "status": 409,
             "detail": "가구를 나가거나 삭제한 뒤 다시 시도해 주세요.", "code": "HAS_HOUSEHOLD"}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;
    @Autowired
    MutableClock clock;
    @Autowired
    DataSource dataSource;

    Households households;
    Instant authAt;
    Person owner;
    ExecutorService executor;
    final List<Future<?>> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        households = new Households(mvc, jdbc, sessions);
        authAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.freezeAt(authAt);
        owner = households.newPerson();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        // 단언이 실패한 경로에서도 서버 쪽 트랜잭션이 끝난 뒤 다음 테스트로 넘어가게 한다
        for (Future<?> f : sent) {
            try {
                f.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 요청 자체의 실패는 각 테스트의 단언이 본다
            }
        }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("요청 스레드가 끝나야 한다").isTrue();
    }

    @Test
    @DisplayName("T066 B10 US4-7 FR-021 SC-008: 마지막 구글 인증이 한 시간 전인 owner의 탈퇴는 401이 아니라 409 HAS_HOUSEHOLD이고 사용자 행, 멤버십, 세션이 그대로다")
    void staleOwnerWithdrawIsConflict() throws Exception {
        households.createHousehold(owner);
        Snapshot before = snapshot(owner);
        clock.freezeAt(authAt.plus(Duration.ofHours(1)));
        // 이 시각에 인증이 실제로 오래되었음을 같은 세션의 초대 발급으로 확인한다
        expectExactProblem(issue(owner), 401, REAUTH_REQUIRED);

        expectExactProblem(withdraw(owner), 409, HAS_HOUSEHOLD);

        assertThat(snapshot(owner)).isEqualTo(before);
        assertThat(before.membership()).contains("|owner");
    }

    @Test
    @DisplayName("T066 B10 US4-7 FR-021 SC-008: 마지막 구글 인증이 한 시간 전인 member의 탈퇴는 409 HAS_HOUSEHOLD이고 그대로이며, 가구를 나간 뒤 같은 세션의 탈퇴는 401 REAUTH_REQUIRED다")
    void staleMemberWithdrawIsConflict() throws Exception {
        households.createHousehold(owner);
        Person member = households.addMember(owner);
        Snapshot before = snapshot(member);
        Snapshot ownerBefore = snapshot(owner);
        clock.freezeAt(authAt.plus(Duration.ofHours(1)));

        expectExactProblem(withdraw(member), 409, HAS_HOUSEHOLD);

        assertThat(snapshot(member)).isEqualTo(before);
        assertThat(before.membership()).contains("|member");
        assertThat(snapshot(owner)).isEqualTo(ownerBefore);

        // 짝: 409가 재인증 판정보다 먼저였음을, 멤버십이 없어진 같은 세션의 탈퇴가 401인 것으로 확인한다
        leave(member).andExpect(status().isNoContent());
        expectExactProblem(withdraw(member), 401, REAUTH_REQUIRED);
        assertThat(userRow(member)).as("재인증 거절도 사용자 행을 지우지 않는다").isEqualTo(before.user());
    }

    @Test
    @DisplayName("T066 B10 FR-021 R9 R13: 권한 확인기를 통과해 사용자 행 잠금을 기다리던 탈퇴는 그사이 그 사람의 멤버십이 커밋되면 409 HAS_HOUSEHOLD이고 사용자 행, 멤버십, 세션이 남는다")
    void withdrawSeesMembershipAfterLock() throws Exception {
        String user = userRow(owner);
        List<String> ownerSessions = sessionIds(owner);

        long lockHousehold;
        Future<MvcResult> pending;
        try (HouseholdLock lock = HouseholdLock.uncommittedMembership(dataSource, owner.userId(), HouseholdRole.OWNER)) {
            lockHousehold = lock.householdId();
            // 멤버십 삽입의 외래 키 확인이 사용자 행에 FOR KEY SHARE를 걸어 두어 탈퇴의 FOR UPDATE가 기다린다
            pending = submit(owner);
            lock.awaitWaiting(1);
            assertThat(pending.isDone()).as("커밋하지 않은 멤버십이 있는 동안 탈퇴가 끝나면 안 된다").isFalse();

            lock.commit();
        }
        MvcResult result = pending.get(10, TimeUnit.SECONDS);

        status().is(409).match(result);
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
        jsonPath("$.status").value(409).match(result);
        jsonPath("$.code").value("HAS_HOUSEHOLD").match(result);
        assertThat(userRow(owner)).as("탈퇴가 거절되어 사용자 행이 남는다").isEqualTo(user);
        assertThat(membership(owner)).isEqualTo(lockHousehold + "|owner");
        assertThat(sessionIds(owner)).isEqualTo(ownerSessions).isNotEmpty();
    }

    @Test
    @DisplayName("T066 B10 US4-8 FR-021: 가구를 나간 member의 탈퇴는 001대로 204이고 사용자 행과 세션 행이 삭제되며 owner의 가구는 그대로다")
    void memberWhoLeftWithdraws() throws Exception {
        long household = households.createHousehold(owner);
        Person member = households.addMember(owner);
        leave(member).andExpect(status().isNoContent());
        Snapshot ownerBefore = snapshot(owner);

        withdraw(member).andExpect(status().isNoContent());

        assertThat(userRow(member)).isNull();
        assertThat(sessionIds(member)).isEmpty();
        assertThat(snapshot(owner)).isEqualTo(ownerBefore);
        assertThat(ownerBefore.membership()).isEqualTo(household + "|owner");
    }

    @Test
    @DisplayName("T066 B10 US4-8 FR-021: 가구를 삭제한 owner의 탈퇴는 001대로 204이고 사용자 행과 세션 행이 삭제되며 member였던 사람의 사용자 행과 세션은 남는다")
    void ownerWhoDeletedWithdraws() throws Exception {
        households.createHousehold(owner);
        Person member = households.addMember(owner);
        mvc.perform(https(delete("/api/v1/household")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(owner.cookie()))
                .andExpect(status().isNoContent());
        Snapshot memberBefore = snapshot(member);
        assertThat(memberBefore.membership()).as("가구 삭제로 member였던 사람의 멤버십도 없어졌다").isNull();

        withdraw(owner).andExpect(status().isNoContent());

        assertThat(userRow(owner)).isNull();
        assertThat(sessionIds(owner)).isEmpty();
        assertThat(snapshot(member)).isEqualTo(memberBefore);
        assertThat(memberBefore.sessions()).isNotEmpty();
    }

    /** 사용자 행 전체, 멤버십(가구 번호|역할, 없으면 null), 세션 번호. 세션은 요청마다 마지막 접근 시각이 바뀌어 번호만 본다. */
    private record Snapshot(String user, String membership, List<String> sessions) {
    }

    private Snapshot snapshot(Person person) {
        return new Snapshot(userRow(person), membership(person), sessionIds(person));
    }

    private String userRow(Person person) {
        return jdbc.sql("select row_to_json(u)::text from \"user\" u where id = :u").param("u", person.userId())
                .query(String.class).optional().orElse(null);
    }

    private String membership(Person person) {
        return jdbc.sql("select household_id || '|' || role from household_member where user_id = :u").param("u", person.userId())
                .query(String.class).optional().orElse(null);
    }

    private List<String> sessionIds(Person person) {
        return sessions.rowsOfUser(person.userId()).stream().map(SessionRows.Row::sessionId).sorted().toList();
    }

    private Future<MvcResult> submit(Person person) {
        Future<MvcResult> future = executor.submit(() -> withdraw(person).andReturn());
        sent.add(future);
        return future;
    }

    private ResultActions withdraw(Person person) throws Exception {
        return mvc.perform(https(delete("/api/v1/me")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie()));
    }

    private ResultActions leave(Person member) throws Exception {
        return mvc.perform(https(delete("/api/v1/household/membership")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(member.cookie()));
    }

    private ResultActions issue(Person person) throws Exception {
        return mvc.perform(https(post("/api/v1/household/invitations")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\": 3}"));
    }
}
