package estate.complex;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import estate.trade.api.TradeItem;

/**
 * 응답의 단지 번호마다 {@code complex} 행을 보장하고 단지 번호에서 {@code id}로의 맵을 돌려준다(FR-013).
 * 없는 단지만 만들고, 이미 있는 행은 이름이나 주소가 달라도 바꾸지 않는다(달라진 단지 번호만 로그).
 * 호출한 쪽의 트랜잭션 안에서 쓴다.
 */
@Component
public class ComplexRegistry {

    private static final Logger log = LoggerFactory.getLogger(ComplexRegistry.class);

    private record Row(long id, String aptSeq, String name, String sggCd, String umdNm) {
    }

    private final JdbcClient jdbc;

    public ComplexRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Long> register(List<TradeItem> items) {
        Map<String, TradeItem> bySeq = new LinkedHashMap<>();
        for (TradeItem item : items) {
            bySeq.putIfAbsent(item.aptSeq(), item);
        }
        if (bySeq.isEmpty()) {
            return Map.of();
        }

        Map<String, Row> existing = new HashMap<>();
        jdbc.sql("select id, apt_seq, name, sgg_cd, umd_nm from complex where apt_seq in (:seqs)")
                .param("seqs", bySeq.keySet())
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("apt_seq"), rs.getString("name"),
                        rs.getString("sgg_cd"), rs.getString("umd_nm")))
                .list().forEach(row -> existing.put(row.aptSeq(), row));

        Map<String, Long> ids = new LinkedHashMap<>();
        bySeq.forEach((aptSeq, item) -> {
            Row row = existing.get(aptSeq);
            if (row == null) {
                ids.put(aptSeq, insert(item));
                return;
            }
            if (!Objects.equals(row.name(), strip(item.aptName())) || !Objects.equals(row.sggCd(), item.sggCd())
                    || !Objects.equals(row.umdNm(), strip(item.umdName()))) {
                log.warn("단지 번호 {}의 이름이나 주소가 저장된 값과 다릅니다. 저장된 값을 유지합니다", aptSeq);
            }
            ids.put(aptSeq, row.id());
        });
        return ids;
    }

    private long insert(TradeItem item) {
        String jibun = item.jibun() == null || item.jibun().isBlank() ? null : item.jibun().strip();
        return jdbc.sql("""
                insert into complex (id, apt_seq, name, sgg_cd, umd_nm, jibun)
                values (nextval('complex_id_seq'), :aptSeq, :name, :sggCd, :umdNm, :jibun)
                returning id""")
                .param("aptSeq", item.aptSeq())
                .param("name", strip(item.aptName()))
                .param("sggCd", item.sggCd())
                .param("umdNm", strip(item.umdName()))
                .param("jibun", jibun)
                .query(Long.class).single();
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
