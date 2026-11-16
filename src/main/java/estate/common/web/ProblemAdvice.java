package estate.common.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ProblemAdvice {

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<Problem> handle(ProblemException e) {
        return ProblemResponses.of(e.problem());
    }
}
