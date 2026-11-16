package estate.common.web;

import java.util.List;

/** 컨트롤러와 서비스가 던지면 {@link ProblemAdvice}가 Problem Details 응답으로 바꾼다. */
public class ProblemException extends RuntimeException {

    private final transient Problem problem;

    public ProblemException(ProblemCode code, List<Problem.FieldError> errors) {
        super(code.name(), null, false, false);
        this.problem = Problem.of(code, errors);
    }

    public ProblemException(ProblemCode code) {
        this(code, null);
    }

    public Problem problem() {
        return problem;
    }
}
