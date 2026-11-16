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

    // 알 수 없는 필드는 400 VALIDATION_FAILED. 예외 메시지에 요청 본문 일부가 있어 로그와 응답에 쓰지 않는다.
    // 그 밖의 읽기 실패는 ProblemErrorController의 400과 같은 본문을 돌려준다
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> notReadable(HttpMessageNotReadableException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof UnrecognizedPropertyException) {
                return ProblemResponses.of(Problem.of(ProblemCode.VALIDATION_FAILED,
                        List.of(new Problem.FieldError("#", "알 수 없는 필드가 있습니다."))));
            }
        }
        return ProblemResponses.of(new Problem("about:blank", HttpStatus.BAD_REQUEST.getReasonPhrase(),
                HttpStatus.BAD_REQUEST.value(), null, null, null));
    }
}
