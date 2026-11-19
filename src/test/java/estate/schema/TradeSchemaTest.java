package estate.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import estate.support.IntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V3__trade_collection.sql의 제약(체크, 유니크)을 SQL로 직접 검사한다. 실패한 제약은 이름으로 확인한다. */
@IntegrationTest
class TradeSchemaTest {

    private static final String APT = "99999-schema";

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from trade_history where trade_id in (select t.id from trade t join complex c"
                + " on c.id = t.complex_id where c.apt_seq = :apt)").param("apt", APT).update();
        jdbc.sql("delete from trade where complex_id in (select id from complex where apt_seq = :apt)")
                .param("apt", APT).update();
        jdbc.sql("delete from complex where apt_seq = :apt").param("apt", APT).update();
        jdbc.sql("delete from fetch_range where lawd_cd in ('99999', '9999')").update();
    }

    @Test
    void rejectsDuplicateAptSeq() {
        insertComplex(APT, "99999");
        assertThat(count("select count(*) from complex where apt_seq = '" + APT + "'")).isEqualTo(1);

        assertThatThrownBy(() -> insertComplex(APT, "99999")).satisfies(e -> assertConstraint(e, "complex_apt_seq_key"));
    }

    @Test
    void rejectsSggCdThatIsNotFiveDigits() {
        assertThatThrownBy(() -> insertComplex(APT, "9999"))
                .satisfies(e -> assertConstraint(e, "complex_sgg_cd_format"));
        assertThat(count("select count(*) from complex where apt_seq = '" + APT + "'")).isZero();
        assertThatThrownBy(() -> insertComplex(APT, "999999"))
                .satisfies(e -> assertConstraint(e, "complex_sgg_cd_format"));
        assertThatThrownBy(() -> insertComplex(APT, "9999x"))
                .satisfies(e -> assertConstraint(e, "complex_sgg_cd_format"));
    }

    @Test
    void treatsAreasWithDifferentScaleAsSameIdentityKey() {
        long complex = insertComplex(APT, "99999");
        insertTrade(complex, "84.9", 1);
        assertThat(count("select count(*) from trade where complex_id = " + complex)).isEqualTo(1);

        assertThatThrownBy(() -> insertTrade(complex, "84.90", 1))
                .satisfies(e -> assertConstraint(e, "trade_identity_key"));
    }

    @Test
    void allowsRowsThatDifferInOneIdentityKeyColumn() {
        long complex = insertComplex(APT, "99999");
        insertTrade(complex, "84.9", 1);

        // 해제 여부, 금액, 층, 계약일 가운데 하나만 달라도 다른 키다
        jdbc.sql("insert into trade (id, complex_id, deal_date, floor, exclusive_area, amount, canceled,"
                        + " same_key_count, first_seen_at) select nextval('trade_id_seq'), complex_id, deal_date, floor,"
                        + " exclusive_area, amount, not canceled, 1, now() from trade where complex_id = :c")
                .param("c", complex).update();
        jdbc.sql("insert into trade (id, complex_id, deal_date, floor, exclusive_area, amount, canceled,"
                        + " same_key_count, first_seen_at) select nextval('trade_id_seq'), complex_id, deal_date, floor,"
                        + " exclusive_area, amount + 1, canceled, 1, now() from trade where complex_id = :c and canceled = false"
                        + " and amount = 50000")
                .param("c", complex).update();

        assertThat(count("select count(*) from trade where complex_id = " + complex)).isEqualTo(3);
    }

    @Test
    void rejectsNegativeSameKeyCount() {
        long complex = insertComplex(APT, "99999");
        insertTrade(complex, "59.9", 0);
        assertThat(count("select count(*) from trade where complex_id = " + complex)).isEqualTo(1);

        assertThatThrownBy(() -> insertTrade(complex, "60", -1))
                .satisfies(e -> assertConstraint(e, "trade_same_key_count_check"));
    }

    @Test
    void rejectsChangeTypeOutsideList() {
        long complex = insertComplex(APT, "99999");
        long trade = insertTrade(complex, "59.9", 1);
        insertHistory(trade, "신규");
        assertThat(count("select count(*) from trade_history where trade_id = " + trade)).isEqualTo(1);

        assertThatThrownBy(() -> insertHistory(trade, "삭제"))
                .satisfies(e -> assertConstraint(e, "trade_history_change_type_check"));
    }

    @Test
    void rejectsFetchRangeStatusOutsideList() {
        insertFetchRange("99999", "202611", "성공");
        assertThat(count("select count(*) from fetch_range where lawd_cd = '99999'")).isEqualTo(1);

        assertThatThrownBy(() -> insertFetchRange("99999", "202610", "대기"))
                .satisfies(e -> assertConstraint(e, "fetch_range_status_check"));
    }

    @Test
    void rejectsFetchRangeLawdCdAndDealYmdWithBadFormat() {
        insertFetchRange("99999", "202601", "실패");
        assertThat(count("select count(*) from fetch_range where lawd_cd = '99999'")).isEqualTo(1);

        assertThatThrownBy(() -> insertFetchRange("9999", "202601", "실패"))
                .satisfies(e -> assertConstraint(e, "fetch_range_lawd_cd_format"));
        assertThatThrownBy(() -> insertFetchRange("99999", "202613", "실패"))
                .satisfies(e -> assertConstraint(e, "fetch_range_deal_ymd_format"));
        assertThatThrownBy(() -> insertFetchRange("99999", "202600", "실패"))
                .satisfies(e -> assertConstraint(e, "fetch_range_deal_ymd_format"));
        assertThatThrownBy(() -> insertFetchRange("99999", "2026011", "실패"))
                .satisfies(e -> assertConstraint(e, "fetch_range_deal_ymd_format"));
        assertThatThrownBy(() -> insertFetchRange("999999", "202601", "실패"))
                .satisfies(e -> assertConstraint(e, "fetch_range_lawd_cd_format"));
        assertThatThrownBy(() -> insertFetchRange("9999x", "202601", "실패"))
                .satisfies(e -> assertConstraint(e, "fetch_range_lawd_cd_format"));
    }

    private long insertComplex(String aptSeq, String sggCd) {
        long id = jdbc.sql("select nextval('complex_id_seq')").query(Long.class).single();
        jdbc.sql("insert into complex (id, apt_seq, name, sgg_cd, umd_nm, jibun)"
                        + " values (:id, :apt, '가상단지', :sgg, '가상동', '1-1')")
                .param("id", id).param("apt", aptSeq).param("sgg", sggCd).update();
        return id;
    }

    private long insertTrade(long complex, String area, int count) {
        long id = jdbc.sql("select nextval('trade_id_seq')").query(Long.class).single();
        jdbc.sql("insert into trade (id, complex_id, deal_date, floor, exclusive_area, amount, canceled,"
                        + " same_key_count, first_seen_at)"
                        + " values (:id, :c, date '2026-11-01', 3, cast(:area as numeric), 50000, false, :n, now())")
                .param("id", id).param("c", complex).param("area", area).param("n", count).update();
        return id;
    }

    private void insertHistory(long trade, String type) {
        long id = jdbc.sql("select nextval('trade_history_id_seq')").query(Long.class).single();
        jdbc.sql("insert into trade_history (id, trade_id, change_type, observed_at) values (:id, :t, :type, now())")
                .param("id", id).param("t", trade).param("type", type).update();
    }

    private void insertFetchRange(String lawdCd, String dealYmd, String status) {
        jdbc.sql("insert into fetch_range (lawd_cd, deal_ymd, status) values (:l, :d, :s)")
                .param("l", lawdCd).param("d", dealYmd).param("s", status).update();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private static void assertConstraint(Throwable e, String name) {
        assertThat(e).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(constraintName(e)).contains(name);
    }

    /** Hibernate가 아니라 드라이버가 서버에서 받은 제약 이름 필드를 읽는다(메시지 언어와 무관). */
    private static Optional<String> constraintName(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return Optional.ofNullable(psql.getServerErrorMessage().getConstraint());
            }
        }
        return Optional.empty();
    }
}
