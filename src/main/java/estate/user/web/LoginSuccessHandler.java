package estate.user.web;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import estate.user.application.LoginService;
import estate.user.security.LoginCookieAuthorizationRequestRepository;
import estate.user.security.LoginCookieAuthorizationRequestRepository.LoginState;
import estate.user.security.SessionLoginInfo;

/**
 * 콜백 성공(research R4, R5 일반 로그인). 사용자 처리, 새 세션 저장, 옛 세션 삭제를 한 트랜잭션으로 커밋한 뒤에만 쿠키를 쓴다.
 * 실패하거나 롤백하면 쿠키 없이 실패 화면으로 보낸다.
 */
@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginSuccessHandler.class);

    private final LoginService loginService;
    private final JdbcIndexedSessionRepository sessions;
    private final HttpSessionIdResolver sessionIdResolver;
    private final TransactionTemplate transaction;
    private final LoginFailureHandler failure;
    private final Clock clock;

    LoginSuccessHandler(LoginService loginService, JdbcIndexedSessionRepository sessions,
            HttpSessionIdResolver sessionIdResolver, PlatformTransactionManager transactionManager,
            LoginFailureHandler failure, Clock clock) {
        this.loginService = loginService;
        this.sessions = sessions;
        this.sessionIdResolver = sessionIdResolver;
        this.transaction = new TransactionTemplate(transactionManager);
        this.failure = failure;
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OidcUser google = (OidcUser) authentication.getPrincipal();
        LoginState state = LoginCookieAuthorizationRequestRepository.loginState(request);
        String sessionId;
        try {
            sessionId = commit(google, state);
        } catch (DataIntegrityViolationException firstLoginRace) {
            // 처음 로그인이 동시에 둘이면 PostgreSQL이 트랜잭션을 중단하므로 전체를 새 트랜잭션으로 한 번 더 한다(research R5)
            try {
                sessionId = commit(google, state);
            } catch (RuntimeException e) {
                failed(request, response, e);
                return;
            }
        } catch (RuntimeException e) {
            failed(request, response, e);
            return;
        }
        sessionIdResolver.setSessionId(request, response, sessionId);
        response.sendRedirect(state.returnTo());
    }

    private String commit(OidcUser google, LoginState state) {
        return transaction.execute(status -> {
            long userId = loginService.login(google.getSubject(), google.getEmail(), google.getEmailVerified(),
                    google.getFullName());
            String sessionId = saveNewSession(sessions, new SessionLoginInfo(userId, clock.instant()));
            if (state.oldSessionId() != null) {
                // 일반 로그인은 지운 행이 없어도 성공이다
                sessions.deleteById(state.oldSessionId());
            }
            return sessionId;
        });
    }

    // JdbcSession은 공개 타입이 아니라 Session으로 다룬다
    private static <S extends Session> String saveNewSession(SessionRepository<S> repository, SessionLoginInfo login) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(login));
        repository.save(session);
        return session.getId();
    }

    private void failed(HttpServletRequest request, HttpServletResponse response, RuntimeException e) throws IOException {
        // 예외 메시지에는 sub나 SQL 값이 들어갈 수 있어 종류만 남긴다
        log.warn("로그인 콜백 처리에 실패했습니다: {}", e.getClass().getName());
        failure.fail(request, response);
    }
}
