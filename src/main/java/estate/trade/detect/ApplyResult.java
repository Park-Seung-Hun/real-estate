package estate.trade.detect;

import java.util.Map;

/**
 * 범위 반영의 결과.
 *
 * @param baseline 기준 적재로 반영했으면 참(이력을 기록하지 않음)
 * @param historyCounts 변경 종류별로 기록한 이력 행 수. 기록하지 않은 종류는 키가 없다
 */
public record ApplyResult(boolean baseline, Map<ChangeType, Integer> historyCounts) {

    public ApplyResult {
        historyCounts = Map.copyOf(historyCounts);
    }

    public int count(ChangeType type) {
        return historyCounts.getOrDefault(type, 0);
    }
}
