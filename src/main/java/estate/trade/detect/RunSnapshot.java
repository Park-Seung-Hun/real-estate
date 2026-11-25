package estate.trade.detect;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 예약 실행이 시작될 때 읽은 대상 지역의 {@code fetch_range} 행(research R6). 기준 적재 판정은 이 값으로만 하고,
 * 같은 실행에서 갱신된 {@code fetch_range}는 판정에 쓰지 않는다.
 *
 * @param runStartedAt 실행 시작 시각. 원본 응답 파일의 오브젝트 키와 같은 값
 * @param rows 범위마다의 행. 키가 없으면 그 범위의 행이 없다(새로 들어온 달)
 */
public record RunSnapshot(Instant runStartedAt, Map<Range, Row> rows) {

    /** 시군구 코드와 계약년월(YYYYMM)로 정한 범위. */
    public record Range(String lawdCd, String dealYmd) {
    }

    /** @param fetchedAt 마지막으로 성공한 범위 반영의 수집 시각. 성공한 적 없이 행만 있으면 null */
    public record Row(Instant fetchedAt) {
    }

    public RunSnapshot {
        Objects.requireNonNull(runStartedAt);
        rows = Map.copyOf(rows);
    }
}
