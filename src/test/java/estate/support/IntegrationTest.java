package estate.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** 실제 PostgreSQL 컨테이너에서 Flyway를 적용한 스프링 컨텍스트로 실행하는 테스트. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import(PostgresTestConfiguration.class)
// 골격 확인용 테이블은 운영 스키마에 남기지 않으려고 테스트 전용 위치에 둔다.
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/test-migration")
public @interface IntegrationTest {
}
