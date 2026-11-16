package estate.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProblemResponsesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final ProblemResponses responses = new ProblemResponses(MAPPER);

    private JsonNode write(ProblemCode code, List<Problem.FieldError> errors) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        responses.write(response, code, errors);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getStatus()).isEqualTo(code.status().value());
        return MAPPER.readTree(response.getContentAsString());
    }

    private void assertBody(JsonNode body, String slug, String title, int status, String detail, String code) {
        assertThat(body.get("type").asString()).isEqualTo("https://example.com/problems/" + slug);
        assertThat(body.get("title").asString()).isEqualTo(title);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("detail").asString()).isEqualTo(detail);
        assertThat(body.get("code").asString()).isEqualTo(code);
    }

    @Test
    void writesContractWordingForEveryCode() throws Exception {
        JsonNode unauthenticated = write(ProblemCode.UNAUTHENTICATED, null);
        assertBody(unauthenticated, "unauthenticated", "로그인이 필요합니다", 401, "다시 로그인해 주세요.", "UNAUTHENTICATED");
        assertThat(unauthenticated.has("errors")).isFalse();
        assertBody(write(ProblemCode.REAUTH_REQUIRED, null), "reauth-required", "구글 계정 확인이 필요합니다", 401,
                "구글 계정을 한 번 더 확인한 뒤 다시 시도해 주세요.", "REAUTH_REQUIRED");
        assertBody(write(ProblemCode.ORIGIN_REJECTED, null), "origin-rejected", "허용하지 않은 요청입니다", 403,
                "서비스 화면에서 다시 시도해 주세요.", "ORIGIN_REJECTED");

        JsonNode validation = write(ProblemCode.VALIDATION_FAILED,
                List.of(new Problem.FieldError("#/displayName", "표시 이름은 1자 이상 20자 이하로 입력해 주세요.")));
        assertBody(validation, "validation-failed", "입력값이 올바르지 않습니다", 400, "입력값 하나를 확인해 주세요.",
                "VALIDATION_FAILED");
        assertThat(validation.get("errors").get(0).get("pointer").asString()).isEqualTo("#/displayName");
        assertThat(validation.get("errors").get(0).get("detail").asString())
                .isEqualTo("표시 이름은 1자 이상 20자 이하로 입력해 주세요.");
    }

    @Test
    void validationFailedRequiresErrors() {
        assertThatThrownBy(() -> new ProblemException(ProblemCode.VALIDATION_FAILED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> responses.write(new MockHttpServletResponse(), ProblemCode.VALIDATION_FAILED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
