package estate.support;

import java.io.IOException;
import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/** {@link CollectorIntegrationTest}가 가져오는 빈. */
@TestConfiguration(proxyBeanMethods = false)
public class CollectorTestConfiguration {

    /** 가짜 인증키. 인코딩이 필요한 글자를 일부러 넣는다. */
    public static final String SERVICE_KEY = "fakeKey+abc/def==";

    @Bean
    FakeTradeApiServer fakeTradeApiServer() throws IOException {
        return FakeTradeApiServer.start();
    }

    @Bean
    DynamicPropertyRegistrar fakeTradeApiProperties(FakeTradeApiServer server) {
        return registry -> registry.add("estate.collector.base-url", server::baseUrl);
    }

    @Bean
    @Primary
    RecordingRawResponseStore recordingRawResponseStore() {
        return new RecordingRawResponseStore();
    }

    /** 운영 정책과 시도 횟수, 대상 예외가 같고 기다리지만 않는다. */
    @Bean
    @Primary
    RetryPolicy zeroDelayRetryPolicy() {
        return RetryPolicy.builder()
                .maxRetries(2)
                .delay(Duration.ZERO)
                .includes(ResourceAccessException.class, HttpStatusCodeException.class)
                .build();
    }

    @Bean
    CollectorFixture collectorFixture(FakeTradeApiServer server, RecordingRawResponseStore store, JdbcClient jdbc) {
        return new CollectorFixture(server, store, jdbc);
    }
}
