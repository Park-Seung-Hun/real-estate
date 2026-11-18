package estate.household.domain;

/** 가구 안의 역할(data-model `household_member.role`). */
public enum HouseholdRole {
    OWNER("owner"),
    MEMBER("member");

    private final String dbValue;

    HouseholdRole(String dbValue) {
        this.dbValue = dbValue;
    }

    /** DB에 저장하는 문자열. */
    public String dbValue() {
        return dbValue;
    }
}
