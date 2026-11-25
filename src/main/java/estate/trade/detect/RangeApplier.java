package estate.trade.detect;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import estate.complex.ComplexRegistry;
import estate.trade.collect.CollectionCalendar;
import estate.trade.detect.ChangeDetector.Entry;
import estate.trade.detect.ChangeDetector.Stored;
import estate.trade.detect.ChangeDetector.TradeKey;
import estate.trade.detect.RunSnapshot.Range;
import estate.trade.detect.RunSnapshot.Row;

/**
 * 범위 하나의 오늘 결과를 직전 결과와 비교해 단지, 거래, 이력, 성공 기록을 한 트랜잭션으로 반영한다(FR-012).
 * 실패 기록은 이 클래스가 하지 않는다. 같은 클래스 안 호출은 Spring 트랜잭션 프록시를 거치지 않고 커밋 때의 오류는
 * 메서드 밖에서 나므로, 호출한 쪽이 던진 예외를 받아 별도 트랜잭션으로 기록한다(research R7).
 */
@Component
public class RangeApplier {

    /** 연속 수집 조건: 실행 시작 전 이 기간 안에 성공한 범위만 직전 결과로 믿는다(FR-018). */
    private static final Duration CONTINUITY = Duration.ofDays(7);

    private final ComplexRegistry complexRegistry;
    private final TradeStore tradeStore;
    private final FetchRangeStore fetchRangeStore;
    private final Clock clock;

    public RangeApplier(ComplexRegistry complexRegistry, TradeStore tradeStore, FetchRangeStore fetchRangeStore,
            Clock clock) {
        this.complexRegistry = complexRegistry;
        this.tradeStore = tradeStore;
        this.fetchRangeStore = fetchRangeStore;
        this.clock = clock;
    }

    @Transactional
    public ApplyResult apply(RangeInput input) {
        // 수집 시각은 한 번만 읽는다. 데이터베이스 시각 정밀도에 맞춰 마이크로초로 자른다
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean baseline = isBaseline(input);

        Map<String, Long> complexIds = complexRegistry.register(input.items());
        Map<TradeKey, Stored> previous = tradeStore.readRange(input.lawdCd(), input.dealYmd());
        List<Entry> entries = ChangeDetector.detect(previous, input.items(), baseline);

        Map<ChangeType, Integer> counts = new EnumMap<>(ChangeType.class);
        for (Entry entry : entries) {
            long tradeId;
            if (entry.isNew()) {
                tradeId = tradeStore.insertTrade(complexIds.get(entry.key().aptSeq()), entry.key(), entry.count(),
                        entry.values(), observedAt);
            } else {
                tradeId = entry.previous().id();
                if (entry.needsUpdate()) {
                    tradeStore.updateTrade(tradeId, entry.count(), entry.values());
                }
            }
            for (ChangeType type : entry.changes()) {
                tradeStore.insertHistory(tradeId, type, observedAt);
                counts.merge(type, 1, Integer::sum);
            }
        }

        // 성공 기록은 마지막 쓰기다. 앞의 쓰기가 실패하면 이 기록도 함께 롤백된다
        fetchRangeStore.markSuccess(input.lawdCd(), input.dealYmd(), observedAt);
        return new ApplyResult(baseline, counts);
    }

    /** 실행 시작 때 읽은 스냅숏으로만 판정한다(research R6). 같은 실행에서 갱신된 값은 쓰지 않는다. */
    private static boolean isBaseline(RangeInput input) {
        RunSnapshot snapshot = input.snapshot();
        Row own = snapshot.rows().get(new Range(input.lawdCd(), input.dealYmd()));
        if (own != null) {
            return !recentSuccess(own, snapshot.runStartedAt());
        }
        Row before = snapshot.rows().get(new Range(input.lawdCd(), CollectionCalendar.previousMonth(input.dealYmd())));
        return before == null || !recentSuccess(before, snapshot.runStartedAt());
    }

    /** {@code runStartedAt - 7일 <= fetched_at < runStartedAt}. */
    private static boolean recentSuccess(Row row, Instant runStartedAt) {
        Instant fetchedAt = row.fetchedAt();
        return fetchedAt != null && !fetchedAt.isBefore(runStartedAt.minus(CONTINUITY)) && fetchedAt.isBefore(runStartedAt);
    }
}
