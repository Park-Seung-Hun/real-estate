package estate.trade.raw;

import estate.trade.CollectorProperties;
import estate.trade.ConditionalOnCollectorRegions;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** 원본 응답 파일 저장소 빈. 수집할 지역이 설정되어 있을 때만 만든다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnCollectorRegions
public class RawStoreConfiguration {

    @Bean(destroyMethod = "close")
    S3Client rawS3Client(CollectorProperties properties) {
        return s3Client(properties.raw());
    }

    @Bean
    RawResponseStore rawResponseStore(S3Client rawS3Client, CollectorProperties properties) {
        return new S3RawResponseStore(rawS3Client, properties.raw().bucket());
    }

    /** 테스트도 같은 설정으로 클라이언트를 만들 수 있게 정적으로 둔다. */
    public static S3Client s3Client(CollectorProperties.Raw raw) {
        return S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .endpointOverride(URI.create(raw.endpoint()))
                .region(Region.of(raw.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(raw.accessKey(), raw.secretKey())))
                .forcePathStyle(true)
                // 일부 S3 호환 저장소가 SDK 기본 체크섬(CRC32)을 거절해, 필요할 때만 쓴다(research R4)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }
}
