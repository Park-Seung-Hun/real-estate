package estate.user.domain;

import java.util.Optional;
import java.util.stream.IntStream;

/** 표시 이름 규칙(research R11). */
public final class DisplayName {

    private static final int MAX_CODE_POINTS = 20;

    private DisplayName() {
    }

    /** 구글 이름에서 금지 문자와 앞뒤 공백을 지우고 20자(코드 포인트)로 자른다. 남는 것이 없으면 빈 값. */
    public static Optional<String> fromGoogle(String googleName) {
        if (googleName == null) {
            return Optional.empty();
        }
        String cleaned = strip(text(googleName.codePoints().filter(cp -> !isForbidden(cp))));
        // 자른 끝에 공백이 오면 사용자 입력 규칙(앞뒤 공백 없음)을 어기므로 한 번 더 지운다
        String name = strip(text(cleaned.codePoints().limit(MAX_CODE_POINTS)));
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    private static boolean isForbidden(int cp) {
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069);
    }

    // String.strip()은 NBSP 같은 유니코드 공백(Zs)을 남기므로 직접 지운다
    private static String strip(String s) {
        int[] cps = s.codePoints().toArray();
        int start = 0;
        int end = cps.length;
        while (start < end && isSpace(cps[start])) {
            start++;
        }
        while (end > start && isSpace(cps[end - 1])) {
            end--;
        }
        return new String(cps, start, end - start);
    }

    private static boolean isSpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static String text(IntStream codePoints) {
        return codePoints.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }
}
