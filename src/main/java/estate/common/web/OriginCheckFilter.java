package estate.common.web;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 상태를 바꾸는 요청의 Origin(없으면 Referer의 출처)이 허용 출처인지 본다(결정 기록 0013, research R9).
 * 보안 설정이 로그인 상태 확인 뒤에 둔다. 빈으로 두지 않아 서블릿 필터로 따로 등록되지 않는다.
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final List<String> allowedOrigins;
    private final ProblemResponses problems;

    public OriginCheckFilter(List<String> allowedOrigins, ProblemResponses problems) {
        this.allowedOrigins = allowedOrigins;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (STATE_CHANGING.contains(request.getMethod())) {
            String origin = originOf(request);
            if (origin == null || !allowedOrigins.contains(origin)) {
                problems.write(response, ProblemCode.ORIGIN_REJECTED);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static String originOf(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null) {
            return origin;
        }
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null) {
            return null;
        }
        try {
            URI uri = URI.create(referer);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
