package estate.support;

import estate.trade.raw.RawResponseStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 저장한 키와 바이트와 저장 순번을 기록하고, 특정 키나 n번째 저장에서 실패를 주입하는 가짜 원본 저장소. */
public class RecordingRawResponseStore implements RawResponseStore {

    public record Saved(String key, byte[] body, long sequence) {
    }

    private final AtomicLong sequence;
    private final List<Saved> saved = new ArrayList<>();
    private Predicate<String> failOnKey = key -> false;
    private int failOnNth = -1;
    private RuntimeException runtimeFailure;
    private int attempts;
    private Consumer<String> onSave = key -> {
    };

    public RecordingRawResponseStore() {
        this(new AtomicLong());
    }

    /** 범위 반영 시점과 저장 순번을 비교할 수 있게 같은 카운터를 나눠 쓴다(research R10 순서 테스트). */
    public RecordingRawResponseStore(AtomicLong sharedSequence) {
        this.sequence = sharedSequence;
    }

    public synchronized RecordingRawResponseStore failOnKey(Predicate<String> predicate) {
        this.failOnKey = predicate;
        return this;
    }

    /** 1부터 세는 n번째 저장 시도를 실패시킨다. */
    public synchronized RecordingRawResponseStore failOnNthAttempt(int n) {
        this.failOnNth = n;
        return this;
    }

    /** 저장을 시도하는 순간(기록하기 전, 실패를 주입하기 전) 키를 받아 실행한다. 순서 확인용. */
    public synchronized RecordingRawResponseStore onSave(Consumer<String> callback) {
        this.onSave = callback;
        return this;
    }

    /** {@link #failOnKey}에 걸린 저장이 IOException 대신 이 예외를 던진다. */
    public synchronized RecordingRawResponseStore failWith(RuntimeException failure) {
        this.runtimeFailure = failure;
        return this;
    }

    @Override
    public synchronized void save(String key, byte[] responseBody) throws IOException {
        attempts++;
        onSave.accept(key);
        if (attempts == failOnNth || failOnKey.test(key)) {
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            throw new IOException("injected failure");
        }
        saved.add(new Saved(key, responseBody.clone(), sequence.incrementAndGet()));
    }

    public synchronized List<Saved> saved() {
        return List.copyOf(saved);
    }

    public synchronized List<String> savedKeys() {
        return saved.stream().map(Saved::key).toList();
    }

    public synchronized void clear() {
        saved.clear();
        attempts = 0;
        failOnKey = key -> false;
        failOnNth = -1;
        runtimeFailure = null;
        onSave = key -> {
        };
    }
}
