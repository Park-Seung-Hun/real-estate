package estate.trade.api;

import static estate.support.TradeFixtures.DEAL_YMD;
import static estate.support.TradeFixtures.LAWD_CD;
import static estate.support.TradeFixtures.item;
import static estate.support.TradeFixtures.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import estate.support.TradeFixtures;
import estate.support.TradeFixtures.Item;
import estate.trade.api.TradeApiException.Kind;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** T011: 응답 해석(contracts/trade-api-response.md). 값은 모두 가짜다. */
class TradeResponseParserTest {

    private static TradePage parse(byte[] body) {
        return TradeResponseParser.parse(body, LAWD_CD, DEAL_YMD);
    }

    private static TradeItem parseOne(Item item) {
        TradePage page = parse(response(item));
        assertThat(page.items()).hasSize(1);
        return page.items().get(0);
    }

    private static TradeApiException failure(byte[] body) {
        return (TradeApiException) assertThatThrownBy(() -> parse(body)).isInstanceOf(TradeApiException.class)
                .actual();
    }

    @Test
    void readsEveryFieldOfOneItem() {
        Item item = item("99999-12").with("aptNm", "가상단지").with("umdNm", "가상동").with("jibun", "123-4")
                .with("dealMonth", "9").with("dealDay", "5").with("floor", "12").with("excluUseAr", "59.9934")
                .with("dealAmount", "98,500").canceled("26.10.03").with("rgstDate", "26.11.20")
                .with("aptDong", "101동");

        TradePage page = parse(response(5, 2, 1000, item));

        assertThat(page.resultCode()).isZero();
        assertThat(page.totalCount()).isEqualTo(5);
        assertThat(page.pageNo()).isEqualTo(2);
        assertThat(page.numOfRows()).isEqualTo(1000);
        assertThat(page.items()).containsExactly(new TradeItem("99999-12", "가상단지", "99999", "가상동", "123-4",
                LocalDate.of(2026, 9, 5), 12, new BigDecimal("59.9934"), 98500L, true, LocalDate.of(2026, 10, 3),
                LocalDate.of(2026, 11, 20), "101동"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"98,500|98500", "1,234,567|1234567", "700|700", " 5 |5"})
    void removesCommasFromAmount(String text, long expected) {
        assertThat(parseOne(item().with("dealAmount", text)).amount()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"114", "84.97", "59.9934", "84.9"})
    void keepsAreaDigitsAsWritten(String area) {
        BigDecimal read = parseOne(item().with("excluUseAr", area)).exclusiveArea();
        assertThat(read).isEqualTo(new BigDecimal(area));
        assertThat(read.scale()).isEqualTo(new BigDecimal(area).scale());
    }

    @Test
    void readsMonthAndDayWithoutLeadingZero() {
        TradeItem read = parseOne(item().with("dealMonth", "9").with("dealDay", "5"));
        assertThat(read.dealDate()).isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    void readsCancelFlagFromNonBlankType() {
        assertThat(parseOne(item().canceled("26.10.03")).canceled()).isTrue();
        assertThat(parseOne(item().with("cdealType", "X")).canceled()).isTrue();
        assertThat(parseOne(item().with("cdealType", " ")).canceled()).isFalse();
        assertThat(parseOne(item().selfClosing("cdealType")).canceled()).isFalse();
        assertThat(parseOne(item().with("cdealType", null)).canceled()).isFalse();
    }

    @Test
    void readsTwoDigitYearDatesAs20YY() {
        TradeItem read = parseOne(item().canceled("26.10.03").with("rgstDate", "27.01.31"));
        assertThat(read.canceledOn()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(read.registeredOn()).isEqualTo(LocalDate.of(2027, 1, 31));

        TradeItem none = parseOne(item());
        assertThat(none.canceledOn()).isNull();
        assertThat(none.registeredOn()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"261003", "2026.10.03", "26.13.03", "26.02.30"})
    void rejectsMalformedOptionalDates(String value) {
        assertThat(failure(response(item().with("rgstDate", value))).kind()).isEqualTo(Kind.PARSE_ERROR);
    }

    @Test
    void stripsDongAndTreatsBlankAsNoValue() {
        assertThat(parseOne(item().with("aptDong", "  101동 ")).dong()).isEqualTo("101동");
        assertThat(parseOne(item().with("aptDong", "가 동")).dong()).isEqualTo("가 동");
        assertThat(parseOne(item().with("aptDong", " ")).dong()).isNull();
    }

    @Test
    void treatsBlankElementEmptyElementAndMissingElementAsTheSame() {
        TradeItem blank = parseOne(item().with("jibun", " ").with("aptDong", " "));
        TradeItem empty = parseOne(item().selfClosing("jibun").selfClosing("aptDong"));
        TradeItem missing = parseOne(item().with("jibun", null).with("aptDong", null));

        assertThat(blank.jibun()).isNull();
        assertThat(blank).isEqualTo(empty).isEqualTo(missing);
    }

    @Test
    void readsEmptyItemsAndZeroTotalCountAsNormal() {
        TradePage page = parse(response(0, 1, 1000));

        assertThat(page.items()).isEmpty();
        assertThat(page.totalCount()).isZero();
    }

    @Test
    void readsManyItemsInOrderOfAppearance() {
        TradePage page = parse(response(item("99999-1"), item("99999-2").with("dealDay", "20"), item("99999-3")));

        assertThat(page.items()).extracting(TradeItem::aptSeq).containsExactly("99999-1", "99999-2", "99999-3");
        assertThat(page.items().get(1).dealDate()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"22", "022", "20", "30", "31", "32", "020", "032"})
    void classifiesStopCodesInBothResponseShapes(String code) {
        for (byte[] body : new byte[][] {TradeFixtures.resultError(code, "msg"),
                TradeFixtures.openApiError(code, "msg")}) {
            TradeApiException e = failure(body);
            assertThat(e.kind()).isEqualTo(Kind.STOP_CODE);
            assertThat(e.isRunStopping()).isTrue();
            assertThat(e.code()).isEqualTo(Integer.parseInt(code));
        }
    }

    @Test
    void writesDailyLimitAndAuthKeyReasons() {
        assertThat(failure(TradeFixtures.resultError("22", "한도")).failureReason()).isEqualTo("DAILY_LIMIT: 22");
        assertThat(failure(TradeFixtures.openApiError("022", "한도")).failureReason()).isEqualTo("DAILY_LIMIT: 22");
        assertThat(failure(TradeFixtures.resultError("30", "x")).failureReason()).isEqualTo("AUTH_KEY_ERROR: 30");
        assertThat(failure(TradeFixtures.openApiError("031", "x")).failureReason()).isEqualTo("AUTH_KEY_ERROR: 31");
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "02", "03", "04", "05", "10", "11", "12", "21", "33", "099", "999"})
    void failsOnlyTheRangeForOtherNonZeroCodes(String code) {
        for (byte[] body : new byte[][] {TradeFixtures.resultError(code, "제공기관 오류"),
                TradeFixtures.openApiError(code, "제공기관 오류")}) {
            TradeApiException e = failure(body);
            assertThat(e.kind()).isEqualTo(Kind.RESULT_CODE);
            assertThat(e.isRunStopping()).isFalse();
            assertThat(e.failureReason()).isEqualTo("RESULT_CODE: " + Integer.parseInt(code) + " 제공기관 오류");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", " ", "2 2", "--"})
    void failsAsParseErrorWhenResultCodeIsNotANumber(String code) {
        assertThat(failure(TradeFixtures.resultError(code, "m")).kind()).isEqualTo(Kind.PARSE_ERROR);
        assertThat(failure(TradeFixtures.openApiError(code, "m")).kind()).isEqualTo(Kind.PARSE_ERROR);
    }

    @Test
    void zeroInEitherSpellingIsNormal() {
        assertThat(parse(TradeFixtures.bytes("<response><header><resultCode>00</resultCode></header><body><items/>"
                + "<numOfRows>1000</numOfRows><pageNo>1</pageNo><totalCount>0</totalCount></body></response>"))
                .items()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not xml at all", "{\"response\":{}}", "<response><header>", "<html><body/></html>",
        "<response/>", "<response><header><resultMsg>OK</resultMsg></header></response>"})
    void failsAsParseErrorWhenBodyIsNotAResponse(String body) {
        assertThat(failure(TradeFixtures.bytes(body)).kind()).isEqualTo(Kind.PARSE_ERROR);
    }

    @Test
    void failsAsParseErrorWhenBodyFieldsAreMissingOrBroken() {
        String head = "<response><header><resultCode>000</resultCode></header><body><items/>";
        assertThat(failure(TradeFixtures.bytes(head + "<numOfRows>1000</numOfRows><pageNo>1</pageNo></body></response>"))
                .kind()).isEqualTo(Kind.PARSE_ERROR);
        assertThat(failure(TradeFixtures.bytes(head
                + "<numOfRows>1000</numOfRows><pageNo>1</pageNo><totalCount>x</totalCount></body></response>"))
                .kind()).isEqualTo(Kind.PARSE_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aptSeq", "aptNm", "sggCd", "umdNm", "dealYear", "dealMonth", "dealDay", "floor",
        "excluUseAr", "dealAmount"})
    void failsWhenARequiredFieldIsBlankEmptyOrMissing(String element) {
        for (Item broken : new Item[] {item().with(element, " "), item().selfClosing(element),
            item().with(element, null)}) {
            TradeApiException e = failure(response(item("99999-1"), broken));
            assertThat(e.kind()).isEqualTo(Kind.PARSE_ERROR);
            assertThat(e.failureReason()).startsWith("PARSE_ERROR: ").contains(element);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"floor|x", "floor|1.5", "dealAmount|98.500", "dealAmount|abc",
        "dealAmount|-", "excluUseAr|abc", "excluUseAr|84,97", "excluUseAr|-84.97", "excluUseAr|1e2",
        "dealYear|26ab", "dealMonth|13", "dealMonth|0", "dealDay|32", "dealDay|0", "dealDay|x"})
    void failsWhenARequiredFieldHasTheWrongFormat(String element, String value) {
        assertThat(failure(response(item().with(element, value))).kind()).isEqualTo(Kind.PARSE_ERROR);
    }

    /** 해석 중의 다른 예외로 새면 그 범위만 실패하지 않고 실행 전체가 끝난다(FR-008). */
    @Test
    void failsAsParseErrorWhenItemsAreNested() {
        // 안쪽 item은 값이 온전해야 필수 항목 검사를 지나 바깥 item이 끝나는 자리까지 간다
        String xml = new String(response(item()), StandardCharsets.UTF_8)
                .replace("<item>", "<item><item>").replace("</item>", "</item></item>");
        assertThat(failure(TradeFixtures.bytes(xml)).kind()).isEqualTo(Kind.PARSE_ERROR);
    }

    @Test
    void failsWhenSggCdDiffersFromRequestedLawdCd() {
        TradeApiException e = failure(response(item("99999-1"), item("99998-1").with("sggCd", "99998")));
        assertThat(e.kind()).isEqualTo(Kind.PARSE_ERROR);
        assertThat(e.failureReason()).contains("sggCd");
    }

    @Test
    void failsWhenDealMonthDiffersFromRequestedDealYmd() {
        assertThat(failure(response(item("99999-1"), item("99999-2").with("dealMonth", "10"))).failureReason())
                .contains("DEAL_YMD");
        assertThat(failure(response(item().with("dealYear", "2025"))).kind()).isEqualTo(Kind.PARSE_ERROR);
    }

    @Test
    void matchesDealMonthWithoutLeadingZeroAgainstSixDigitDealYmd() {
        assertThat(TradeResponseParser.parse(response(item().with("dealMonth", "3")), LAWD_CD, "202603").items())
                .hasSize(1);
        assertThat(failure(response(item().with("dealMonth", "3"))).kind()).isEqualTo(Kind.PARSE_ERROR);
    }

    @Test
    void doesNotResolveExternalEntities(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "SECRET-MARKER-FOR-TEST");
        String xml = new String(response(item()), StandardCharsets.UTF_8).replace("<response>",
                "<!DOCTYPE response [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]><response>")
                .replace("<aptNm>가상단지</aptNm>", "<aptNm>&xxe;</aptNm>");
        assertThat(xml).contains("&xxe;");

        assertThatThrownBy(() -> parse(TradeFixtures.bytes(xml)))
                .isInstanceOfSatisfying(TradeApiException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.PARSE_ERROR);
                    assertThat(e.getMessage()).doesNotContain("SECRET-MARKER-FOR-TEST");
                });
    }
}
