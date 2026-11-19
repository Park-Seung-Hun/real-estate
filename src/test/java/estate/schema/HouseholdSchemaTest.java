package estate.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import estate.support.IntegrationTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V2__household.sql이 지켜야 할 제약(체크, 유니크, 연쇄 삭제 없음)을 SQL로 직접 검사한다. */
@IntegrationTest
class HouseholdSchemaTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final List<Long> householdIds = new ArrayList<>();

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from invitation where created_by in"
                        + " (select id from \"user\" where provider_subject like 'schema-test-%')")
                .update();
        jdbc.sql("delete from household_member where user_id in"
                        + " (select id from \"user\" where provider_subject like 'schema-test-%')")
                .update();
        for (long id : householdIds) {
            jdbc.sql("delete from invitation where household_id = :id").param("id", id).update();
            jdbc.sql("delete from household_member where household_id = :id").param("id", id).update();
            jdbc.sql("delete from household where id = :id").param("id", id).update();
        }
        jdbc.sql("delete from \"user\" where provider_subject like 'schema-test-%'").update();
    }

    @Test
    void rejectsTokenHashThatIsNot32Bytes() {
        long user = insertUser("schema-test-hash");
        long household = insertHousehold();

        assertThatThrownBy(() -> insertInvitation(household, user, new byte[43], "대기", "1 day"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));

        insertInvitation(household, user, new byte[32], "대기", "1 day");

        assertThat(count("select count(*) from invitation where household_id = " + household)).isEqualTo(1);
    }

    @Test
    void rejectsRoleOutsideList() {
        long user = insertUser("schema-test-role");
        long household = insertHousehold();

        assertThatThrownBy(() -> insertMember(household, user, "admin"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));

        insertMember(household, user, "owner");

        assertThat(count("select count(*) from household_member where user_id = " + user)).isEqualTo(1);
    }

    @Test
    void acceptsMemberRole() {
        long user = insertUser("schema-test-member");

        insertMember(insertHousehold(), user, "member");

        assertThat(count("select count(*) from household_member where user_id = " + user)).isEqualTo(1);
    }

    @Test
    void rejectsStatusOutsideList() {
        long user = insertUser("schema-test-status");
        long household = insertHousehold();

        assertThatThrownBy(() -> insertInvitation(household, user, new byte[32], "만료", "1 day"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));

        // 목록 안 세 값이 모두 저장되는 것으로 위 실패가 값 때문임을 보인다
        byte seed = 1;
        for (String status : List.of("대기", "수락", "취소")) {
            insertInvitation(household, user, filled(seed++), status, "1 day");
        }

        assertThat(count("select count(*) from invitation where household_id = " + household)).isEqualTo(3);
    }

    @Test
    void rejectsSecondMembershipOfSameUser() {
        long user = insertUser("schema-test-twice");
        insertMember(insertHousehold(), user, "owner");
        long other = insertHousehold();

        assertThatThrownBy(() -> insertMember(other, user, "member"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    assertThat(sqlState(e)).isEqualTo(UNIQUE_VIOLATION);
                    assertThat(e.getMessage()).contains("household_member_user_id_key");
                });
    }

    @Test
    void rejectsExpiryEqualToCreation() {
        long user = insertUser("schema-test-expiry");
        long household = insertHousehold();

        assertThatThrownBy(() -> insertInvitation(household, user, new byte[32], "대기", "0 seconds"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION);
                    assertThat(e.getMessage()).contains("invitation_expiry_after_creation");
                });

        insertInvitation(household, user, new byte[32], "대기", "1 microsecond");

        assertThat(count("select count(*) from invitation where household_id = " + household)).isEqualTo(1);
    }

    @Test
    void rejectsDeletingHouseholdWithMember() {
        long user = insertUser("schema-test-del-member");
        long household = insertHousehold();
        insertMember(household, user, "owner");

        assertThatThrownBy(() -> jdbc.sql("delete from household where id = :id").param("id", household).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(FOREIGN_KEY_VIOLATION));
        assertThat(count("select count(*) from household_member where household_id = " + household)).isEqualTo(1);
    }

    @Test
    void rejectsDeletingHouseholdWithInvitation() {
        long user = insertUser("schema-test-del-inv");
        long household = insertHousehold();
        insertInvitation(household, user, new byte[32], "대기", "1 day");

        assertThatThrownBy(() -> jdbc.sql("delete from household where id = :id").param("id", household).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(FOREIGN_KEY_VIOLATION));
        assertThat(count("select count(*) from invitation where household_id = " + household)).isEqualTo(1);
    }

    @Test
    void rejectsDeletingUserWithMembership() {
        long user = insertUser("schema-test-del-user");
        insertMember(insertHousehold(), user, "owner");

        assertThatThrownBy(() -> jdbc.sql("delete from \"user\" where id = :id").param("id", user).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(FOREIGN_KEY_VIOLATION));
        assertThat(count("select count(*) from \"user\" where id = " + user)).isEqualTo(1);
    }

    private long insertUser(String subject) {
        long id = jdbc.sql("select nextval('user_id_seq')").query(Long.class).single();
        jdbc.sql("insert into \"user\" (id, provider, provider_subject, login_email, email_verified)"
                        + " values (:id, 'google', :sub, :email, true)")
                .param("id", id)
                .param("sub", subject)
                .param("email", new byte[] {9, 9, 9}) // 암호화된 값 자리. 임의 바이트
                .update();
        return id;
    }

    private long insertHousehold() {
        long id = jdbc.sql("select nextval('household_id_seq')").query(Long.class).single();
        jdbc.sql("insert into household (id) values (:id)").param("id", id).update();
        householdIds.add(id);
        return id;
    }

    private void insertMember(long household, long user, String role) {
        jdbc.sql("insert into household_member (household_id, user_id, role, joined_at)"
                        + " values (:h, :u, :role, now())")
                .param("h", household)
                .param("u", user)
                .param("role", role)
                .update();
    }

    private void insertInvitation(long household, long user, byte[] tokenHash, String status, String lifetime) {
        long id = jdbc.sql("select nextval('invitation_id_seq')").query(Long.class).single();
        jdbc.sql("insert into invitation (id, household_id, created_by, token_hash, status, expires_at, created_at)"
                        + " values (:id, :h, :u, :hash, :status, now() + cast(:lifetime as interval), now())")
                .param("id", id)
                .param("h", household)
                .param("u", user)
                .param("hash", tokenHash)
                .param("status", status)
                .param("lifetime", lifetime)
                .update();
    }

    private static byte[] filled(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
