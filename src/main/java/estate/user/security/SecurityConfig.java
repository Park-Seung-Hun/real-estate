package estate.user.security;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

import estate.common.web.OriginCheckFilter;
import estate.common.web.ProblemCode;
import estate.common.web.ProblemResponses;
import estate.user.web.LoginFailureHandler;
import estate.user.web.LoginSuccessHandler;

/** 보안 설정(research R4, R6, R9). */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment, ProblemResponses problems,
            LoginCookieAuthorizationRequestRepository authorizationRequests, OAuth2AuthorizationRequestResolver resolver,
            LoginSuccessHandler success, LoginFailureHandler failure,
            Customizer<HeadersConfigurer<HttpSecurity>> securityHeaders) throws Exception {
        List<String> allowedOrigins = Binder.get(environment)
                .bind("estate.web.allowed-origins", Bindable.listOf(String.class)).get();
        http
                // 요청 위조는 Strict 쿠키와 Origin 검사로 막는다(결정 기록 0013)
                .csrf(AbstractHttpConfigurer::disable)
                // 기본 로그아웃은 GET도 받고 로그인 상태가 없어도 성공으로 응답한다. 로그아웃은 컨트롤러가 한다
                .logout(AbstractHttpConfigurer::disable)
                // 401 진입점과 로그인 시작이 세션을 만들지 않게 한다
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .headers(securityHeaders)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, e) -> problems.write(response, ProblemCode.UNAUTHENTICATED)))
                .oauth2Login(login -> login
                        // 로그인 화면은 웹 화면이 그린다. 지정하지 않으면 Spring 기본 로그인 페이지가 /login에 생긴다
                        .loginPage("/")
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestRepository(authorizationRequests)
                                .authorizationRequestResolver(resolver))
                        .authorizedClientRepository(new NotStoredAuthorizedClientRepository())
                        .successHandler(success)
                        .failureHandler(failure)
                        // 설정 마지막 단계가 전역 저장소와 세션 전략으로 덮어쓰므로 그 뒤에 콜백 필터에만 바꾼다(research R4)
                        .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
                            @Override
                            public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
                                filter.setSecurityContextRepository(new NullSecurityContextRepository());
                                filter.setSessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy());
                                return filter;
                            }
                        }))
                // 로그인 상태 확인(401) 뒤에 출처를 본다(research R9)
                .addFilterAfter(new OriginCheckFilter(allowedOrigins, problems), AuthorizationFilter.class);
        return http.build();
    }

    /** 구글 토큰을 보관하지 않는다(결정 기록 0012). 기본 구현은 액세스 토큰을 메모리에 둔다. */
    static final class NotStoredAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
        }

        @Override
        public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
        }
    }
}
