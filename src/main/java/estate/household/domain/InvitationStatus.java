package estate.household.domain;

/** 초대 상태(data-model `invitation.status`). 만료는 상태가 아니라 expires_at으로 판단한다. */
public enum InvitationStatus {
    PENDING("대기"),
    ACCEPTED("수락"),
    REVOKED("취소");

    private final String dbValue;

    InvitationStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** DB에 저장하는 문자열. */
    public String dbValue() {
        return dbValue;
    }
}
