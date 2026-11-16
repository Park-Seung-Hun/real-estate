package estate.user.session;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.session.web.http.CookieSerializer.CookieValue;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;

/**
 * 받은 세션 쿠키 값을 해시해 세션 ID로 쓰고, 쿠키에는 같은 요청에서 만든 원문만 쓴다(research R3).
 * 그래서 Spring Session과 데이터베이스는 해시만 본다.
 */
final class HashedCookieSessionIdResolver implements HttpSessionIdResolver {

    static final String COOKIE_NAME = "__Host-Http-session";
    static final int MAX_AGE_SECONDS = 30 * 24 * 60 * 60;

    private final DefaultCookieSerializer cookies = new DefaultCookieSerializer();

    HashedCookieSessionIdResolver() {
        cookies.setCookieName(COOKIE_NAME);
        cookies.setUseBase64Encoding(false);
        cookies.setUseHttpOnlyCookie(true);
        // 프록시 뒤에서 request.isSecure()가 거짓이어도 Secure를 붙인다
        cookies.setUseSecureCookie(true);
        cookies.setSameSite("Strict");
        cookies.setCookiePath("/");
        // 쿠키는 로그인과 재인증 때만 쓰므로 30일이 곧 절대 만료다
        cookies.setCookieMaxAge(MAX_AGE_SECONDS);
    }

    @Override
    public List<String> resolveSessionIds(HttpServletRequest request) {
        return cookies.readCookieValues(request).stream().map(HashedSessionIdGenerator::hash).toList();
    }

    @Override
    public void setSessionId(HttpServletRequest request, HttpServletResponse response, String sessionId) {
        if (!(request.getAttribute(HashedSessionIdGenerator.RAW_TOKEN_ATTRIBUTE) instanceof String raw)
                || !HashedSessionIdGenerator.hash(raw).equals(sessionId)) {
            return;
        }
        // 한 요청에서 두 번 불려도 Set-Cookie는 한 줄만 나가게 원문을 바로 지운다
        request.removeAttribute(HashedSessionIdGenerator.RAW_TOKEN_ATTRIBUTE);
        cookies.writeCookieValue(new CookieValue(request, response, raw));
    }

    @Override
    public void expireSession(HttpServletRequest request, HttpServletResponse response) {
        // 빈 값이면 DefaultCookieSerializer가 Max-Age=0으로 쓴다
        cookies.writeCookieValue(new CookieValue(request, response, ""));
    }
}
