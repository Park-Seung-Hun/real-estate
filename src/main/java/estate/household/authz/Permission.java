package estate.household.authz;

import estate.household.domain.HouseholdRole;

/** 이 기능이 쓰는 권한 키와 역할별 허용(결정 기록 0011 권한 키 표). 0011의 나머지 키는 해당 기능이 추가한다. */
public enum Permission {
    HOUSEHOLD_READ("household:read", true, true),
    INVITATION_READ("invitation:read", true, false),
    INVITATION_CREATE("invitation:create", true, false),
    INVITATION_REVOKE("invitation:revoke", true, false),
    MEMBER_REMOVE("member:remove", true, false),
    MEMBER_LEAVE("member:leave", false, true),
    HOUSEHOLD_DELETE("household:delete", true, false),
    PROFILE_WRITE("profile:write", true, true),
    /** 계약의 {@code x-permission: none}. 3단계는 통과한다. */
    NONE("none", true, true);

    private final String key;
    private final boolean ownerAllowed;
    private final boolean memberAllowed;

    Permission(String key, boolean ownerAllowed, boolean memberAllowed) {
        this.key = key;
        this.ownerAllowed = ownerAllowed;
        this.memberAllowed = memberAllowed;
    }

    /** 계약의 x-permission 문자열. */
    public String key() {
        return key;
    }

    public boolean allows(HouseholdRole role) {
        return role == HouseholdRole.OWNER ? ownerAllowed : memberAllowed;
    }
}
