package estate.skeleton;

import static org.assertj.core.api.Assertions.assertThat;

import estate.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class SequencePrimaryKeyTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SkeletonCheckRepository repository;

    @Test
    void savesAndFindsEntityWithPrimaryKeyTakenFromSequenceBeforeInsert() {
        long id = jdbc.sql("select nextval('skeleton_check_seq')").query(Long.class).single();

        repository.saveAndFlush(new SkeletonCheck(id, "가짜 단지"));

        assertThat(repository.findById(id))
                .hasValueSatisfying(found -> {
                    assertThat(found.getId()).isEqualTo(id);
                    assertThat(found.getName()).isEqualTo("가짜 단지");
                });
    }
}
