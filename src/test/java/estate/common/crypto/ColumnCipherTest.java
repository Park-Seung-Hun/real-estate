package estate.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 개인정보 열 암호화 단위 테스트(research R10, 결정 기록 0012). 스프링 컨텍스트 없이 돈다.
 *
 * <p>구현이 따라야 할 공개 표면:
 * <ul>
 *   <li>클래스 {@code estate.common.crypto.ColumnCipher}</li>
 *   <li>생성자 {@code ColumnCipher(String keys, String activeVersion)}: {@code keys}는
 *       {@code ESTATE_COLUMN_KEYS} 형식(`버전:base64 32바이트`를 쉼표로 이음), {@code activeVersion}은
 *       {@code ESTATE_COLUMN_KEY_ACTIVE} 값. 키가 없거나, 32바이트가 아니거나, 활성 버전이 목록에 없으면
 *       {@code IllegalArgumentException}을 던진다(앱 시작 실패).</li>
 *   <li>{@code byte[] encrypt(String plaintext, String aad)}: 평문은 UTF-8, 결과는
 *       키 버전(1바이트) + nonce(12바이트) + 암호문 + 태그(16바이트). 새 값은 활성 버전 키로 암호화한다.</li>
 *   <li>{@code String decrypt(byte[] stored, String aad)}: 첫 바이트의 키 버전으로 복호화한다. AAD가 다르거나
 *       값이 변조되면 {@code IllegalStateException}을 던진다.</li>
 * </ul>
 */
class ColumnCipherTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String EMAIL = "fake-user@example.com";

    private static String randomKey(int bytes) {
        byte[] key = new byte[bytes];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static String aad(long userId) {
        return "user.login_email:" + userId;
    }

    @Test
    void decryptReturnsOriginalValue() {
        ColumnCipher cipher = new ColumnCipher("1:" + randomKey(32), "1");

        byte[] stored = cipher.encrypt(EMAIL, aad(1));

        assertThat(cipher.decrypt(stored, aad(1))).isEqualTo(EMAIL);
    }

    @Test
    void resultHoldsKeyVersionNonceCiphertextAndTag() {
        ColumnCipher cipher = new ColumnCipher("1:" + randomKey(32), "1");
        String plaintext = "가짜사용자@example.com";

        byte[] stored = cipher.encrypt(plaintext, aad(1));

        int plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8).length;
        assertThat(stored).hasSize(1 + 12 + plaintextBytes + 16);
        assertThat(stored[0]).isEqualTo((byte) 1);
    }

    @Test
    void sameValueGetsNewNonceEachTime() {
        ColumnCipher cipher = new ColumnCipher("1:" + randomKey(32), "1");

        byte[] first = cipher.encrypt(EMAIL, aad(1));
        byte[] second = cipher.encrypt(EMAIL, aad(1));

        assertThat(Arrays.copyOfRange(first, 1, 13)).isNotEqualTo(Arrays.copyOfRange(second, 1, 13));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void decryptFailsWhenAadDiffers() {
        ColumnCipher cipher = new ColumnCipher("1:" + randomKey(32), "1");

        byte[] stored = cipher.encrypt(EMAIL, aad(1));

        assertThatThrownBy(() -> cipher.decrypt(stored, aad(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decryptsOldValueWithPreviousKeyVersion() {
        String key1 = randomKey(32);
        ColumnCipher oldCipher = new ColumnCipher("1:" + key1, "1");
        byte[] old = oldCipher.encrypt(EMAIL, aad(1));

        ColumnCipher rotated = new ColumnCipher("1:" + key1 + ",2:" + randomKey(32), "2");

        assertThat(rotated.decrypt(old, aad(1))).isEqualTo(EMAIL);
        byte[] fresh = rotated.encrypt(EMAIL, aad(1));
        assertThat(fresh[0]).isEqualTo((byte) 2);
        assertThat(rotated.decrypt(fresh, aad(1))).isEqualTo(EMAIL);
    }

    @Test
    void failsToStartWhenKeyIsNot32Bytes() {
        assertThatThrownBy(() -> new ColumnCipher("1:" + randomKey(16), "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnCipher("1:" + randomKey(33), "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failsToStartWhenKeysAreMissing() {
        assertThatThrownBy(() -> new ColumnCipher(null, "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnCipher("", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failsToStartWhenActiveVersionIsNotInKeys() {
        assertThatThrownBy(() -> new ColumnCipher("1:" + randomKey(32), "2"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
