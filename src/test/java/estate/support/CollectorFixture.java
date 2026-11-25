package estate.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** 수집 실행 테스트의 가짜 API 서버, 가짜 원본 저장소, 수집 테이블을 함께 되돌린다. */
public record CollectorFixture(FakeTradeApiServer server, RecordingRawResponseStore rawStore, JdbcClient jdbc) {

    /** 지정하지 않은 범위는 0건 정상 응답으로 두고, 원본 저장 기록과 수집 테이블 넷을 비운다. */
    public void reset() {
        server.reset();
        server.fallback(request -> FakeTradeApiServer.Reply.body(TradeFixtures.response()));
        rawStore.clear();
        jdbc.sql("delete from trade_history").update();
        jdbc.sql("delete from trade").update();
        jdbc.sql("delete from complex").update();
        jdbc.sql("delete from fetch_range").update();
    }
}
