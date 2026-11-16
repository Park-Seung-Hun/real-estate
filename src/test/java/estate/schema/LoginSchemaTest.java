package estate.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import estate.support.IntegrationTest;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V1__login.sql이 지켜야 할 제약(체크, 유니크, 연쇄 삭제)을 SQL로 직접 검사한다. */
@IntegrationTest
class LoginSchemaTest {

    // PostgreSQL SQLSTATE: 제약 종류를 예외 클래스가 아니라 코드로 구분한다.
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String HASH_64 = "0123456789abcdef".repeat(4);
    // 원문 토큰 형식(패딩 없는 base64url 43자). 해시가 아니라서 저장되면 안 된다.
    private static final String RAW_TOKEN_43 = "A".repeat(43);

    @Autowired
    JdbcClient jdbc;

    // 다른 테스트와 섞이지 않게 넣은 행을 지운다(세션 속성은 연쇄 삭제).
    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from spring_session where principal_name = 'schema-test'").update();
        jdbc.sql("delete from \"user\" where provider_subject like 'schema-test-%'").update();
    }

    @Test
    void rejectsSessionIdThatIsNotSha256Hex() {
        assertThatThrownBy(() -> insertSession(UUID.randomUUID().toString(), RAW_TOKEN_43))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));

        insertSession(UUID.randomUUID().toString(), HASH_64);

        assertThat(count("select count(*) from spring_session where session_id = '" + HASH_64 + "'")).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateProviderSubject() {
        insertUser("schema-test-dup");

        assertThatThrownBy(() -> insertUser("schema-test-dup"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(UNIQUE_VIOLATION));
    }

    @Test
    void deletingSessionDeletesItsAttributes() {
        String primaryId = UUID.randomUUID().toString();
        insertSession(primaryId, HASH_64);
        jdbc.sql("insert into spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)"
                        + " values (:id, 'login', :bytes)")
                .param("id", primaryId)
                .param("bytes", new byte[] {1, 2, 3})
                .update();
        assertThat(count("select count(*) from spring_session_attributes where session_primary_id = '" + primaryId + "'"))
                .isEqualTo(1);

        jdbc.sql("delete from spring_session where primary_id = :id").param("id", primaryId).update();

        assertThat(count("select count(*) from spring_session_attributes where session_primary_id = '" + primaryId + "'"))
                .isZero();
    }

    private void insertUser(String subject) {
        long id = jdbc.sql("select nextval('user_id_seq')").query(Long.class).single();
        jdbc.sql("insert into \"user\" (id, provider, provider_subject, login_email, email_verified)"
                        + " values (:id, 'google', :sub, :email, true)")
                .param("id", id)
                .param("sub", subject)
                .param("email", new byte[] {9, 9, 9}) // 암호화된 값 자리. 임의 바이트
                .update();
    }

    // spring_session 나머지 열은 Spring Session JDBC 기본 PostgreSQL 스키마의 이름을 가정한다.
    private void insertSession(String primaryId, String sessionId) {
        jdbc.sql("insert into spring_session (primary_id, session_id, creation_time, last_access_time,"
                        + " max_inactive_interval, expiry_time, principal_name)"
                        + " values (:pid, :sid, 1000, 1000, 604800, 605800000, 'schema-test')")
                .param("pid", primaryId)
                .param("sid", sessionId)
                .update();
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
