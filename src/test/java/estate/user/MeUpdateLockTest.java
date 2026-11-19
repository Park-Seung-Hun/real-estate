package estate.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import estate.support.HouseholdLock;
import estate.support.Households;
import estate.support.Households.Person;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T075 research R15: 표시 이름 수정(PATCH /api/v1/me)이 트랜잭션 안에서 세션 사용자의 행을 잠근다.
 * 다른 연결이 그 행을 {@code for update}로 잡은 동안 요청이 잠금을 기다리는 것을 pg_stat_activity로 확인하고, 풀린 뒤에 저장되는지 본다.
 * 잠금 없이 읽어도 커밋 때 UPDATE가 같은 잠금을 기다리므로, 잠금을 잡은 쪽이 바꾼 열이 남는지로 읽기 전에 잠갔는지를 가른다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class MeUpdateLockTest {

    private static final String ORIGIN = "https://localhost";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;
    @Autowired
    DataSource dataSource;

    Person person;
    ExecutorService executor;
    Future<MvcResult> sent;

    @BeforeEach
    void setUp() throws Exception {
        person = new Households(mvc, jdbc, sessions).newPerson();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        // 단언이 실패한 경로에서도 서버 쪽 트랜잭션이 끝난 뒤 다음 테스트로 넘어가게 한다
        if (sent != null) {
            try {
                sent.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 요청 자체의 실패는 테스트의 단언이 본다
            }
        }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("요청 스레드가 끝나야 한다").isTrue();
    }

    @Test
    @DisplayName("T075 R15 FR-018: 다른 연결이 사용자 행을 FOR UPDATE로 잡은 동안 표시 이름 수정은 그 잠금을 기다리고, 풀리면 200이고 새 이름이 저장되며 그 연결이 바꾼 다른 열을 덮어쓰지 않는다")
    void patchWaitsForUserRowLock() throws Exception {
        String before = storedName();
        boolean verifiedBefore = storedVerified();
        try (HouseholdLock lock = HouseholdLock.holdingUser(dataSource, person.userId())) {
            sent = executor.submit(() -> mvc.perform(patch("/api/v1/me").secure(true)
                            .with(request -> {
                                request.setScheme("https");
                                request.setServerPort(443);
                                return request;
                            })
                            .header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie())
                            .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8")
                            .content("{\"displayName\":\"잠금 뒤 이름\"}")).andReturn());
            lock.awaitWaiting(1);
            assertThat(sent.isDone()).as("사용자 행이 잠긴 동안 요청이 끝나면 안 된다").isFalse();
            assertThat(storedName()).as("기다리는 동안 이름은 그대로다").isEqualTo(before);

            // 로그인 콜백처럼 잠금을 잡은 쪽이 검증 여부를 바꾼다(001 research R5). 잠금 없이 먼저 읽었다면 수정이 옛 값으로 덮어쓴다.
            lock.execute("update \"user\" set email_verified = " + !verifiedBefore + " where id = " + person.userId());
            lock.commit();
        }
        MvcResult result = sent.get(10, TimeUnit.SECONDS);

        status().isOk().match(result);
        jsonPath("$.displayName").value("잠금 뒤 이름").match(result);
        assertThat(storedName()).isEqualTo("잠금 뒤 이름");
        assertThat(storedVerified()).as("잠금을 잡은 쪽이 바꾼 검증 여부가 남는다").isEqualTo(!verifiedBefore);
    }

    private boolean storedVerified() {
        return jdbc.sql("select email_verified from \"user\" where id = :u").param("u", person.userId()).query(Boolean.class).single();
    }

    private String storedName() {
        return jdbc.sql("select display_name from \"user\" where id = :u").param("u", person.userId()).query(String.class).single();
    }
}
