package estate.user.security;

/**
 * 로그인 뒤 돌아갈 경로 검사(결정 기록 0013, research R6). 값은 서블릿이 질의 값을 한 번 푼 것이고,
 * `%`를 받지 않으므로 다시 풀어도 같은 값이라 검사한 값과 Location에 쓰는 값이 같다.
 */
public final class ReturnPath {

    private static final String ROOT = "/";

    private ReturnPath() {
    }

    /** 서비스 안 경로면 그대로, 아니면 `/`. */
    public static String sanitize(String requested) {
        if (requested == null || !requested.startsWith("/") || requested.startsWith("//") || requested.startsWith("/\\")) {
            return ROOT;
        }
        // 출력 가능한 ASCII(0x20~0x7E)만 받으므로 제어 문자와 ASCII 밖 문자가 빠진다
        boolean allowed = requested.chars().allMatch(c -> c >= 0x20 && c <= 0x7E && c != '\\' && c != '%');
        return allowed ? requested : ROOT;
    }
}
