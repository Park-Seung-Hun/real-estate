package estate.trade.api;

/**
 * 실거래 API 호출과 응답 해석의 실패. 원인 예외 객체를 가지지 않는다(super(message)만 쓴다).
 * 메시지에는 고정 문장, 상태 코드, 원인 클래스 이름, 결과 코드와 (인증키를 가린) 결과 메시지만 담는다(research R9).
 */
public class TradeApiException extends RuntimeException {

    /** 실패 종류. */
    public enum Kind {
        /** HTTP 상태가 200이 아님(재시도를 모두 쓴 뒤). */
        HTTP_ERROR,
        /** 연결 실패, 끊김, 시간 초과(재시도를 모두 쓴 뒤). */
        NETWORK_ERROR,
        /** 정상(0)이 아니고 실행을 멈추지 않는 결과 코드. 그 범위만 실패. */
        RESULT_CODE,
        /** 실행을 멈추는 결과 코드(20, 22, 30, 31, 32). */
        STOP_CODE,
        /** XML이 아니거나 형식, 필수 항목, 요청과의 일치가 틀림. */
        PARSE_ERROR,
        /** 스스로 멈추는 호출 수 기준값에 도달해 요청하지 않음. */
        SELF_STOP
    }

    private static final int MAX_RESULT_MESSAGE = 200;

    private final Kind kind;
    private final String failureReason;
    private final int code;
    private final String resultMessage;
    /** 자르기 전 결과 메시지. 인증키를 가리는 쪽(TradeApiClient)만 읽고, 가린 뒤 새 예외로 바꿔 던진다. */
    private final String fullResultMessage;

    private TradeApiException(Kind kind, String failureReason, int code, String resultMessage,
            String fullResultMessage) {
        super("실거래 API 호출 실패(" + failureReason + ")");
        this.kind = kind;
        this.failureReason = failureReason;
        this.code = code;
        this.resultMessage = resultMessage;
        this.fullResultMessage = fullResultMessage;
    }

    public static TradeApiException httpError(int status) {
        return new TradeApiException(Kind.HTTP_ERROR, "HTTP_ERROR: " + status, -1, null, null);
    }

    public static TradeApiException networkError(String causeClassName) {
        return new TradeApiException(Kind.NETWORK_ERROR, "NETWORK_ERROR: " + causeClassName, -1, null, null);
    }

    public static TradeApiException resultCode(int code, String resultMessage) {
        String full = resultMessage == null ? "" : resultMessage.strip();
        String message = full.length() > MAX_RESULT_MESSAGE ? full.substring(0, MAX_RESULT_MESSAGE) : full;
        return new TradeApiException(Kind.RESULT_CODE, ("RESULT_CODE: " + code + " " + message).stripTrailing(), code,
                message, full);
    }

    /** 22는 하루 호출 한도(DAILY_LIMIT), 나머지는 인증키 오류(AUTH_KEY_ERROR)로 적는다. */
    public static TradeApiException stopCode(int code) {
        String reason = code == 22 ? "DAILY_LIMIT: 22" : "AUTH_KEY_ERROR: " + code;
        return new TradeApiException(Kind.STOP_CODE, reason, code, null, null);
    }

    public static TradeApiException parseError(String reason) {
        return new TradeApiException(Kind.PARSE_ERROR, "PARSE_ERROR: " + reason, -1, null, null);
    }

    public static TradeApiException selfStop(int calls) {
        return new TradeApiException(Kind.SELF_STOP, "SELF_STOP: " + calls, -1, null, null);
    }

    public Kind kind() {
        return kind;
    }

    /** 실행 전체를 멈춰야 하는 실패인가(멈추는 결과 코드, 스스로 멈춤). */
    public boolean isRunStopping() {
        return kind == Kind.STOP_CODE || kind == Kind.SELF_STOP;
    }

    /** fetch_range.failure_reason에 적는 "코드: 설명"(research R7). */
    public String failureReason() {
        return failureReason;
    }

    /** RESULT_CODE와 STOP_CODE일 때 숫자로 바꾼 결과 코드. 그 밖은 -1. */
    public int code() {
        return code;
    }

    /** RESULT_CODE일 때 결과 메시지(앞뒤 공백 제거, 200자까지). 그 밖은 null. */
    public String resultMessage() {
        return resultMessage;
    }

    /** 자르기 전 결과 메시지. 인증키를 가린 뒤 자르려고 TradeApiClient만 쓴다. */
    String fullResultMessage() {
        return fullResultMessage;
    }
}
