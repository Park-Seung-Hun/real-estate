package estate.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 가짜 실거래 API 서버(JDK HttpServer, 빈 포트로 시작). 사용법:
 *
 * <pre>
 * try (FakeTradeApiServer server = FakeTradeApiServer.start()) {
 *     server.enqueue("99999", "202609", 1, Reply.status(500), Reply.status(500), Reply.body(xml));
 *     // CollectorProperties의 baseUrl을 server.baseUrl()로 준다
 *     server.requests();   // 받은 요청을 받은 순서대로(인증키가 든 쿼리 포함)
 * }
 * </pre>
 *
 * <ul>
 * <li>{@link #enqueue}는 (LAWD_CD, DEAL_YMD, pageNo)마다 응답을 순서대로 지정한다. 지정한 응답을 다 쓰면 마지막 응답을
 * 계속 돌려준다(세 번 모두 실패하는 경우를 한 줄로 쓰기 위해). 지정하지 않은 조합은 HTTP 404.</li>
 * <li>{@link #onRequest}는 요청을 받는 순간(응답하기 전) 실행할 콜백이다. 콜백이 던진 예외는 무시한다.</li>
 * </ul>
 */
public final class FakeTradeApiServer implements AutoCloseable {

    /** 서버가 보낼 응답이나 동작. */
    public record Reply(int status, byte[] body, boolean dropConnection, Duration delay) {

        /** HTTP 200과 본문. */
        public static Reply body(byte[] body) {
            return new Reply(200, body, false, Duration.ZERO);
        }

        /** 본문 없이 이 상태 코드(예: 500). */
        public static Reply status(int status) {
            return new Reply(status, new byte[0], false, Duration.ZERO);
        }

        /** 응답 없이 연결을 끊는다. */
        public static Reply disconnect() {
            return new Reply(0, new byte[0], true, Duration.ZERO);
        }

        /** 이 시간을 기다린 뒤 응답한다(읽기 타임아웃보다 길게 주면 시간 초과를 낸다). */
        public Reply after(Duration delay) {
            return new Reply(status, body, dropConnection, delay);
        }
    }

    /**
     * 받은 요청. {@link #query()}는 서버가 디코딩한 값, {@link #rawQuery()}는 받은 그대로의 쿼리 문자열이다.
     *
     * @param sequence 1부터 세는 받은 순서
     */
    public record Request(int sequence, String lawdCd, String dealYmd, int pageNo, Map<String, String> query,
            String rawQuery) {

        /** 서버가 디코딩한 인증키. */
        public String serviceKey() {
            return query.get("serviceKey");
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Deque<Reply>> replies = new HashMap<>();
    private final Map<String, Reply> lastReplies = new HashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private Consumer<Request> onRequest = request -> {
    };

    private FakeTradeApiServer(HttpServer server) {
        this.server = server;
    }

    public static FakeTradeApiServer start() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        FakeTradeApiServer fake = new FakeTradeApiServer(httpServer);
        httpServer.createContext("/", fake::handle);
        httpServer.setExecutor(fake.executor);
        httpServer.start();
        return fake;
    }

    /** CollectorProperties의 baseUrl로 쓸 주소(쿼리 없음). */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/getRTMSDataSvcAptTradeDev";
    }

    /** 이 (LAWD_CD, DEAL_YMD, pageNo)에 대한 응답을 요청 순서대로 지정한다. */
    public synchronized void enqueue(String lawdCd, String dealYmd, int pageNo, Reply... sequence) {
        replies.computeIfAbsent(key(lawdCd, dealYmd, pageNo), k -> new ArrayDeque<>()).addAll(List.of(sequence));
    }

    public synchronized void onRequest(Consumer<Request> callback) {
        this.onRequest = callback;
    }

    /** 받은 요청의 복사본(받은 순서). */
    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    public synchronized int requestCount() {
        return requests.size();
    }

    /** 이 범위와 페이지로 받은 요청 수. */
    public synchronized long requestCount(String lawdCd, String dealYmd, int pageNo) {
        return requests.stream().filter(r -> r.lawdCd().equals(lawdCd) && r.dealYmd().equals(dealYmd)
                && r.pageNo() == pageNo).count();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static String key(String lawdCd, String dealYmd, int pageNo) {
        return lawdCd + "/" + dealYmd + "/" + pageNo;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            Map<String, String> query = decode(rawQuery);
            int pageNo = parseInt(query.get("pageNo"));
            String key = key(query.get("LAWD_CD"), query.get("DEAL_YMD"), pageNo);
            Request request;
            Reply reply;
            Consumer<Request> callback;
            synchronized (this) {
                request = new Request(requests.size() + 1, query.get("LAWD_CD"), query.get("DEAL_YMD"), pageNo,
                        query, rawQuery);
                requests.add(request);
                callback = onRequest;
                Deque<Reply> queue = replies.get(key);
                if (queue != null && !queue.isEmpty()) {
                    lastReplies.put(key, queue.poll());
                }
                reply = lastReplies.getOrDefault(key, Reply.status(404));
            }
            try {
                callback.accept(request);
            } catch (RuntimeException ignored) {
                // 콜백의 실패가 응답을 바꾸지 않는다
            }
            if (reply.dropConnection()) {
                exchange.getRequestBody().close();
                // 응답 헤더를 보내지 않고 닫아 연결을 끊는다
                return;
            }
            if (!reply.delay().isZero()) {
                try {
                    Thread.sleep(reply.delay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/xml;charset=UTF-8");
            exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
            if (reply.body().length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(reply.body());
                }
            }
        } catch (IOException ignored) {
            // 클라이언트가 먼저 끊은 경우(읽기 타임아웃)
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Map<String, String> decode(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            query.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }
}
