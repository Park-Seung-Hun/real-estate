package estate.trade.collect;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.postgresql.util.PSQLException;
import org.springframework.stereotype.Component;

import estate.trade.ConditionalOnCollectorRegions;
import estate.trade.api.CallCounter;
import estate.trade.api.TradeApiClient;
import estate.trade.api.TradeApiClient.PageResult;
import estate.trade.api.TradeApiException;
import estate.trade.api.TradeItem;
import estate.trade.detect.ApplyResult;
import estate.trade.detect.FetchRangeStore;
import estate.trade.detect.RangeApplier;
import estate.trade.detect.RangeInput;
import estate.trade.detect.RunSnapshot;
import estate.trade.detect.RunSnapshot.Range;
import estate.trade.detect.TradeStore;
import estate.trade.raw.RawObjectKey;
import estate.trade.raw.RawResponseStore;

/**
 * 범위 하나를 수집한다: 모든 페이지를 수집하고, 원본을 저장하고, 건수를 확인한 뒤 범위 반영을 부른다(research R7). 외부 API 호출과
 * 원본 저장은 트랜잭션 밖이다. 그 범위만의 실패는 여기서 기록하고, 실행을 멈추는 실패는 기록하지 않고 호출한 쪽에 던진다.
 */
@Component
@ConditionalOnCollectorRegions
class RangeCollector {

    /** @param failureReason 성공이면 null. @param applied 실패면 null */
    record Outcome(String failureReason, ApplyResult applied) {
    }

    private final TradeApiClient apiClient;
    private final RawResponseStore rawStore;
    private final RangeApplier applier;
    private final TradeStore tradeStore;
    private final FetchRangeStore fetchRangeStore;

    RangeCollector(TradeApiClient apiClient, RawResponseStore rawStore, RangeApplier applier, TradeStore tradeStore,
            FetchRangeStore fetchRangeStore) {
        this.apiClient = apiClient;
        this.rawStore = rawStore;
        this.applier = applier;
        this.tradeStore = tradeStore;
        this.fetchRangeStore = fetchRangeStore;
    }

    /** @throws TradeApiException 실행을 멈추는 실패(멈추는 결과 코드, 스스로 멈춤) */
    Outcome collect(Range range, RunSnapshot snapshot, CallCounter counter) {
        List<PageResult> pages;
        try {
            pages = fetchAllPages(range, counter);
        } catch (TradeApiException e) {
            if (e.isRunStopping()) {
                throw e;
            }
            return failed(range, e.failureReason());
        }

        // 건수 확인보다 먼저 저장한다. 건수가 다른 범위도 원본이 남아 원인을 조사할 수 있다(FR-010, FR-011)
        try {
            saveRaw(range, snapshot.runStartedAt(), pages);
        } catch (IOException | RuntimeException e) {
            return failed(range, "RAW_SAVE_FAILED: " + e.getClass().getSimpleName());
        }

        int totalCount = pages.getFirst().page().totalCount();
        List<TradeItem> items = pages.stream().flatMap(page -> page.page().items().stream()).toList();
        List<Integer> totals = pages.stream().map(page -> page.page().totalCount()).distinct().toList();
        if (totals.size() > 1 || items.size() != totalCount) {
            return failed(range, "COUNT_MISMATCH: 수집 " + items.size() + ", 전체 " + totals);
        }

        // 범위 반영의 트랜잭션 프록시 밖에서 받아야 커밋 때의 오류도 잡히고, 실패 기록이 롤백에 휩쓸리지 않는다
        try {
            if (!items.isEmpty() || !tradeStore.hasTrades(range.lawdCd(), range.dealYmd())) {
                return new Outcome(null,
                        applier.apply(new RangeInput(range.lawdCd(), range.dealYmd(), items, snapshot)));
            }
        } catch (RuntimeException e) {
            return failed(range, applyFailure(e));
        }
        // try 밖에서 기록한다. 안에 두면 이 기록의 실패가 APPLY_FAILED로 바뀌고 실행이 이어진다
        return failed(range, "EMPTY_AFTER_TRADES");
    }

    /** 첫 응답의 전체 건수로 마지막 페이지를 정한다. 페이지마다 전체 건수가 다른지는 뒤에서 확인한다. */
    private List<PageResult> fetchAllPages(Range range, CallCounter counter) {
        List<PageResult> pages = new ArrayList<>();
        pages.add(apiClient.fetchPage(range.lawdCd(), range.dealYmd(), 1, counter));
        int lastPage = Math.ceilDiv(pages.getFirst().page().totalCount(), TradeApiClient.NUM_OF_ROWS);
        for (int pageNo = 2; pageNo <= lastPage; pageNo++) {
            pages.add(apiClient.fetchPage(range.lawdCd(), range.dealYmd(), pageNo, counter));
        }
        return pages;
    }

    private void saveRaw(Range range, Instant runStartedAt, List<PageResult> pages) throws IOException {
        for (int i = 0; i < pages.size(); i++) {
            rawStore.save(RawObjectKey.of(runStartedAt, range.lawdCd(), range.dealYmd(), i + 1),
                    pages.get(i).rawBody());
        }
    }

    private Outcome failed(Range range, String reason) {
        fetchRangeStore.markFailure(range.lawdCd(), range.dealYmd(), reason);
        return new Outcome(reason, null);
    }

    /** 예외 메시지에는 SQL 값이 담길 수 있어 클래스 이름과 제약 이름만 남긴다(research R7). */
    private static String applyFailure(RuntimeException e) {
        String reason = "APPLY_FAILED: " + e.getClass().getSimpleName();
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException sql && sql.getServerErrorMessage() != null
                    && sql.getServerErrorMessage().getConstraint() != null) {
                return reason + " " + sql.getServerErrorMessage().getConstraint();
            }
        }
        return reason;
    }
}
