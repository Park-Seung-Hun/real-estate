package estate.user.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.session.SessionIdGenerator;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 32바이트 무작위 원문 토큰을 만들어 그 SHA-256 해시를 세션 ID로 돌려준다(research R3, 결정 기록 0013).
 * 원문은 현재 요청 속성에만 두고, 쿠키를 쓸 때 {@link HashedCookieSessionIdResolver}가 꺼낸다.
 */
final class HashedSessionIdGenerator implements SessionIdGenerator {

    static final String RAW_TOKEN_ATTRIBUTE = HashedSessionIdGenerator.class.getName() + ".RAW_TOKEN";

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private final SecureRandom random = new SecureRandom();

    @Override
    public String generate() {
        // 원문을 넘길 요청이 없으면 쿠키를 발급할 수 없으므로 세션도 만들지 않는다
        RequestAttributes request = RequestContextHolder.currentRequestAttributes();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = BASE64URL.encodeToString(bytes);
        request.setAttribute(RAW_TOKEN_ATTRIBUTE, raw, RequestAttributes.SCOPE_REQUEST);
        return hash(raw);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
