package estate.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    // shortcut: 컨테이너가 스프링 컨텍스트마다 하나 뜬다. 컨텍스트 종류가 늘어 느려지면 정적 싱글턴으로 바꾼다.
    // 운영 PostgreSQL 메이저 버전과 같은 값을 쓴다(결정 기록 0016).
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:18");
    }
}
