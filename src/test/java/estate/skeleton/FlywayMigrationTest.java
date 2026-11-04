package estate.skeleton;

import static org.assertj.core.api.Assertions.assertThat;

import estate.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class FlywayMigrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void flywayAppliedMigrationsSuccessfully() {
        int failed = jdbc.sql("select count(*) from flyway_schema_history where success = false")
                .query(Integer.class).single();
        int applied = jdbc.sql("select count(*) from flyway_schema_history where version = '0.1' and success = true")
                .query(Integer.class).single();

        assertThat(failed).isZero();
        assertThat(applied).isEqualTo(1);
    }
}
