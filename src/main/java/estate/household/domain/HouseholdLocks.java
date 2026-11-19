package estate.household.domain;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 가구 행 잠금(research R9, 결정 기록 0016). 구성원이나 초대를 바꾸는 트랜잭션이 첫 쓰기 전에 부른다. */
@Component
public class HouseholdLocks {

    private final JdbcClient jdbc;

    HouseholdLocks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 잠그면 true. 빈 결과는 삭제된 가구이므로 false. 트랜잭션 밖에서 부르면 잠금이 바로 풀리므로 막는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lock(long householdId) {
        return jdbc.sql("select id from household where id = :id for update").param("id", householdId)
                .query(Long.class).optional().isPresent();
    }
}
