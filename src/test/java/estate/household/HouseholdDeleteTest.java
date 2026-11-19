package estate.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import estate.support.Households;
import estate.support.Households.Person;
import estate.support.Households.World;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T069 US4: 가구 삭제(DELETE /api/v1/household)의 동작(FR-019~FR-021, FR-025). member는 초대 발급과 수락 API로 만든다.
 * 권한, 응답 순서, 재인증, 잠금 순서는 security 패키지의 B1~B6, B11 테스트가 맡는다. 모든 요청은 로그인 직후(마지막 구글 인증 5분 안)에 보낸다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class HouseholdDeleteTest {

    private static final String ORIGIN = "https://localhost";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;

    Households households;
    World world;

    @BeforeEach
    void setUp() throws Exception {
        households = new Households(mvc, jdbc, sessions);
        world = households.world();
    }

    @Test
    @DisplayName("T069 US4-4 FR-019: owner가 가구를 삭제하면 204이고 본문이 없으며 owner와 member의 다음 GET /api/v1/household가 403 NO_HOUSEHOLD이고 가구, 멤버십, 초대 행이 없어지고 owner가 가구를 다시 만들 수 있다")
    void ownerDeletesThenRecreates() throws Exception {
        // 가구 A는 구성원이 둘이라 대기 초대를 API로 만들 수 없다(FR-009). 삭제될 대기 초대 행을 SQL로 넣는다
        Instant now = Instant.now();
        households.insertInvitation(world.householdA(), world.ownerA().userId(), "대기", now, now.plus(Duration.ofDays(3)));
        households.invite(world.ownerB(), 3);
        List<Map<String, Object>> otherHousehold = rowsOfHousehold(world.householdB());

        byte[] body = deleteHousehold(world.ownerA()).andExpect(status().isNoContent()).andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEmpty();
        assertThat(rowsOfHousehold(world.householdA())).as("가구 A의 가구, 멤버십, 초대 행이 모두 없다").isEmpty();
        assertThat(rowsOfHousehold(world.householdB())).as("가구 B의 가구, 멤버십, 초대 행이 그대로").isEqualTo(otherHousehold).isNotEmpty();
        for (Person person : new Person[] {world.ownerA(), world.memberA()}) {
            assertThat(code(list(person).andExpect(status().isForbidden()))).as("삭제된 가구의 구성원이었던 사람").isEqualTo("NO_HOUSEHOLD");
            assertThat(jdbc.sql("select count(*) from \"user\" where id = :u").param("u", person.userId()).query(Long.class).single())
                    .as("사용자 행이 남는다").isEqualTo(1);
            assertThat(sessions.exists(SessionRows.idOfCookie(person.cookie().getValue()))).as("세션 행이 남는다").isTrue();
        }

        mvc.perform(write(post("/api/v1/household"), world.ownerA())).andExpect(status().isCreated());

        long recreated = households.householdIdOf(world.ownerA());
        assertThat(recreated).as("새 가구").isNotIn(world.householdA(), world.householdB());
        assertThat(jdbc.sql("select user_id || '|' || role from household_member where household_id = :h").param("h", recreated)
                .query(String.class).list()).containsExactly(world.ownerA().userId() + "|owner");
        list(world.ownerA()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T069 US2-13 spec Edge Cases: 가구 B의 대기 링크는 owner A의 수락이 409 HAS_HOUSEHOLD이고, owner A가 자기 가구를 삭제한 뒤에는 같은 링크가 204이며 가구 B의 member가 된다")
    void deleteThenAcceptOtherLink() throws Exception {
        String token = households.invite(world.ownerB(), 3);
        assertThat(code(acceptRaw(world.ownerA(), token).andExpect(status().isConflict()))).isEqualTo("HAS_HOUSEHOLD");
        assertThat(invitationStatusOf(world.householdB())).as("거절된 수락은 링크를 쓰지 않는다").isEqualTo("대기");

        deleteHousehold(world.ownerA()).andExpect(status().isNoContent());
        acceptRaw(world.ownerA(), token).andExpect(status().isNoContent());

        Map<String, Object> row = jdbc.sql("select household_id, role from household_member where user_id = :u")
                .param("u", world.ownerA().userId()).query().singleRow();
        assertThat(row.get("household_id")).isEqualTo(world.householdB());
        assertThat(row.get("role")).isEqualTo("member");
        assertThat(invitationStatusOf(world.householdB())).isEqualTo("수락");
        list(world.ownerA()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T069 US4-8 FR-025: 가구를 삭제한 owner가 탈퇴하면 001대로 204이고 사용자 행과 세션 행이 삭제되며 member의 사용자 행과 세션 행은 남는다")
    void withdrawAfterDelete() throws Exception {
        deleteHousehold(world.ownerA()).andExpect(status().isNoContent());

        mvc.perform(write(delete("/api/v1/me"), world.ownerA())).andExpect(status().isNoContent());

        assertThat(jdbc.sql("select count(*) from \"user\" where id = :u").param("u", world.ownerA().userId()).query(Long.class).single())
                .as("owner의 사용자 행이 삭제된다").isZero();
        assertThat(sessions.exists(SessionRows.idOfCookie(world.ownerA().cookie().getValue()))).as("owner의 세션 행이 삭제된다").isFalse();
        assertThat(jdbc.sql("select count(*) from \"user\" where id = :u").param("u", world.memberA().userId()).query(Long.class).single())
                .as("member의 사용자 행이 남는다").isEqualTo(1);
        assertThat(sessions.exists(SessionRows.idOfCookie(world.memberA().cookie().getValue()))).as("member의 세션 행이 남는다").isTrue();
    }

    private ResultActions deleteHousehold(Person person) throws Exception {
        return mvc.perform(write(delete("/api/v1/household"), person));
    }

    private ResultActions list(Person person) throws Exception {
        return mvc.perform(https(get("/api/v1/household")).cookie(person.cookie()));
    }

    private ResultActions acceptRaw(Person person, String token) throws Exception {
        return mvc.perform(write(post("/api/v1/invitations/accept"), person).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\"}"));
    }

    private static String code(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("code").stringValue();
    }

    private String invitationStatusOf(long household) {
        return jdbc.sql("select status from invitation where household_id = :h").param("h", household).query(String.class).single();
    }

    /** 이 가구의 가구, 멤버십, 초대 행(어느 행이 남았는지 보도록 번호와 값을 모두 준다). */
    private List<Map<String, Object>> rowsOfHousehold(long household) {
        return jdbc.sql("""
                select 'household' as kind, id::text as key, '' as detail from household where id = :h
                union all select 'member', user_id::text, role || joined_at from household_member where household_id = :h
                union all select 'invitation', id::text, status || created_by || expires_at from invitation where household_id = :h
                order by 1, 2""").param("h", household).query().listOfRows();
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, Person person) {
        return https(builder).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie());
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
