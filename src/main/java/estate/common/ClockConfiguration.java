package estate.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    // 테스트는 @Primary 빈으로 이 시계를 대신한다
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
