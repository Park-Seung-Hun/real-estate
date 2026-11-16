package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;

/**
 * T019 보안 경계: 로그인 뒤 돌아갈 경로(US1-7, FR-006, SC-005, 결정 기록 0013, research R6).
 * 값은 서블릿이 질의 값을 한 번 푼 값(getParameter)이다. MockMvc의 queryParam은 이 값을 그대로 파라미터로 두고 질의 문자열은 인코딩한다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class ReturnPathSecurityTest {

    @Autowired
    MockMvc mvc;
    LoginClient login;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "/\t/evil.example", // `/` 다음 탭. 질의 문자열 `/%09/evil.example`을 서블릿이 푼 값과 같다
            "/%09/x",           // 한 번 푼 값에 남은 퍼센트 인코딩. 다시 풀면 `/` 다음 탭
            "//x",
            "/\\x",
            "/x\\y",            // 중간의 역슬래시(FR-006)
            "/x\ny"             // 줄바꿈(FR-006 제어 문자)
    })
    @DisplayName("T019 US1-7 FR-006 SC-005: 서비스 밖이 될 수 있는 돌아갈 경로는 로그인 뒤 / 로 간다")
    void unsafeReturnPathGoesToRoot(String returnTo) throws Exception {
        assertThat(pathAfterLogin(returnTo)).isEqualTo("/");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/a%20b", "/withdraw%3Fx", "/집", "/café"})
    @DisplayName("T019 FR-006 research R6: % 가 있거나 ASCII 밖 문자가 있으면 / 로 간다")
    void percentOrNonAsciiGoesToRoot(String returnTo) throws Exception {
        assertThat(pathAfterLogin(returnTo)).isEqualTo("/");
    }

    @Test
    @DisplayName("T019 US1-7 FR-005: /withdraw 는 그대로 간다")
    void safeReturnPathIsKept() throws Exception {
        assertThat(pathAfterLogin("/withdraw")).isEqualTo("/withdraw");
    }

    /** 로그인에 성공했음을 확인하고 콜백의 Location을 서비스 안 경로로 돌려준다(같은 출처 절대 주소도 받는다). */
    private String pathAfterLogin(String returnTo) throws Exception {
        String id = UUID.randomUUID().toString();
        LoginClient.Result result = login.login(GoogleAccount.of("rp-" + id, "rp-" + id + "@example.com"), returnTo, null, null);

        assertThat(result.sessionCookie()).as("로그인은 성공해야 한다(돌아갈 경로만 바뀜)").isNotNull();
        assertThat(result.callback().getResponse().getStatus()).isBetween(300, 399);
        String location = result.location();
        assertThat(location).isNotNull();
        return location.startsWith("https://localhost/") ? location.substring("https://localhost".length()) : location;
    }
}
