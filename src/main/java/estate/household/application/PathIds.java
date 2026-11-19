package estate.household.application;

import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;

/** 경로 번호 해석. 숫자가 아니거나 long 범위를 넘으면 404다(research R3). */
final class PathIds {

    private PathIds() {
    }

    static long parse(String value) {
        // 경로 값을 예외 메시지에 넣지 않는다
        try {
            if (value.matches("[0-9]{1,19}")) {
                return Long.parseLong(value);
            }
        } catch (NumberFormatException e) {
            // 19자리가 long 범위를 넘으면 아래 404
        }
        throw new ProblemException(ProblemCode.NOT_FOUND);
    }
}
