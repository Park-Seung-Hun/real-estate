package estate.user.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import estate.common.crypto.LoginCookieKey;
import tools.jackson.databind.ObjectMapper;

/**
 * 인가 요청과 돌아갈 경로, 시작 시각, 옛 세션 참조, 목적을 `__Host-Http-login` 쿠키에 AES-GCM으로 담는다(research R6, 결정 기록 0013).
 * Strict 세션 쿠키는 구글에서 돌아오는 콜백에 실리지 않고, 로그인 전에는 세션을 만들지 않기 때문이다.
 */
@Component
public class LoginCookieAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String COOKIE_NAME = "__Host-Http-login";
    static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final String REAUTH = "reauth";
    private static final String LOGIN_STATE_ATTRIBUTE = LoginCookieAuthorizationRequestRepository.class.getName() + ".LOGIN_STATE";
    private static final int NONCE_BYTES = 12;
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    /** 콜백 처리기가 읽는 값. 옛 세션 참조와 목적은 로그인 시작 때 세션이 있었을 때만 있다. */
    public record LoginState(String returnTo, String purpose, String oldSessionId) {
        public boolean reauth() {
            return REAUTH.equals(purpose);
        }
    }

    /** 쿠키 본문. 인가 요청은 콜백이 다시 만들 수 있는 항목만 담는다(nonce, PKCE 검증값은 attributes). */
    record Stored(String authorizationUri, String clientId, String redirectUri, Set<String> scopes, String state,
            Map<String, Object> additionalParameters, Map<String, Object> attributes, String returnTo, String purpose,
            String oldSessionId, long startedAt) {
    }

    private final SecretKeySpec key;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    LoginCookieAuthorizationRequestRepository(LoginCookieKey key, ObjectMapper json, Clock clock) {
        this.key = new SecretKeySpec(key.bytes(), "AES");
        this.json = json;
        this.clock = clock;
    }

    /** 콜백에서 쿠키를 풀었으면 그 값, 아니면 null. */
    public static LoginState loginState(HttpServletRequest request) {
        return (LoginState) request.getAttribute(LOGIN_STATE_ATTRIBUTE);
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Stored stored = read(request);
        return stored != null && valid(stored, request) ? toRequest(stored) : null;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
            HttpServletResponse response) {
        if (authorizationRequest == null) {
            delete(response);
            return;
        }
        // 해석기가 이미 해시한 세션 ID라 쿠키 원문을 다시 다루지 않는다(research R3)
        HttpSession session = request.getSession(false);
        String purpose = session != null && REAUTH.equals(request.getParameter("purpose")) ? REAUTH : null;
        Stored stored = new Stored(authorizationRequest.getAuthorizationUri(), authorizationRequest.getClientId(),
                authorizationRequest.getRedirectUri(), authorizationRequest.getScopes(), authorizationRequest.getState(),
                authorizationRequest.getAdditionalParameters(), authorizationRequest.getAttributes(),
                ReturnPath.sanitize(request.getParameter("returnTo")), purpose,
                session != null ? session.getId() : null, clock.millis());
        write(response, encrypt(json.writeValueAsBytes(stored)), LIFETIME);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        Stored stored = read(request);
        if (stored == null) {
            return null;
        }
        // 확인 결과와 관계없이 한 번 쓴 임시 값은 지운다
        delete(response);
        request.setAttribute(LOGIN_STATE_ATTRIBUTE, new LoginState(stored.returnTo(), stored.purpose(), stored.oldSessionId()));
        return valid(stored, request) ? toRequest(stored) : null;
    }

    private boolean valid(Stored stored, HttpServletRequest request) {
        // 쿠키 수명은 브라우저가 지키는 것이라 서버도 시작 시각을 확인한다
        boolean fresh = !clock.instant().isAfter(Instant.ofEpochMilli(stored.startedAt()).plus(LIFETIME));
        String state = request.getParameter("state");
        return fresh && state != null && MessageDigest.isEqual(stored.state().getBytes(StandardCharsets.UTF_8),
                state.getBytes(StandardCharsets.UTF_8));
    }

    private static OAuth2AuthorizationRequest toRequest(Stored stored) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(stored.authorizationUri())
                .clientId(stored.clientId())
                .redirectUri(stored.redirectUri())
                .scopes(stored.scopes())
                .state(stored.state())
                .additionalParameters(stored.additionalParameters())
                .attributes(stored.attributes())
                .build();
    }

    private Stored read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                byte[] plain = decrypt(cookie.getValue());
                return plain == null ? null : json.readValue(plain, Stored.class);
            }
        }
        return null;
    }

    private String encrypt(byte[] plain) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(COOKIE_NAME.getBytes(StandardCharsets.UTF_8));
            byte[] body = cipher.doFinal(plain);
            return BASE64URL.encodeToString(ByteBuffer.allocate(NONCE_BYTES + body.length).put(nonce).put(body).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("로그인 임시 값 암호화에 실패했습니다.");
        }
    }

    /** 위조되었거나 형식이 틀리면 null(쿠키가 없는 것과 같이 다룬다). */
    private byte[] decrypt(String value) {
        try {
            byte[] stored = Base64.getUrlDecoder().decode(value);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, stored, 0, NONCE_BYTES));
            cipher.updateAAD(COOKIE_NAME.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private static void delete(HttpServletResponse response) {
        write(response, "", Duration.ZERO);
    }

    private static void write(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(true).sameSite("Lax")
                .path("/").maxAge(maxAge).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
