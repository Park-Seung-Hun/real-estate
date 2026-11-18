package estate.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.RealServerClient;

/**
 * T022 실제 서버 확인(US1-3, US1-6, research R5, R16): 403 NO_HOUSEHOLD와 409 HAS_HOUSEHOLD의 본문을 실제 서버(RANDOM_PORT)에서 받아
 * 계약 예시와 키까지 같은지, 같은 세션 쿠키로 MockMvc가 받은 본문(T017)과 같은지 본다. 설정 방식은 ErrorResponseLeakTest와 같다.
 */
@LoginIntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class HouseholdErrorBodyServerTest {

    static final String ORIGIN = RealServerClient.ORIGIN;
    private static final ObjectMapper JSON = new ObjectMapper();

    // contracts/openapi.yaml components.responses의 NoHousehold, HasHousehold 예시
    static final String NO_HOUSEHOLD = """
            {"type": "https://example.com/problems/no-household", "title": "가구가 없습니다", "status": 403,
             "detail": "가구를 만들거나 초대 링크로 들어온 뒤 다시 시도해 주세요.", "code": "NO_HOUSEHOLD"}""";
    static final String HAS_HOUSEHOLD = """
            {"type": "https://example.com/problems/has-household", "title": "이미 가구가 있습니다", "status": 409,
             "detail": "가구를 나가거나 삭제한 뒤 다시 시도해 주세요.", "code": "HAS_HOUSEHOLD"}""";

    @LocalServerPort
    int port;
    @Autowired
    MockMvc mvc;

    private RealServerClient server;

    @BeforeEach
    void setUp() {
        server = new RealServerClient(port);
    }

    @Test
    @DisplayName("T022 US1-6 FR-026 실서버: 멤버십 없는 사람의 GET /api/v1/household는 403 NO_HOUSEHOLD problem+json이고 계약 예시, MockMvc 본문과 같으며, 가구를 만든 뒤에는 200")
    void noHouseholdBodyOnRealServer() throws Exception {
        String session = server.login();

        HttpResponse<String> response = server.send(HttpRequest.newBuilder(server.url("/api/v1/household")).GET()
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));

        JsonNode real = expectExactProblem(response, 403, NO_HOUSEHOLD);
        MvcResult mock = mvc.perform(https(get("/api/v1/household")).cookie(cookie(session))).andReturn();
        assertThat(mock.getResponse().getStatus()).isEqualTo(403);
        assertThat(JSON.readTree(mock.getResponse().getContentAsString(StandardCharsets.UTF_8))).as("MockMvc 본문과 같다").isEqualTo(real);

        // 짝: 가구를 만들면 같은 요청이 200
        assertThat(create(session).statusCode()).isEqualTo(201);
        assertThat(server.send(HttpRequest.newBuilder(server.url("/api/v1/household")).GET()
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("T022 US1-3 FR-005 실서버: 가구가 있는 사람의 POST /api/v1/household는 409 HAS_HOUSEHOLD problem+json이고 계약 예시, MockMvc 본문과 같다(첫 요청은 201)")
    void hasHouseholdBodyOnRealServer() throws Exception {
        String session = server.login();
        HttpResponse<String> first = create(session);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.body()).as("201 본문이 없다").isEmpty();

        JsonNode real = expectExactProblem(create(session), 409, HAS_HOUSEHOLD);

        MvcResult mock = mvc.perform(https(post("/api/v1/household")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie(session)))
                .andReturn();
        assertThat(mock.getResponse().getStatus()).isEqualTo(409);
        assertThat(JSON.readTree(mock.getResponse().getContentAsString(StandardCharsets.UTF_8))).as("MockMvc 본문과 같다").isEqualTo(real);
    }

    private HttpResponse<String> create(String session) throws Exception {
        return server.send(HttpRequest.newBuilder(server.url("/api/v1/household"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Origin", ORIGIN)
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));
    }

    /** 상태 코드, application/problem+json, 본문 JSON이 계약 예시와 키까지 같다. 본문 JSON을 준다. */
    private static JsonNode expectExactProblem(HttpResponse<String> response, int status, String expectedJson) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).as("Content-Type이 application/problem+json").isTrue();
        JsonNode body = JSON.readTree(response.body());
        assertThat(body).isEqualTo(JSON.readTree(expectedJson));
        return body;
    }

    private static Cookie cookie(String session) {
        return new Cookie(LoginClient.SESSION_COOKIE, session);
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
