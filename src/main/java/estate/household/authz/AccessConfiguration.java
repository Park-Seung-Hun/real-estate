package estate.household.authz;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import estate.household.domain.HouseholdMemberRepository;

/** 권한 확인기와 {@link HouseholdMembership} 인자 해석기를 MVC에 등록한다(research R2, R3). */
@Configuration(proxyBeanMethods = false)
class AccessConfiguration implements WebMvcConfigurer {

    private final HouseholdMemberRepository members;

    AccessConfiguration(HouseholdMemberRepository members) {
        this.members = members;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccessInterceptor(members)).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == HouseholdMembership.class;
            }

            // 멤버십이 없으면(OPTIONAL) 속성이 없어 null
            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return webRequest.getAttribute(AccessInterceptor.MEMBERSHIP_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            }
        });
    }
}
