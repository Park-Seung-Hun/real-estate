package estate.trade.raw;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 원본 응답 파일의 오브젝트 키(contracts/raw-response-object-key.md).
 * 날짜와 시각은 실행 시작 시각을 한국 시간으로 바꾼 값이다.
 */
public final class RawObjectKey {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private RawObjectKey() {
    }

    public static String of(Instant runStartedAt, String lawdCd, String dealYmd, int pageNo) {
        var seoul = runStartedAt.atZone(SEOUL);
        return DAY.format(seoul) + "/" + TIME.format(seoul) + "/" + lawdCd + "-" + dealYmd + "-p" + pageNo + ".xml.gz";
    }
}
