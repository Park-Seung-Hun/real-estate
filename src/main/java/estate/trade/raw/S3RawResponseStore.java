package estate.trade.raw;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 원본 응답 바이트를 gzip으로 압축해 S3 호환 저장소의 원본 응답 버킷에 올린다. */
public class S3RawResponseStore implements RawResponseStore {

    private final S3Client client;
    private final String bucket;

    public S3RawResponseStore(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void save(String key, byte[] responseBody) throws IOException {
        try {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(responseBody);
            }
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("application/gzip")
                            .build(),
                    RequestBody.fromBytes(compressed.toByteArray()));
        } catch (SdkException | IOException e) {
            // 요청 주소와 키 값이 메시지에 실리지 않게 원인 예외를 넘기지 않고 종류만 남긴다
            String status = e instanceof S3Exception s3 ? ", HTTP " + s3.statusCode() : "";
            throw new IOException("원본 응답 파일 저장에 실패했습니다: " + e.getClass().getSimpleName() + status);
        }
    }
}
