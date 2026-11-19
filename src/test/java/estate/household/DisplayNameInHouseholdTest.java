package estate.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import estate.support.GoogleAccount;
import estate.support.Households;
import estate.support.Households.Person;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.support.SessionRows;

/**
 * T076 US5: 표시 이름 수정(PATCH /api/v1/me)과 함께 보는 사람 목록, 초대 확인의 연결(US5-2~US5-4, FR-025~FR-027, research R8).
 * 이름 규칙의 경계는 001의 T058이 이미 보므로 여기서는 가구 안에서 보이는 결과와 다른 구성원이 바뀌지 않음에 집중한다.
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class DisplayNameInHouseholdTest {

    private static final String ORIGIN = "https://localhost";
    private static final String ME = "/api/v1/me";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionRows sessions;

    LoginClient login;
    Households households;

    /** 가구장(owner)과 구성원(member). */
    Person owner;
    Person member;

    @BeforeEach
    void setUp() throws Exception {
        login = new LoginClient(mvc);
        households = new Households(mvc, jdbc, sessions);
        owner = loginAs("가구장");
        households.createHousehold(owner);
        member = loginAs("구성원");
        households.accept(member, households.invite(owner, 3));
    }

    @Test
    @DisplayName("T076 US5-2 FR-027: member가 이름을 바꾸면 200이고 본인과 owner의 함께 보는 사람 목록에 새 이름이 보이며 owner의 이름은 그대로다")
    void memberRenameShowsInBothLists() throws Exception {
        sendPatch(member, body("새 이름")).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("새 이름"));

        assertThat(listNames(member)).containsExactly("가구장", "새 이름");
        assertThat(listNames(owner)).containsExactly("가구장", "새 이름");
        assertThat(storedName(member)).isEqualTo("새 이름");
        assertThat(storedName(owner)).isEqualTo("가구장");
    }

    @Test
    @DisplayName("T076 US5-2 FR-027: owner가 이름을 바꿔도 양쪽 목록에 새 이름이 보이고 member의 이름은 그대로다")
    void ownerRenameShowsInBothLists() throws Exception {
        sendPatch(owner, body("새 가구장")).andExpect(status().isOk());

        assertThat(listNames(owner)).containsExactly("새 가구장", "구성원");
        assertThat(listNames(member)).containsExactly("새 가구장", "구성원");
    }

    @Test
    @DisplayName("T076 US5-3 FR-027: 20자 이름은 200이고 목록에 그대로 보인다(21자와 짝인 경계)")
    void twentyCharsAreAccepted() throws Exception {
        String twenty = "가".repeat(20);

        sendPatch(member, body(twenty)).andExpect(status().isOk());

        assertThat(listNames(member)).containsExactly("가구장", twenty);
        assertThat(listNames(owner)).containsExactly("가구장", twenty);
    }

    @ParameterizedTest(name = "[{index}] 길이 {0}")
    @ValueSource(strings = {"", "   ", "가가가가가가가가가가가가가가가가가가가가가"})
    @DisplayName("T076 US5-3 FR-027: 빈 이름, 공백만, 21자 이름은 400 VALIDATION_FAILED, pointer #/displayName이고 양쪽 목록의 이름이 이전 그대로다")
    void invalidNameLeavesListsUnchanged(String name) throws Exception {
        sendPatch(member, body(name))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/displayName"));

        assertThat(listNames(member)).containsExactly("가구장", "구성원");
        assertThat(listNames(owner)).containsExactly("가구장", "구성원");
        assertThat(storedName(member)).isEqualTo("구성원");
    }

    @Test
    @DisplayName("T076 US5-4 FR-027: 본문에 userId나 다른 키를 넣으면 올바른 이름과 함께여도 400이고 다른 구성원은 물론 본인의 이름도 바뀌지 않는다")
    void extraKeysAreRejectedAndNobodyChanges() throws Exception {
        for (String json : List.of(
                "{\"displayName\":\"바꾼이름\",\"userId\":" + owner.userId() + "}",
                "{\"displayName\":\"바꾼이름\",\"userId\":" + member.userId() + "}",
                "{\"displayName\":\"바꾼이름\",\"targetUserId\":\"" + owner.userId() + "\"}",
                "{\"displayName\":\"바꾼이름\",\"members\":[{\"userId\":" + owner.userId() + ",\"displayName\":\"x\"}]}")) {
            sendPatch(member, json)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].pointer").value("#"));

            assertThat(storedName(owner)).as(json).isEqualTo("가구장");
            assertThat(storedName(member)).as(json).isEqualTo("구성원");
            assertThat(listNames(owner)).containsExactly("가구장", "구성원");
            assertThat(listNames(member)).containsExactly("가구장", "구성원");
        }
    }

    @Test
    @DisplayName("T076 US5-4 FR-027: 두 계약을 읽으면 표시 이름을 바꾸는 연산은 PATCH /api/v1/me 하나뿐이고 경로 변수가 없으며 본문은 알 수 없는 키를 막는다")
    @SuppressWarnings("unchecked")
    void contractsHaveOnlyOneRenameOperation() throws IOException {
        List<String> renames = new ArrayList<>();
        List<String> updates = new ArrayList<>();
        for (String feature : List.of("001-login", "002-household-sharing")) {
            Map<String, Object> root;
            try (Reader reader = Files.newBufferedReader(Path.of("specs", feature, "contracts/openapi.yaml"))) {
                root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            }
            Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) root.get("components")).get("schemas");
            ((Map<String, Object>) root.get("paths")).forEach((path, item) -> ((Map<String, Object>) item).forEach((method, op) -> {
                if (method.equals("patch") || method.equals("put")) {
                    updates.add(method + " " + path);
                }
                Object body = ((Map<String, Object>) op).get("requestBody");
                if (body == null) {
                    return;
                }
                Map<String, Object> media = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) body).get("content"))
                        .get("application/json");
                String ref = (String) ((Map<String, Object>) media.get("schema")).get("$ref");
                Map<String, Object> schema = (Map<String, Object>) schemas.get(ref.substring(ref.lastIndexOf('/') + 1));
                if (((Map<String, Object>) schema.get("properties")).containsKey("displayName")) {
                    renames.add(method + " " + path);
                    assertThat(schema.get("additionalProperties")).as(ref).isEqualTo(false);
                }
            }));
        }

        assertThat(renames).as("displayName을 받는 요청").containsExactly("patch /api/v1/me");
        assertThat(updates).as("수정(PATCH, PUT) 연산 전부").containsExactly("patch /api/v1/me");
    }

    @Test
    @DisplayName("T076 FR-025 FR-026 profile:write OPTIONAL: owner, member, 멤버십이 없는 사람 모두 이름을 수정할 수 있다")
    void everyoneCanRename() throws Exception {
        Person loner = loginAs("혼자");

        sendPatch(owner, body("가구장2")).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("가구장2"));
        sendPatch(member, body("구성원2")).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("구성원2"));
        sendPatch(loner, body("혼자2")).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("혼자2"));

        assertThat(storedName(owner)).isEqualTo("가구장2");
        assertThat(storedName(member)).isEqualTo("구성원2");
        assertThat(storedName(loner)).isEqualTo("혼자2");
    }

    @Test
    @DisplayName("T076 R8 FR-012: 구성원이 owner 한 명인 가구에서 owner가 이름을 바꾸면 가구 없는 사람의 초대 확인 inviterDisplayName이 새 이름이다")
    void inviterNameInPreviewFollowsRename() throws Exception {
        Person soloOwner = loginAs("옛 이름");
        households.createHousehold(soloOwner);
        String token = households.invite(soloOwner, 3);
        Person loner = loginAs("혼자");
        assertThat(previewName(loner, token)).isEqualTo("옛 이름");

        sendPatch(soloOwner, body("새 이름")).andExpect(status().isOk());

        assertThat(previewName(loner, token)).isEqualTo("새 이름");
    }

    private String previewName(Person person, String token) throws Exception {
        String text = mvc.perform(https(post("/api/v1/invitations/preview")).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\": \"" + token + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(text).path("inviterDisplayName").stringValue();
    }

    /** 함께 보는 사람 목록(GET /api/v1/household)의 표시 이름, owner와 member 순서. */
    private List<String> listNames(Person person) throws Exception {
        String text = mvc.perform(https(get("/api/v1/household")).cookie(person.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> names = new ArrayList<>();
        for (JsonNode m : JSON.readTree(text).path("members")) {
            names.add(m.path("displayName").stringValue());
        }
        return names;
    }

    private String storedName(Person person) {
        return jdbc.sql("select display_name from \"user\" where id = :u").param("u", person.userId()).query(String.class).single();
    }

    private ResultActions sendPatch(Person person, String json) throws Exception {
        return mvc.perform(https(patch(ME)).header(HttpHeaders.ORIGIN, ORIGIN).cookie(person.cookie())
                .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(json));
    }

    private static String body(String displayName) {
        return JSON.writeValueAsString(Map.of("displayName", displayName));
    }

    private Person loginAs(String name) throws Exception {
        String id = UUID.randomUUID().toString();
        Cookie cookie = login.login(GoogleAccount.of("display-name-" + id, "display-name-" + id + "@example.com").withName(name))
                .requireSessionCookie();
        return new Person(cookie, Long.parseLong(sessions.rowOf(SessionRows.idOfCookie(cookie.getValue())).principalName()));
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true).with(request -> {
            request.setScheme("https");
            request.setServerPort(443);
            return request;
        });
    }
}
