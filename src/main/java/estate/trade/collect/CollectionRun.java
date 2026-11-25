package estate.trade.collect;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import estate.trade.CollectorProperties;
import estate.trade.api.CallCounter;
import estate.trade.api.TradeApiException;
import estate.trade.detect.ChangeType;
import estate.trade.detect.FetchRangeStore;
import estate.trade.detect.RunSnapshot;
import estate.trade.detect.RunSnapshot.Range;

/**
 * 예약 실행 하나: 매일 수집을 먼저 하고, 멈추지 않았으면 행이 없는 지역마다 초기 적재나 범위 확장을 한다(FR-003). 매일 수집과 지역마다의
 * 적재는 각각 하나의 실행이라 호출 수와 요약 로그가 따로다(research R3).
 */
@Component
public class CollectionRun {

    private static final Logger log = LoggerFactory.getLogger(CollectionRun.class);

    private final CollectorProperties properties;
    // 수집할 지역이 없으면 API 어댑터와 저장소 빈이 없어 범위 수집도 없다
    private final ObjectProvider<RangeCollector> rangeCollector;
    private final FetchRangeStore fetchRangeStore;
    private final Clock clock;

    CollectionRun(CollectorProperties properties, ObjectProvider<RangeCollector> rangeCollector,
            FetchRangeStore fetchRangeStore, Clock clock) {
        this.properties = properties;
        this.rangeCollector = rangeCollector;
        this.fetchRangeStore = fetchRangeStore;
        this.clock = clock;
    }

    @Scheduled(cron = "${estate.collector.cron}", zone = "Asia/Seoul")
    public void scheduledRun() {
        run(properties.regions());
    }

    public void run(List<String> regions) {
        if (regions.isEmpty()) {
            log.info("수집할 지역이 없어 예약 실행을 건너뜁니다");
            return;
        }
        RangeCollector collector = rangeCollector.getObject();
        Instant runStartedAt = clock.instant();
        // 기준 적재 판정은 이때 읽은 값으로만 한다. 같은 실행에서 갱신한 값을 쓰면 달이 바뀐 날 새 달의 신규가 사라진다
        RunSnapshot snapshot = fetchRangeStore.readSnapshot(runStartedAt, regions);
        List<Range> daily = CollectionPlanner.dailyRanges(regions, snapshot,
                fetchRangeStore.readFailedRanges(regions));
        if (!execute(collector, "매일 수집", daily, snapshot)) {
            return;
        }
        for (String region : CollectionPlanner.newRegions(regions, snapshot)) {
            if (!initialLoad(collector, region, runStartedAt)) {
                return;
            }
        }
    }

    /** @return 실행을 멈추는 실패가 없었으면 true */
    private boolean initialLoad(RangeCollector collector, String region, Instant runStartedAt) {
        List<String> months = CollectionCalendar.initialLoadMonths(runStartedAt);
        String kind;
        try {
            kind = fetchRangeStore.hasOtherRegionRows(region) ? "범위 확장" : "초기 적재";
            // 첫 호출보다 먼저 기록한다. 도중에 멈춰도 남은 범위가 다음 날 실패한 범위로 이어진다(FR-005)
            fetchRangeStore.preRecord(region, months);
        } catch (RuntimeException e) {
            log.error("선기록에 실패해 이번 실행에서 건너뜁니다: 지역={}, 원인={}", region, e.getClass().getSimpleName());
            return true;
        }
        // 선기록 뒤에 읽어야 이 지역의 범위가 모두 앞서 실패만 한 범위(기준 적재)가 된다
        return execute(collector, kind, months.stream().map(month -> new Range(region, month)).toList(),
                fetchRangeStore.readSnapshot(runStartedAt, List.of(region)));
    }

    private boolean execute(RangeCollector collector, String kind, List<Range> ranges, RunSnapshot snapshot) {
        CallCounter counter = new CallCounter(properties.selfStopCalls());
        Map<ChangeType, Integer> history = new EnumMap<>(ChangeType.class);
        int succeeded = 0;
        int failed = 0;
        String stopReason = null;
        for (int i = 0; i < ranges.size() && stopReason == null; i++) {
            Range range = ranges.get(i);
            try {
                RangeCollector.Outcome outcome = collector.collect(range, snapshot, counter);
                if (outcome.failureReason() == null) {
                    succeeded++;
                    outcome.applied().historyCounts().forEach((type, count) -> history.merge(type, count, Integer::sum));
                } else {
                    failed++;
                    logFailure(range, outcome.failureReason());
                }
            } catch (TradeApiException e) {
                stopReason = e.failureReason();
                failed += recordStopped(range, ranges.subList(i + 1, ranges.size()), stopReason);
            }
        }
        log.info("수집 실행 요약: 종류={}, 대상 범위={}, 성공={}, 실패={}, 호출={}, 이력={{}}, 멈춘 이유={}",
                kind, ranges.size(), succeeded, failed, counter.calls(),
                Arrays.stream(ChangeType.values())
                        .map(type -> type.label() + "=" + history.getOrDefault(type, 0))
                        .collect(Collectors.joining(", ")),
                stopReason == null ? "없음" : stopReason);
        return stopReason == null;
    }

    /** 멈춘 범위와 시도하지 않은 범위 가운데 행이 있는 것만 실패로 기록한다(FR-008, FR-009). 기록한 수를 돌려준다. */
    private int recordStopped(Range stopped, List<Range> remaining, String stopReason) {
        int recorded = record(stopped.lawdCd(), List.of(stopped.dealYmd()), stopReason);
        String remainingReason = "RUN_STOPPED: " + stopReason.substring(0, stopReason.indexOf(':'));
        Map<String, List<String>> byRegion = remaining.stream().collect(Collectors.groupingBy(Range::lawdCd,
                LinkedHashMap::new, Collectors.mapping(Range::dealYmd, Collectors.toList())));
        for (Map.Entry<String, List<String>> region : byRegion.entrySet()) {
            recorded += record(region.getKey(), region.getValue(), remainingReason);
        }
        return recorded;
    }

    private int record(String lawdCd, List<String> dealYmds, String reason) {
        List<String> recorded = fetchRangeStore.markFailureIfRecorded(lawdCd, dealYmds, reason);
        recorded.stream().sorted().forEach(dealYmd -> logFailure(new Range(lawdCd, dealYmd), reason));
        return recorded.size();
    }

    private static void logFailure(Range range, String reason) {
        log.warn("수집 실패 범위: 지역={}, 계약년월={}, 이유={}", range.lawdCd(), range.dealYmd(), reason);
    }
}
