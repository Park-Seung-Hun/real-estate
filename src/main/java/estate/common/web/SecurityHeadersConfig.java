package estate.common.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;

/** 응답 보안 헤더(research R15). 보안 설정이 이 Customizer를 받아 붙인다. */
@Configuration(proxyBeanMethods = false)
public class SecurityHeadersConfig {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
            + " connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";

    @Bean
    Customizer<HeadersConfigurer<HttpSecurity>> securityHeaders() {
        // HSTS는 Spring Security가 HTTPS 요청에만 붙인다
        return headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000));
    }
}
