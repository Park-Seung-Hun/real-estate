package estate.user.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import estate.common.crypto.ColumnCipher;
import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import estate.user.domain.User;
import estate.user.domain.UserRepository;
import estate.user.security.SessionLoginInfo;

/** 내 정보 조회(contracts `GET /api/v1/me`). */
@RestController
class MeController {

    record Me(String displayName, String loginEmail, boolean emailVerified) {
    }

    private final UserRepository users;
    private final ColumnCipher cipher;

    MeController(UserRepository users, ColumnCipher cipher) {
        this.users = users;
        this.cipher = cipher;
    }

    @GetMapping("/api/v1/me")
    Me me(SessionLoginInfo login) {
        User user = users.findById(login.userId()).orElseThrow(() -> new ProblemException(ProblemCode.UNAUTHENTICATED));
        return new Me(user.getDisplayName(), cipher.decrypt(user.getLoginEmail(), User.loginEmailAad(user.getId())),
                user.isEmailVerified());
    }
}
