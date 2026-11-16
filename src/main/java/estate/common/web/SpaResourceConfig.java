package estate.common.web;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 화면 경로를 index.html로 돌린다(research R2). 정적 파일이 있으면 그 파일, 없으면 index.html이고,
 * API와 로그인 경로는 index.html로 새지 않게 404로 둔다.
 */
// Boot 기본 정적 파일 설정(@Order(0))보다 먼저 `/**`를 등록해야 Boot가 같은 경로를 다시 등록하지 않는다
@Configuration(proxyBeanMethods = false)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpaResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 캐시를 켜면 아무 경로나 요청할 때마다 캐시 항목이 늘어난다
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(false)
                .addResolver(new SpaResourceResolver());
    }

    static final class SpaResourceResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            if (under(resourcePath, "api") || under(resourcePath, "oauth2") || under(resourcePath, "login/oauth2")) {
                return null;
            }
            Resource resource = super.getResource(resourcePath, location);
            return resource != null ? resource : super.getResource("index.html", location);
        }

        // 경로 경계로 본다: `api`, `api/x`는 해당하고 `apiary`는 아니다
        private static boolean under(String path, String prefix) {
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
    }
}
