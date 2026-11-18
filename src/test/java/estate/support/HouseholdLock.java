package estate.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import javax.sql.DataSource;

import estate.household.domain.HouseholdRole;

/**
 * 다른 연결의 열린 트랜잭션. {@code holding}은 가구 행을 {@code for update}로 잡고, {@code uncommittedMembership}은 커밋하지 않은 가구와 멤버십 행을 넣어 둔다.
 * {@code awaitWaiting(n)}으로 요청이 잠금을 기다리는 수를 확인한 뒤 {@code commit}이나 {@code rollback}으로 푼다. close는 아직 안 풀었으면 롤백하므로 try-with-resources로 쓴다.
 */
public final class HouseholdLock implements AutoCloseable {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    private final Connection connection;
    private final long householdId;
    private boolean released;

    private HouseholdLock(Connection connection, long householdId) throws SQLException {
        this.connection = connection;
        this.householdId = householdId;
    }

    /** 이 가구 행을 잡는다. 가구 행이 없으면 예외. */
    public static HouseholdLock holding(DataSource dataSource, long householdId) throws SQLException {
        Connection c = open(dataSource);
        try (PreparedStatement ps = c.prepareStatement("select id from household where id = ? for update")) {
            ps.setLong(1, householdId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("잠글 가구 행이 없다: " + householdId);
                }
            }
            return new HouseholdLock(c, householdId);
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    /** 새 가구와 이 사용자의 멤버십 행을 커밋하지 않고 넣는다. 다른 연결에는 아직 보이지 않는다. */
    public static HouseholdLock uncommittedMembership(DataSource dataSource, long userId, HouseholdRole role) throws SQLException {
        Connection c = open(dataSource);
        try {
            long householdId;
            try (PreparedStatement ps = c.prepareStatement("select nextval('household_id_seq')"); ResultSet rs = ps.executeQuery()) {
                rs.next();
                householdId = rs.getLong(1);
            }
            try (PreparedStatement ps = c.prepareStatement("insert into household (id) values (?)")) {
                ps.setLong(1, householdId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into household_member (household_id, user_id, role, joined_at) values (?, ?, ?, now())")) {
                ps.setLong(1, householdId);
                ps.setLong(2, userId);
                ps.setString(3, role.dbValue());
                ps.executeUpdate();
            }
            return new HouseholdLock(c, householdId);
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    public long householdId() {
        return householdId;
    }

    /** 이 잠금 연결이 막은 세션이 n개가 될 때까지 최대 10초 기다린다. 넘으면 예외. */
    public void awaitWaiting(int n) throws SQLException, InterruptedException {
        awaitWaiting(n, WAIT_LIMIT);
    }

    /** 상한을 줄여 확인할 때 쓴다. */
    public void awaitWaiting(int n, Duration limit) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        long waiting = -1;
        while (System.nanoTime() < deadline) {
            // 같은 행을 기다리는 두 번째 이후 세션은 앞선 대기자에게 막혀 pg_blocking_pids에 이 연결이 없다(PostgreSQL 18에서 확인)
            try (PreparedStatement ps = connection.prepareStatement(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()")) {
                // 한 트랜잭션 안에서는 처음 조회한 세션 목록이 재사용되므로 매번 비워 새로 연결된 대기자도 센다
                try (PreparedStatement clear = connection.prepareStatement("select pg_stat_clear_snapshot()")) {
                    clear.execute();
                }
                ResultSet rs = ps.executeQuery();
                rs.next();
                waiting = rs.getLong(1);
            }
            if (waiting == n) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("잠금을 기다리는 연결이 " + n + "개가 되지 않았다(마지막 확인 " + waiting + "개)");
    }

    public void commit() throws SQLException {
        finish(true);
    }

    public void rollback() throws SQLException {
        finish(false);
    }

    @Override
    public void close() throws SQLException {
        if (!released) {
            rollback();
        }
    }

    private void finish(boolean commit) throws SQLException {
        released = true;
        try (connection) {
            if (commit) {
                connection.commit();
            } else {
                connection.rollback();
            }
        }
    }

    private static Connection open(DataSource dataSource) throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("set local lock_timeout = '10s'"); // 커밋 뒤 풀로 돌아간 연결에 남지 않게
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }
}
