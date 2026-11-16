package estate.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import estate.support.LoginIntegrationTest;

/** T018: 응답 보안 헤더(research R15). 화면 응답(/)과 API 응답(로그인 없는 /api/v1/me, 401) 모두 본다. */
@LoginIntegrationTest
@AutoConfigureMockMvc
class SecurityHeadersTest {

    /** research R15 원문 그대로. */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self';"
            + " object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";

    @Autowired
    MockMvc mvc;

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @CsvSource({"/, 200", "/api/v1/me, 401"})
    @DisplayName("T018 research R15: CSP 값이 원문 그대로이고 frame-ancestors 'none' 을 담는다")
    void cspIsExact(String path, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(https(get(path))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(result.getResponse().getHeaders("Content-Security-Policy")).containsExactly(CSP);
        assertThat(result.getResponse().getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @CsvSource({"/, 200", "/api/v1/me, 401"})
    @DisplayName("T018 research R15: HTTPS 요청에는 HSTS max-age 1년")
    void hstsOnHttps(String path, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(https(get(path))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(result.getResponse().getHeader("Strict-Transport-Security"))
                .isNotNull()
                .matches("max-age=31536000(\\s*;.*)?");
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @CsvSource({"/, 200", "/api/v1/me, 401"})
    @DisplayName("T018 research R15 짝: HTTP 요청에는 HSTS가 없고 CSP는 붙는다")
    void noHstsOnHttp(String path, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(path).secure(false)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(result.getResponse().getHeader("Strict-Transport-Security")).isNull();
        assertThat(result.getResponse().getHeaders("Content-Security-Policy")).containsExactly(CSP);
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
