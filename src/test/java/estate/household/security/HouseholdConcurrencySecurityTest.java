package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.https;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import estate.household.application.HouseholdService;
import estate.household.domain.HouseholdRole;
import estate.support.HouseholdLock;
import estate.support.MutableClock;
import estate.support.Households;
import estate.support.Households.Person;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.RealServerClient;
import estate.support.SessionRows;

/**
 * T018 B6(US1 몫): 같은 사람의 동시 가구 만들기와, 탈퇴와 가구 만들기의 겹침(US1-4, FR-004, research R9).
 * 다른 연결이 커밋하지 않은 행이나 사용자 행 잠금을 쥔 채 요청을 보내고, 요청이 잠금을 기다리는 것을 확인한 뒤 풀어 두 순서를 실제로 만든다.
 * 탈퇴가 먼저인 가구 만들기의 500은 Boot 오류 처리(error dispatch)가 쓰는데 MockMvc는 이를 하지 않으므로 그 요청만 실제 서버(RANDOM_PORT)로 보낸다.
 */
@LoginIntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class HouseholdConcurrencySecurityTest {

    static final String ORIGIN = "https://localhost";
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ACCEPTORS = 10;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;
    @Autowired
    DataSource dataSource;
    @Autowired
    HouseholdService householdService;
    @Autowired
    MutableClock clock;
    @LocalServerPort
    int port;

    Person person;
    ExecutorService executor;
    final List<Future<?>> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        person = new Households(mvc, jdbc, sessions).newPerson();
        assertThat(userCount()).as("가구 행이 없다는 확인이 의미가 있도록 사용자 행이 먼저 있어야 한다").isEqualTo(1);
        executor = Executors.newFixedThreadPool(ACCEPTORS); // T030의 동시 수락 열 건이 한꺼번에 잠금을 기다리게 한다
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
        sent.clear();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("요청 스레드가 끝나야 한다").isTrue();
    }

    @Test
    @DisplayName("T018 B6 US1-4 FR-004 R9: 다른 연결의 커밋하지 않은 멤버십을 기다리던 가구 만들기는 그 연결이 커밋하면 409 HAS_HOUSEHOLD이고 요청 쪽 가구 행이 남지 않는다")
    void concurrentCreateAfterCommitIsConflict() throws Exception {
        long lockHousehold;
        Future<MvcResult> pending;
        try (HouseholdLock lock = HouseholdLock.uncommittedMembership(dataSource, person.userId(), HouseholdRole.OWNER)) {
            lockHousehold = lock.householdId();
            pending = submit(() -> create().andReturn());
            lock.awaitWaiting(1);
            assertThat(pending.isDone()).as("커밋하지 않은 멤버십이 있는 동안 요청이 끝나면 안 된다").isFalse();

            lock.commit();
        }
        MvcResult result = pending.get(10, TimeUnit.SECONDS);

        expectProblem(result, 409, "HAS_HOUSEHOLD");
        assertThat(householdIds()).as("요청 쪽 가구 행은 롤백되어 다른 연결이 커밋한 가구만 남는다").containsExactly(lockHousehold);
        assertThat(membershipOf()).isEqualTo(lockHousehold + "|owner");
    }

    @Test
    @DisplayName("T018 B6 US1-4 FR-004 R9: 다른 연결의 커밋하지 않은 멤버십을 기다리던 가구 만들기는 그 연결이 롤백하면 201이고 그 사람의 가구는 하나다")
    void concurrentCreateAfterRollbackIsCreated() throws Exception {
        long lockHousehold;
        Future<MvcResult> pending;
        try (HouseholdLock lock = HouseholdLock.uncommittedMembership(dataSource, person.userId(), HouseholdRole.OWNER)) {
            lockHousehold = lock.householdId();
            pending = submit(() -> create().andReturn());
            lock.awaitWaiting(1);
            assertThat(pending.isDone()).as("커밋하지 않은 멤버십이 있는 동안 요청이 끝나면 안 된다").isFalse();

            lock.rollback();
        }
        MvcResult result = pending.get(10, TimeUnit.SECONDS);

        status().isCreated().match(result);
        assertThat(householdIds()).hasSize(1).doesNotContain(lockHousehold);
        assertThat(membershipOf()).isEqualTo(householdIds().getFirst() + "|owner");
    }

    @Test
    @DisplayName("T018 B6 FR-004 R9: 사용자 행 잠금 뒤에 탈퇴가 먼저, 가구 만들기가 다음에 줄 서면 탈퇴는 204, 가구 만들기는 Problem 형식의 500이고 가구와 멤버십 행이 남지 않는다")
    void withdrawFirstThenCreateFails() throws Exception {
        Future<MvcResult> withdraw;
        Future<HttpResponse<String>> create;
        try (Connection lock = lockUserRow()) {
            withdraw = submit(() -> withdraw().andReturn());
            awaitLockWaiters(1);
            create = submit(createOnRealServer());
            awaitLockWaiters(2);
            assertThat(withdraw.isDone()).isFalse();
            assertThat(create.isDone()).isFalse();

            lock.rollback();
        }
        MvcResult withdrawn = withdraw.get(10, TimeUnit.SECONDS);
        HttpResponse<String> created = create.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(withdrawn);
        assertThat(created.statusCode()).isEqualTo(500);
        assertThat(created.headers().firstValue(HttpHeaders.CONTENT_TYPE).map(MediaType::parseMediaType).orElseThrow()
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).as("500도 Problem 형식").isTrue();
        JsonNode problem = JSON.readTree(created.body());
        assertThat(problem.path("status").isIntegralNumber()).isTrue();
        assertThat(problem.path("status").longValue()).isEqualTo(500);
        assertThat(userCount()).as("탈퇴가 먼저 처리되었다").isZero();
        assertThat(householdIds()).as("가구 만들기의 가구 행은 롤백된다").isEmpty();
        assertThat(jdbc.sql("select count(*) from household_member").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("T018 B6 FR-004 FR-021 R9: 사용자 행 잠금 뒤에 가구 만들기가 먼저, 탈퇴가 다음에 줄 서면 가구 만들기는 201, 탈퇴는 409 HAS_HOUSEHOLD이고 사용자 행이 남는다")
    void createFirstThenWithdrawIsConflict() throws Exception {
        Future<MvcResult> create;
        Future<MvcResult> withdraw;
        try (Connection lock = lockUserRow()) {
            create = submit(() -> create().andReturn());
            awaitLockWaiters(1);
            withdraw = submit(() -> withdraw().andReturn());
            awaitLockWaiters(2);
            assertThat(create.isDone()).isFalse();
            assertThat(withdraw.isDone()).isFalse();

            lock.rollback();
        }
        MvcResult created = create.get(10, TimeUnit.SECONDS);
        MvcResult withdrawn = withdraw.get(10, TimeUnit.SECONDS);

        status().isCreated().match(created);
        expectProblem(withdrawn, 409, "HAS_HOUSEHOLD");
        assertThat(userCount()).as("탈퇴가 거절되어 사용자 행이 남는다").isEqualTo(1);
        assertThat(householdIds()).hasSize(1);
        assertThat(membershipOf()).isEqualTo(householdIds().getFirst() + "|owner");
        assertThat(sessions.rowsOfUser(person.userId())).as("세션도 남는다").isNotEmpty();
    }

    @Test
    @DisplayName("T030 B6 US2-16 FR-015 SC-003 R9: 가구 없는 열 명이 같은 링크의 수락으로 모두 가구 행 잠금을 기다리다 풀리면 1건만 204, 9건은 409 INVITATION_USED이고 구성원이 둘이다")
    void concurrentAcceptOnlyOneJoins() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        String token = households.invite(person, 3);
        List<Person> joiners = new ArrayList<>();
        for (int i = 0; i < ACCEPTORS; i++) {
            joiners.add(households.newPerson());
        }

        List<Future<MvcResult>> accepts = new ArrayList<>();
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            for (Person joiner : joiners) {
                accepts.add(submit(() -> accept(joiner, token).andReturn()));
            }
            lock.awaitWaiting(ACCEPTORS);
            assertThat(accepts).as("가구 행이 잠긴 동안 수락이 끝나면 안 된다").noneMatch(Future::isDone);

            lock.commit();
        }

        List<Long> joined = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < ACCEPTORS; i++) {
            MvcResult result = accepts.get(i).get(10, TimeUnit.SECONDS);
            if (result.getResponse().getStatus() == 204) {
                joined.add(joiners.get(i).userId());
            } else {
                expectProblem(result, 409, "INVITATION_USED");
                used++;
            }
        }
        assertThat(joined).as("수락 성공은 정확히 한 건").hasSize(1);
        assertThat(used).isEqualTo(ACCEPTORS - 1);
        assertThat(jdbc.sql("select user_id || '|' || role from household_member where household_id = :h order by role desc")
                .param("h", household).query(String.class).list())
                .as("구성원은 owner와 수락에 성공한 한 사람뿐").containsExactly(person.userId() + "|owner", joined.getFirst() + "|member");
        assertThat(jdbc.sql("select count(*) from household_member").query(Long.class).single()).as("진 아홉 명에게 멤버십이 없다").isEqualTo(2);
        assertThat(invitationStatuses(household)).containsExactly("수락");
    }

    @Test
    @DisplayName("T030 B6 US2-5 FR-009 R9: 같은 가구의 초대 발급 두 건이 모두 가구 행 잠금을 기다리다 풀리면 1건만 201, 다른 건은 409 INVITATION_PENDING이고 초대 행이 하나다")
    void concurrentIssueOnlyOneCreated() throws Exception {
        long household = new Households(mvc, jdbc, sessions).createHousehold(person);

        Future<MvcResult> first;
        Future<MvcResult> second;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            first = submit(() -> issue(2).andReturn());
            second = submit(() -> issue(5).andReturn());
            lock.awaitWaiting(2);
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();

            lock.commit();
        }
        MvcResult a = first.get(10, TimeUnit.SECONDS);
        MvcResult b = second.get(10, TimeUnit.SECONDS);

        MvcResult created = a.getResponse().getStatus() == 201 ? a : b;
        MvcResult rejected = created == a ? b : a;
        status().isCreated().match(created);
        expectProblem(rejected, 409, "INVITATION_PENDING");
        assertThat(invitationStatuses(household)).as("초대 행은 하나").containsExactly("대기");
        long createdId = JSON.readTree(created.getResponse().getContentAsString()).path("id").longValue();
        assertThat(jdbc.sql("select id from invitation").query(Long.class).single()).as("남은 행은 201 응답의 초대").isEqualTo(createdId);
    }

    @Test
    @DisplayName("T030 B6 FR-011 R8 R9: 가구 행 잠금 뒤에 초대 취소가 먼저, 수락이 다음에 줄 서면 취소는 204, 수락은 409 INVITATION_REVOKED이고 멤버십이 생기지 않는다")
    void revokeFirstThenAcceptIsRevoked() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        String token = households.invite(person, 3);
        long invitation = jdbc.sql("select id from invitation").query(Long.class).single();
        Person joiner = households.newPerson();

        Future<MvcResult> revoke;
        Future<MvcResult> accept;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            revoke = submit(() -> revoke(invitation).andReturn());
            lock.awaitWaiting(1);
            accept = submit(() -> accept(joiner, token).andReturn());
            lock.awaitWaiting(2);
            assertThat(revoke.isDone()).isFalse();
            assertThat(accept.isDone()).isFalse();

            lock.commit();
        }
        MvcResult revoked = revoke.get(10, TimeUnit.SECONDS);
        MvcResult accepted = accept.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(revoked);
        expectProblem(accepted, 409, "INVITATION_REVOKED");
        assertThat(invitationStatuses(household)).containsExactly("취소");
        assertThat(jdbc.sql("select count(*) from household_member where user_id = :u").param("u", joiner.userId())
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from household_member where household_id = :h").param("h", household)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("T030 B6 FR-011 R8 R9: 가구 행 잠금 뒤에 수락이 먼저, 초대 취소가 다음에 줄 서면 수락은 204, 취소는 409 INVITATION_NOT_PENDING이고 초대는 수락 상태다")
    void acceptFirstThenRevokeIsNotPending() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        String token = households.invite(person, 3);
        long invitation = jdbc.sql("select id from invitation").query(Long.class).single();
        Person joiner = households.newPerson();

        Future<MvcResult> accept;
        Future<MvcResult> revoke;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            accept = submit(() -> accept(joiner, token).andReturn());
            lock.awaitWaiting(1);
            revoke = submit(() -> revoke(invitation).andReturn());
            lock.awaitWaiting(2);
            assertThat(accept.isDone()).isFalse();
            assertThat(revoke.isDone()).isFalse();

            lock.commit();
        }
        MvcResult accepted = accept.get(10, TimeUnit.SECONDS);
        MvcResult revoked = revoke.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(accepted);
        expectProblem(revoked, 409, "INVITATION_NOT_PENDING");
        assertThat(invitationStatuses(household)).containsExactly("수락");
        assertThat(jdbc.sql("select household_id || '|' || role from household_member where user_id = :u").param("u", joiner.userId())
                .query(String.class).single()).isEqualTo(household + "|member");
    }

    @Test
    @DisplayName("T030 B6 FR-014 R9 T041: 다른 연결의 커밋하지 않은 멤버십을 기다리던 초대 수락은 그 연결이 커밋하면 409 HAS_HOUSEHOLD이고 초대는 대기, 초대한 가구의 구성원 수는 그대로다")
    void concurrentAcceptAfterCommitIsConflict() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        Person inviter = households.newPerson();
        long household = households.createHousehold(inviter);
        String token = households.invite(inviter, 3);

        long lockHousehold;
        Future<MvcResult> pending;
        try (HouseholdLock lock = HouseholdLock.uncommittedMembership(dataSource, person.userId(), HouseholdRole.OWNER)) {
            lockHousehold = lock.householdId();
            pending = submit(() -> accept(person, token).andReturn());
            lock.awaitWaiting(1);
            assertThat(pending.isDone()).as("커밋하지 않은 멤버십이 있는 동안 수락이 끝나면 안 된다").isFalse();

            lock.commit();
        }
        MvcResult result = pending.get(10, TimeUnit.SECONDS);

        expectProblem(result, 409, "HAS_HOUSEHOLD");
        assertThat(invitationStatuses(household)).as("거절된 수락은 초대 행을 바꾸지 않는다").containsExactly("대기");
        assertThat(jdbc.sql("select user_id || '|' || role from household_member where household_id = :h").param("h", household)
                .query(String.class).list()).as("초대한 가구의 구성원은 owner 한 사람뿐").containsExactly(inviter.userId() + "|owner");
        assertThat(membershipOf()).as("다른 연결이 커밋한 멤버십만 남는다").isEqualTo(lockHousehold + "|owner");
    }

    @Test
    @DisplayName("T030 B6 US2 R9 T041: 다른 연결의 커밋하지 않은 멤버십을 기다리던 초대 수락은 그 연결이 롤백하면 204이고 초대는 수락, 그 사람은 초대한 가구의 member다")
    void concurrentAcceptAfterRollbackJoins() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        Person inviter = households.newPerson();
        long household = households.createHousehold(inviter);
        String token = households.invite(inviter, 3);

        long lockHousehold;
        Future<MvcResult> pending;
        try (HouseholdLock lock = HouseholdLock.uncommittedMembership(dataSource, person.userId(), HouseholdRole.OWNER)) {
            lockHousehold = lock.householdId();
            pending = submit(() -> accept(person, token).andReturn());
            lock.awaitWaiting(1);
            assertThat(pending.isDone()).as("커밋하지 않은 멤버십이 있는 동안 수락이 끝나면 안 된다").isFalse();

            lock.rollback();
        }
        MvcResult result = pending.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(result);
        assertThat(invitationStatuses(household)).containsExactly("수락");
        assertThat(membershipOf()).isEqualTo(household + "|member");
        assertThat(householdIds()).as("롤백된 연결의 가구 행은 남지 않는다").doesNotContain(lockHousehold);
    }

    @Test
    @DisplayName("T049 B6 US3 R3 R9: 가구 행 잠금 뒤에 member의 나가기가 먼저, owner의 그 member 제거가 다음에 줄 서면 나가기는 204, 제거는 404 NOT_FOUND이고 owner 멤버십만 남는다")
    void leaveFirstThenRemoveIsNotFound() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        Person member = households.addMember(person);

        Future<MvcResult> leave;
        Future<MvcResult> remove;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            leave = submit(() -> leave(member).andReturn());
            lock.awaitWaiting(1);
            remove = submit(() -> remove(member).andReturn());
            lock.awaitWaiting(2);
            assertThat(leave.isDone()).isFalse();
            assertThat(remove.isDone()).isFalse();

            lock.commit();
        }
        MvcResult left = leave.get(10, TimeUnit.SECONDS);
        MvcResult removed = remove.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(left);
        expectProblem(removed, 404, "NOT_FOUND");
        assertThat(membershipRows(household)).containsExactly(person.userId() + "|owner");
    }

    @Test
    @DisplayName("T049 B6 US3 R3 R9: 가구 행 잠금 뒤에 owner의 member 제거가 먼저, 그 member의 나가기가 다음에 줄 서면 제거는 204, 나가기는 403 NO_HOUSEHOLD이고 owner 멤버십만 남는다")
    void removeFirstThenLeaveIsNoHousehold() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        Person member = households.addMember(person);

        Future<MvcResult> remove;
        Future<MvcResult> leave;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            remove = submit(() -> remove(member).andReturn());
            lock.awaitWaiting(1);
            leave = submit(() -> leave(member).andReturn());
            lock.awaitWaiting(2);
            assertThat(remove.isDone()).isFalse();
            assertThat(leave.isDone()).isFalse();

            lock.commit();
        }
        MvcResult removed = remove.get(10, TimeUnit.SECONDS);
        MvcResult left = leave.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(removed);
        expectProblem(left, 403, "NO_HOUSEHOLD");
        assertThat(membershipRows(household)).containsExactly(person.userId() + "|owner");
    }

    @Test
    @DisplayName("T065 B6 US4-5 FR-020 R9: 가구 행 잠금 뒤에 가구 삭제가 먼저, 수락이 다음에 줄 서면 삭제는 204, 수락은 404 NOT_FOUND이고 멤버십이 생기지 않는다")
    void deleteFirstThenAcceptIsNotFound() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        String token = households.invite(person, 3);
        Person joiner = households.newPerson();

        Future<MvcResult> deletion;
        Future<MvcResult> accept;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            deletion = submit(() -> deleteHousehold().andReturn());
            lock.awaitWaiting(1);
            accept = submit(() -> accept(joiner, token).andReturn());
            lock.awaitWaiting(2);
            assertThat(deletion.isDone()).isFalse();
            assertThat(accept.isDone()).isFalse();

            lock.commit();
        }
        MvcResult deleted = deletion.get(10, TimeUnit.SECONDS);
        MvcResult accepted = accept.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(deleted);
        expectProblem(accepted, 404, "NOT_FOUND");
        assertThat(householdIds()).isEmpty();
        assertThat(allMemberships()).as("수락한 사람의 멤버십이 생기지 않는다").isEmpty();
        assertThat(invitationStatuses(household)).isEmpty();
    }

    @Test
    @DisplayName("T065 B6 US4-4 FR-020 R9: 가구 행 잠금 뒤에 수락이 먼저, 가구 삭제가 다음에 줄 서면 둘 다 204이고 삭제가 새 멤버십까지 지운다")
    void acceptFirstThenDeleteRemovesJoiner() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        String token = households.invite(person, 3);
        Person joiner = households.newPerson();

        Future<MvcResult> accept;
        Future<MvcResult> deletion;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            accept = submit(() -> accept(joiner, token).andReturn());
            lock.awaitWaiting(1);
            deletion = submit(() -> deleteHousehold().andReturn());
            lock.awaitWaiting(2);
            assertThat(accept.isDone()).isFalse();
            assertThat(deletion.isDone()).isFalse();

            lock.commit();
        }
        MvcResult accepted = accept.get(10, TimeUnit.SECONDS);
        MvcResult deleted = deletion.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(accepted);
        status().isNoContent().match(deleted);
        assertThat(householdIds()).isEmpty();
        assertThat(allMemberships()).as("수락으로 생긴 멤버십도 삭제된다").isEmpty();
        assertThat(invitationStatuses(household)).isEmpty();
        assertThat(jdbc.sql("select count(*) from \"user\" where id in (:ids)").param("ids", List.of(person.userId(), joiner.userId()))
                .query(Long.class).single()).as("사용자 행은 남는다").isEqualTo(2);
    }

    @Test
    @DisplayName("T065 B6 US4 R3 R9: 가구 행 잠금 뒤에 가구 삭제가 먼저, 초대 발급이 다음에 줄 서면 삭제는 204, 발급은 403 NO_HOUSEHOLD이고 초대 행이 생기지 않는다")
    void deleteFirstThenIssueIsNoHousehold() throws Exception {
        long household = new Households(mvc, jdbc, sessions).createHousehold(person);

        Future<MvcResult> deletion;
        Future<MvcResult> issue;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            deletion = submit(() -> deleteHousehold().andReturn());
            lock.awaitWaiting(1);
            issue = submit(() -> issue(3).andReturn());
            lock.awaitWaiting(2);
            assertThat(deletion.isDone()).isFalse();
            assertThat(issue.isDone()).isFalse();

            lock.commit();
        }
        MvcResult deleted = deletion.get(10, TimeUnit.SECONDS);
        MvcResult issued = issue.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(deleted);
        expectProblem(issued, 403, "NO_HOUSEHOLD");
        assertThat(jdbc.sql("select count(*) from invitation").query(Long.class).single()).isZero();
        assertThat(householdIds()).isEmpty();
    }

    @Test
    @DisplayName("T065 B6 US4 FR-020 R9: 가구 행 잠금 뒤에 초대 발급이 먼저, 가구 삭제가 다음에 줄 서면 발급은 201, 삭제는 204이고 그 새 초대까지 지운다")
    void issueFirstThenDeleteRemovesInvite() throws Exception {
        long household = new Households(mvc, jdbc, sessions).createHousehold(person);

        Future<MvcResult> issue;
        Future<MvcResult> deletion;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            issue = submit(() -> issue(3).andReturn());
            lock.awaitWaiting(1);
            deletion = submit(() -> deleteHousehold().andReturn());
            lock.awaitWaiting(2);
            assertThat(issue.isDone()).isFalse();
            assertThat(deletion.isDone()).isFalse();

            lock.commit();
        }
        MvcResult issued = issue.get(10, TimeUnit.SECONDS);
        MvcResult deleted = deletion.get(10, TimeUnit.SECONDS);

        status().isCreated().match(issued);
        status().isNoContent().match(deleted);
        assertThat(jdbc.sql("select count(*) from invitation").query(Long.class).single()).as("발급된 초대도 삭제된다").isZero();
        assertThat(householdIds()).isEmpty();
        assertThat(allMemberships()).isEmpty();
    }

    @Test
    @DisplayName("T065 B6 US4 R3 R9: 가구 행 잠금 뒤에 가구 삭제가 먼저, member의 나가기가 다음에 줄 서면 삭제는 204, 나가기는 403 NO_HOUSEHOLD이고 멤버십과 가구 행이 없다")
    void deleteFirstThenLeaveIsNoHousehold() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        Person member = households.addMember(person);

        Future<MvcResult> deletion;
        Future<MvcResult> leave;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            deletion = submit(() -> deleteHousehold().andReturn());
            lock.awaitWaiting(1);
            leave = submit(() -> leave(member).andReturn());
            lock.awaitWaiting(2);
            assertThat(deletion.isDone()).isFalse();
            assertThat(leave.isDone()).isFalse();

            lock.commit();
        }
        MvcResult deleted = deletion.get(10, TimeUnit.SECONDS);
        MvcResult left = leave.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(deleted);
        expectProblem(left, 403, "NO_HOUSEHOLD");
        assertThat(householdIds()).isEmpty();
        assertThat(allMemberships()).isEmpty();
        assertThat(jdbc.sql("select count(*) from invitation").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("T065 B6 US4 R10 R9: 가구 행 잠금 뒤에 같은 owner의 가구 삭제 둘이 줄 서면 먼저 것은 204, 다음 것은 잠금 조회가 빈 결과라 403 NO_HOUSEHOLD다")
    void secondQueuedDeleteIsNoHousehold() throws Exception {
        long household = new Households(mvc, jdbc, sessions).createHousehold(person);

        Future<MvcResult> first;
        Future<MvcResult> second;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            first = submit(() -> deleteHousehold().andReturn());
            lock.awaitWaiting(1);
            second = submit(() -> deleteHousehold().andReturn());
            lock.awaitWaiting(2);
            lock.commit();
        }

        status().isNoContent().match(first.get(10, TimeUnit.SECONDS));
        expectProblem(second.get(10, TimeUnit.SECONDS), 403, "NO_HOUSEHOLD");
        assertThat(householdIds()).isEmpty();
    }

    @Test
    @DisplayName("T065 B3 US4 R10: 재인증 판정이 가구 행 잠금보다 먼저라, 다른 트랜잭션이 잠금을 쥐고 있어도 인증이 오래된 owner의 가구 삭제는 기다리지 않고 401 REAUTH_REQUIRED다")
    void staleDeleteDoesNotWaitForLock() throws Exception {
        long household = new Households(mvc, jdbc, sessions).createHousehold(person);
        clock.advance(Duration.ofMinutes(10));

        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            Future<MvcResult> deletion = submit(() -> deleteHousehold().andReturn());
            expectProblem(deletion.get(5, TimeUnit.SECONDS), 401, "REAUTH_REQUIRED");
            lock.commit();
        }
        assertThat(householdIds()).containsExactly(household);
    }

    @Test
    @DisplayName("T065 B6 US4 R9: 가구 행 잠금 뒤에 member의 나가기가 먼저, 가구 삭제가 다음에 줄 서면 둘 다 204이고 가구 행과 멤버십이 없다")
    void leaveFirstThenDeleteRemovesHousehold() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        Person member = households.addMember(person);

        Future<MvcResult> leave;
        Future<MvcResult> deletion;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            leave = submit(() -> leave(member).andReturn());
            lock.awaitWaiting(1);
            deletion = submit(() -> deleteHousehold().andReturn());
            lock.awaitWaiting(2);
            assertThat(leave.isDone()).isFalse();
            assertThat(deletion.isDone()).isFalse();

            lock.commit();
        }
        MvcResult left = leave.get(10, TimeUnit.SECONDS);
        MvcResult deleted = deletion.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(left);
        status().isNoContent().match(deleted);
        assertThat(householdIds()).isEmpty();
        assertThat(allMemberships()).isEmpty();
        assertThat(jdbc.sql("select count(*) from invitation").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("T065 B6 FR-021 R9: 사용자 행 잠금 뒤에 탈퇴가 먼저, 같은 사람의 수락이 다음에 줄 서면 탈퇴는 204, 수락은 Problem 형식의 500이고 멤버십이 없다")
    void withdrawFirstThenAcceptFails() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        Person inviter = households.newPerson();
        long household = households.createHousehold(inviter);
        String token = households.invite(inviter, 3);

        Future<MvcResult> withdraw;
        Future<HttpResponse<String>> accept;
        try (Connection lock = lockUserRow()) {
            withdraw = submit(() -> withdraw().andReturn());
            awaitLockWaiters(1);
            accept = submit(acceptOnRealServer(token));
            awaitLockWaiters(2);
            assertThat(withdraw.isDone()).isFalse();
            assertThat(accept.isDone()).isFalse();

            lock.rollback();
        }
        MvcResult withdrawn = withdraw.get(10, TimeUnit.SECONDS);
        HttpResponse<String> accepted = accept.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(withdrawn);
        assertThat(accepted.statusCode()).isEqualTo(500);
        assertThat(accepted.headers().firstValue(HttpHeaders.CONTENT_TYPE).map(MediaType::parseMediaType).orElseThrow()
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).as("500도 Problem 형식").isTrue();
        JsonNode problem = JSON.readTree(accepted.body());
        assertThat(problem.path("status").isIntegralNumber()).isTrue();
        assertThat(problem.path("status").longValue()).isEqualTo(500);
        assertThat(userCount()).as("탈퇴가 먼저 처리되었다").isZero();
        assertThat(jdbc.sql("select count(*) from household_member where user_id = :u").param("u", person.userId())
                .query(Long.class).single()).isZero();
        assertThat(membershipRows(household)).as("초대한 가구의 구성원은 그대로").containsExactly(inviter.userId() + "|owner");
        assertThat(invitationStatuses(household)).as("수락은 롤백되어 초대가 대기로 남는다").containsExactly("대기");
        assertThat(orphanMemberships()).isZero();
    }

    @Test
    @DisplayName("T065 B6 FR-021 R9 R13: 사용자 행 잠금 뒤에 수락이 먼저, 같은 사람의 탈퇴가 다음에 줄 서면 수락은 204, 탈퇴는 409 HAS_HOUSEHOLD이고 사용자 행이 남는다")
    void acceptFirstThenWithdrawIsConflict() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        Person inviter = households.newPerson();
        long household = households.createHousehold(inviter);
        String token = households.invite(inviter, 3);

        Future<MvcResult> accept;
        Future<MvcResult> withdraw;
        try (Connection lock = lockUserRow()) {
            accept = submit(() -> accept(person, token).andReturn());
            awaitLockWaiters(1);
            withdraw = submit(() -> withdraw().andReturn());
            awaitLockWaiters(2);
            assertThat(accept.isDone()).isFalse();
            assertThat(withdraw.isDone()).isFalse();

            lock.rollback();
        }
        MvcResult accepted = accept.get(10, TimeUnit.SECONDS);
        MvcResult withdrawn = withdraw.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(accepted);
        expectProblem(withdrawn, 409, "HAS_HOUSEHOLD");
        assertThat(userCount()).as("탈퇴가 거절되어 사용자 행이 남는다").isEqualTo(1);
        assertThat(membershipOf()).isEqualTo(household + "|member");
        assertThat(sessions.rowsOfUser(person.userId())).as("세션도 남는다").isNotEmpty();
        assertThat(orphanMemberships()).isZero();
    }

    @Test
    @DisplayName("T065 B6 FR-004 FR-014 R9: 사용자 행 잠금 뒤에 가구 만들기가 먼저, 같은 사람의 수락이 다음에 줄 서면 만들기는 201, 수락은 409 HAS_HOUSEHOLD이고 멤버십 행이 하나다")
    void createFirstThenAcceptIsConflict() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        Person inviter = households.newPerson();
        long household = households.createHousehold(inviter);
        String token = households.invite(inviter, 3);

        Future<MvcResult> create;
        Future<MvcResult> accept;
        // 멤버십 삽입의 외래 키 확인(FOR KEY SHARE)이 잠금을 기다리는 동안 먼저 넣은 쪽의 user_id 유니크 항목이 뒤에 온 삽입을 그 트랜잭션 뒤에 세운다
        try (Connection lock = lockUserRow()) {
            create = submit(() -> create().andReturn());
            awaitLockWaiters(1);
            accept = submit(() -> accept(person, token).andReturn());
            awaitLockWaiters(2);
            assertThat(create.isDone()).isFalse();
            assertThat(accept.isDone()).isFalse();

            lock.rollback();
        }
        MvcResult created = create.get(10, TimeUnit.SECONDS);
        MvcResult accepted = accept.get(10, TimeUnit.SECONDS);

        status().isCreated().match(created);
        expectProblem(accepted, 409, "HAS_HOUSEHOLD");
        long own = householdIds().stream().filter(id -> id != household).findFirst().orElseThrow();
        assertThat(membershipOf()).as("그 사람의 멤버십은 만든 가구의 owner 하나").isEqualTo(own + "|owner");
        assertThat(membershipRows(household)).containsExactly(inviter.userId() + "|owner");
        assertThat(invitationStatuses(household)).containsExactly("대기");
    }

    @Test
    @DisplayName("T065 B6 FR-004 FR-014 R9: 사용자 행 잠금 뒤에 수락이 먼저, 같은 사람의 가구 만들기가 다음에 줄 서면 수락은 204, 만들기는 409 HAS_HOUSEHOLD이고 멤버십 행이 하나다")
    void acceptFirstThenCreateIsConflict() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        Person inviter = households.newPerson();
        long household = households.createHousehold(inviter);
        String token = households.invite(inviter, 3);

        Future<MvcResult> accept;
        Future<MvcResult> create;
        try (Connection lock = lockUserRow()) {
            accept = submit(() -> accept(person, token).andReturn());
            awaitLockWaiters(1);
            create = submit(() -> create().andReturn());
            awaitLockWaiters(2);
            assertThat(accept.isDone()).isFalse();
            assertThat(create.isDone()).isFalse();

            lock.rollback();
        }
        MvcResult accepted = accept.get(10, TimeUnit.SECONDS);
        MvcResult created = create.get(10, TimeUnit.SECONDS);

        status().isNoContent().match(accepted);
        expectProblem(created, 409, "HAS_HOUSEHOLD");
        assertThat(membershipOf()).as("그 사람의 멤버십은 초대한 가구의 member 하나").isEqualTo(household + "|member");
        assertThat(householdIds()).as("만들기의 가구 행은 롤백된다").containsExactly(household);
        assertThat(invitationStatuses(household)).containsExactly("수락");
    }

    @Test
    @DisplayName("T065 R3 R9: 권한 확인기를 통과한 뒤 가구가 삭제되었으면 서비스의 구성원 제거와 가구 나가기는 404가 아니라 403 NO_HOUSEHOLD다")
    void serviceAfterDeletionIsNoHousehold() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        Person member = households.addMember(person);
        // 권한 확인기가 멤버십을 읽은 뒤 서비스가 잠금을 얻기 전에 가구 삭제가 커밋된 상황
        jdbc.sql("delete from invitation where household_id = :h").param("h", household).update();
        jdbc.sql("delete from household_member where household_id = :h").param("h", household).update();
        jdbc.sql("delete from household where id = :h").param("h", household).update();

        assertThatThrownBy(() -> householdService.removeMember(household, person.userId(), Instant.now(), String.valueOf(member.userId())))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.problem().code()).isEqualTo(ProblemCode.NO_HOUSEHOLD));
        assertThatThrownBy(() -> householdService.leave(household, member.userId()))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.problem().code()).isEqualTo(ProblemCode.NO_HOUSEHOLD));
    }

    @Test
    @DisplayName("T065 B6 US4 R3 R9: 가구 행 잠금 뒤에 가구 삭제가 먼저, 같은 owner의 구성원 제거가 다음에 줄 서면 삭제는 204, 제거는 잠금 조회가 빈 결과라 403 NO_HOUSEHOLD다")
    void deleteFirstThenRemoveIsNoHousehold() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        Person member = households.addMember(person);

        Future<MvcResult> deletion;
        Future<MvcResult> removal;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            deletion = submit(() -> deleteHousehold().andReturn());
            lock.awaitWaiting(1);
            removal = submit(() -> mvc.perform(https(delete("/api/v1/household/members/" + member.userId()))
                    .header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie())).andReturn());
            lock.awaitWaiting(2);
            lock.commit();
        }

        status().isNoContent().match(deletion.get(10, TimeUnit.SECONDS));
        expectProblem(removal.get(10, TimeUnit.SECONDS), 403, "NO_HOUSEHOLD");
        assertThat(householdIds()).isEmpty();
        assertThat(allMemberships()).isEmpty();
    }

    @Test
    @DisplayName("T065 B6 US4 R3 R9: 가구 행 잠금 뒤에 가구 삭제가 먼저, 같은 owner의 초대 취소가 다음에 줄 서면 삭제는 204, 취소는 잠금 조회가 빈 결과라 403 NO_HOUSEHOLD다")
    void deleteFirstThenRevokeIsNoHousehold() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        households.invite(person, 3);
        long invitation = jdbc.sql("select id from invitation where household_id = :h").param("h", household).query(Long.class).single();

        Future<MvcResult> deletion;
        Future<MvcResult> revoke;
        try (HouseholdLock lock = HouseholdLock.holding(dataSource, household)) {
            deletion = submit(() -> deleteHousehold().andReturn());
            lock.awaitWaiting(1);
            revoke = submit(() -> mvc.perform(https(delete("/api/v1/household/invitations/" + invitation))
                    .header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie())).andReturn());
            lock.awaitWaiting(2);
            lock.commit();
        }

        status().isNoContent().match(deletion.get(10, TimeUnit.SECONDS));
        expectProblem(revoke.get(10, TimeUnit.SECONDS), 403, "NO_HOUSEHOLD");
        assertThat(householdIds()).isEmpty();
        assertThat(jdbc.sql("select count(*) from invitation").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("T062 B3 FR-022: 마지막 구글 인증 기록이 없으면(null) 서비스의 가구 삭제는 401 REAUTH_REQUIRED이고 가구, 멤버십, 초대가 그대로다(HTTP로는 기록 없는 세션을 만들 수 없어 서비스를 직접 부른다)")
    void deleteWithoutAuthRecordIsReauth() throws Exception {
        Households households = new Households(mvc, jdbc, sessions);
        long household = households.createHousehold(person);
        households.invite(person, 3);

        assertThatThrownBy(() -> householdService.deleteHousehold(household, null))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.problem().code()).isEqualTo(ProblemCode.REAUTH_REQUIRED));
        assertThat(householdIds()).containsExactly(household);
        assertThat(allMemberships()).containsExactly(household + "|" + person.userId());
        assertThat(jdbc.sql("select count(*) from invitation where household_id = :h").param("h", household).query(Long.class).single())
                .isEqualTo(1);
    }

    /** owner(person)가 보낸다. 로그인 직후라 재인증 판정(5분)을 통과한다. */
    private ResultActions deleteHousehold() throws Exception {
        return mvc.perform(https(delete("/api/v1/household")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie()));
    }

    private List<String> allMemberships() {
        return jdbc.sql("select household_id || '|' || user_id from household_member").query(String.class).list();
    }

    /** 사용자 행이 없는 멤버십 수(외래 키가 막으므로 늘 0이어야 한다). */
    private long orphanMemberships() {
        return jdbc.sql("select count(*) from household_member m where not exists (select 1 from \"user\" u where u.id = m.user_id)")
                .query(Long.class).single();
    }

    private Callable<HttpResponse<String>> acceptOnRealServer(String token) {
        RealServerClient server = new RealServerClient(port);
        return () -> server.send(HttpRequest.newBuilder(server.url("/api/v1/invitations/accept"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\": \"" + token + "\"}"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Origin", RealServerClient.ORIGIN)
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + person.cookie().getValue()));
    }

    private ResultActions leave(Person member) throws Exception {
        return mvc.perform(https(delete("/api/v1/household/membership")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(member.cookie()));
    }

    /** owner(person)가 보낸다. 로그인 직후라 재인증 판정(5분)을 통과한다. */
    private ResultActions remove(Person member) throws Exception {
        return mvc.perform(https(delete("/api/v1/household/members/" + member.userId())).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(person.cookie()));
    }

    private List<String> membershipRows(long household) {
        return jdbc.sql("select user_id || '|' || role from household_member where household_id = :h order by user_id")
                .param("h", household).query(String.class).list();
    }

    private ResultActions accept(Person joiner, String token) throws Exception {
        return mvc.perform(https(post("/api/v1/invitations/accept")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(joiner.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\": \"" + token + "\"}"));
    }

    /** 로그인 직후라 재인증 판정(5분)을 통과한다. */
    private ResultActions issue(int days) throws Exception {
        return mvc.perform(https(post("/api/v1/household/invitations")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\": " + days + "}"));
    }

    private ResultActions revoke(long invitationId) throws Exception {
        return mvc.perform(https(delete("/api/v1/household/invitations/" + invitationId)).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(person.cookie()));
    }

    private List<String> invitationStatuses(long household) {
        return jdbc.sql("select status from invitation where household_id = :h order by id").param("h", household).query(String.class).list();
    }

    private <T> Future<T> submit(Callable<T> task) {
        Future<T> future = executor.submit(task);
        sent.add(future);
        return future;
    }

    private ResultActions create() throws Exception {
        return mvc.perform(https(post("/api/v1/household")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie()));
    }

    /** 로그인 직후라 재인증 판정(5분)을 통과한다. 시계는 옮기지 않는다. */
    private ResultActions withdraw() throws Exception {
        return mvc.perform(https(delete("/api/v1/me")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie()));
    }

    private Callable<HttpResponse<String>> createOnRealServer() {
        RealServerClient server = new RealServerClient(port);
        return () -> server.send(HttpRequest.newBuilder(server.url("/api/v1/household"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Origin", RealServerClient.ORIGIN)
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + person.cookie().getValue()));
    }

    /** 다른 스레드에서 받은 응답이 이 상태와 code의 Problem이다. */
    private static void expectProblem(MvcResult result, int status, String code) throws Exception {
        status().is(status).match(result);
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
        jsonPath("$.status").value(status).match(result);
        jsonPath("$.code").value(code).match(result);
    }

    /** 별도 연결의 열린 트랜잭션에서 이 사람의 사용자 행을 FOR UPDATE로 잡는다. close는 커밋하지 않은 채 닫아 롤백된다. */
    private Connection lockUserRow() throws Exception {
        Connection c = dataSource.getConnection();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("select id from \"user\" where id = ? for update")) {
                ps.setLong(1, person.userId());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("잠글 사용자 행이 있어야 한다").isTrue();
                }
            }
            return c;
        } catch (Exception | AssertionError e) {
            c.rollback();
            c.close();
            throw e;
        }
    }

    /** 잠금을 기다리는 연결이 n개가 될 때까지 기다린다(HouseholdLock.awaitWaiting과 같은 셈). */
    private void awaitLockWaiters(int n) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        long waiting = -1;
        while (System.nanoTime() < deadline) {
            waiting = jdbc.sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()")
                    .query(Long.class).single();
            if (waiting == n) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("잠금을 기다리는 연결이 " + n + "개가 되지 않았다(마지막 확인 " + waiting + "개)");
    }

    private long userCount() {
        return jdbc.sql("select count(*) from \"user\" where id = :u").param("u", person.userId()).query(Long.class).single();
    }

    private List<Long> householdIds() {
        return jdbc.sql("select id from household order by id").query(Long.class).list();
    }

    /** 이 사람의 멤버십 한 행(가구 번호|역할). 없거나 둘 이상이면 예외. */
    private String membershipOf() {
        return jdbc.sql("select household_id || '|' || role from household_member where user_id = :u")
                .param("u", person.userId()).query(String.class).single();
    }
}
