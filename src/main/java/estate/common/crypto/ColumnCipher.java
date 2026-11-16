package estate.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 개인정보 열 암호화(AES-256-GCM). 저장 형식은 키 버전 1바이트, nonce 12바이트, 암호문, 태그 16바이트. */
public class ColumnCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, SecretKeySpec> keys = new HashMap<>();
    private final int activeVersion;

    /** keys는 `버전:base64 32바이트`를 쉼표로 이은 값. 오류 메시지에 키 값은 넣지 않는다. */
    public ColumnCipher(String keys, String activeVersion) {
        if (keys == null || keys.isBlank()) {
            throw new IllegalArgumentException("열 암호화 키가 없습니다.");
        }
        // limit -1: 끝 쉼표가 빈 항목으로 남아 거절된다
        for (String entry : keys.split(",", -1)) {
            String[] parts = entry.split(":", -1);
            if (parts.length != 2 || entry.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("열 암호화 키 항목 형식이 `버전:base64`가 아닙니다.");
            }
            int version = parseVersion(parts[0]);
            SecretKeySpec key = new SecretKeySpec(Keys.decode32(parts[1], "열 암호화"), "AES");
            if (this.keys.put(version, key) != null) {
                throw new IllegalArgumentException("열 암호화 키 버전이 중복입니다.");
            }
        }
        this.activeVersion = parseVersion(activeVersion);
        if (!this.keys.containsKey(this.activeVersion)) {
            throw new IllegalArgumentException("활성 키 버전이 키 목록에 없습니다.");
        }
    }

    private static int parseVersion(String text) {
        // 1~255, 앞의 +나 0 붙임 없음
        if (text == null || !text.matches("[1-9][0-9]{0,2}") || Integer.parseInt(text) > 255) {
            throw new IllegalArgumentException("키 버전은 1~255 정수여야 합니다.");
        }
        return Integer.parseInt(text);
    }

    public byte[] encrypt(String plaintext, String aad) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeVersion), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] body = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + NONCE_BYTES + body.length)
                    .put((byte) activeVersion).put(nonce).put(body).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("열 암호화에 실패했습니다.");
        }
    }

    public String decrypt(byte[] stored, String aad) {
        // 최소 길이: 버전 + nonce + 태그
        if (stored == null || stored.length < 1 + NONCE_BYTES + 16) {
            throw new IllegalStateException("열 복호화에 실패했습니다.");
        }
        SecretKeySpec key = keys.get(stored[0] & 0xff);
        if (key == null) {
            throw new IllegalStateException("열 복호화에 실패했습니다.");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, NONCE_BYTES));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(stored, 1 + NONCE_BYTES, stored.length - 1 - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // 원인 예외를 붙이지 않는다(메시지에 값이 섞이지 않게)
            throw new IllegalStateException("열 복호화에 실패했습니다.");
        }
    }

    static final class Keys {
        private Keys() {}

        static byte[] decode32(String base64, String what) {
            byte[] key;
            try {
                key = Base64.getDecoder().decode(base64.trim());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException(what + " 키가 base64가 아닙니다.");
            }
            if (key.length != 32) {
                throw new IllegalArgumentException(what + " 키는 32바이트여야 합니다.");
            }
            return key;
        }
    }
}
