package estate.support;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 가짜 실거래 API 응답(XML) 생성기. 지역 코드, 단지 이름, 값은 모두 가짜다(공개 저장소).
 * 항목은 실제 응답처럼 요소 32개를 늘 가지고, 값이 없으면 공백 한 칸이다.
 *
 * <pre>
 * byte[] body = TradeFixtures.response(TradeFixtures.item("99999-1").with("dealDay", "20"));
 * </pre>
 */
public final class TradeFixtures {

    /** 가짜 시군구 코드 둘. */
    public static final String LAWD_CD = "99999";
    public static final String OTHER_LAWD_CD = "99998";
    public static final String DEAL_YMD = "202609";

    private static final String[] ELEMENTS = {
        "sggCd", "umdNm", "aptNm", "jibun", "excluUseAr", "dealYear", "dealMonth", "dealDay", "dealAmount", "floor",
        "buildYear", "cdealType", "cdealDay", "dealingGbn", "estateAgentSggNm", "rgstDate", "aptDong", "slerGbn",
        "buyerGbn", "landLeaseholdGbn", "aptSeq", "bonbun", "bubun", "landCd", "roadNm", "roadNmSggCd", "roadNmCd",
        "roadNmSeq", "roadNmbCd", "roadNmBonbun", "roadNmBubun", "umdCd"};

    private static final String SELF_CLOSING = "\u0000selfClosing";

    private TradeFixtures() {
    }

    /** item 하나. 기본값은 99999 지역 2026년 9월 15일 거래이고 {@link #with}로 요소 값을 바꾼다. */
    public static final class Item {

        private final Map<String, String> values = new LinkedHashMap<>();

        private Item(String aptSeq) {
            for (String name : ELEMENTS) {
                values.put(name, " ");
            }
            values.put("aptSeq", aptSeq);
            values.put("sggCd", LAWD_CD);
            values.put("umdNm", "가상동");
            values.put("aptNm", "가상단지");
            values.put("jibun", "123");
            values.put("excluUseAr", "84.97");
            values.put("dealYear", "2026");
            values.put("dealMonth", "9");
            values.put("dealDay", "15");
            values.put("dealAmount", "98,500");
            values.put("floor", "7");
            values.put("buildYear", "2010");
            values.put("dealingGbn", "중개거래");
        }

        /** 요소 값을 바꾼다. 빈 문자열은 공백 한 칸(실제 응답과 같음), null은 요소를 아예 빼 둔다. */
        public Item with(String element, String value) {
            if (!values.containsKey(element)) {
                throw new IllegalArgumentException("항목에 없는 요소: " + element);
            }
            if (value == null) {
                values.remove(element);
            } else {
                values.put(element, value.isEmpty() ? " " : value);
            }
            return this;
        }

        /** 요소를 {@code <name/>} 모양의 빈 요소로 둔다. */
        public Item selfClosing(String element) {
            values.put(element, SELF_CLOSING);
            return this;
        }

        /** 해제 거래로 만든다(cdealType O, 해제 사유 발생일). */
        public Item canceled(String yyMmDd) {
            return with("cdealType", "O").with("cdealDay", yyMmDd);
        }

        String toXml() {
            StringBuilder xml = new StringBuilder("<item>");
            values.forEach((name, value) -> {
                if (SELF_CLOSING.equals(value)) {
                    xml.append('<').append(name).append("/>");
                } else {
                    xml.append('<').append(name).append('>').append(escape(value)).append("</").append(name)
                            .append('>');
                }
            });
            return xml.append("</item>").toString();
        }
    }

    public static Item item(String aptSeq) {
        return new Item(aptSeq);
    }

    /** 이 지역, 이 계약년월(YYYYMM) 15일의 항목. 수집 테스트는 항목의 시군구 코드와 계약년월이 요청과 같아야 한다. */
    public static Item item(String lawdCd, String dealYmd, String aptSeq) {
        return new Item(aptSeq).with("sggCd", lawdCd).with("dealYear", dealYmd.substring(0, 4))
                .with("dealMonth", String.valueOf(Integer.parseInt(dealYmd.substring(4))));
    }

    /** 기본 항목 하나("99999-1"). */
    public static Item item() {
        return item("99999-1");
    }

    /** 정상 응답. totalCount는 항목 수, pageNo 1, numOfRows 1000. */
    public static byte[] response(Item... items) {
        return response(items.length, 1, 1000, items);
    }

    /** 정상 응답. 여러 페이지를 흉내 낼 때 totalCount와 pageNo를 직접 준다. 항목이 없으면 {@code <items/>}. */
    public static byte[] response(int totalCount, int pageNo, int numOfRows, Item... items) {
        StringBuilder xml = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?><response>"
                        + "<header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header><body>");
        if (items.length == 0) {
            xml.append("<items/>");
        } else {
            xml.append("<items>");
            for (Item item : items) {
                xml.append(item.toXml());
            }
            xml.append("</items>");
        }
        xml.append("<numOfRows>").append(numOfRows).append("</numOfRows><pageNo>").append(pageNo)
                .append("</pageNo><totalCount>").append(totalCount).append("</totalCount></body></response>");
        return bytes(xml.toString());
    }

    /** response/header/resultCode가 정상이 아닌 응답. */
    public static byte[] resultError(String resultCode, String resultMsg) {
        return bytes("<?xml version=\"1.0\" encoding=\"utf-8\"?><response><header><resultCode>" + resultCode
                + "</resultCode><resultMsg>" + escape(resultMsg) + "</resultMsg></header></response>");
    }

    /** 루트가 OpenAPI_ServiceResponse인 응답(cmmMsgHeader/returnReasonCode). */
    public static byte[] openApiError(String reasonCode, String authMsg) {
        return bytes("<?xml version=\"1.0\" encoding=\"UTF-8\"?><OpenAPI_ServiceResponse><cmmMsgHeader>"
                + "<errMsg>SERVICE ERROR</errMsg><returnAuthMsg>" + escape(authMsg) + "</returnAuthMsg>"
                + "<returnReasonCode>" + reasonCode + "</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>");
    }

    public static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
