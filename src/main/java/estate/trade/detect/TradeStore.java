package estate.trade.detect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import estate.trade.detect.ChangeDetector.Stored;
import estate.trade.detect.ChangeDetector.TradeKey;
import estate.trade.detect.ChangeDetector.Values;

/** {@code trade}와 {@code trade_history}의 읽기와 쓰기(research R5). 호출한 쪽의 트랜잭션 안에서 쓴다. */
@Component
public class TradeStore {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcClient jdbc;

    public TradeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 범위의 거래 행 모두(건수 0 포함). 범위는 시군구 코드가 같은 단지의 거래 가운데 계약일이 그 달 안인 것이다. */
    public Map<TradeKey, Stored> readRange(String lawdCd, String dealYmd) {
        LocalDate first = YearMonth.parse(dealYmd, YMD).atDay(1);
        Map<TradeKey, Stored> rows = new HashMap<>();
        jdbc.sql("""
                select t.id, c.apt_seq, t.deal_date, t.floor, t.exclusive_area, t.amount, t.canceled,
                       t.same_key_count, t.cancel_date, t.registry_date, t.apt_dong
                from trade t join complex c on c.id = t.complex_id
                where c.sgg_cd = :lawdCd and t.deal_date >= :first and t.deal_date < :next""")
                .param("lawdCd", lawdCd)
                .param("first", first)
                .param("next", first.plusMonths(1))
                .query((rs, n) -> Map.entry(
                        new TradeKey(rs.getString("apt_seq"), rs.getObject("deal_date", LocalDate.class),
                                rs.getInt("floor"), rs.getBigDecimal("exclusive_area"), rs.getLong("amount"),
                                rs.getBoolean("canceled")),
                        new Stored(rs.getLong("id"), rs.getInt("same_key_count"),
                                new Values(rs.getObject("cancel_date", LocalDate.class),
                                        rs.getObject("registry_date", LocalDate.class), rs.getString("apt_dong")))))
                .list().forEach(e -> rows.put(e.getKey(), e.getValue()));
        return rows;
    }

    /** 직전에 거래가 있던 범위인가: 건수가 1 이상인 거래 행이 있다(FR-011). */
    public boolean hasTrades(String lawdCd, String dealYmd) {
        return readRange(lawdCd, dealYmd).values().stream().anyMatch(row -> row.count() >= 1);
    }

    public long insertTrade(long complexId, TradeKey key, int count, Values values, Instant firstSeenAt) {
        return jdbc.sql("""
                insert into trade (id, complex_id, deal_date, floor, exclusive_area, amount, canceled, same_key_count,
                                   cancel_date, registry_date, apt_dong, first_seen_at)
                values (nextval('trade_id_seq'), :complexId, :dealDate, :floor, :area, :amount, :canceled, :count,
                        :cancelDate, :registryDate, :dong, :firstSeenAt)
                returning id""")
                .param("complexId", complexId)
                .param("dealDate", key.dealDate())
                .param("floor", key.floor())
                .param("area", key.exclusiveArea())
                .param("amount", key.amount())
                .param("canceled", key.canceled())
                .param("count", count)
                .param("cancelDate", values.cancelDate())
                .param("registryDate", values.registryDate())
                .param("dong", values.dong())
                .param("firstSeenAt", java.sql.Timestamp.from(firstSeenAt))
                .query(Long.class).single();
    }

    public void updateTrade(long id, int count, Values values) {
        jdbc.sql("""
                update trade set same_key_count = :count, cancel_date = :cancelDate, registry_date = :registryDate,
                                 apt_dong = :dong
                where id = :id""")
                .param("id", id)
                .param("count", count)
                .param("cancelDate", values.cancelDate())
                .param("registryDate", values.registryDate())
                .param("dong", values.dong())
                .update();
    }

    public void insertHistory(long tradeId, ChangeType type, Instant observedAt) {
        jdbc.sql("""
                insert into trade_history (id, trade_id, change_type, observed_at)
                values (nextval('trade_history_id_seq'), :tradeId, :type, :observedAt)""")
                .param("tradeId", tradeId)
                .param("type", type.label())
                .param("observedAt", java.sql.Timestamp.from(observedAt))
                .update();
    }
}
