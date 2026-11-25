package estate.trade.detect;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import estate.trade.detect.RunSnapshot.Range;
import estate.trade.detect.RunSnapshot.Row;

/**
 * {@code fetch_range}의 읽기와 기록(research R7). 성공 기록은 호출한 쪽(범위 반영)의 트랜잭션 안에서 하고,
 * 실패 기록과 선기록과 멈춤 기록은 각각 새 트랜잭션으로 한다.
 */
@Component
public class FetchRangeStore {

    private final JdbcClient jdbc;

    public FetchRangeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 대상 지역들의 모든 행. 실행이 시작될 때 한 번 읽는다. */
    public RunSnapshot readSnapshot(Instant runStartedAt, Collection<String> lawdCds) {
        Map<Range, Row> rows = new HashMap<>();
        if (!lawdCds.isEmpty()) {
            jdbc.sql("select lawd_cd, deal_ymd, fetched_at from fetch_range where lawd_cd in (:cds)")
                    .param("cds", lawdCds)
                    .query((rs, n) -> Map.entry(new Range(rs.getString("lawd_cd"), rs.getString("deal_ymd")),
                            new Row(toInstant(rs.getTimestamp("fetched_at")))))
                    .list().forEach(e -> rows.put(e.getKey(), e.getValue()));
        }
        return new RunSnapshot(runStartedAt, rows);
    }

    /** 성공 기록. 범위 반영 트랜잭션의 마지막 쓰기다. */
    public void markSuccess(String lawdCd, String dealYmd, Instant observedAt) {
        jdbc.sql("""
                insert into fetch_range (lawd_cd, deal_ymd, status, fetched_at, failure_reason)
                values (:lawdCd, :dealYmd, '성공', :observedAt, null)
                on conflict (lawd_cd, deal_ymd)
                do update set status = '성공', fetched_at = :observedAt, failure_reason = null""")
                .param("lawdCd", lawdCd)
                .param("dealYmd", dealYmd)
                .param("observedAt", Timestamp.from(observedAt))
                .update();
    }

    /** 실패 기록. {@code fetched_at}은 그대로 둔다. 행이 없으면 새로 만든다(앞서 실패만 한 범위가 됨). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailure(String lawdCd, String dealYmd, String reason) {
        jdbc.sql("""
                insert into fetch_range (lawd_cd, deal_ymd, status, fetched_at, failure_reason)
                values (:lawdCd, :dealYmd, '실패', null, :reason)
                on conflict (lawd_cd, deal_ymd)
                do update set status = '실패', failure_reason = :reason""")
                .param("lawdCd", lawdCd)
                .param("dealYmd", dealYmd)
                .param("reason", reason)
                .update();
    }

    /** 초기 적재와 범위 확장의 선기록. 한 트랜잭션에서 {@code 실패, NOT_YET_COLLECTED}로 만든다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void preRecord(String lawdCd, List<String> dealYmds) {
        for (String dealYmd : dealYmds) {
            jdbc.sql("""
                    insert into fetch_range (lawd_cd, deal_ymd, status, fetched_at, failure_reason)
                    values (:lawdCd, :dealYmd, '실패', null, 'NOT_YET_COLLECTED')
                    on conflict (lawd_cd, deal_ymd) do nothing""")
                    .param("lawdCd", lawdCd)
                    .param("dealYmd", dealYmd)
                    .update();
        }
    }

    /** 실행이 멈출 때 시도하지 않은 범위 가운데 행이 있는 범위만 실패로 기록한다. 행이 없는 범위는 만들지 않는다(FR-019). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunStopped(String lawdCd, Collection<String> dealYmds, String stopReasonCode) {
        if (dealYmds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                update fetch_range set status = '실패', failure_reason = :reason
                where lawd_cd = :lawdCd and deal_ymd in (:dealYmds)""")
                .param("lawdCd", lawdCd)
                .param("dealYmds", dealYmds)
                .param("reason", "RUN_STOPPED: " + stopReasonCode)
                .update();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
