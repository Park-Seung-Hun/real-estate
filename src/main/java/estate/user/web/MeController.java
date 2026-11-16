package estate.user.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import estate.common.crypto.ColumnCipher;
import estate.common.web.Problem;
import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import estate.user.application.WithdrawService;
import estate.user.domain.DisplayName;
import estate.user.domain.User;
import estate.user.domain.UserRepository;
import estate.user.security.SessionLoginInfo;
import tools.jackson.databind.JsonNode;

/** 내 정보 조회, 표시 이름 수정, 탈퇴(contracts `GET`, `PATCH`, `DELETE /api/v1/me`). */
@RestController
class MeController {

    record Me(String displayName, String loginEmail, boolean emailVerified) {
    }

    // 계약이 string이라 Jackson이 숫자와 참거짓을 문자열로 바꿔 받지 않게 노드로 받는다
    record UpdateMe(JsonNode displayName) {
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
        return toMe(user);
    }

    /** 세션 사용자의 행만 고친다. 행을 잠가 같은 사용자의 로그인 콜백과 한 줄로 선다(research R5). 요청 값은 응답에 담지 않는다. */
    @PatchMapping("/api/v1/me")
    @Transactional
    Me updateMe(SessionLoginInfo login, @RequestBody UpdateMe request) {
        // 키 없음은 null, JSON null은 NullNode로 온다. 둘 다 문자열 노드가 아니다
        JsonNode node = request.displayName();
        String name = node == null || !node.isString() ? null : DisplayName.fromUser(node.stringValue()).orElse(null);
        if (name == null) {
            throw new ProblemException(ProblemCode.VALIDATION_FAILED,
                    List.of(new Problem.FieldError("#/displayName", "표시 이름은 1자 이상 20자 이하로 입력해 주세요.")));
        }
        User user = users.findByIdForUpdate(login.userId()).orElseThrow(() -> new ProblemException(ProblemCode.UNAUTHENTICATED));
        user.changeDisplayName(name);
        return toMe(user);
    }

    private Me toMe(User user) {
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
