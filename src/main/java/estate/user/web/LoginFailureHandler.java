package estate.user.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import estate.user.security.LoginCookieAuthorizationRequestRepository;
import estate.user.security.LoginCookieAuthorizationRequestRepository.LoginState;

/**
 * 콜백 실패는 세션을 만들거나 건드리지 않고 실패 화면으로 보낸다(research R6). 기본 처리기는 세션에 오류를 담아 세션 행과 쿠키가 생긴다.
 * 구글 오류, state 불일치, 임시 값 쿠키 없음, 10분 지남, ID 토큰 검증 실패가 모두 여기로 온다.
 */
@Component
public class LoginFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        fail(request, response);
    }

    void fail(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        LoginState state = LoginCookieAuthorizationRequestRepository.loginState(request);
        response.sendRedirect(state != null && state.reauth() ? "/reauth-failed" : "/login-failed");
    }
}
