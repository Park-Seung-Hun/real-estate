package estate.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * 수집 실행 테스트의 컨텍스트. {@code @LoginIntegrationTest}(실제 PostgreSQL, {@link MutableClock})에 수집 지역 설정, 가짜
 * 실거래 API 서버, 기록하는 가짜 원본 저장소, 대기 0 재시도 정책을 더한다. 예약은 꺼져 있어 실행 메서드를 직접 부른다.
 * {@code @Autowired CollectorFixture}를 받아 {@code @BeforeEach}와 {@code @AfterEach}에서 {@code reset()}을 부른다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@LoginIntegrationTest
@Import(CollectorTestConfiguration.class)
@TestPropertySource(properties = {
        "estate.collector.regions=99999",
        "estate.collector.service-key=" + CollectorTestConfiguration.SERVICE_KEY,
        "estate.collector.raw.endpoint=http://127.0.0.1:1",
        "estate.collector.raw.region=garage",
        "estate.collector.raw.bucket=raw-test",
        "estate.collector.raw.access-key=test-access-key",
        "estate.collector.raw.secret-key=test-secret-key",
        "estate.collector.read-timeout=1s"})
public @interface CollectorIntegrationTest {
}
