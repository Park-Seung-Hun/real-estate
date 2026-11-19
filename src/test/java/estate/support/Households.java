package estate.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 가구 테스트 도우미: {@code new Households(mockMvc, jdbc, sessions)}. 가구 만들기, 초대 발급, 수락은 계약의 API로 하고(T021, T042 뒤에 동작),
 * API로 만들 수 없는 상태(구성원이 둘이면서 수락 대기 중인 초대, 정해진 status, created_at, expires_at의 초대)만 {@code insertInvitation}으로 SQL에 넣는다.
 * 구성원이 둘이면서 수락 대기 중인 초대가 있는 상태: {@code world()} 뒤에 {@code insertInvitation(world.householdA(), world.ownerA().userId(), "대기", ...)}.
 */
public final class Households {

    /** 로그인한 가짜 계정. */
    public record Person(Cookie cookie, long userId) {
    }

    /** 가구 A(ownerA, memberA), 가구 B(ownerB), 가구 없는 사람(loner). */
    public record World(long householdA, long householdB, Person ownerA, Person memberA, Person ownerB, Person loner) {
    }

    /** SQL로 넣은 초대. token은 원문이고 DB에는 SHA-256만 있다. */
    public record InsertedInvitation(long id, String token) {
    }

    private static final String ORIGIN = "https://localhost";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MockMvc mockMvc;
    private final JdbcClient jdbc;
    private final SessionRows sessions;
    private final LoginClient login;

    public Households(MockMvc mockMvc, JdbcClient jdbc, SessionRows sessions) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.login = new LoginClient(mockMvc);
    }

    /** 새 sub의 가짜 계정으로 로그인한다. 초대 발급은 재인증 대상이라 로그인 직후(5분 안)에 호출한다. */
    public Person newPerson() throws Exception {
        String id = UUID.randomUUID().toString();
        Cookie cookie = login.login(GoogleAccount.of("household-" + id, "household-" + id + "@example.com")).requireSessionCookie();
        return new Person(cookie, Long.parseLong(sessions.rowOf(SessionRows.idOfCookie(cookie.getValue())).principalName()));
    }

    /** POST /api/v1/household(201). 만든 가구 번호를 준다. */
    public long createHousehold(Person owner) throws Exception {
        mockMvc.perform(write(post("/api/v1/household"), owner)).andExpect(status().isCreated());
        return householdIdOf(owner);
    }

    /** POST /api/v1/household/invitations(201). 응답의 token(링크의 # 뒤 값)을 준다. */
    public String invite(Person owner, int expiresInDays) throws Exception {
        String body = mockMvc.perform(write(post("/api/v1/household/invitations"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\": " + expiresInDays + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode token = JSON.readTree(body).path("token");
        assertThat(token.isString()).as("초대 발급 응답의 token").isTrue();
        return token.asString();
    }

    /** POST /api/v1/invitations/accept(204). */
    public void accept(Person person, String token) throws Exception {
        mockMvc.perform(write(post("/api/v1/invitations/accept"), person)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\": \"" + token + "\"}"))
                .andExpect(status().isNoContent());
    }

    /** owner가 3일짜리 초대를 발급하고 새 사람이 수락해 member가 된다. */
    public Person addMember(Person owner) throws Exception {
        Person member = newPerson();
        accept(member, invite(owner, 3));
        return member;
    }

    /** 가구 둘과 사용자 넷: 가구 A의 owner와 member, 가구 B의 owner, 가구 없는 사람. */
    public World world() throws Exception {
        Person ownerA = newPerson();
        long householdA = createHousehold(ownerA);
        Person memberA = addMember(ownerA);
        Person ownerB = newPerson();
        long householdB = createHousehold(ownerB);
        return new World(householdA, householdB, ownerA, memberA, ownerB, newPerson());
    }

    /** 초대 한 건을 SQL로 넣는다(번호는 시퀀스). 토큰 원문은 43자 base64url이고 token_hash에는 그 ASCII 바이트의 SHA-256 32바이트를 넣는다. */
    public InsertedInvitation insertInvitation(long householdId, long createdBy, String status, Instant createdAt, Instant expiresAt) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long id = jdbc.sql("select nextval('invitation_id_seq')").query(Long.class).single();
        jdbc.sql("""
                insert into invitation (id, household_id, created_by, token_hash, status, expires_at, created_at)
                values (:id, :household, :creator, :hash, :status, :expires, :created)""")
                .param("id", id).param("household", householdId).param("creator", createdBy).param("hash", sha256(token))
                .param("status", status).param("expires", Timestamp.from(expiresAt)).param("created", Timestamp.from(createdAt))
                .update();
        return new InsertedInvitation(id, token);
    }

    /** 이 사용자의 가구 번호. 가구가 없으면 예외. */
    public long householdIdOf(Person person) {
        return jdbc.sql("select household_id from household_member where user_id = :u").param("u", person.userId())
                .query(Long.class).single();
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, Person person) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        }).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie());
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
