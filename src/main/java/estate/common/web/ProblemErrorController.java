package estate.common.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * `sendError`로 끝난 모든 오류(405, 415, 400 본문 파싱, 방화벽 거절, 500 등)를 Problem Details로 응답한다.
 * Boot 기본 오류 응답은 요청 경로(`path`)를 본문에 담고 형식도 problem+json이 아니어서 대신한다.
 * 요청 경로, 요청 값, 예외 메시지는 담지 않는다. 계약에 이 오류들의 code가 없어 code는 비운다.
 */
@RestController
class ProblemErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<Problem> error(HttpServletRequest request) {
        HttpStatus status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
                && HttpStatus.resolve(code) != null ? HttpStatus.resolve(code) : HttpStatus.INTERNAL_SERVER_ERROR;
        return ProblemResponses.of(new Problem("about:blank", status.getReasonPhrase(), status.value(), null, null, null));
    }
}
