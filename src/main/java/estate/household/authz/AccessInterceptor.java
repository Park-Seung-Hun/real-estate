package estate.household.authz;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import estate.common.web.ProblemCode;
import estate.common.web.ProblemException;
import estate.household.domain.HouseholdMember;
import estate.household.domain.HouseholdMemberRepository;
import estate.user.security.SessionLoginInfo;

/**
 * 권한 확인 2단계(멤버십)와 3단계(권한 키)(research R2). MVC 인터셉터라 Spring Security 필터 뒤, 인자 해석과 본문 읽기 앞에서 돈다.
 * 던진 예외는 핸들러가 정해진 뒤라 {@code ProblemAdvice}가 처리한다.
 */
class AccessInterceptor implements HandlerInterceptor {

    static final String MEMBERSHIP_ATTRIBUTE = AccessInterceptor.class.getName() + ".membership";

    private final HouseholdMemberRepository members;

    AccessInterceptor(HouseholdMemberRepository members) {
        this.members = members;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        // 로그인 상태가 없는 요청은 Spring Security가 먼저 막으므로 여기 오지 않는다. 혹시 오면 거절한다
        if (!(request.getUserPrincipal() instanceof SessionLoginInfo login)) {
            throw new ProblemException(ProblemCode.UNAUTHENTICATED);
        }
        Access access = method.getMethodAnnotation(Access.class);
        if (access == null) {
            throw new ProblemException(ProblemCode.PERMISSION_DENIED);
        }
        Optional<HouseholdMember> member = members.findByUserId(login.userId());
        switch (access.membership()) {
            case ABSENT -> {
                if (member.isPresent()) {
                    throw new ProblemException(ProblemCode.HAS_HOUSEHOLD);
                }
                return true;
            }
            case REQUIRED -> {
                if (member.isEmpty()) {
                    throw new ProblemException(ProblemCode.NO_HOUSEHOLD);
                }
            }
            case OPTIONAL -> {
                if (member.isEmpty()) {
                    return true;
                }
            }
        }
        HouseholdMember m = member.get();
        if (!access.value().allows(m.getRole())) {
            throw new ProblemException(ProblemCode.PERMISSION_DENIED);
        }
        request.setAttribute(MEMBERSHIP_ATTRIBUTE, new HouseholdMembership(m.getHouseholdId(), m.getUserId(), m.getRole()));
        return true;
    }
}
