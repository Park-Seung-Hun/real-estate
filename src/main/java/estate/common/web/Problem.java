package estate.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** `application/problem+json` 본문. 요청 값은 담지 않는다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(String type, String title, int status, String detail, ProblemCode code, List<FieldError> errors) {

    public record FieldError(String pointer, String detail) {}

    public static Problem of(ProblemCode code, List<FieldError> errors) {
        boolean hasErrors = errors != null && !errors.isEmpty();
        if (code == ProblemCode.VALIDATION_FAILED && !hasErrors) {
            throw new IllegalArgumentException("VALIDATION_FAILED에는 errors가 필요합니다.");
        }
        return new Problem(code.type, code.title, code.status.value(), code.detail, code,
                hasErrors && code == ProblemCode.VALIDATION_FAILED ? errors : null);
    }
}
