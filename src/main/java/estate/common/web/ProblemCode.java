package estate.common.web;

import org.springframework.http.HttpStatus;

/** 오류 코드별 고정 문구(contracts/openapi.yaml 예시와 같다). */
public enum ProblemCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated", "로그인이 필요합니다", "다시 로그인해 주세요."),
    REAUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "reauth-required", "구글 계정 확인이 필요합니다",
            "구글 계정을 한 번 더 확인한 뒤 다시 시도해 주세요."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation-failed", "입력값이 올바르지 않습니다", "입력값 하나를 확인해 주세요."),
    ORIGIN_REJECTED(HttpStatus.FORBIDDEN, "origin-rejected", "허용하지 않은 요청입니다", "서비스 화면에서 다시 시도해 주세요.");

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
