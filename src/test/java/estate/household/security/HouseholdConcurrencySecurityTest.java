package estate.household.security;

import static estate.household.security.AccessInterceptorSecurityTest.https;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Disabled;
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

import estate.household.domain.HouseholdRole;
import estate.support.HouseholdLock;
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

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;
    @Autowired
    DataSource dataSource;
    @LocalServerPort
    int port;

    Person person;
    ExecutorService executor;
    final List<Future<?>> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        person = new Households(mvc, jdbc, sessions).newPerson();
        assertThat(userCount()).as("가구 행이 없다는 확인이 의미가 있도록 사용자 행이 먼저 있어야 한다").isEqualTo(1);
        executor = Executors.newFixedThreadPool(2);
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
    @Disabled("T071에서 켬") // 잠금 뒤 멤버십 재확인(WithdrawService의 hasMembership, research R13)은 US4 T071이 만든다
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
