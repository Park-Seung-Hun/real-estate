package estate.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.RealServerClient;

/**
 * 오류 본문의 요청 값 비반사(FR-020, tasks T012 "오류 본문과 로그에 요청 값을 그대로 넣지 않는다", API 공통 규칙 오류 응답 절의 application/problem+json).
 * 우리 advice가 받지 않는 오류(405, 방화벽 거절 400, 본문 파싱 400과 415)는 sendError로 Boot 기본 오류 처리에 가므로,
 * error dispatch를 하지 않는 MockMvc 대신 실제 서버를 띄워 java.net.http로 요청한다.
 * 직접 붙인 @SpringBootTest(RANDOM_PORT)가 @LoginIntegrationTest 안의 @SpringBootTest보다 가깝게 선언돼 이긴다
 * (두 선언의 @BootstrapWith 값이 같아 충돌하지 않음). 그 밖의 @IntegrationTest 설정(가짜 키, 가짜 구글, 허용 출처, PostgreSQL)은 그대로 쓴다.
 * 로그인은 MockMvc LoginClient를 쓸 수 없어 실제 서버 주소로 가짜 구글 흐름을 직접 돈다.
 */
@LoginIntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ErrorResponseLeakTest {

    static final String ORIGIN = RealServerClient.ORIGIN;
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    private RealServerClient server;

    @BeforeEach
    void setUp() {
        server = new RealServerClient(port);
    }

    @Test
    @DisplayName("FR-020: 로그인 상태의 GET /api/v1/logout(POST만 받음) 오류 본문에 경로와 표식이 없다")
    void methodNotAllowedDoesNotEcho() throws Exception {
        String session = server.login();
        String marker = marker();

        HttpResponse<String> response = server.send(HttpRequest.newBuilder(server.url("/api/v1/logout?" + marker)).GET()
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));

        assertThat(response.statusCode()).isEqualTo(405);
        assertNoEcho(response, "/api/v1/logout", marker);
    }

    @Test
    @DisplayName("FR-020: 로그인 상태에서 방화벽이 거절한 경로(세미콜론)의 400 본문에 경로와 표식이 없다")
    void firewallRejectionDoesNotEchoWhenLoggedIn() throws Exception {
        String session = server.login();
        String marker = marker();

        HttpResponse<String> response = server.send(HttpRequest.newBuilder(server.url("/api/v1/x;" + marker)).GET()
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));

        assertThat(response.statusCode()).isEqualTo(400);
        assertNoEcho(response, "/api/v1/x", marker);
    }

    @Test
    @DisplayName("FR-020: 로그인 없이도 방화벽이 거절한 경로(세미콜론)의 400 본문에 경로와 표식이 없다")
    void firewallRejectionDoesNotEchoWithoutLogin() throws Exception {
        String marker = marker();

        HttpResponse<String> response = server.send(HttpRequest.newBuilder(server.url("/api/v1/x;" + marker)).GET());

        assertThat(response.statusCode()).isEqualTo(400);
        assertNoEcho(response, "/api/v1/x", marker);
    }

    @Test
    @DisplayName("FR-020: 로그인 상태의 POST /api/v1/logout 에 깨진 JSON 본문을 보내도 오류 본문에 경로와 표식이 없다(상태 코드는 정하지 않음)")
    void brokenJsonBodyDoesNotEcho() throws Exception {
        String session = server.login();
        String marker = marker();

        HttpResponse<String> response = server.send(HttpRequest.newBuilder(server.url("/api/v1/logout?" + marker))
                .POST(HttpRequest.BodyPublishers.ofString("{\"" + marker + "\": "))
                .header("Content-Type", "application/json")
                .header("Origin", ORIGIN)
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));

        assertNoEcho(response, "/api/v1/logout", marker);
    }

    @Test
    @DisplayName("T057 FR-018 FR-020 실서버: 21자 이름의 PATCH /api/v1/me 는 400 VALIDATION_FAILED, pointer #/displayName, 정한 문구이고 본문에 보낸 이름이 없다")
    void tooLongDisplayNameOnRealServer() throws Exception {
        String session = server.login();
        // 코드 포인트 21개: "긴이름" 3자와 고유한 16진수 18자
        String name = "긴이름" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        assertThat(name.codePointCount(0, name.length())).isEqualTo(21);

        HttpResponse<String> response = patchMe(session, "{\"displayName\":\"" + name + "\"}");

        JsonNode problem = expectProblem(response, 400);
        assertThat(problem.path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.path("errors").path(0).path("pointer").asString()).isEqualTo("#/displayName");
        assertThat(problem.path("errors").path(0).path("detail").asString()).isEqualTo("표시 이름은 1자 이상 20자 이하로 입력해 주세요.");
        assertThat(response.body()).as("오류 본문에 보낸 이름이 없어야 한다").doesNotContain(name);
    }

    @Test
    @DisplayName("T057 FR-017 FR-020 실서버: 알 수 없는 필드(loginEmail)가 있는 PATCH /api/v1/me 는 400 VALIDATION_FAILED, pointer #, 정한 문구이고 본문에 필드 이름과 값이 없다")
    void unknownFieldOnRealServer() throws Exception {
        String session = server.login();

        HttpResponse<String> response = patchMe(session, "{\"displayName\":\"이름\",\"loginEmail\":\"leak@example.com\"}");

        JsonNode problem = expectProblem(response, 400);
        assertThat(problem.path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.path("errors").path(0).path("pointer").asString()).isEqualTo("#");
        assertThat(problem.path("errors").path(0).path("detail").asString()).isEqualTo("알 수 없는 필드가 있습니다.");
        assertThat(response.body()).doesNotContain("leak@example.com").doesNotContain("loginEmail");
    }

    @Test
    @DisplayName("T057 FR-020 계약 Problem.code 필수 실서버: 깨진 JSON의 PATCH /api/v1/me 는 400 VALIDATION_FAILED, pointer #, 읽을 수 없다는 문구이고 본문에 보낸 조각이 없다")
    void brokenJsonPatchMeOnRealServer() throws Exception {
        String session = server.login();
        String marker = marker();

        HttpResponse<String> response = patchMe(session, "{\"displayName\":\"" + marker);

        JsonNode problem = expectProblem(response, 400);
        assertThat(problem.path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.path("detail").asString()).isEqualTo("입력값 하나를 확인해 주세요.");
        assertThat(problem.path("errors").path(0).path("pointer").asString()).isEqualTo("#");
        assertThat(problem.path("errors").path(0).path("detail").asString()).isEqualTo("요청 본문을 읽을 수 없습니다.");
        assertThat(response.body()).doesNotContain(marker).doesNotContain("displayName");
    }

    @Test
    @DisplayName("FR-020 실서버: 로그인 없이 등록하지 않은 제공자(이메일 모양, github)로 로그인을 시작하면 404 problem+json이고 본문과 서버 출력에 보낸 값이 없다")
    void unknownLoginProviderOnRealServer(CapturedOutput output) throws Exception {
        String email = "leak-" + UUID.randomUUID() + "@example.com";
        for (String provider : new String[] {email, "github"}) {
            HttpResponse<String> response = server.send(HttpRequest.newBuilder(server.url("/oauth2/authorization/" + provider)).GET());

            expectProblem(response, 404);
            assertThat(response.body()).as("404 본문이 있어야 비반사 검사가 의미 있다").isNotBlank();
            assertThat(response.body()).as(provider + " 응답 본문").doesNotContain(provider);
        }

        // 대조: 서버 출력을 캡처가 실제로 잡는다(없음 검사가 빈 캡처로 통과하지 않게)
        String marker = marker();
        LoggerFactory.getLogger(ErrorResponseLeakTest.class).warn(marker);
        String all = output.getAll();
        assertThat(all).contains(marker);
        assertThat(all).as("서버 출력").doesNotContain(email).doesNotContain("github");
    }

    @Test
    @DisplayName("FR-018 계약 Problem.code 필수 실서버: 본문이 빈 PATCH /api/v1/me 는 400 VALIDATION_FAILED, pointer #, 읽을 수 없다는 문구다")
    void emptyBodyPatchMeOnRealServer() throws Exception {
        String session = server.login();

        HttpResponse<String> response = patchMe(session, "");

        JsonNode problem = expectProblem(response, 400);
        assertThat(problem.path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.path("detail").asString()).isEqualTo("입력값 하나를 확인해 주세요.");
        assertThat(problem.path("errors").path(0).path("pointer").asString()).isEqualTo("#");
        assertThat(problem.path("errors").path(0).path("detail").asString()).isEqualTo("요청 본문을 읽을 수 없습니다.");
    }

    private HttpResponse<String> patchMe(String session, String body) throws Exception {
        return server.send(HttpRequest.newBuilder(server.url("/api/v1/me"))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .header("Origin", ORIGIN)
                .header("Cookie", LoginClient.SESSION_COOKIE + "=" + session));
    }

    /** 상태 코드와 application/problem+json 을 확인하고 본문을 JSON으로 읽는다. */
    private static JsonNode expectProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("본문: " + response.body()).isEqualTo(status);
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        assertThat(contentType).as("Content-Type이 있어야 한다").isNotNull();
        assertThat(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as("오류 본문은 application/problem+json: " + contentType).isTrue();
        return JSON.readTree(response.body());
    }

    /** 본문에 경로와 표식이 없고, 본문이 있으면 application/problem+json 이다. */
    private static void assertNoEcho(HttpResponse<String> response, String path, String marker) {
        String body = response.body();
        assertThat(body).as("오류 본문에 표식이 없어야 한다").doesNotContain(marker);
        assertThat(body).as("오류 본문에 요청 경로가 없어야 한다").doesNotContain(path);
        if (!body.isEmpty()) {
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            assertThat(contentType).as("본문이 있으면 Content-Type이 있어야 한다").isNotNull();
            assertThat(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .as("오류 본문은 application/problem+json: " + contentType).isTrue();
        }
    }

    private static String marker() {
        return "marker-" + UUID.randomUUID();
    }
}
