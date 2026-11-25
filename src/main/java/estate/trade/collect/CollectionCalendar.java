package estate.trade.collect;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 실행 시작 시각(UTC)을 한국 시간으로 바꿔 이번 달과 수집할 달 목록을 정한다. */
public final class CollectionCalendar {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMM");
    /** 결정 기록 0009: 이번 달을 포함한 최근 6개월. */
    public static final int REFETCH_MONTHS = 6;
    /** 초기 적재와 범위 확장: 이번 달을 포함한 최근 60개월. */
    public static final int INITIAL_LOAD_MONTHS = 60;

    private CollectionCalendar() {
    }

    public static YearMonth thisMonth(Instant runStartedAt) {
        return YearMonth.from(runStartedAt.atZone(SEOUL));
    }

    /** 오래된 달부터 이번 달까지(YYYYMM). */
    public static List<String> refetchWindow(Instant runStartedAt) {
        return lastMonths(runStartedAt, REFETCH_MONTHS);
    }

    public static List<String> initialLoadMonths(Instant runStartedAt) {
        return lastMonths(runStartedAt, INITIAL_LOAD_MONTHS);
    }

    /** 바로 앞 달. 1월이면 전해 12월. */
    public static String previousMonth(String dealYmd) {
        return YMD.format(YearMonth.parse(dealYmd, YMD).minusMonths(1));
    }

    private static List<String> lastMonths(Instant runStartedAt, int count) {
        YearMonth current = thisMonth(runStartedAt);
        List<String> months = new ArrayList<>(count);
        for (int back = count - 1; back >= 0; back--) {
            months.add(YMD.format(current.minusMonths(back)));
        }
        return months;
    }
}
