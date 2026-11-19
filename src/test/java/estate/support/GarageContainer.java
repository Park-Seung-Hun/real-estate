package estate.support;

import java.io.IOException;
import java.nio.file.Path;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * 테스트용 Garage(S3 호환 저장소) 컨테이너. compose/garage.toml과 같은 설정으로 뜨고,
 * 시작 뒤 레이아웃, 버킷 둘, 고정 키, 원본 응답 버킷 권한까지 준비한다.
 * 키는 원본 응답 버킷에만 읽기와 쓰기 권한이 있다(spec FR-023).
 */
public class GarageContainer extends GenericContainer<GarageContainer> {

    public static final String RAW_BUCKET = "test-raw";
    public static final String PHOTO_BUCKET = "test-photos";
    public static final String REGION = "garage";
    // 가짜 값. Garage 키 ID는 GK와 hex 24자, 비밀 키는 hex 64자 형식이다
    public static final String ACCESS_KEY = "GK0000000000000000000000aa";
    public static final String SECRET_KEY = "00000000000000000000000000000000000000000000000000000000000000bb";

    private static final int S3_PORT = 3900;

    public GarageContainer() {
        super("dxflrs/garage:v2.5.0");
        withExposedPorts(S3_PORT);
        withCopyFileToContainer(
                MountableFile.forHostPath(Path.of("compose", "garage.toml").toAbsolutePath()), "/etc/garage.toml");
        waitingFor(Wait.forLogMessage(".*S3 API server listening.*\\n", 1));
    }

    public String endpoint() {
        return "http://" + getHost() + ":" + getMappedPort(S3_PORT);
    }

    @Override
    public void start() {
        super.start();
        try {
            prepare();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Garage 준비에 실패했습니다", e);
        }
    }

    private void prepare() throws IOException, InterruptedException {
        String status = garage("status");
        String nodeId = status.lines()
                .map(String::trim)
                .filter(line -> line.matches("^[0-9a-f]{16}\\s.*"))
                .map(line -> line.substring(0, 16))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Garage 노드 ID를 찾지 못했습니다: " + status));
        garage("layout", "assign", "-z", "test", "-c", "1GB", nodeId);
        garage("layout", "apply", "--version", "1");
        for (String bucket : new String[] {RAW_BUCKET, PHOTO_BUCKET}) {
            createBucketWithRetry(bucket);
        }
        garage("key", "import", "--yes", "-n", "test-collector", ACCESS_KEY, SECRET_KEY);
        garage("bucket", "allow", RAW_BUCKET, "--key", ACCESS_KEY, "--read", "--write");
    }

    // 레이아웃을 적용한 직후에는 버킷 생성이 잠깐 실패할 수 있다
    private void createBucketWithRetry(String bucket) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                garage("bucket", "create", bucket);
                return;
            } catch (IllegalStateException e) {
                if (attempt >= 10) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
    }

    private String garage(String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "/garage";
        System.arraycopy(args, 0, command, 1, args.length);
        Container.ExecResult result = execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(
                    "garage " + String.join(" ", args) + " 실패: " + result.getStderr() + result.getStdout());
        }
        return result.getStdout();
    }
}
