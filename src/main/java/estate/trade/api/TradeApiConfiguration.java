package estate.trade.api;

import estate.trade.CollectorProperties;
import estate.trade.ConditionalOnCollectorRegions;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/** 수집할 지역이 있을 때만 API 어댑터와 재시도 정책을 만든다(research R9). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnCollectorRegions
public class TradeApiConfiguration {

    /** 재시도 두 번, 대기 2초 다음 4초. 네트워크 오류와 HTTP 오류만 대상이다(research R2). */
    public static RetryPolicy productionRetryPolicy() {
        return RetryPolicy.builder()
                .maxRetries(2)
                .delay(Duration.ofSeconds(2))
                .multiplier(2)
                .includes(ResourceAccessException.class, HttpStatusCodeException.class)
                .build();
    }

    @Bean
    RetryPolicy tradeApiRetryPolicy() {
        return productionRetryPolicy();
    }

    @Bean
    TradeApiClient tradeApiClient(CollectorProperties properties, RetryPolicy tradeApiRetryPolicy) {
        return new TradeApiClient(properties, tradeApiRetryPolicy);
    }
}
