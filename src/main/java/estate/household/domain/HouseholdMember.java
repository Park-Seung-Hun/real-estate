package estate.household.domain;

import java.time.Instant;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** 가구 구성원(data-model `household_member`). 기본 키는 (household_id, user_id), user_id는 유니크라 사용자는 가구 하나에만 속한다. */
@Entity
@Table(name = "household_member")
@IdClass(HouseholdMember.Key.class)
public class HouseholdMember {

    public record Key(long householdId, long userId) {
    }

    @Converter
    static class RoleConverter implements AttributeConverter<HouseholdRole, String> {
        @Override
        public String convertToDatabaseColumn(HouseholdRole role) {
            return role == null ? null : role.dbValue();
        }

        @Override
        public HouseholdRole convertToEntityAttribute(String dbValue) {
            for (HouseholdRole role : HouseholdRole.values()) {
                if (role.dbValue().equals(dbValue)) {
                    return role;
                }
            }
            return null;
        }
    }

    @Id
    private long householdId;

    @Id
    private long userId;

    @Convert(converter = RoleConverter.class)
    private HouseholdRole role;

    private Instant joinedAt;

    protected HouseholdMember() {
    }

    public HouseholdMember(long householdId, long userId, HouseholdRole role, Instant joinedAt) {
        this.householdId = householdId;
        this.userId = userId;
        this.role = role;
        this.joinedAt = joinedAt;
    }

    public long getHouseholdId() {
        return householdId;
    }

    public long getUserId() {
        return userId;
    }

    public HouseholdRole getRole() {
        return role;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
