package estate.trade.api;

import java.util.List;

/**
 * 정상 응답 한 페이지. 정상이 아닌 결과 코드는 해석기가 예외로 던지므로 resultCode는 늘 0이다.
 *
 * @param totalCount 전체 건수(모든 페이지 합)
 */
public record TradePage(int resultCode, List<TradeItem> items, int totalCount, int pageNo, int numOfRows) {

    public TradePage {
        items = List.copyOf(items);
    }
}
