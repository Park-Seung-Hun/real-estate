package estate.trade.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import estate.support.GarageContainer;
import estate.trade.CollectorProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 실제 S3 호환 저장소(Garage 컨테이너)에 올리고 내려받아 확인한다(research R4). */
class S3RawResponseStoreTest {

    private static final GarageContainer GARAGE = new GarageContainer();
    private static S3Client client;
    private static S3RawResponseStore store;

    private final byte[] body = "<response><items/></response>".repeat(50).getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void startGarage() {
        GARAGE.start();
        client = RawStoreConfiguration.s3Client(new CollectorProperties.Raw(
                GARAGE.endpoint(), GarageContainer.REGION, GarageContainer.RAW_BUCKET,
                GarageContainer.ACCESS_KEY, GarageContainer.SECRET_KEY));
        store = new S3RawResponseStore(client, GarageContainer.RAW_BUCKET);
    }

    @AfterAll
    static void stopGarage() {
        if (client != null) {
            client.close();
        }
        GARAGE.stop();
    }

    @Test
    void 응답을_gzip으로_올리고_내려받아_압축을_풀면_원래_바이트와_같다() throws IOException {
        String key = "2026-11-20/090000/99999-202611-p1.xml.gz";

        store.save(key, body);

        byte[] stored = client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(GarageContainer.RAW_BUCKET).key(key).build()).asByteArray();
        assertThat(stored).isNotEqualTo(body);
        assertThat(gunzip(stored)).isEqualTo(body);
    }

    @Test
    void Content_Type은_application_gzip이다() throws IOException {
        String key = "2026-11-20/090000/99999-202611-p2.xml.gz";

        store.save(key, body);

        assertThat(client.headObject(HeadObjectRequest.builder().bucket(GarageContainer.RAW_BUCKET).key(key).build())
                .contentType()).isEqualTo("application/gzip");
    }

    @Test
    void collector_키로_사진_버킷에_쓰면_거절된다() {
        assertThatThrownBy(() -> client.putObject(
                PutObjectRequest.builder().bucket(GarageContainer.PHOTO_BUCKET).key("photo.jpg").build(),
                RequestBody.fromBytes(body)))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
    }

    @Test
    void 저장_실패는_주소와_키를_담지_않은_IOException으로_바뀐다() {
        S3RawResponseStore broken = new S3RawResponseStore(client, "no-such-bucket");
        String key = "2026-11-20/090000/99999-202611-p3.xml.gz";

        assertThatThrownBy(() -> broken.save(key, body))
                .isInstanceOf(IOException.class)
                .hasNoCause()
                .message().doesNotContain(key).doesNotContain(GARAGE.endpoint()).doesNotContain("no-such-bucket");
    }

    private static byte[] gunzip(byte[] gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }
}
