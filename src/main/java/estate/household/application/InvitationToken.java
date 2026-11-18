package estate.household.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** 초대 토큰(research R6). 원문과 해시를 예외 메시지와 로그에 쓰지 않는다. */
final class InvitationToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private InvitationToken() {
    }

    /** 32바이트 무작위 값의 패딩 없는 base64url 43자. */
    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean isValid(String token) {
        return FORMAT.matcher(token).matches();
    }

    /** 43자 문자열의 ASCII 바이트의 SHA-256 32바이트. */
    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.");
        }
    }
}
