package estate.common.crypto;

/** 로그인 임시 값 쿠키 전용 32바이트 키. 값이 로그에 나오지 않게 toString을 막는다. */
public final class LoginCookieKey {

    private final byte[] bytes;

    public LoginCookieKey(String base64) {
        this.bytes = ColumnCipher.Keys.decode32(base64 == null ? "" : base64, "로그인 쿠키");
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "LoginCookieKey[***]";
    }
}
