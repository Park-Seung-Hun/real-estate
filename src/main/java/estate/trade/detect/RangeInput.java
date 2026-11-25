package estate.trade.detect;

import java.util.List;
import java.util.Objects;

import estate.trade.api.TradeItem;

/**
 * 범위 하나의 오늘 결과. 수집 쪽이 페이지를 모두 수집하고 원본 저장과 건수 확인을 마친 뒤 넘긴다.
 *
 * @param lawdCd 시군구 코드(다섯 자리)
 * @param dealYmd 계약년월(YYYYMM)
 * @param items 응답 항목 전부(같은 거래가 여러 건이면 항목도 여러 개)
 * @param snapshot 이 실행이 시작될 때 읽은 {@code fetch_range}
 */
public record RangeInput(String lawdCd, String dealYmd, List<TradeItem> items, RunSnapshot snapshot) {

    public RangeInput {
        Objects.requireNonNull(lawdCd);
        Objects.requireNonNull(dealYmd);
        Objects.requireNonNull(snapshot);
        items = List.copyOf(items);
    }
}
