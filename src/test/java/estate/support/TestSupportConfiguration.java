package estate.support;

import java.time.Clock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code @LoginIntegrationTest}가 가져오는 설정. 메인의 {@code clock} 빈과 이름이 같으면 Boot가 덮어쓰기를 막아 컨텍스트가 죽으므로
 * 다른 이름의 {@code @Primary} 빈을 더한다(Clock을 받는 곳은 이 빈을 받음. 메인 빈이 아직 없어도 동작함).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }

    /** Spring이 시작할 때 discovery 문서를 읽으므로 issuer 주소를 컨텍스트 시작 때 넣는다. */
    @Bean
    static DynamicPropertyRegistrar fakeGoogleProperties() {
        return registry -> {
            registry.add("spring.security.oauth2.client.provider.google.issuer-uri", FakeGoogleServer::issuer);
            registry.add("estate.oidc.allowed-issuers[0]", FakeGoogleServer::issuer);
        };
    }

    @Bean
    SessionRows sessionRows(JdbcClient jdbc) {
        return new SessionRows(jdbc);
    }
}
