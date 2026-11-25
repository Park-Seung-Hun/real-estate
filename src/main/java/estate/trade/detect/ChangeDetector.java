package estate.trade.detect;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import estate.trade.api.TradeItem;

/**
 * 범위 하나의 직전 결과와 오늘 결과를 비교해 만들 행, 갱신할 행, 기록할 이력을 정한다. 데이터베이스를 쓰지 않는 순수 계산이다
 * (research R5의 표). 기준 적재면 이력 없이 거래 행만 맞춘다(research R6).
 */
public final class ChangeDetector {

    private ChangeDetector() {
    }

    /** 거래의 식별 키. 전용면적은 자릿수를 맞춘 값으로 비교한다(84.9와 84.90은 같음). */
    public record TradeKey(String aptSeq, LocalDate dealDate, int floor, BigDecimal exclusiveArea, long amount,
            boolean canceled) {

        public TradeKey {
            exclusiveArea = exclusiveArea.stripTrailingZeros();
        }

        static TradeKey of(TradeItem item) {
            return new TradeKey(item.aptSeq(), item.dealDate(), item.floor(), item.exclusiveArea(), item.amount(),
                    item.canceled());
        }
    }

    /** 건수 말고 갱신하는 세 값. 없는 값은 null이다. */
    public record Values(LocalDate cancelDate, LocalDate registryDate, String dong) {
    }

    /** 데이터베이스에 있는 거래 행(건수 0 포함). */
    public record Stored(long id, int count, Values values) {
    }

    /**
     * 키 하나의 반영 계획.
     *
     * @param previous 직전 행. null이면 새로 만드는 행
     */
    public record Entry(TradeKey key, Stored previous, int count, Values values, List<ChangeType> changes) {

        public boolean isNew() {
            return previous == null;
        }

        public boolean needsUpdate() {
            return previous != null && (count != previous.count() || !values.equals(previous.values()));
        }
    }

    private record Today(int count, Values values) {
    }

    /** 바뀐 키(새 행, 갱신할 행, 이력이 있는 키)만 순서대로 돌려준다. */
    public static List<Entry> detect(Map<TradeKey, Stored> previous, List<TradeItem> items, boolean baseline) {
        Map<TradeKey, Today> today = group(items);
        Set<TradeKey> keys = new LinkedHashSet<>(today.keySet());
        keys.addAll(previous.keySet());

        List<Entry> entries = new ArrayList<>();
        for (TradeKey key : keys) {
            Entry entry = plan(key, previous.get(key), today.get(key), baseline);
            if (entry != null && (entry.isNew() || entry.needsUpdate() || !entry.changes().isEmpty())) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static Entry plan(TradeKey key, Stored prev, Today today, boolean baseline) {
        int todayCount = today == null ? 0 : today.count();
        if (prev == null) {
            if (today == null) {
                return null;
            }
            List<ChangeType> changes = baseline ? List.of() : List.of(key.canceled() ? ChangeType.CANCELED : ChangeType.NEW);
            return new Entry(key, null, todayCount, today.values(), changes);
        }

        Values values = mergedValues(prev, today);
        List<ChangeType> changes = new ArrayList<>();
        if (!baseline) {
            if (todayCount > prev.count()) {
                changes.add(key.canceled() ? ChangeType.CANCELED : ChangeType.NEW);
            } else if (todayCount < prev.count()) {
                changes.add(ChangeType.VANISHED);
            }
            if (registered(prev, today)) {
                changes.add(ChangeType.REGISTERED);
            }
        }
        return new Entry(key, prev, todayCount, values, changes);
    }

    /** 건수 1이면 오늘 값, 2 이상이면 비어 있던 열만 채움, 0이면 그대로. 어느 쪽인지는 오늘 결과의 건수로 정한다. */
    private static Values mergedValues(Stored prev, Today today) {
        if (today == null) {
            return prev.values();
        }
        if (today.count() == 1) {
            return today.values();
        }
        Values old = prev.values();
        Values now = today.values();
        return new Values(
                old.cancelDate() != null ? old.cancelDate() : now.cancelDate(),
                old.registryDate() != null ? old.registryDate() : now.registryDate(),
                old.dong() != null ? old.dong() : now.dong());
    }

    private static boolean registered(Stored prev, Today today) {
        if (today == null || prev.count() < 1) {
            return false;
        }
        Values old = prev.values();
        Values now = today.values();
        if (today.count() == 1) {
            return (old.registryDate() == null && now.registryDate() != null) || (old.dong() == null && now.dong() != null);
        }
        return old.registryDate() == null && old.dong() == null && (now.registryDate() != null || now.dong() != null);
    }

    /** 키마다 건수를 세고, 세 값은 묶음 안에서 처음 나온 비어 있지 않은 값을 쓴다(응답의 행 순서가 날마다 바뀜). */
    private static Map<TradeKey, Today> group(List<TradeItem> items) {
        Map<TradeKey, Today> grouped = new LinkedHashMap<>();
        for (TradeItem item : items) {
            Values values = new Values(item.canceledOn(), item.registeredOn(), blankToNull(item.dong()));
            grouped.merge(TradeKey.of(item), new Today(1, values), (a, b) -> new Today(a.count() + 1, new Values(
                    a.values().cancelDate() != null ? a.values().cancelDate() : b.values().cancelDate(),
                    a.values().registryDate() != null ? a.values().registryDate() : b.values().registryDate(),
                    a.values().dong() != null ? a.values().dong() : b.values().dong())));
        }
        return grouped;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
