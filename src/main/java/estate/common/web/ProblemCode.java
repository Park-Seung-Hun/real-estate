package estate.common.web;

import org.springframework.http.HttpStatus;

/** 오류 코드별 고정 문구(contracts/openapi.yaml 예시와 같다). */
public enum ProblemCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated", "로그인이 필요합니다", "다시 로그인해 주세요."),
    REAUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "reauth-required", "구글 계정 확인이 필요합니다",
            "구글 계정을 한 번 더 확인한 뒤 다시 시도해 주세요."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation-failed", "입력값이 올바르지 않습니다", "입력값 하나를 확인해 주세요."),
    ORIGIN_REJECTED(HttpStatus.FORBIDDEN, "origin-rejected", "허용하지 않은 요청입니다", "서비스 화면에서 다시 시도해 주세요."),
    NO_HOUSEHOLD(HttpStatus.FORBIDDEN, "no-household", "가구가 없습니다",
            "가구를 만들거나 초대 링크로 들어온 뒤 다시 시도해 주세요."),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "permission-denied", "할 수 없는 동작입니다", "이 가구에서 할 수 없는 동작입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not-found", "찾을 수 없습니다", "주소를 확인해 주세요."),
    HAS_HOUSEHOLD(HttpStatus.CONFLICT, "has-household", "이미 가구가 있습니다", "가구를 나가거나 삭제한 뒤 다시 시도해 주세요."),
    HOUSEHOLD_FULL(HttpStatus.CONFLICT, "household-full", "구성원이 이미 둘입니다", "가구 구성원은 둘까지입니다."),
    INVITATION_PENDING(HttpStatus.CONFLICT, "invitation-pending", "수락 대기 중인 초대가 있습니다",
            "수락 대기 중인 초대를 취소한 뒤 다시 시도해 주세요."),
    INVITATION_NOT_PENDING(HttpStatus.CONFLICT, "invitation-not-pending", "취소할 수 없는 초대입니다",
            "이미 수락되었거나 취소되었거나 만료된 초대입니다."),
    INVITATION_REVOKED(HttpStatus.CONFLICT, "invitation-revoked", "취소된 초대 링크입니다", "가족에게 새 초대 링크를 받아 주세요."),
    INVITATION_USED(HttpStatus.CONFLICT, "invitation-used", "이미 사용된 초대 링크입니다", "가족에게 새 초대 링크를 받아 주세요."),
    INVITATION_EXPIRED(HttpStatus.CONFLICT, "invitation-expired", "초대 링크가 만료되었습니다", "가족에게 새 초대 링크를 받아 주세요."),
    ALREADY_IN_THIS_HOUSEHOLD(HttpStatus.CONFLICT, "already-in-this-household", "이미 이 가구의 구성원입니다",
            "링크는 가족에게 보내세요.");

    final HttpStatus status;
    final String type;
    final String title;
    final String detail;

    ProblemCode(HttpStatus status, String slug, String title, String detail) {
        this.status = status;
        this.type = "https://example.com/problems/" + slug;
        this.title = title;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }
}
