package estate.household.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import estate.household.domain.HouseholdRole;

class PermissionTest {

    /** 결정 기록 0011 권한 키 표의 owner, member 열(true가 허용). */
    private static final Map<String, boolean[]> TABLE = new LinkedHashMap<>();

    static {
        TABLE.put("household:read", new boolean[] {true, true});
        TABLE.put("invitation:read", new boolean[] {true, false});
        TABLE.put("invitation:create", new boolean[] {true, false});
        TABLE.put("invitation:revoke", new boolean[] {true, false});
        TABLE.put("member:remove", new boolean[] {true, false});
        TABLE.put("member:leave", new boolean[] {false, true});
        TABLE.put("household:delete", new boolean[] {true, false});
        TABLE.put("profile:write", new boolean[] {true, true});
        TABLE.put("none", new boolean[] {true, true});
    }

    @Test
    @DisplayName("T006 0011 권한 키 표: 상수는 아홉이고 키마다 owner와 member 허용이 표와 같다")
    void allowsMatchTheDecisionRecordTable() {
        assertThat(Permission.values()).hasSize(TABLE.size());
        assertThat(Arrays.stream(Permission.values()).map(Permission::key)).containsExactlyInAnyOrderElementsOf(TABLE.keySet());
        for (Permission permission : Permission.values()) {
            boolean[] expected = TABLE.get(permission.key());
            assertThat(expected).as("표에 있는 키: " + permission.key()).isNotNull();
            assertThat(permission.allows(HouseholdRole.OWNER)).as(permission.key() + " owner").isEqualTo(expected[0]);
            assertThat(permission.allows(HouseholdRole.MEMBER)).as(permission.key() + " member").isEqualTo(expected[1]);
        }
    }

    @Test
    @DisplayName("T006 역할의 DB 문자열은 owner, member다")
    void roleDbValues() {
        assertThat(HouseholdRole.OWNER.dbValue()).isEqualTo("owner");
        assertThat(HouseholdRole.MEMBER.dbValue()).isEqualTo("member");
    }
}
