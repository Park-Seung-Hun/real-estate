package estate.trade.raw;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RawObjectKeyTest {

    @Test
    void convertsRunStartToSeoulDateAndTime() {
        // 한국 시간으로 2026-11-01 00:30 (UTC로는 10월 31일)
        Instant start = Instant.parse("2026-10-31T15:30:00Z");

        assertThat(RawObjectKey.of(start, "99999", "202611", 1))
                .isEqualTo("2026-11-01/003000/99999-202611-p1.xml.gz");
    }

    @Test
    void pageNumberHasNoLeadingZero() {
        Instant start = Instant.parse("2026-11-20T00:00:00Z");

        assertThat(RawObjectKey.of(start, "99998", "202606", 12))
                .isEqualTo("2026-11-20/090000/99998-202606-p12.xml.gz");
    }
}
