package estate.user.application;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import estate.common.crypto.ColumnCipher;
import estate.user.domain.User;
import estate.user.domain.UserRepository;

/** 사용자 모듈의 공개 조회(research R1). 가구 모듈 화면이 사용자 테이블을 직접 읽지 않고 이것으로 표시 자료를 받는다. */
@Service
public class UserProfiles {

    public record Profile(long userId, String displayName, String loginEmail, boolean emailVerified) {
    }

    private final UserRepository users;
    private final ColumnCipher cipher;

    UserProfiles(UserRepository users, ColumnCipher cipher) {
        this.users = users;
        this.cipher = cipher;
    }

    /** 없는 번호는 결과에 없다. */
    public Map<Long, Profile> find(Collection<Long> userIds) {
        return users.findAllById(userIds).stream().map(this::toProfile)
                .collect(Collectors.toMap(Profile::userId, Function.identity()));
    }

    private Profile toProfile(User user) {
        String name = user.getDisplayName();
        return new Profile(user.getId(), name == null || name.isEmpty() ? null : name,
                cipher.decrypt(user.getLoginEmail(), User.loginEmailAad(user.getId())), user.isEmailVerified());
    }
}
