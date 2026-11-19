package estate.trade.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 응답의 거래 한 건. 없는 값은 null이다.
 *
 * @param aptSeq 단지 번호(문자열 그대로)
 * @param amount 거래 금액(만원)
 * @param canceled 해제 여부(cdealType이 비어 있지 않으면 참)
 * @param canceledOn 해제 사유 발생일
 * @param registeredOn 등기일
 * @param dong 동(앞뒤 공백만 뺀 문자열)
 */
public record TradeItem(
        String aptSeq,
        String aptName,
        String sggCd,
        String umdName,
        String jibun,
        LocalDate dealDate,
        int floor,
        BigDecimal exclusiveArea,
        long amount,
        boolean canceled,
        LocalDate canceledOn,
        LocalDate registeredOn,
        String dong) {
}
