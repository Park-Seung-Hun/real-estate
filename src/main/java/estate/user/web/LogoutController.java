package estate.user.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import estate.household.authz.Access;
import estate.household.authz.MembershipRule;
import estate.household.authz.Permission;

/**
 * 로그아웃(contracts `POST /api/v1/logout`, research R9). 로그인 상태 확인은 보안 설정이 먼저 한다.
 * 세션을 무효화하면 Spring Session이 그 행을 지우고 응답에 세션 쿠키 삭제(Max-Age=0)를 쓴다.
 */
@RestController
class LogoutController {

    @PostMapping("/api/v1/logout")
    @Access(value = Permission.NONE, membership = MembershipRule.OPTIONAL)
    ResponseEntity<Void> logout(HttpServletRequest request) {
        request.getSession(false).invalidate();
        return ResponseEntity.noContent().build();
    }
}
