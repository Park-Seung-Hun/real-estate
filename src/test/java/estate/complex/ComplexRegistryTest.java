package estate.complex;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import estate.support.IntegrationTest;
import estate.trade.api.TradeItem;

@IntegrationTest
class ComplexRegistryTest {

    @Autowired
    ComplexRegistry registry;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.sql("delete from trade_history").update();
        jdbc.sql("delete from trade").update();
        jdbc.sql("delete from complex").update();
    }

    private static TradeItem item(String aptSeq, String name, String jibun) {
        return new TradeItem(aptSeq, name, "99999", " 가상동 ", jibun, LocalDate.of(2026, 9, 15), 7,
                new BigDecimal("84.97"), 98500, false, null, null, null);
    }

    private Map<String, Long> register(TradeItem... items) {
        return tx.execute(status -> registry.register(List.of(items)));
    }

    @Test
    void createsNewComplexWithResponseValues() {
        Map<String, Long> ids = register(item("99999-1", " 가상단지 ", "123"));

        assertThat(ids).containsOnlyKeys("99999-1");
        assertThat(jdbc.sql("select apt_seq, name, sgg_cd, umd_nm, jibun from complex where id = :id")
                .param("id", ids.get("99999-1")).query().singleRow())
                .containsEntry("apt_seq", "99999-1").containsEntry("name", "가상단지")
                .containsEntry("sgg_cd", "99999").containsEntry("umd_nm", "가상동").containsEntry("jibun", "123");
    }

    @Test
    void sameAptSeqIsOneRowEvenWhenRegisteredTwiceOrRepeatedInOneCall() {
        Map<String, Long> first = register(item("99999-1", "가상단지", "123"), item("99999-1", "가상단지", "123"));
        Map<String, Long> second = register(item("99999-1", "가상단지", "123"));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("select count(*) from complex").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void keepsExistingRowWhenNameDiffers() {
        register(item("99999-1", "가상단지", "123"));

        register(item("99999-1", "바뀐 이름", "999"));

        assertThat(jdbc.sql("select name, jibun from complex").query().singleRow())
                .containsEntry("name", "가상단지").containsEntry("jibun", "123");
    }

    @Test
    void blankJibunIsStoredAsNull() {
        Map<String, Long> ids = register(item("99999-2", "가상단지", " "));

        assertThat(jdbc.sql("select jibun from complex where id = :id").param("id", ids.get("99999-2"))
                .query(String.class).optional()).isEmpty();
    }
}
