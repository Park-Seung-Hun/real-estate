package estate.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code @Autowired SessionRows}. 옮기기: {@code shiftBackByUser(userId, creation, lastAccess, expiry)}는 각 시각 열(밀리초 epoch)을 그만큼 과거로 보낸다.
 * 조회: {@code count()}, {@code exists(id)}, {@code rowsOfUser(id)}, {@code rowOf(sessionId)}와 {@code attributes(sessionId)}(세션이 없으면 예외, 없음은 exists로).
 * 삭제 실패: {@code failDeleteOf(sessionId)}(Reset이 지움). 누출 검사: {@code columnsContaining(문자열)}. 쿠키 값의 세션 ID는 {@code idOfCookie(값)}.
 */
public final class SessionRows {

    public record Row(String primaryId, String sessionId, long creationTime, long lastAccessTime,
                      int maxInactiveInterval, long expiryTime, String principalName) {
    }

    private final JdbcClient jdbc;

    SessionRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        return jdbc.sql("select count(*) from spring_session").query(Long.class).single();
    }

    public List<Row> rowsOfUser(long userId) {
        return jdbc.sql("select * from spring_session where principal_name = :p order by creation_time")
                .param("p", Long.toString(userId)).query(Row.class).list();
    }

    public boolean exists(String sessionId) {
        return jdbc.sql("select count(*) from spring_session where session_id = :s").param("s", sessionId).query(Long.class).single() > 0;
    }

    /** 세션 행 하나. 없으면 예외(없음을 확인하려면 exists나 count를 쓴다). */
    public Row rowOf(String sessionId) {
        return jdbc.sql("select * from spring_session where session_id = :s").param("s", sessionId).query(Row.class).optional()
                .orElseThrow(() -> new IllegalStateException("세션 행이 없다: " + sessionId));
    }

    /** 속성 이름과 직렬화된 바이트(예: 이메일이 없는지 바이트에서 찾는다). 세션 행이 없으면 예외(없는 세션으로 거짓 통과하지 않게). */
    public Map<String, byte[]> attributes(String sessionId) {
        rowOf(sessionId);
        Map<String, byte[]> result = new LinkedHashMap<>();
        jdbc.sql("""
                select a.attribute_name, a.attribute_bytes from spring_session_attributes a
                join spring_session s on s.primary_id = a.session_primary_id where s.session_id = :s""")
                .param("s", sessionId)
                .query((rs, n) -> result.put(rs.getString(1), rs.getBytes(2))).list();
        return result;
    }

    public int shiftBackByUser(long userId, Duration creation, Duration lastAccess, Duration expiry) {
        return shiftBack("principal_name = :k", Long.toString(userId), creation, lastAccess, expiry);
    }

    public int shiftBackBySessionId(String sessionId, Duration creation, Duration lastAccess, Duration expiry) {
        return shiftBack("session_id = :k", sessionId, creation, lastAccess, expiry);
    }

    private int shiftBack(String where, String key, Duration creation, Duration lastAccess, Duration expiry) {
        int updated = jdbc.sql("update spring_session set creation_time = creation_time - :c, last_access_time = last_access_time - :l,"
                        + " expiry_time = expiry_time - :e where " + where)
                .param("c", creation.toMillis()).param("l", lastAccess.toMillis()).param("e", expiry.toMillis())
                .param("k", key).update();
        if (updated == 0) {
            throw new IllegalStateException("옮길 세션 행이 없다: " + where.replace(" = :k", "") + " = " + key);
        }
        return updated;
    }

    public void deleteAll() {
        jdbc.sql("delete from spring_session").update();
    }

    private static final String BLOCK_PREFIX = "test_block_del_";

    /** 이 세션 ID 해시의 행을 삭제하려 하면 예외를 던지는 트리거를 설치한다(롤백 테스트용). Reset이 clearDeleteFailures로 지운다. */
    public void failDeleteOf(String sessionId) {
        if (!sessionId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("세션 ID 해시(소문자 16진수 64자)가 아니다: " + sessionId);
        }
        jdbc.sql("""
                create or replace function test_block_session_delete() returns trigger language plpgsql as $$
                begin
                    if old.session_id = tg_argv[0] then raise exception 'test: spring_session delete blocked'; end if;
                    return old;
                end $$""").update();
        jdbc.sql("create trigger " + BLOCK_PREFIX + sessionId.substring(0, 16) + " before delete on spring_session for each row"
                + " execute function test_block_session_delete('" + sessionId + "')").update();
    }

    public void clearDeleteFailures() {
        for (String name : jdbc.sql("select tgname from pg_trigger where tgname like :p and not tgisinternal")
                .param("p", BLOCK_PREFIX + "%").query(String.class).list()) {
            jdbc.sql("drop trigger " + name + " on spring_session").update();
        }
    }

    /** 모든 사용자 테이블의 text 계열(json 포함)과 bytea 열에서 문자열(UTF-8 바이트)이 들어 있는 "표.열" 목록. 비어 있어야 누출이 없는 것. */
    public List<String> columnsContaining(String needle) {
        List<String> hits = new ArrayList<>();
        var columns = jdbc.sql("""
                select c.table_name, c.column_name, c.data_type from information_schema.columns c
                join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
                where c.table_schema not in ('pg_catalog', 'information_schema') and t.table_type = 'BASE TABLE'
                  and c.data_type in ('text', 'character varying', 'character', 'json', 'jsonb', 'bytea')""")
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).list();
        for (String[] c : columns) {
            boolean bytea = c[2].equals("bytea");
            var q = jdbc.sql("select count(*) from " + quote(c[0]) + " where position(:n in " + quote(c[1]) + (bytea ? "" : "::text") + ") > 0");
            long count = (bytea ? q.param("n", needle.getBytes(StandardCharsets.UTF_8)) : q.param("n", needle)).query(Long.class).single();
            if (count > 0) {
                hits.add(c[0] + "." + c[1]);
            }
        }
        return hits;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /** 쿠키 원문 토큰에 해당하는 spring_session.session_id(SHA-256 소문자 16진수). */
    public static String idOfCookie(String cookieValue) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cookieValue.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
