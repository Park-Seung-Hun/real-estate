package estate.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

/** 실제 PostgreSQL 컨테이너에서 Flyway를 적용한 스프링 컨텍스트로 실행하는 테스트. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ContextConfiguration(initializers = TestKeysInitializer.class)
// 골격 확인용 테이블은 운영 스키마에 남기지 않으려고 테스트 전용 위치에 둔다.
// 실제 구글 값과 .env 없이 컨텍스트가 뜨도록 가짜 클라이언트 값을 둔다
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/test-migration",
        "GOOGLE_CLIENT_ID=test-client-id",
        "GOOGLE_CLIENT_SECRET=test-client-secret",
        // HouseholdLock이 풀에서 연결을 하나 더 쓰므로 요청 10건이 잠금을 기다려도 풀이 마르지 않게 한다
        "spring.datasource.hikari.maximum-pool-size=20",
        // MockMvc 요청은 https://localhost로 가므로 요청 출처 검사의 허용 출처도 같게 둔다
        "estate.web.allowed-origins=https://localhost",
        // 테스트에서는 예약이 돌지 않는다. 수집 테스트는 실행 메서드를 직접 부른다
        "estate.collector.cron=-"})
public @interface IntegrationTest {
}
