package estate.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/** ID 토큰 iss 변환과 허용 목록 검증(결정 기록 0013 ID 토큰 확인 표, OidcConfig). */
class OidcIssuerTest {

    static final List<String> ALLOWED = List.of("https://accounts.google.com", "accounts.google.com");
    static final ClientRegistration GOOGLE = CommonOAuth2Provider.GOOGLE.getBuilder("google")
            .clientId("cid").clientSecret("secret").build();
    static final OAuth2TokenValidator<Jwt> VALIDATOR = OidcConfig.idTokenValidator(GOOGLE, ALLOWED);

    /** 디코더가 서명 확인 뒤 거치는 클레임 변환을 지난 토큰(iat, exp는 초 단위 숫자로 온다). */
    private static Jwt decoded(String iss, String aud) {
        Map<String, Object> raw = new HashMap<>();
        long now = Instant.now().getEpochSecond();
        raw.put("iss", iss);
        raw.put("sub", "sub-1");
        raw.put("aud", List.of(aud));
        raw.put("iat", now - 10);
        raw.put("exp", now + 300);
        Map<String, Object> claims = OidcConfig.CLAIM_TYPE_CONVERTER.convert(raw);
        return Jwt.withTokenValue("token").header("alg", "RS256").claims(c -> c.putAll(claims)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://accounts.google.com", "accounts.google.com"})
    void 구글의_두_iss_형식은_문자열_그대로_두고_받는다(String iss) {
        Jwt jwt = decoded(iss, "cid");

        assertThat(jwt.getClaims().get("iss")).isEqualTo(iss);
        assertThat(VALIDATOR.validate(jwt).hasErrors()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example.com", "evil.example.com", "https://accounts.google.com/"})
    void 허용_목록_밖_iss는_거절한다(String iss) {
        assertThat(VALIDATOR.validate(decoded(iss, "cid")).hasErrors()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"accounts.google.com"})
    void iss를_통과해도_나머지_기본_검증은_그대로다(String iss) {
        assertThat(VALIDATOR.validate(decoded(iss, "other-client")).hasErrors()).isTrue();
    }
}
