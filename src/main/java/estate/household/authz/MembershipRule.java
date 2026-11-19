package estate.household.authz;

/** 권한 확인 2단계가 멤버십을 어떻게 다루는지(research R2). */
public enum MembershipRule {
    /** 멤버십이 있어야 한다. 없으면 403 NO_HOUSEHOLD, 있으면 3단계로 간다. */
    REQUIRED,
    /** 없으면 3, 4단계를 건너뛰고, 있으면 3단계로 간다. */
    OPTIONAL,
    /** 없으면 통과하고, 있으면 409 HAS_HOUSEHOLD. */
    ABSENT
}
