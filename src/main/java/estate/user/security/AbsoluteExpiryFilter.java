package estate.user.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.core.Ordered;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로그인한 지 30일이 지난 세션을 지운다(research R8). Spring Session에는 절대 만료가 없다.
 * 세션 필터 뒤, 보안 필터 앞에서 돌아 지운 세션은 로그인 상태가 없는 요청으로 이어진다(`/api/**`는 401, 화면 경로는 그대로).
 * 세션 쿠키가 왔는데 세션이 없으면(유휴 만료, 삭제됨) 응답에서 쿠키를 지운다.
 */
@Component
public class AbsoluteExpiryFilter extends OncePerRequestFilter implements Ordered {

    public static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(30);

    private static final String CALLBACK_PREFIX = "/login/oauth2/code/";

    private final Clock clock;
    private final HttpSessionIdResolver sessionIdResolver;

    AbsoluteExpiryFilter(Clock clock, HttpSessionIdResolver sessionIdResolver) {
        this.clock = clock;
        this.sessionIdResolver = sessionIdResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 콜백은 새 세션 쿠키를 쓰는 유일한 요청이라, 여기서 쿠키 삭제를 쓰면 새 쿠키와 겹쳐 로그인 상태를 잃을 수 있다
        if (!request.getRequestURI().startsWith(request.getContextPath() + CALLBACK_PREFIX)) {
            HttpSession session = request.getSession(false);
            if (session == null && request.getRequestedSessionId() != null) {
                sessionIdResolver.expireSession(request, response);
            }
            // 로그인마다 새 세션을 만들므로 creationTime이 곧 로그인 시각이다
            if (session != null && clock.instant().isAfter(Instant.ofEpochMilli(session.getCreationTime()).plus(ABSOLUTE_LIFETIME))) {
                // Spring Session이 그 행을 지우고 응답에 쿠키 삭제를 쓴다
                session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    public int getOrder() {
        return SessionRepositoryFilter.DEFAULT_ORDER + 1;
    }
}
