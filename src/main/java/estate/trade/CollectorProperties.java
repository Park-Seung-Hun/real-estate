package estate.trade;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * 실거래 수집 설정. 형식이 틀리면 생성자가 던져 애플리케이션이 시작하지 않는다.
 * 예외 메시지에는 잘못된 지역 값과 설정 이름만 넣고 인증키, 저장소 키 값은 넣지 않는다.
 */
@ConfigurationProperties("estate.collector")
public record CollectorProperties(
        List<String> regions,
        String serviceKey,
        String cron,
        String baseUrl,
        int dailyCallLimit,
        int selfStopMargin,
        Duration connectTimeout,
        Duration readTimeout,
        Raw raw) {

    private static final Pattern REGION = Pattern.compile("[0-9]{5}");

    /** 원본 응답 버킷(S3 호환 저장소) 설정. */
    public record Raw(String endpoint, String region, String bucket, String accessKey, String secretKey) {

        /** 로그나 단언 실패 출력에 비밀값이 실리지 않게 한다. */
        @Override
        public String toString() {
            return "Raw[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket
                    + ", accessKey=***, secretKey=***]";
        }
    }

    public CollectorProperties {
        regions = regions == null ? List.of() : List.copyOf(regions);
        raw = raw == null ? new Raw(null, null, null, null, null) : raw;

        Set<String> seen = new HashSet<>();
        for (String region : regions) {
            if (!REGION.matcher(region).matches()) {
                throw new IllegalArgumentException("estate.collector.regions의 값이 숫자 다섯 자리가 아닙니다: " + region);
            }
            if (!seen.add(region)) {
                throw new IllegalArgumentException("estate.collector.regions에 같은 값이 두 번 있습니다: " + region);
            }
        }
        if (!regions.isEmpty()) {
            requireText(serviceKey, "estate.collector.service-key");
            requireText(raw.endpoint(), "estate.collector.raw.endpoint");
            requireText(raw.region(), "estate.collector.raw.region");
            requireText(raw.bucket(), "estate.collector.raw.bucket");
            requireText(raw.accessKey(), "estate.collector.raw.access-key");
            requireText(raw.secretKey(), "estate.collector.raw.secret-key");
        }
        if (cron == null || !(Scheduled.CRON_DISABLED.equals(cron) || CronExpression.isValidExpression(cron))) {
            throw new IllegalArgumentException("estate.collector.cron이 cron 형식이 아닙니다");
        }
        if (dailyCallLimit <= 0 || selfStopMargin <= 0 || selfStopMargin >= dailyCallLimit) {
            throw new IllegalArgumentException(
                    "estate.collector.daily-call-limit과 self-stop-margin은 양수이고 여유가 한도보다 작아야 합니다");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("estate.collector의 연결, 읽기 타임아웃은 양수여야 합니다");
        }
    }

    /** 로그나 단언 실패 출력에 인증키가 실리지 않게 한다. */
    @Override
    public String toString() {
        return "CollectorProperties[regions=" + regions + ", serviceKey=***, cron=" + cron + ", baseUrl=" + baseUrl
                + ", dailyCallLimit=" + dailyCallLimit + ", selfStopMargin=" + selfStopMargin
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + ", raw=" + raw + "]";
    }

    /** 스스로 멈추는 호출 수 기준값. 실행 안에서 센 호출 수가 이 값에 도달하면 다음 호출을 하지 않는다. */
    public int selfStopCalls() {
        return dailyCallLimit - selfStopMargin;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "이 비어 있습니다(지역을 설정하면 필요합니다)");
        }
    }
}
