package estate.user.security;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * 세션에 두는 로그인 정보. 사용자 번호와 마지막 구글 인증 시각만 담아, 직렬화해도 이메일, sub, 토큰이 들어갈 자리가 없다
 * (결정 기록 0012, 0013, research R4). 역할은 요청마다 멤버십에서 읽으므로 권한 목록은 비어 있다.
 */
public record SessionLoginInfo(long userId, Instant lastGoogleAuthAt) implements Authentication {

    @Override
    public String getName() {
        return Long.toString(userId);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (!authenticated) {
            throw new IllegalArgumentException("로그인 정보는 바꿀 수 없습니다.");
        }
    }
}
