package estate.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import estate.support.GoogleAccount;
import estate.support.LoginClient;
import estate.support.LoginIntegrationTest;
import estate.user.application.UserProfiles;

/**
 * T014 research R1: 사용자 모듈의 공개 조회 {@code UserProfiles.find}가 번호마다 표시 이름(비어 있으면 null), 복호화한 로그인 이메일,
 * 검증 여부를 주고 없는 번호는 결과에 넣지 않는다. 사용자는 가짜 구글 로그인으로 만든다(login_email은 AAD user.login_email:{id}로 암호화됨).
 */
@LoginIntegrationTest
@AutoConfigureMockMvc
class UserProfilesTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    UserProfiles profiles;
    LoginClient login;

    @BeforeEach
    void setUp() {
        login = new LoginClient(mvc);
    }

    @Test
    @DisplayName("T014 research R1: 이름 있는 검증된 계정과 이름 없는 미검증 계정의 표시 이름, 복호화한 이메일, 검증 여부를 번호별로 준다")
    void findReturnsProfilesByUserId() throws Exception {
        GoogleAccount named = account("named").withName("프로필이름");
        GoogleAccount unnamed = account("unnamed").withoutName().withEmailVerified(false);
        long namedId = loginAndId(named);
        long unnamedId = loginAndId(unnamed);

        Map<Long, UserProfiles.Profile> found = profiles.find(List.of(namedId, unnamedId));

        assertThat(found).containsOnlyKeys(namedId, unnamedId);
        assertThat(found.get(namedId)).isEqualTo(new UserProfiles.Profile(namedId, "프로필이름", named.email(), true));
        assertThat(found.get(unnamedId)).as("구글이 이름을 보내지 않으면 displayName은 null")
                .isEqualTo(new UserProfiles.Profile(unnamedId, null, unnamed.email(), false));
    }

    @Test
    @DisplayName("T014 research R1: 없는 번호는 결과에 없고 함께 받은 있는 번호는 결과에 있다")
    void missingUserIdIsLeftOut() throws Exception {
        GoogleAccount account = account("present");
        long id = loginAndId(account);
        long missing = jdbc.sql("select max(id) + 1000 from \"user\"").query(Long.class).single();

        Map<Long, UserProfiles.Profile> found = profiles.find(List.of(id, missing));

        assertThat(found).containsOnlyKeys(id);
        assertThat(found.get(id).loginEmail()).isEqualTo(account.email());
    }

    private long loginAndId(GoogleAccount account) throws Exception {
        login.login(account).requireSessionCookie();
        return jdbc.sql("select id from \"user\" where provider = 'google' and provider_subject = :sub").param("sub", account.sub())
                .query(Long.class).single();
    }

    private static GoogleAccount account(String tag) {
        String id = UUID.randomUUID().toString();
        return GoogleAccount.of("profiles-" + tag + "-" + id, "profiles-" + tag + "-" + id + "@example.com");
    }
}
