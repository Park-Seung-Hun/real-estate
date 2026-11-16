package estate.user.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 서비스 사용자(data-model `user`). 기본 키는 이메일 암호화 AAD에 쓰므로 시퀀스에서 먼저 받아 넣는다. */
@Entity
@Table(name = "\"user\"")
public class User {

    @Id
    private Long id;

    private String provider;

    private String providerSubject;

    private byte[] loginEmail;

    private boolean emailVerified;

    private String displayName;

    protected User() {
    }

    public User(long id, String provider, String providerSubject, byte[] loginEmail, boolean emailVerified,
            String displayName) {
        this.id = id;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.loginEmail = loginEmail;
        this.emailVerified = emailVerified;
        this.displayName = displayName;
    }

    /** `login_email` 암호화의 AAD(결정 기록 0012). */
    public static String loginEmailAad(long id) {
        return "user.login_email:" + id;
    }

    public long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public byte[] getLoginEmail() {
        return loginEmail;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void changeLoginEmail(byte[] encrypted) {
        this.loginEmail = encrypted;
    }

    public void changeEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }
}
