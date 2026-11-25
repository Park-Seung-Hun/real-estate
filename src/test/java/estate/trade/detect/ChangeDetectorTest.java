package estate.trade.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import estate.trade.api.TradeItem;
import estate.trade.detect.ChangeDetector.Entry;
import estate.trade.detect.ChangeDetector.Stored;
import estate.trade.detect.ChangeDetector.TradeKey;
import estate.trade.detect.ChangeDetector.Values;

/** 데이터베이스 없이 비교 규칙만 확인한다(research R5). 모든 값은 가짜다. */
class ChangeDetectorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);
    private static final LocalDate REGISTERED = LocalDate.of(2026, 10, 1);
    private static final Values EMPTY = new Values(null, null, null);

    private static TradeItem item(long amount, String area, LocalDate registeredOn, String dong) {
        return new TradeItem("99999-1", "가상단지", "99999", "가상동", "123", DAY, 7, new BigDecimal(area), amount, false,
                null, registeredOn, dong);
    }

    private static TradeKey key(long amount, String area) {
        return new TradeKey("99999-1", DAY, 7, new BigDecimal(area), amount, false);
    }

    private static Map<TradeKey, Stored> previous(Object... keyAndStored) {
        Map<TradeKey, Stored> map = new HashMap<>();
        for (int i = 0; i < keyAndStored.length; i += 2) {
            map.put((TradeKey) keyAndStored[i], (Stored) keyAndStored[i + 1]);
        }
        return map;
    }

    private static List<Entry> detect(Map<TradeKey, Stored> previous, boolean baseline, TradeItem... items) {
        return ChangeDetector.detect(previous, List.of(items), baseline);
    }

    @Test
    void sameValueWithDifferentScaleIsTheSameKey() {
        Map<TradeKey, Stored> prev = previous(key(98500, "84.9"), new Stored(1, 1, EMPTY));

        assertThat(detect(prev, false, item(98500, "84.90", null, null))).isEmpty();
    }

    @Test
    void newKeyIsInsertedWithHistoryOnlyWhenComparing() {
        assertThat(detect(Map.of(), false, item(98500, "84.9", REGISTERED, null)))
                .singleElement().satisfies(e -> {
                    assertThat(e.isNew()).isTrue();
                    assertThat(e.changes()).containsExactly(ChangeType.NEW);
                });
        assertThat(detect(Map.of(), true, item(98500, "84.9", REGISTERED, null)))
                .singleElement().satisfies(e -> {
                    assertThat(e.isNew()).isTrue();
                    assertThat(e.changes()).isEmpty();
                });
    }

    @Test
    void groupedValueIsTheFirstNonEmptyOneRegardlessOfRowOrder() {
        List<TradeItem> blankFirst = List.of(item(98500, "84.9", null, null), item(98500, "84.9", REGISTERED, "101"));

        assertThat(ChangeDetector.detect(Map.of(), blankFirst, true)).singleElement().satisfies(e -> {
            assertThat(e.count()).isEqualTo(2);
            assertThat(e.values().registryDate()).isEqualTo(REGISTERED);
            assertThat(e.values().dong()).isEqualTo("101");
        });
    }

    @Test
    void missingKeyBecomesCountZeroAndKeepsItsValues() {
        Values stored = new Values(null, REGISTERED, "101");
        Map<TradeKey, Stored> prev = previous(key(98500, "84.9"), new Stored(1, 1, stored));

        assertThat(detect(prev, false)).singleElement().satisfies(e -> {
            assertThat(e.count()).isZero();
            assertThat(e.values()).isEqualTo(stored);
            assertThat(e.changes()).containsExactly(ChangeType.VANISHED);
        });
    }

    @Test
    void countOneRowTakesTodaysValuesAndRecordsRegistryOnlyWhenEmptyBecameFilled() {
        Map<TradeKey, Stored> prev = previous(key(98500, "84.9"), new Stored(1, 1, new Values(null, REGISTERED, null)));

        // 등기일이 다른 날짜로 바뀌는 것은 값 갱신일 뿐 등기가 아니다
        assertThat(detect(prev, false, item(98500, "84.9", REGISTERED.plusDays(1), null))).singleElement().satisfies(e -> {
            assertThat(e.values().registryDate()).isEqualTo(REGISTERED.plusDays(1));
            assertThat(e.changes()).isEmpty();
        });
        // 동이 비어 있다가 생기면 등기
        assertThat(detect(prev, false, item(98500, "84.9", REGISTERED, "101"))).singleElement()
                .satisfies(e -> assertThat(e.changes()).containsExactly(ChangeType.REGISTERED));
    }

    @Test
    void countTwoRowKeepsFirstValuesAndRecordsRegistryOnce() {
        Map<TradeKey, Stored> prev = previous(key(98500, "84.9"), new Stored(1, 2, EMPTY));

        assertThat(detect(prev, false, item(98500, "84.9", REGISTERED, null), item(98500, "84.9", null, null)))
                .singleElement().satisfies(e -> assertThat(e.changes()).containsExactly(ChangeType.REGISTERED));

        Map<TradeKey, Stored> after = previous(key(98500, "84.9"), new Stored(1, 2, new Values(null, REGISTERED, null)));
        assertThat(detect(after, false, item(98500, "84.9", REGISTERED.plusDays(3), "101"),
                item(98500, "84.9", null, null))).singleElement().satisfies(e -> {
                    assertThat(e.values().registryDate()).isEqualTo(REGISTERED);
                    assertThat(e.values().dong()).isEqualTo("101");
                    assertThat(e.changes()).isEmpty();
                });
    }

    @Test
    void revivedKeyRecordsNewButNotRegistry() {
        Map<TradeKey, Stored> prev = previous(key(98500, "84.9"), new Stored(1, 0, EMPTY));

        assertThat(detect(prev, false, item(98500, "84.9", REGISTERED, "101"))).singleElement().satisfies(e -> {
            assertThat(e.isNew()).isFalse();
            assertThat(e.count()).isEqualTo(1);
            assertThat(e.changes()).containsExactly(ChangeType.NEW);
        });
    }

    @Test
    void countIncreaseWithRegistryFilledRecordsNewAndRegistry() {
        Map<TradeKey, Stored> prev = previous(key(98500, "84.9"), new Stored(1, 1, EMPTY));

        assertThat(detect(prev, false, item(98500, "84.9", REGISTERED, null), item(98500, "84.9", null, null)))
                .singleElement().satisfies(e -> assertThat(e.changes())
                        .containsExactly(ChangeType.NEW, ChangeType.REGISTERED));
    }

    @Test
    void existingCanceledKeyRecordsCanceledWhenCountIncreasesOrReappears() {
        TradeKey canceled = new TradeKey("99999-1", DAY, 7, new BigDecimal("84.9"), 98500, true);
        TradeItem canceledItem = new TradeItem("99999-1", "가상단지", "99999", "가상동", "123", DAY, 7,
                new BigDecimal("84.9"), 98500, true, DAY.plusDays(3), null, null);

        // 건수 1에서 2로 늘어남
        assertThat(detect(previous(canceled, new Stored(1, 1, EMPTY)), false, canceledItem, canceledItem))
                .singleElement().satisfies(e -> assertThat(e.changes()).containsExactly(ChangeType.CANCELED));
        // 건수 0(사라졌던 키)에서 1로 다시 보임
        assertThat(detect(previous(canceled, new Stored(1, 0, EMPTY)), false, canceledItem))
                .singleElement().satisfies(e -> assertThat(e.changes()).containsExactly(ChangeType.CANCELED));
    }

    @Test
    void baselineSetsCountsAndValuesWithoutHistory() {
        Map<TradeKey, Stored> prev = previous(
                key(98500, "84.9"), new Stored(1, 1, new Values(null, null, "101")),
                key(99000, "84.9"), new Stored(2, 1, EMPTY));

        List<Entry> entries = detect(prev, true, item(98500, "84.9", REGISTERED, "102"));

        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(e -> assertThat(e.changes()).isEmpty());
        assertThat(entries).filteredOn(e -> e.key().amount() == 98500).singleElement()
                .satisfies(e -> assertThat(e.values()).isEqualTo(new Values(null, REGISTERED, "102")));
        assertThat(entries).filteredOn(e -> e.key().amount() == 99000).singleElement()
                .satisfies(e -> assertThat(e.count()).isZero());
    }
}
