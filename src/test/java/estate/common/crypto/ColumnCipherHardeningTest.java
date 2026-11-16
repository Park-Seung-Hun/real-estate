package estate.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * {@link ColumnCipherTest}를 보강한다. 키를 실제로 쓰는지, 변조와 잘린 입력, 키 설정 문자열 규칙
 * (`버전:base64` 항목을 쉼표로 이음, 버전 1~255, 중복과 빈 항목 거부), 쿠키 키를 확인한다.
 */
class ColumnCipherHardeningTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String EMAIL = "fake-user@example.com";
    private static final String AAD = "user.login_email:1";

    private static String randomKey(int bytes) {
        byte[] key = new byte[bytes];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static byte[] flipBit(byte[] stored, int index) {
        byte[] copy = stored.clone();
        copy[index] ^= 0x01;
        return copy;
    }

    @Test
    void usesTheGivenKey() throws Exception {
        String key = randomKey(32);
        ColumnCipher cipher = new ColumnCipher("1:" + key, "1");
        ColumnCipher otherKey = new ColumnCipher("1:" + randomKey(32), "1");

        byte[] stored = cipher.encrypt(EMAIL, AAD);

        assertThatThrownBy(() -> otherKey.decrypt(stored, AAD)).isInstanceOf(IllegalStateException.class);

        Cipher jce = Cipher.getInstance("AES/GCM/NoPadding");
        jce.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(key), "AES"),
                new GCMParameterSpec(128, Arrays.copyOfRange(stored, 1, 13)));
        jce.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
        byte[] plain = jce.doFinal(Arrays.copyOfRange(stored, 13, stored.length));
        assertThat(new String(plain, StandardCharsets.UTF_8)).isEqualTo(EMAIL);
    }

    @Test
    void encryptsWithNewKeyAfterRotation() {
        String key1 = randomKey(32);
        String key2 = randomKey(32);
        ColumnCipher rotated = new ColumnCipher("1:" + key1 + ",2:" + key2, "2");

        byte[] stored = rotated.encrypt(EMAIL, AAD);

        assertThat(new ColumnCipher("2:" + key2, "2").decrypt(stored, AAD)).isEqualTo(EMAIL);
        ColumnCipher onlyKey1 = new ColumnCipher("1:" + key1, "1");
        assertThatThrownBy(() -> onlyKey1.decrypt(stored, AAD)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTamperedValue() {
        ColumnCipher cipher = new ColumnCipher("1:" + randomKey(32), "1");
        byte[] stored = cipher.encrypt(EMAIL, AAD);
        int tagStart = stored.length - 16;

        // 순서: 버전(0), nonce(1~12), 암호문(13~태그 앞), 태그(뒤 16바이트)
        assertThatThrownBy(() -> cipher.decrypt(flipBit(stored, 13), AAD)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(flipBit(stored, tagStart), AAD))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(flipBit(stored, 1), AAD)).isInstanceOf(IllegalStateException.class);

        byte[] unknownVersion = stored.clone();
        unknownVersion[0] = 7;
        assertThatThrownBy(() -> cipher.decrypt(unknownVersion, AAD)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTruncatedInput() {
        ColumnCipher cipher = new ColumnCipher("1:" + randomKey(32), "1");
        byte[] tooShort = new byte[28];
        tooShort[0] = 1;

        assertThatThrownBy(() -> cipher.decrypt(new byte[0], AAD)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(new byte[] {1}, AAD)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(tooShort, AAD)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsDuplicateVersion() {
        assertThatThrownBy(() -> new ColumnCipher("1:" + randomKey(32) + ",1:" + randomKey(32), "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyEntries() {
        String a = "1:" + randomKey(32);
        String b = "2:" + randomKey(32);

        assertThatThrownBy(() -> new ColumnCipher("," + a, "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnCipher(a + ",," + b, "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnCipher(a + ",", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsVersionOutsideOneTo255() {
        String a = "1:" + randomKey(32);

        assertThatThrownBy(() -> new ColumnCipher(a + ",0:" + randomKey(32), "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnCipher(a + ",256:" + randomKey(32), "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonCanonicalVersionAndWhitespace() {
        String key = randomKey(32);

        assertThatThrownBy(() -> new ColumnCipher("+1:" + key, "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnCipher("01:" + key, "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnCipher("1: " + key, "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnCipher(" 1:" + key, "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedActiveVersion() {
        String keys = "1:" + randomKey(32);

        assertThatThrownBy(() -> new ColumnCipher(keys, "+1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnCipher(keys, "01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnCipher(keys, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsValueWithLastByteCut() {
        ColumnCipher cipher = new ColumnCipher("1:" + randomKey(32), "1");
        byte[] stored = cipher.encrypt(EMAIL, AAD);
        byte[] cut = Arrays.copyOf(stored, stored.length - 1);

        assertThat(cut.length).isGreaterThanOrEqualTo(29);
        assertThatThrownBy(() -> cipher.decrypt(cut, AAD)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsValueRelabeledToOtherRegisteredVersion() {
        String key1 = randomKey(32);
        byte[] stored = new ColumnCipher("1:" + key1, "1").encrypt(EMAIL, AAD);
        ColumnCipher both = new ColumnCipher("1:" + key1 + ",2:" + randomKey(32), "2");

        byte[] relabeled = stored.clone();
        relabeled[0] = 2;

        assertThatThrownBy(() -> both.decrypt(relabeled, AAD)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loginCookieKeyRejectsWrongLength() {
        assertThatThrownBy(() -> new LoginCookieKey(randomKey(31))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginCookieKey(randomKey(33))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginCookieKey("not base64 !!")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginCookieKeyReturnsCopy() {
        String base64 = randomKey(32);
        byte[] expected = Base64.getDecoder().decode(base64);
        LoginCookieKey key = new LoginCookieKey(base64);

        byte[] first = key.bytes();
        assertThat(first).isEqualTo(expected);
        first[0] ^= 0x01;

        assertThat(key.bytes()).isEqualTo(expected);
    }
}
