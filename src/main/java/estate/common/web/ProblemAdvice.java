package estate.common.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.databind.exc.UnrecognizedPropertyException;

@RestControllerAdvice
class ProblemAdvice {

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<Problem> handle(ProblemException e) {
        return ProblemResponses.of(e.problem());
    }

    // 없는 API 경로(research R2). ProblemDetail로 돌려주면 Spring MVC가 instance에 요청 경로를 넣으므로 우리 형식으로 쓴다.
    // 계약의 code에 404용 값이 없어 code는 비운다
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Problem> notFound() {
        return ProblemResponses.of(new Problem("about:blank", "Not Found", HttpStatus.NOT_FOUND.value(), null, null, null));
    }

    // 본문을 읽지 못하면 400 VALIDATION_FAILED(계약의 Problem.code가 필수). 알 수 없는 필드와 그 밖의 읽기 실패(깨진 JSON, 빈 본문)는
    // errors의 문구만 다르다. 예외 메시지에 요청 본문 일부가 있어 로그와 응답에 쓰지 않는다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> notReadable(HttpMessageNotReadableException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof UnrecognizedPropertyException) {
                return validationFailed("알 수 없는 필드가 있습니다.");
            }
        }
        return validationFailed("요청 본문을 읽을 수 없습니다.");
    }

    private static ResponseEntity<Problem> validationFailed(String detail) {
        return ProblemResponses.of(Problem.of(ProblemCode.VALIDATION_FAILED, List.of(new Problem.FieldError("#", detail))));
    }
}
