package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 돌아갈 경로 규칙(T031, 결정 기록 0013, research R6). 값은 ReturnPathSecurityTest와 같다. */
class ReturnPathTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"withdraw", "https://evil.example", "/\t/evil.example", "/%09/x", "//x", "/\\x", "/x\\y",
            "/x\ny", "/x\u007F", "/a%20b", "/withdraw%3Fx", "/집", "/café"})
    void 서비스_밖이_될_수_있는_값은_루트(String requested) {
        assertThat(ReturnPath.sanitize(requested)).isEqualTo("/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/withdraw", "/no-household?x=1#y"})
    void 서비스_안_경로는_그대로(String requested) {
        assertThat(ReturnPath.sanitize(requested)).isEqualTo(requested);
    }
}
