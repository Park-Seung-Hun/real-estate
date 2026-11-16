/*
 * 구현이 따라야 할 공개 표면(tasks T028, research R11):
 *
 *   package estate.user.domain;
 *   public final class DisplayName {
 *       // 구글이 보낸 이름을 다듬는다. 앞뒤 공백 제거, 제어 문자(Cc), 줄 구분 문자(Zl, Zp),
 *       // 방향 제어 문자(U+202A~U+202E, U+2066~U+2069) 제거 뒤 20자(코드 포인트)로 자른다.
 *       // 입력이 null이거나 결과가 비면 Optional.empty()(사용자 행의 display_name은 NULL).
 *       public static Optional<String> fromGoogle(String googleName);
 *   }
 *
 * 사용자가 직접 입력한 표시 이름 검증(T059)은 다듬지 않고 거부하는 다른 메서드다. 이 테스트는 fromGoogle만 본다.
 */
package estate.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 구글 이름 다듬기(tasks T026, research R11). */
class DisplayNameTest {

    private static final String TWENTY = "가나다라마바사아자차카타파하거너더러머버서".substring(0, 20);

    @Test
    void 앞뒤_공백을_지운다() {
        assertThat(DisplayName.fromGoogle("  엄마 ")).contains("엄마");
        assertThat(DisplayName.fromGoogle("　엄마　")).contains("엄마");
    }

    @Test
    void 가운데_공백은_남긴다() {
        assertThat(DisplayName.fromGoogle("김 엄마")).contains("김 엄마");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000", "\u0007", "\t", "\n", "\r", "\u001B", "\u007F", "\u0085", "\u009F"})
    void 제어_문자_Cc를_지운다(String control) {
        assertThat(Character.getType(control.codePointAt(0))).isEqualTo((int) Character.CONTROL);
        assertThat(DisplayName.fromGoogle("엄" + control + "마")).contains("엄마");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", " "})
    void 줄_구분_문자_Zl_Zp를_지운다(String separator) {
        assertThat(DisplayName.fromGoogle("엄" + separator + "마")).contains("엄마");
    }

    @ParameterizedTest
    @ValueSource(strings = {"‪", "‫", "‬", "‭", "‮", "⁦", "⁧", "⁨", "⁩"})
    void 방향_제어_문자를_지운다(String bidi) {
        assertThat(DisplayName.fromGoogle("엄" + bidi + "마")).contains("엄마");
        assertThat(DisplayName.fromGoogle(bidi + "엄마" + bidi)).contains("엄마");
    }

    @Test
    void 이십자는_그대로이고_이십일자는_앞_20자로_자른다() {
        assertThat(DisplayName.fromGoogle(TWENTY)).contains(TWENTY);
        assertThat(DisplayName.fromGoogle(TWENTY + "끝")).contains(TWENTY);
    }

    @Test
    void 길이는_UTF_16_단위가_아니라_코드_포인트로_센다() {
        String twentyEmoji = "😀".repeat(20);
        assertThat(twentyEmoji.length()).isEqualTo(40);

        assertThat(DisplayName.fromGoogle(twentyEmoji)).contains(twentyEmoji);
        assertThat(DisplayName.fromGoogle(twentyEmoji + "😀")).contains(twentyEmoji);
    }

    @Test
    void 금지_문자와_앞뒤_공백을_지운_뒤에_자른다() {
        // R11: "앞뒤 공백과 금지 문자를 제거하고 20자로 자른 값". 지우기 전에 자르면 19자가 남는다
        assertThat(DisplayName.fromGoogle("‮" + TWENTY)).contains(TWENTY);
        assertThat(DisplayName.fromGoogle("가\u0000" + TWENTY.substring(1) + "끝")).contains(TWENTY);
        assertThat(DisplayName.fromGoogle("   " + TWENTY + "   ")).contains(TWENTY);
    }

    @Test
    void 결과가_비면_빈_값이다() {
        assertThat(DisplayName.fromGoogle(null)).isEmpty();
        assertThat(DisplayName.fromGoogle("")).isEmpty();
        assertThat(DisplayName.fromGoogle("   ")).isEmpty();
        assertThat(DisplayName.fromGoogle("‮\u0000 ⁩")).isEmpty();
    }

    @Test
    void 이모지_결합_문자_ZWJ는_남긴다() {
        String family = "👨‍👩‍👧";
        String coder = "엄마👩‍💻";

        assertThat(DisplayName.fromGoogle(family)).contains(family);
        assertThat(DisplayName.fromGoogle(coder)).contains(coder);
    }
}
