package estate.trade.collect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import estate.trade.detect.RunSnapshot;
import estate.trade.detect.RunSnapshot.Range;

/**
 * 예약 실행 하나가 수집할 범위를 정한다(research R3). 설정에서 뺀 지역의 범위는 넣지 않는다(FR-020). 입력은 실행 시작 때 읽은
 * 값이다.
 */
final class CollectionPlanner {

    private CollectionPlanner() {
    }

    /**
     * 매일 수집의 범위. 행이 있는 지역의 재수집 기간 달(오래된 달부터)을 모두 둔 뒤 그 밖의 실패한 범위를 둔다. 순서가 뒤바뀌면
     * 호출 한도에 걸린 날 재수집 기간의 달이 밀린다(FR-004).
     *
     * @param failedRanges 설정한 지역의 실패한 범위만. 뺀 지역은 읽을 때 거른다(FR-020)
     */
    static List<Range> dailyRanges(List<String> regions, RunSnapshot snapshot, Collection<Range> failedRanges) {
        Set<String> collected = regionsWithRows(snapshot);
        Set<Range> ranges = new LinkedHashSet<>();
        for (String region : regions) {
            if (collected.contains(region)) {
                for (String dealYmd : CollectionCalendar.refetchWindow(snapshot.runStartedAt())) {
                    ranges.add(new Range(region, dealYmd));
                }
            }
        }
        ranges.addAll(failedRanges);
        return new ArrayList<>(ranges);
    }

    /** 초기 적재나 범위 확장 대상: 설정한 지역 가운데 {@code fetch_range} 행이 하나도 없는 지역(FR-003). */
    static List<String> newRegions(List<String> regions, RunSnapshot snapshot) {
        Set<String> collected = regionsWithRows(snapshot);
        return regions.stream().filter(region -> !collected.contains(region)).toList();
    }

    private static Set<String> regionsWithRows(RunSnapshot snapshot) {
        return snapshot.rows().keySet().stream().map(Range::lawdCd).collect(Collectors.toSet());
    }
}
