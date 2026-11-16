package estate.user.web;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import estate.user.application.LoginService.AccountMatch;
import estate.user.security.AbsoluteExpiryFilter;
import estate.user.security.LoginCookieAuthorizationRequestRepository;
import estate.user.security.LoginCookieAuthorizationRequestRepository.LoginState;
import estate.user.security.SessionLoginInfo;

/**
 * 콜백 성공(research R4, R5). 사용자 처리, 새 세션 저장, 옛 세션 삭제를 한 트랜잭션으로 커밋한 뒤에만 쿠키를 쓴다.
 * 실패하거나 롤백하면 쿠키 없이 실패 화면으로 보낸다. 재인증(`LoginState.reauth()`)은 사용자를 만들지 않고 옛 세션 주인과 비교한다.
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
    private final JdbcClient jdbc;

    LoginSuccessHandler(LoginService loginService, JdbcIndexedSessionRepository sessions,
            HttpSessionIdResolver sessionIdResolver, PlatformTransactionManager transactionManager,
            LoginFailureHandler failure, Clock clock, JdbcClient jdbc) {
        this.jdbc = jdbc;
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
        if (state.reauth()) {
            reauth(request, response, google, state);
            return;
        }
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

    private void reauth(HttpServletRequest request, HttpServletResponse response, OidcUser google, LoginState state)
            throws IOException {
        String sessionId;
        try {
            sessionId = transaction.execute(status -> reauthCommit(google, state.oldSessionId()));
        } catch (ReauthRejected rejected) {
            if (!rejected.accountMismatch) {
                failed(request, response, rejected);
                return;
            }
            // 다른 계정이면 앞 트랜잭션은 아무것도 바꾸지 않았다. 옛 세션만 다른 트랜잭션에서 지운다
            try {
                transaction.executeWithoutResult(status -> sessions.deleteById(state.oldSessionId()));
            } catch (RuntimeException e) {
                failed(request, response, e);
                return;
            }
            response.sendRedirect("/reauth-failed?reason=account-mismatch");
            return;
        } catch (RuntimeException e) {
            failed(request, response, e);
            return;
        }
        sessionIdResolver.setSessionId(request, response, sessionId);
        response.sendRedirect(state.returnTo());
    }

    /** 재인증 트랜잭션(research R5). 거절은 예외로 던져 롤백한다. */
    private String reauthCommit(OidcUser google, String oldSessionId) {
        if (oldSessionId == null) {
            throw new ReauthRejected(false);
        }
        // 옛 세션이 없으면 주인을 알 수 없다. 유휴 만료 확인은 sub를 비교한 뒤 아래 findById에서 한다
        String principal = jdbc.sql("select principal_name from spring_session where session_id = :id")
                .param("id", oldSessionId).query(String.class).optional().orElseThrow(() -> new ReauthRejected(false));
        long userId = Long.parseLong(principal);
        switch (loginService.lockAndCompare(userId, google.getSubject())) {
            case MISSING -> throw new ReauthRejected(false);
            case DIFFERENT -> throw new ReauthRejected(true);
            case SAME -> { }
        }
        // 유휴 만료면 findById가 null을 돌려준다. 절대 만료는 필터와 같은 기준이다
        Session old = sessions.findById(oldSessionId);
        if (old == null || clock.instant().isAfter(old.getCreationTime().plus(AbsoluteExpiryFilter.ABSOLUTE_LIFETIME))) {
            throw new ReauthRejected(false);
        }
        loginService.refresh(userId, google.getEmail(), google.getEmailVerified());
        String sessionId = saveNewSession(sessions, new SessionLoginInfo(userId, clock.instant()));
        int deleted = jdbc.sql("delete from spring_session where session_id = :id").param("id", oldSessionId).update();
        if (deleted != 1) {
            throw new ReauthRejected(false);
        }
        return sessionId;
    }

    private static final class ReauthRejected extends RuntimeException {

        final boolean accountMismatch;

        ReauthRejected(boolean accountMismatch) {
            super(null, null, false, false);
            this.accountMismatch = accountMismatch;
        }
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
