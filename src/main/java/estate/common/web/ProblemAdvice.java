package estate.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
}
