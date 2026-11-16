package estate.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;

/**
 * T058 내 정보 API: 조회 세 필드(US4-1, FR-017), 표시 이름 수정(US4-2, FR-018), 이름 없는 첫 로그인(US4-3), 입력 규칙 경계값(US4-4, research R11).
 * 상태를 바꾸는 요청이라 허용 출처 https://localhost 의 Origin을 붙인다. 사용자 행은 Reset이 지우지 않으므로 테스트마다 고유 sub를 쓴다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class MeApiTest {

    static final String ORIGIN = "https://localhost";
    private static final String NAME_RULE = "표시 이름은 1자 이상 20자 이하로 입력해 주세요.";
    private static final String UNKNOWN_FIELD = "알 수 없는 필드가 있습니다.";
    private static final String UNREADABLE_BODY = "요청 본문을 읽을 수 없습니다.";
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    LoginClient login;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    @Test
    @DisplayName("T058 US4-1 FR-017: 로그인 뒤 GET은 200이고 displayName, loginEmail, emailVerified가 구글 계정의 값과 같다")
    void getReturnsThreeFields() throws Exception {
        GoogleAccount account = account("get").withName("엄마");
        Cookie cookie = login.login(account).requireSessionCookie();

        mvc.perform(https(get("/api/v1/me")).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("엄마"))
                .andExpect(jsonPath("$.loginEmail").value(account.email()))
                .andExpect(jsonPath("$.emailVerified").value(true));
    }

    @Test
    @DisplayName("T058 US4-2 FR-018: PATCH로 새 이름을 보내면 200이고 응답과 이어진 GET이 새 이름이며 이메일은 그대로다")
    void patchSavesNewName() throws Exception {
        GoogleAccount account = account("patch");
        Cookie cookie = login.login(account).requireSessionCookie();
        byte[] emailBefore = loginEmailBytes(account.sub());

        sendPatch(cookie, body("새 이름"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("새 이름"))
                .andExpect(jsonPath("$.loginEmail").value(account.email()))
                .andExpect(jsonPath("$.emailVerified").value(true));

        mvc.perform(https(get("/api/v1/me")).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("새 이름"))
                .andExpect(jsonPath("$.loginEmail").value(account.email()));
        assertThat(storedName(account.sub())).isEqualTo("새 이름");
        assertThat(loginEmailBytes(account.sub())).isEqualTo(emailBefore);
    }

    @Test
    @DisplayName("T058 US4-2 FR-018: 앞뒤 공백(U+3000 포함)은 지우고 저장하며 응답도 지운 값이다")
    void patchTrimsSurroundingWhitespace() throws Exception {
        GoogleAccount account = account("trim");
        Cookie cookie = login.login(account).requireSessionCookie();

        sendPatch(cookie, body("  아빠  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("아빠"));
        assertThat(storedName(account.sub())).isEqualTo("아빠");

        sendPatch(cookie, body("\u3000엄마\u3000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("엄마"));
        assertThat(storedName(account.sub())).isEqualTo("엄마");
    }

    @Test
    @DisplayName("T058 US4-3: 구글이 이름을 보내지 않은 첫 로그인은 displayName이 null이고, PATCH로 저장하면 GET에 나온다")
    void noNameFromGoogleThenSaveByHand() throws Exception {
        GoogleAccount account = account("noname").withoutName();
        Cookie cookie = login.login(account).requireSessionCookie();

        mvc.perform(https(get("/api/v1/me")).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasKey("displayName")))
                .andExpect(jsonPath("$.displayName", nullValue()));
        assertThat(storedName(account.sub())).isNull();

        sendPatch(cookie, body("직접 쓴 이름")).andExpect(status().isOk());

        mvc.perform(https(get("/api/v1/me")).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("직접 쓴 이름"));
    }

    static Stream<Arguments> rejectedNames() {
        return Stream.of(
                Arguments.of("빈 문자열", ""),
                Arguments.of("공백만", "   "),
                Arguments.of("한글 21자", "가".repeat(21)),
                Arguments.of("이모지 21 코드 포인트(length 42)", "😀".repeat(21)),
                Arguments.of("U+0007", "이\u0007름"),
                Arguments.of("U+2028", "이\u2028름"),
                Arguments.of("U+2029", "이\u2029름"),
                Arguments.of("U+202E", "이\u202E름"),
                Arguments.of("U+2066", "이\u2066름"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("rejectedNames")
    @DisplayName("T058 US4-4 FR-018: 규칙에 어긋난 이름은 400 VALIDATION_FAILED, pointer #/displayName이고 저장값은 그대로이며 본문에 보낸 이름이 없다")
    void invalidNameIsRejected(String label, String name) throws Exception {
        Saved saved = loginWithName("invalid");

        ResultActions result = sendPatch(saved.cookie(), body(name));

        expectValidationFailed(result, "#/displayName", NAME_RULE);
        // 빈 문자열과 공백만은 본문에 그대로 있어도 의미가 없어 포함 검사를 하지 않는다
        if (!name.isBlank()) {
            assertThat(responseBody(result)).doesNotContain(name);
        }
        assertThat(storedName(saved.account().sub())).isEqualTo(saved.name());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("missingNameBodies")
    @DisplayName("T058 US4-4 FR-018: displayName 키가 없거나 null이거나 JSON 문자열이 아니면 400 VALIDATION_FAILED, pointer #/displayName이고 저장값은 그대로다")
    void missingOrNullNameIsRejected(String label, String json) throws Exception {
        Saved saved = loginWithName("missing");

        expectValidationFailed(sendPatch(saved.cookie(), json), "#/displayName", NAME_RULE);
        assertThat(storedName(saved.account().sub())).isEqualTo(saved.name());
    }

    static Stream<Arguments> missingNameBodies() {
        return Stream.of(
                Arguments.of("displayName 키 없음", "{}"),
                Arguments.of("displayName null", "{\"displayName\":null}"),
                Arguments.of("숫자 123", "{\"displayName\":123}"),
                Arguments.of("true", "{\"displayName\":true}"),
                Arguments.of("배열", "{\"displayName\":[\"a\"]}"),
                Arguments.of("객체", "{\"displayName\":{\"a\":1}}"));
    }

    @Test
    @DisplayName("T058 US4-4 FR-018: 한글 20자와 20 코드 포인트 이모지(length 40)는 200이고 그대로 저장된다")
    void twentyCodePointsAreAccepted() throws Exception {
        GoogleAccount account = account("twenty");
        Cookie cookie = login.login(account).requireSessionCookie();

        String korean = "가".repeat(20);
        sendPatch(cookie, body(korean)).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value(korean));
        assertThat(storedName(account.sub())).isEqualTo(korean);

        String emoji = "😀".repeat(20);
        assertThat(emoji.length()).isEqualTo(40);
        sendPatch(cookie, body(emoji)).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value(emoji));
        assertThat(storedName(account.sub())).isEqualTo(emoji);
    }

    @Test
    @DisplayName("T058 US4-4 FR-018: 앞뒤 공백을 지우면 20자가 되는 22자 입력은 200이다")
    void twentyTwoWithPaddingIsAccepted() throws Exception {
        GoogleAccount account = account("padded");
        Cookie cookie = login.login(account).requireSessionCookie();

        String core = "나".repeat(20);
        sendPatch(cookie, body(" " + core + "\u3000")).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value(core));
        assertThat(storedName(account.sub())).isEqualTo(core);
    }

    @Test
    @DisplayName("T058 US4-4 FR-018: 최소 길이 경계 한글 1자와 이모지 1 코드 포인트, 앞뒤 공백을 지우면 1자가 되는 입력은 200이고 지운 값으로 저장된다")
    void oneCodePointIsAccepted() throws Exception {
        GoogleAccount account = account("one");
        Cookie cookie = login.login(account).requireSessionCookie();

        for (String[] c : new String[][] {{"가", "가"}, {"😀", "😀"}, {" 가 ", "가"}}) {
            sendPatch(cookie, body(c[0])).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value(c[1]));
            assertThat(storedName(account.sub())).isEqualTo(c[1]);
        }
    }

    @Test
    @DisplayName("T058 FR-018 계약 Problem.code 필수: 깨진 JSON 본문은 400 VALIDATION_FAILED, pointer #, 읽을 수 없다는 문구이고 보낸 조각이 본문에 없고 저장값은 그대로다")
    void malformedJsonIsValidationFailed() throws Exception {
        Saved saved = loginWithName("malformed");

        ResultActions result = sendPatch(saved.cookie(), "{\"displayName\":");

        expectValidationFailed(result, "#", UNREADABLE_BODY);
        assertThat(responseBody(result)).doesNotContain("displayName");
        assertThat(storedName(saved.account().sub())).isEqualTo(saved.name());
    }

    @Test
    @DisplayName("T058 FR-018 계약 Problem.code 필수: 본문이 빈 PATCH(Content-Type JSON)는 400 VALIDATION_FAILED, pointer #이고 저장값은 그대로다")
    void emptyBodyIsValidationFailed() throws Exception {
        Saved saved = loginWithName("empty-body");

        sendPatch(saved.cookie(), "")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#"));
        assertThat(storedName(saved.account().sub())).isEqualTo(saved.name());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unknownFieldBodies")
    @DisplayName("T058 US4-4 FR-018: 알 수 없는 필드가 있으면 올바른 이름과 함께여도 400 VALIDATION_FAILED, pointer #이고 아무것도 바뀌지 않으며 본문에 필드 이름과 이름 값이 없다")
    void unknownFieldIsRejected(String label, String json, String fieldName) throws Exception {
        Saved saved = loginWithName("unknown");
        byte[] emailBefore = loginEmailBytes(saved.account().sub());

        ResultActions result = sendPatch(saved.cookie(), json);

        expectValidationFailed(result, "#", UNKNOWN_FIELD);
        String responseBody = responseBody(result);
        assertThat(responseBody).doesNotContain(fieldName).doesNotContain("바꾼이름");
        assertThat(storedName(saved.account().sub())).isEqualTo(saved.name());
        assertThat(loginEmailBytes(saved.account().sub())).isEqualTo(emailBefore);
    }

    static Stream<Arguments> unknownFieldBodies() {
        return Stream.of(
                Arguments.of("foo", "{\"displayName\":\"바꾼이름\",\"foo\":1}", "foo"),
                Arguments.of("loginEmail", "{\"displayName\":\"바꾼이름\",\"loginEmail\":\"x@example.com\"}", "loginEmail"),
                Arguments.of("emailVerified", "{\"displayName\":\"바꾼이름\",\"emailVerified\":false}", "emailVerified"));
    }

    @Test
    @DisplayName("T058 FR-017 FR-018: 세션 없는 GET과 PATCH는 401 UNAUTHENTICATED이고, 틀린 본문을 보내도 401이 먼저다")
    void withoutSessionIs401() throws Exception {
        mvc.perform(https(get("/api/v1/me")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        for (String json : new String[] {body("이름"), "{}"}) {
            mvc.perform(https(patch("/api/v1/me")).header(HttpHeaders.ORIGIN, ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(json))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    private record Saved(GoogleAccount account, Cookie cookie, String name) {
    }

    /** 구글 이름 "원래 이름"으로 로그인해 둔 사용자(거절 테스트가 저장값이 안 바뀐 것을 비교한다). */
    private Saved loginWithName(String tag) throws Exception {
        GoogleAccount account = account(tag).withName("원래 이름");
        Cookie cookie = login.login(account).requireSessionCookie();
        assertThat(storedName(account.sub())).isEqualTo("원래 이름");
        return new Saved(account, cookie, "원래 이름");
    }

    private ResultActions sendPatch(Cookie cookie, String json) throws Exception {
        return mvc.perform(https(patch("/api/v1/me")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(json));
    }

    private static void expectValidationFailed(ResultActions result, String pointer, String errorDetail) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value(pointer))
                .andExpect(jsonPath("$.detail").value("입력값 하나를 확인해 주세요."))
                .andExpect(jsonPath("$.errors[0].detail").value(errorDetail));
    }

    private static String responseBody(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String body(String displayName) {
        return JSON.writeValueAsString(Map.of("displayName", displayName));
    }

    private String storedName(String sub) {
        // display_name은 null일 수 있어 single()이 null 값을 거부하므로 배열로 감싸 읽는다
        return jdbc.sql("select display_name from \"user\" where provider = 'google' and provider_subject = :sub")
                .param("sub", sub).query((rs, i) -> new String[] {rs.getString(1)}).single()[0];
    }

    private byte[] loginEmailBytes(String sub) {
        return jdbc.sql("select login_email from \"user\" where provider = 'google' and provider_subject = :sub")
                .param("sub", sub).query(byte[].class).single();
    }

    private static GoogleAccount account(String tag) {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("me-api-" + tag + "-" + id, "me-api-" + tag + "-" + id + "@example.com");
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
