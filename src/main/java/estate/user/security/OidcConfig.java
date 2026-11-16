package estate.user.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;

/** 로그인 시작 요청의 인가 요청과 ID 토큰 검증(결정 기록 0013 ID 토큰 확인 표, research R6). */
@Configuration(proxyBeanMethods = false)
public class OidcConfig {

    @Bean
    OAuth2AuthorizationRequestResolver authorizationRequestResolver(ClientRegistrationRepository registrations) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        // Boot 4.1.2 등록 속성에는 PKCE 항목이 없어 해석기에서 켠다. 계정 선택은 일반 로그인과 재인증 모두에 붙인다
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.additionalParameters(params -> params.put("prompt", "select_account"))));
        return resolver;
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(Environment environment) {
        List<String> allowedIssuers = Binder.get(environment)
                .bind("estate.oidc.allowed-issuers", Bindable.listOf(String.class)).get();
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwsAlgorithmResolver(registration -> SignatureAlgorithm.RS256);
        factory.setClaimTypeConverterFactory(registration -> CLAIM_TYPE_CONVERTER);
        factory.setJwtValidatorFactory(registration -> idTokenValidator(registration, allowedIssuers));
        return factory;
    }

    // 기본 변환은 iss를 URL로 바꿔 구글이 쓰는 스킴 없는 값(accounts.google.com)을 거절하므로 iss만 문자열로 둔다
    static final ClaimTypeConverter CLAIM_TYPE_CONVERTER = claimTypeConverter();

    private static ClaimTypeConverter claimTypeConverter() {
        Map<String, Converter<Object, ?>> converters = new HashMap<>(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverters());
        converters.put(IdTokenClaimNames.ISS, value -> value == null ? null : value.toString());
        return new ClaimTypeConverter(converters);
    }

    /**
     * iss는 허용 목록(문자열)으로 확인하고, 나머지 항목(aud, azp, exp, iat, 필수 클레임)은 Spring 기본 검증에 맡긴다.
     * 기본 검증은 iss를 URL로 읽고 등록의 issuer 하나와만 비교하므로, 목록 확인을 통과한 토큰은 iss를 등록 issuer로 바꿔 넘긴다.
     */
    static OAuth2TokenValidator<Jwt> idTokenValidator(ClientRegistration registration, List<String> allowedIssuers) {
        String registered = registration.getProviderDetails().getIssuerUri();
        OAuth2TokenValidator<Jwt> rest = JwtValidators.createDefaultWithValidators(List.of(new OidcIdTokenValidator(registration)));
        return jwt -> {
            if (!allowedIssuers.contains(jwt.getClaimAsString(IdTokenClaimNames.ISS))) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_id_token", "허용하지 않은 iss입니다.", null));
            }
            return rest.validate(Jwt.withTokenValue(jwt.getTokenValue())
                    .headers(headers -> headers.putAll(jwt.getHeaders()))
                    .claims(claims -> {
                        claims.putAll(jwt.getClaims());
                        claims.put(IdTokenClaimNames.ISS, registered);
                    })
                    .build());
        };
    }
}
