package estate.support;

/**
 * 가짜 구글 서버가 ID 토큰에 담을 계정: {@code GoogleAccount.of("sub-1", "user1@example.com")}(이름은 기본 "테스트 사용자").
 * 이름 없음은 {@code .withoutName()}, 검증 안 된 이메일은 {@code .withEmailVerified(false)}. 테스트마다 고유 sub를 쓴다(사용자 행은 Reset이 지우지 않음).
 */
public record GoogleAccount(String sub, String email, boolean emailVerified, String name) {

    public static GoogleAccount of(String sub, String email) {
        return new GoogleAccount(sub, email, true, "테스트 사용자");
    }

    public GoogleAccount withName(String name) {
        return new GoogleAccount(sub, email, emailVerified, name);
    }

    public GoogleAccount withoutName() {
        return withName(null);
    }

    public GoogleAccount withEmail(String email) {
        return new GoogleAccount(sub, email, emailVerified, name);
    }

    public GoogleAccount withEmailVerified(boolean emailVerified) {
        return new GoogleAccount(sub, email, emailVerified, name);
    }

    /** ID 토큰의 email, email_verified, name claim(이름이 없으면 name 없음). */
    public java.util.Map<String, Object> claims() {
        var claims = new java.util.LinkedHashMap<String, Object>();
        claims.put("email", email);
        claims.put("email_verified", emailVerified);
        if (name != null) {
            claims.put("name", name);
        }
        return claims;
    }
}
