package estate.trade.api;

import estate.trade.CollectorProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * 실거래 API 한 페이지를 요청하고 해석한다(research R1, R2, R8, R9).
 * 요청 URI와 인증키는 로그에 쓰지 않고, 던지는 예외에는 원인 예외 객체를 넘기지 않는다.
 */
public class TradeApiClient {

    private static final int NUM_OF_ROWS = 1000;

    /** 원본 응답 바이트(그대로 보관할 대상)와 해석한 페이지. */
    public record PageResult(byte[] rawBody, TradePage page) {
    }

    /** 200이 아닌데 Spring이 오류로 보지 않은 상태(3xx 등)를 재시도 대상 HTTP 오류로 바꾸는 예외. */
    private static final class UnexpectedStatusException extends HttpStatusCodeException {
        UnexpectedStatusException(HttpStatusCode status) {
            super(status);
        }
    }

    private final String baseUrl;
    private final String serviceKey;
    private final RestClient restClient;
    private final RetryTemplate retryTemplate;

    public TradeApiClient(CollectorProperties properties, RetryPolicy retryPolicy) {
        this.baseUrl = properties.baseUrl();
        this.serviceKey = properties.serviceKey();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.retryTemplate = new RetryTemplate(retryPolicy);
    }

    /**
     * 한 페이지를 요청한다. 재시도를 포함해 요청마다 호출 수를 센다.
     *
     * @throws TradeApiException 재시도를 모두 쓴 HTTP 또는 네트워크 오류, 결과 코드 오류, 해석 실패, 스스로 멈춤
     */
    public PageResult fetchPage(String lawdCd, String dealYmd, int pageNo, CallCounter counter) {
        URI uri = buildUri(lawdCd, dealYmd, pageNo);
        byte[] body;
        try {
            body = retryTemplate.invoke(() -> request(uri, counter));
        } catch (TradeApiException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw TradeApiException.httpError(e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw TradeApiException.networkError(
                    (e.getCause() != null ? e.getCause() : e).getClass().getSimpleName());
        } catch (RuntimeException e) {
            throw TradeApiException.networkError(e.getClass().getSimpleName());
        }
        try {
            return new PageResult(body, TradeResponseParser.parse(body, lawdCd, dealYmd));
        } catch (TradeApiException e) {
            if (e.kind() == TradeApiException.Kind.RESULT_CODE) {
                throw TradeApiException.resultCode(e.code(), maskServiceKey(e.fullResultMessage()));
            }
            throw e;
        }
    }

    /** 인증키를 URI 템플릿 변수로 넘겨 엄격 인코딩한다. 문자열이 아닌 URI 객체로 넘겨야 두 번 인코딩되지 않는다. */
    private URI buildUri(String lawdCd, String dealYmd, int pageNo) {
        try {
            return UriComponentsBuilder.fromUriString(baseUrl)
                    .queryParam("serviceKey", "{serviceKey}")
                    .queryParam("LAWD_CD", lawdCd)
                    .queryParam("DEAL_YMD", dealYmd)
                    .queryParam("pageNo", pageNo)
                    .queryParam("numOfRows", NUM_OF_ROWS)
                    .encode()
                    .buildAndExpand(Map.of("serviceKey", serviceKey))
                    .toUri();
        } catch (RuntimeException e) {
            throw TradeApiException.parseError("요청 주소를 만들지 못함");
        }
    }

    private byte[] request(URI uri, CallCounter counter) {
        counter.beforeRequest();
        ResponseEntity<byte[]> response = restClient.get().uri(uri).retrieve().toEntity(byte[].class);
        if (response.getStatusCode().value() != 200) {
            throw new UnexpectedStatusException(response.getStatusCode());
        }
        return response.getBody() == null ? new byte[0] : response.getBody();
    }

    /** 결과 메시지에 인증키(원문, 폼 인코딩, 엄격 인코딩)가 들어 있으면 가린다. */
    private String maskServiceKey(String message) {
        if (message == null || serviceKey.isEmpty()) {
            return message;
        }
        return message.replace(serviceKey, "***")
                .replace(URLEncoder.encode(serviceKey, StandardCharsets.UTF_8), "***")
                .replace(UriUtils.encode(serviceKey, StandardCharsets.UTF_8), "***");
    }
}
