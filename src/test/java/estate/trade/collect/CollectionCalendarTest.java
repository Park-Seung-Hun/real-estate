package estate.trade.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class CollectionCalendarTest {

    @Test
    void thisMonthUsesSeoulCalendarNotUtc() {
        // UTC로는 10월 31일 15:30, 한국 시간으로는 11월 1일 00:30
        Instant start = Instant.parse("2026-10-31T15:30:00Z");

        assertThat(CollectionCalendar.thisMonth(start)).isEqualTo(YearMonth.of(2026, 11));
    }

    @Test
    void lastDayInSeoulStaysInSameMonth() {
        // 한국 시간 10월 31일 23:59 (UTC 14:59)
        assertThat(CollectionCalendar.thisMonth(Instant.parse("2026-10-31T14:59:00Z")))
                .isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void refetchWindowIsSixMonthsIncludingThisMonthOldestFirst() {
        Instant start = Instant.parse("2026-10-31T15:30:00Z");

        assertThat(CollectionCalendar.refetchWindow(start))
                .containsExactly("202606", "202607", "202608", "202609", "202610", "202611");
    }

    @Test
    void initialLoadIsSixtyMonthsIncludingThisMonth() {
        Instant start = Instant.parse("2026-10-31T15:30:00Z");

        var months = CollectionCalendar.initialLoadMonths(start);

        assertThat(months).hasSize(60).doesNotHaveDuplicates();
        assertThat(months.getFirst()).isEqualTo("202112");
        assertThat(months.getLast()).isEqualTo("202611");
    }

    @Test
    void previousMonthOfJanuaryIsLastYearDecember() {
        assertThat(CollectionCalendar.previousMonth("202601")).isEqualTo("202512");
        assertThat(CollectionCalendar.previousMonth("202611")).isEqualTo("202610");
    }
}
