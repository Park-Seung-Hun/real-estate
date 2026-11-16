package estate.common.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Problem Details 응답을 만드는 진입점. 컨트롤러는 ProblemException, 보안 필터는 write를 쓴다. */
@Component
public class ProblemResponses {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
    private final ObjectMapper objectMapper;

    ProblemResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    static ResponseEntity<Problem> of(Problem problem) {
        return ResponseEntity.status(problem.status()).contentType(PROBLEM_JSON).body(problem);
    }

    /** 필터와 인증 진입점처럼 컨트롤러 밖에서 응답을 직접 쓴다. */
    public void write(HttpServletResponse response, ProblemCode code) throws IOException {
        write(response, code, null);
    }

    public void write(HttpServletResponse response, ProblemCode code, List<Problem.FieldError> errors)
            throws IOException {
        Problem problem = Problem.of(code, errors);
        response.setStatus(code.status.value());
        response.setContentType(PROBLEM_JSON.toString());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
