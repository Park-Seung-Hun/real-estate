package estate.trade.api;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * 아파트 매매 실거래가 상세 응답(XML)을 해석한다(contracts/trade-api-response.md).
 * 정상이 아닌 결과 코드와 해석 실패는 {@link TradeApiException}으로 던진다.
 */
public final class TradeResponseParser {

    /** 이 코드를 받으면 실행 전체를 멈춘다(research R1). */
    private static final Set<Integer> STOP_CODES = Set.of(20, 22, 30, 31, 32);
    private static final Pattern SHORT_DATE = Pattern.compile("(\\d{2})\\.(\\d{2})\\.(\\d{2})");
    private static final Pattern AREA = Pattern.compile("\\d+(\\.\\d+)?");

    private static final XMLInputFactory FACTORY = newFactory();

    private TradeResponseParser() {
    }

    private static XMLInputFactory newFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    /** 요소를 읽은 결과. 잎 요소의 글자를 이름으로, item은 목록으로 모은다. */
    private record Doc(String root, Map<String, String> leaves, List<Map<String, String>> items) {
    }

    public static TradePage parse(byte[] body, String expectedLawdCd, String expectedDealYmd) {
        Doc doc = read(body);
        int resultCode;
        String resultMessage;
        if ("OpenAPI_ServiceResponse".equals(doc.root())) {
            resultCode = code(doc.leaves().get("returnReasonCode"));
            resultMessage = firstPresent(doc.leaves().get("returnAuthMsg"), doc.leaves().get("errMsg"));
        } else if ("response".equals(doc.root())) {
            resultCode = code(doc.leaves().get("resultCode"));
            resultMessage = doc.leaves().get("resultMsg");
        } else {
            throw TradeApiException.parseError("알 수 없는 루트 요소");
        }
        if (resultCode != 0) {
            throw STOP_CODES.contains(resultCode)
                    ? TradeApiException.stopCode(resultCode)
                    : TradeApiException.resultCode(resultCode, resultMessage);
        }
        List<TradeItem> items = new ArrayList<>();
        for (Map<String, String> raw : doc.items()) {
            items.add(item(raw, expectedLawdCd, expectedDealYmd));
        }
        return new TradePage(0, items, integer(doc.leaves(), "totalCount"), integer(doc.leaves(), "pageNo"),
                integer(doc.leaves(), "numOfRows"));
    }

    private static Doc read(byte[] body) {
        Map<String, String> leaves = new HashMap<>();
        List<Map<String, String>> items = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        Deque<Boolean> hasChild = new ArrayDeque<>();
        StringBuilder text = new StringBuilder();
        Map<String, String> currentItem = null;
        String root = null;
        XMLStreamReader reader = null;
        try {
            reader = FACTORY.createXMLStreamReader(new ByteArrayInputStream(body));
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String name = reader.getLocalName();
                        if (root == null) {
                            root = name;
                        }
                        if (!hasChild.isEmpty()) {
                            hasChild.pop();
                            hasChild.push(true);
                        }
                        path.push(name);
                        hasChild.push(false);
                        text.setLength(0);
                        if ("item".equals(name)) {
                            // 중첩되면 바깥 item이 끝날 때 목록에 null이 들어가 해석 실패가 아닌 예외로 샌다
                            if (currentItem != null) {
                                throw TradeApiException.parseError("item이 중첩됨");
                            }
                            currentItem = new HashMap<>();
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(reader.getText());
                    case XMLStreamConstants.END_ELEMENT -> {
                        String name = path.pop();
                        boolean leaf = !hasChild.pop();
                        if ("item".equals(name)) {
                            items.add(currentItem);
                            currentItem = null;
                        } else if (leaf) {
                            (currentItem != null ? currentItem : leaves).put(name, text.toString());
                        }
                        text.setLength(0);
                    }
                    default -> {
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw TradeApiException.parseError("XML 해석 실패");
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // 닫기 실패는 해석 결과에 영향이 없다
                }
            }
        }
        if (root == null) {
            throw TradeApiException.parseError("XML 해석 실패");
        }
        return new Doc(root, leaves, items);
    }

    /** 결과 코드를 숫자로 바꾼다. "22"와 "022"는 같은 코드다. */
    private static int code(String value) {
        String text = blankToNull(value);
        if (text == null) {
            throw TradeApiException.parseError("결과 코드 없음");
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw TradeApiException.parseError("결과 코드가 숫자가 아님");
        }
    }

    private static TradeItem item(Map<String, String> raw, String expectedLawdCd, String expectedDealYmd) {
        String aptSeq = required(raw, "aptSeq");
        String sggCd = required(raw, "sggCd");
        if (!sggCd.equals(expectedLawdCd)) {
            throw TradeApiException.parseError("sggCd가 요청한 LAWD_CD와 다름");
        }
        int year = integer(raw, "dealYear");
        int month = integer(raw, "dealMonth");
        int day = integer(raw, "dealDay");
        LocalDate dealDate;
        try {
            dealDate = LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            throw TradeApiException.parseError("계약일 형식이 틀림");
        }
        if (!String.format("%04d%02d", year, month).equals(expectedDealYmd)) {
            throw TradeApiException.parseError("계약년월이 요청한 DEAL_YMD와 다름");
        }
        String area = required(raw, "excluUseAr");
        if (!AREA.matcher(area).matches()) {
            throw TradeApiException.parseError("excluUseAr 형식이 틀림");
        }
        String amount = required(raw, "dealAmount").replace(",", "");
        long won;
        try {
            won = Long.parseLong(amount);
        } catch (NumberFormatException e) {
            throw TradeApiException.parseError("dealAmount 형식이 틀림");
        }
        return new TradeItem(
                aptSeq,
                required(raw, "aptNm"),
                sggCd,
                required(raw, "umdNm"),
                blankToNull(raw.get("jibun")),
                dealDate,
                integer(raw, "floor"),
                new BigDecimal(area),
                won,
                blankToNull(raw.get("cdealType")) != null,
                shortDate(raw, "cdealDay"),
                shortDate(raw, "rgstDate"),
                blankToNull(raw.get("aptDong")));
    }

    private static String required(Map<String, String> values, String name) {
        String value = blankToNull(values.get(name));
        if (value == null) {
            throw TradeApiException.parseError("필수 항목이 비어 있음: " + name);
        }
        return value;
    }

    private static int integer(Map<String, String> values, String name) {
        try {
            return Integer.parseInt(required(values, name));
        } catch (NumberFormatException e) {
            throw TradeApiException.parseError("정수 형식이 틀림: " + name);
        }
    }

    /** YY.MM.DD를 20YY-MM-DD로 읽는다. 값이 없으면 null, 형식이 틀리면 해석 실패. */
    private static LocalDate shortDate(Map<String, String> values, String name) {
        String value = blankToNull(values.get(name));
        if (value == null) {
            return null;
        }
        Matcher matcher = SHORT_DATE.matcher(value);
        if (!matcher.matches()) {
            throw TradeApiException.parseError("날짜 형식이 틀림: " + name);
        }
        try {
            return LocalDate.of(2000 + Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (DateTimeException e) {
            throw TradeApiException.parseError("날짜 형식이 틀림: " + name);
        }
    }

    private static String firstPresent(String first, String second) {
        String value = blankToNull(first);
        return value != null ? value : blankToNull(second);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
