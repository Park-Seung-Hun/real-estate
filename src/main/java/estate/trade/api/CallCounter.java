package estate.trade.api;

/**
 * 한 번의 실행 안에서 센 외부 API 호출 수(research R8). 실행마다 하나를 만들어 쓴다.
 * 재시도를 포함해 요청 한 번마다 요청하기 전에 {@link #beforeRequest()}를 부른다.
 */
public final class CallCounter {

    private final int selfStopCalls;
    private int calls;

    public CallCounter(int selfStopCalls) {
        this.selfStopCalls = selfStopCalls;
    }

    /** 호출 수가 기준값 미만이면 1 늘리고, 기준값에 도달했으면 늘리지 않고 SELF_STOP 예외를 던진다. */
    public synchronized void beforeRequest() {
        if (calls >= selfStopCalls) {
            throw TradeApiException.selfStop(calls);
        }
        calls++;
    }

    public synchronized int calls() {
        return calls;
    }
}
