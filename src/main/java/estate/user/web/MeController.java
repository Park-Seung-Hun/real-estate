package estate.user.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import estate.common.crypto.ColumnCipher;
import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import estate.user.application.WithdrawService;
import estate.user.domain.User;
import estate.user.domain.UserRepository;
import estate.user.security.SessionLoginInfo;

/** 내 정보 조회와 탈퇴(contracts `GET /api/v1/me`, `DELETE /api/v1/me`). */
@RestController
class MeController {

    record Me(String displayName, String loginEmail, boolean emailVerified) {
    }

    private final UserRepository users;
    private final ColumnCipher cipher;

    private final WithdrawService withdrawService;

    MeController(UserRepository users, ColumnCipher cipher, WithdrawService withdrawService) {
        this.users = users;
        this.cipher = cipher;
        this.withdrawService = withdrawService;
    }

    @GetMapping("/api/v1/me")
    Me me(SessionLoginInfo login) {
        User user = users.findById(login.userId()).orElseThrow(() -> new ProblemException(ProblemCode.UNAUTHENTICATED));
        return new Me(user.getDisplayName(), cipher.decrypt(user.getLoginEmail(), User.loginEmailAad(user.getId())),
                user.isEmailVerified());
    }

    /** 서비스가 커밋한 뒤에 현재 세션을 무효화한다. 요청 끝에 Spring Session이 지워진 행을 다시 저장하지 않고 쿠키 삭제를 쓴다. */
    @DeleteMapping("/api/v1/me")
    ResponseEntity<Void> withdraw(SessionLoginInfo login, HttpServletRequest request) {
        withdrawService.withdraw(login.userId(), login.lastGoogleAuthAt());
        request.getSession(false).invalidate();
        return ResponseEntity.noContent().build();
    }
}
