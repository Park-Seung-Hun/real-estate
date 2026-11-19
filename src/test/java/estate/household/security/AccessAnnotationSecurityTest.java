package estate.household.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import estate.household.authz.Access;
import estate.support.LoginIntegrationTest;

/**
 * T008 B11: 모든 /api/** 핸들러에 {@code @Access}가 있고, specs/*&#47;contracts/openapi.yaml의 x-permission이 표시의 권한 키와 같다
 * (research R2, FR-025, FR-026). 경로 변수 이름은 비교하지 않는다({@code {userId}}와 {@code {id}}를 같은 자리로 본다).
 */
@LoginIntegrationTest
class AccessAnnotationSecurityTest {

    static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /** 계약 연산 하나. extensions는 x-로 시작하는 필드. */
    record Operation(Path contract, String method, String path, Map<String, Object> extensions) {
        String key() {
            return method + " " + path;
        }
    }

    @Autowired
    List<RequestMappingHandlerMapping> mappings;

    @Test
    @DisplayName("T008 B11 research R2 결정 기록 0011 기본 거부: 모든 /api/** 핸들러에 @Access가 있다(GET /api/v1/me 등 001 핸들러가 검사 대상에 들어 있음)")
    void everyApiHandlerHasAccess() {
        Map<String, HandlerMethod> handlers = apiHandlers();
        assertThat(handlers.keySet()).as("검사 대상이 비지 않았다").contains("GET /api/v1/me", "DELETE /api/v1/me", "POST /api/v1/logout");

        List<String> unmarked = handlers.entrySet().stream()
                .filter(e -> e.getValue().getMethodAnnotation(Access.class) == null).map(Map.Entry::getKey).toList();
        assertThat(unmarked).as("@Access가 없는 /api/** 핸들러").isEmpty();
    }

    @Test
    @DisplayName("T008 B11 research R2 FR-025 FR-026: 계약의 /api/ 경로와 메서드마다 핸들러가 있다")
    void everyContractOperationHasHandler() throws IOException {
        List<String> missing = contractOperations().stream().filter(op -> handlerFor(op).isEmpty()).map(Operation::key).toList();
        assertThat(missing).as("핸들러가 없는 계약 연산").isEmpty();
    }

    @Test
    @DisplayName("T008 B11 research R2 FR-025 FR-026: 핸들러가 있는 계약 연산마다 x-permission이 @Access 권한 키와 같다(none은 NONE)")
    void contractPermissionMatchesAccess() throws IOException {
        Map<String, String> mismatches = new TreeMap<>();
        Set<String> compared = new TreeSet<>();
        for (Operation op : contractOperations()) {
            Optional<HandlerMethod> handler = handlerFor(op);
            if (handler.isEmpty()) {
                // 핸들러가 아직 없는 연산은 everyContractOperationHasHandler가 맡는다
                continue;
            }
            compared.add(op.key());
            Access access = handler.get().getMethodAnnotation(Access.class);
            String actual = access == null ? "@Access 없음" : access.value().key();
            Object expected = op.extensions().get("x-permission");
            if (!actual.equals(expected)) {
                mismatches.put(op.contract() + " " + op.key(), "계약 " + expected + ", 표시 " + actual);
            }
        }
        assertThat(compared).as("001 계약의 연산은 핸들러가 있어 비교 대상이다")
                .contains("get /api/v1/me", "patch /api/v1/me", "delete /api/v1/me", "post /api/v1/logout");
        assertThat(mismatches).as("x-permission과 @Access 권한 키가 다른 연산").isEmpty();
    }

    @Test
    @DisplayName("T008 B11 research R2: 두 계약에 같은 경로와 메서드(DELETE /api/v1/me)가 있으면 x- 필드 값이 서로 같다")
    void sameOperationInTwoContractsAgrees() throws IOException {
        Map<String, List<Operation>> byKey = new TreeMap<>();
        for (Operation op : contractOperations()) {
            byKey.computeIfAbsent(op.key(), k -> new ArrayList<>()).add(op);
        }
        Map<String, List<Map<String, Object>>> duplicated = new TreeMap<>();
        byKey.forEach((key, ops) -> {
            if (ops.size() > 1) {
                duplicated.put(key, ops.stream().map(Operation::extensions).toList());
            }
        });
        assertThat(duplicated).as("두 계약에 함께 있는 연산").containsKey("delete /api/v1/me");

        duplicated.forEach((key, values) -> assertThat(values).as(key + "의 x- 필드").allSatisfy(v -> assertThat(v).isEqualTo(values.get(0))));
    }

    /** "GET /api/v1/me" 같은 키로 /api/ 핸들러를 모은다. 메서드 조건이 없는 핸들러는 "* 경로". */
    private Map<String, HandlerMethod> apiHandlers() {
        Map<String, HandlerMethod> handlers = new TreeMap<>();
        for (RequestMappingHandlerMapping mapping : mappings) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
                Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
                for (String pattern : e.getKey().getPatternValues()) {
                    if (!pattern.startsWith("/api/")) {
                        continue;
                    }
                    if (methods.isEmpty()) {
                        handlers.put("* " + pattern, e.getValue());
                    }
                    methods.forEach(m -> handlers.put(m.name() + " " + pattern, e.getValue()));
                }
            }
        }
        return handlers;
    }

    private Optional<HandlerMethod> handlerFor(Operation op) {
        String path = normalize(op.path());
        return apiHandlers().entrySet().stream().filter(e -> {
            String[] parts = e.getKey().split(" ", 2);
            return normalize(parts[1]).equals(path) && (parts[0].equals("*") || parts[0].equalsIgnoreCase(op.method()));
        }).map(Map.Entry::getValue).findFirst();
    }

    private static String normalize(String path) {
        return path.replaceAll("\\{[^}]*}", "{}");
    }

    /** specs/*&#47;contracts/openapi.yaml의 /api/ 연산. 테스트는 프로젝트 폴더에서 돈다(Gradle 기본 작업 폴더). */
    @SuppressWarnings("unchecked")
    private static List<Operation> contractOperations() throws IOException {
        List<Path> contracts = new ArrayList<>();
        try (DirectoryStream<Path> features = Files.newDirectoryStream(Path.of("specs"), Files::isDirectory)) {
            for (Path feature : features) {
                Path contract = feature.resolve("contracts/openapi.yaml");
                if (Files.isRegularFile(contract)) {
                    contracts.add(contract);
                }
            }
        }
        assertThat(contracts).as("001과 002 계약을 읽었다")
                .contains(Path.of("specs/001-login/contracts/openapi.yaml"), Path.of("specs/002-household-sharing/contracts/openapi.yaml"));

        List<Operation> operations = new ArrayList<>();
        for (Path contract : contracts) {
            Map<String, Object> root;
            try (Reader reader = Files.newBufferedReader(contract)) {
                root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            }
            Map<String, Object> paths = (Map<String, Object>) root.get("paths");
            paths.forEach((path, item) -> {
                if (!path.startsWith("/api/")) {
                    return;
                }
                ((Map<String, Object>) item).forEach((method, op) -> {
                    if (!HTTP_METHODS.contains(method)) {
                        return;
                    }
                    Map<String, Object> extensions = new LinkedHashMap<>();
                    ((Map<String, Object>) op).forEach((k, v) -> {
                        if (k.startsWith("x-")) {
                            extensions.put(k, v);
                        }
                    });
                    operations.add(new Operation(contract, method, path, extensions));
                });
            });
        }
        return operations;
    }
}
