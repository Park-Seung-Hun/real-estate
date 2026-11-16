package estate.user.application;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import estate.common.crypto.ColumnCipher;
import estate.user.domain.DisplayName;
import estate.user.domain.User;
import estate.user.domain.UserRepository;

/** 일반 로그인 콜백의 사용자 찾기와 만들기(research R5). */
@Service
public class LoginService {

    static final String PROVIDER = "google";

    private final UserRepository users;
    private final JdbcClient jdbc;
    private final ColumnCipher cipher;

    public LoginService(UserRepository users, JdbcClient jdbc, ColumnCipher cipher) {
        this.users = users;
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /**
     * 사용자 행을 잠그고 없으면 만들고 있으면 이메일과 검증 여부를 맞춘 뒤 사용자 번호를 돌려준다.
     * 처음 로그인이 동시에 둘이면 유니크 위반이 그대로 나가고, 부르는 쪽이 트랜잭션 전체를 다시 한다(T035).
     */
    // 잠금과 세션 저장이 한 트랜잭션이어야 하므로 부르는 쪽 트랜잭션 안에서만 돈다
    @Transactional(propagation = Propagation.MANDATORY)
    public long login(String subject, String email, Boolean emailVerified, String googleName) {
        boolean verified = Boolean.TRUE.equals(emailVerified);
        Optional<User> found = users.findForUpdate(PROVIDER, subject);
        if (found.isEmpty()) {
            long id = jdbc.sql("select nextval('user_id_seq')").query(Long.class).single();
            // 유니크 위반이 커밋 때가 아니라 여기서 나게 바로 flush한다
            users.saveAndFlush(new User(id, PROVIDER, subject, cipher.encrypt(email, User.loginEmailAad(id)), verified,
                    DisplayName.fromGoogle(googleName).orElse(null)));
            return id;
        }
        syncEmail(found.get(), email, verified);
        return found.get().getId();
    }

    public enum AccountMatch { SAME, DIFFERENT, MISSING }

    /** 재인증의 첫 단계. 사용자 행을 잠그고 sub가 같은지만 본다(아무것도 바꾸지 않는다). */
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountMatch lockAndCompare(long userId, String subject) {
        return users.findByIdForUpdate(userId)
                .map(user -> user.getProviderSubject().equals(subject) ? AccountMatch.SAME : AccountMatch.DIFFERENT)
                .orElse(AccountMatch.MISSING);
    }

    /** 같은 계정으로 재인증이 끝났을 때 이메일과 검증 여부를 맞춘다. 사용자 행은 lockAndCompare가 이미 잠갔다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(long userId, String email, Boolean emailVerified) {
        syncEmail(users.findById(userId).orElseThrow(), email, Boolean.TRUE.equals(emailVerified));
    }

    private void syncEmail(User user, String email, boolean verified) {
        String aad = User.loginEmailAad(user.getId());
        if (!email.equals(cipher.decrypt(user.getLoginEmail(), aad))) {
            user.changeLoginEmail(cipher.encrypt(email, aad));
        }
        if (user.isEmailVerified() != verified) {
            user.changeEmailVerified(verified);
        }
    }
}
