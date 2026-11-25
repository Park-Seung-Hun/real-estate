package estate.trade.api;

import estate.trade.CollectorProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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

    /** 한 번에 요청하는 건수. 마지막 페이지 번호를 계산하는 쪽도 이 값을 쓴다. */
    public static final int NUM_OF_ROWS = 1000;

    /** 원본 응답 바이트(그대로 보관할 대상)와 해석한 페이지. */
    public record PageResult(byte[] rawBody, TradePage page) {
    }

    /** 200이 아닌 상태를 상태 코드만 담은 재시도 대상 HTTP 오류로 바꾸는 예외. 응답 본문과 헤더는 담지 않는다. */
    private static final class StatusOnlyException extends HttpStatusCodeException {
        StatusOnlyException(HttpStatusCode status) {
            super(status);
        }
    }

    /** 네트워크 오류를 원인 예외의 클래스 이름만 담은 재시도 대상 예외로 바꾼다. 원인 객체는 담지 않는다. */
    private static final class CauseNameOnlyException extends ResourceAccessException {
        private final String causeName;

        CauseNameOnlyException(String causeName) {
            super("네트워크 오류: " + causeName);
            this.causeName = causeName;
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
        } catch (StatusOnlyException e) {
            throw TradeApiException.httpError(e.getStatusCode().value());
        } catch (CauseNameOnlyException e) {
            throw TradeApiException.networkError(e.causeName);
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
        ResponseEntity<byte[]> response;
        // RetryTemplate은 받은 예외를 원인까지 DEBUG 로그에 남긴다. 예외 메시지에는 응답 본문이나 서버가 보낸 글자가
        // 담길 수 있어, RuntimeException은 어느 것도 그대로 밖으로 내보내지 않는다
        try {
            response = restClient.get().uri(uri).retrieve().toEntity(byte[].class);
        } catch (RestClientResponseException e) {
            throw new StatusOnlyException(e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new CauseNameOnlyException((e.getCause() != null ? e.getCause() : e).getClass().getSimpleName());
        } catch (RuntimeException e) {
            throw TradeApiException.networkError(e.getClass().getSimpleName());
        }
        if (response.getStatusCode().value() != 200) {
            throw new StatusOnlyException(response.getStatusCode());
        }
        return response.getBody() == null ? new byte[0] : response.getBody();
    }

    /** 결과 메시지에 인증키(원문, 폼 인코딩, 엄격 인코딩, 이중 인코딩)가 들어 있으면 가린다. 16진수 대소문자는 서버마다 다르다. */
    private String maskServiceKey(String message) {
        if (message == null || serviceKey.isEmpty()) {
            return message;
        }
        String masked = message;
        String formEncoded = URLEncoder.encode(serviceKey, StandardCharsets.UTF_8);
        String strictEncoded = UriUtils.encode(serviceKey, StandardCharsets.UTF_8);
        for (String form : List.of(serviceKey, formEncoded, strictEncoded,
                URLEncoder.encode(formEncoded, StandardCharsets.UTF_8))) {
            masked = Pattern.compile(Pattern.quote(form), Pattern.CASE_INSENSITIVE).matcher(masked).replaceAll("***");
        }
        return masked;
    }
}
