package estate.trade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** spec US4-4, FR-001, FR-009: 설정 형식이 틀리면 애플리케이션이 시작하지 않는다. */
class CollectorPropertiesTest {

    private static final String SERVICE_KEY = "fake+key/with=chars";
    private static final String SECRET = "fake-secret-value";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(CollectorProperties.class)
    static class Config {
    }

    /** application.yml과 같은 기본값 위에 시험할 값을 얹는다. */
    private ApplicationContextRunner withDefaults(String... overrides) {
        List<String> values = new ArrayList<>(List.of(
                "estate.collector.cron=0 0 9 * * *",
                "estate.collector.base-url=http://localhost/fake",
                "estate.collector.daily-call-limit=10000",
                "estate.collector.self-stop-margin=500",
                "estate.collector.connect-timeout=5s",
                "estate.collector.read-timeout=30s"));
        values.addAll(List.of(overrides));
        return runner.withPropertyValues(values.toArray(String[]::new));
    }

    private static String[] withRegions(String regions) {
        return new String[] {
                "estate.collector.regions=" + regions,
                "estate.collector.service-key=" + SERVICE_KEY,
                "estate.collector.raw.endpoint=http://localhost:3900",
                "estate.collector.raw.region=garage",
                "estate.collector.raw.bucket=raw-fake",
                "estate.collector.raw.access-key=fake-access",
                "estate.collector.raw.secret-key=" + SECRET};
    }

    @Test
    void bindsCommaSeparatedRegionsIntoList() {
        withDefaults(withRegions("99999,99998")).run(context ->
                assertThat(context.getBean(CollectorProperties.class).regions()).containsExactly("99999", "99998"));
    }

    /** application.yml이 환경 변수 이름을 설정에 잇는지 본다. 값은 환경 변수 이름으로만 준다. */
    @Test
    void applicationYmlMapsEnvironmentVariables() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "ESTATE_COLLECTOR_REGIONS=99999,99998",
                        "DATA_GO_KR_SERVICE_KEY=" + SERVICE_KEY,
                        "ESTATE_COLLECTOR_CRON=0 30 7 * * *",
                        "ESTATE_RAW_S3_ENDPOINT=http://localhost:3999",
                        "ESTATE_RAW_S3_REGION=fake-region",
                        "ESTATE_RAW_S3_BUCKET=raw-fake",
                        "ESTATE_RAW_S3_ACCESS_KEY=fake-access",
                        "ESTATE_RAW_S3_SECRET_KEY=" + SECRET)
                .run(context -> {
                    CollectorProperties properties = context.getBean(CollectorProperties.class);
                    assertThat(properties.regions()).containsExactly("99999", "99998");
                    assertThat(properties.serviceKey()).isEqualTo(SERVICE_KEY);
                    assertThat(properties.cron()).isEqualTo("0 30 7 * * *");
                    assertThat(properties.raw()).extracting(CollectorProperties.Raw::endpoint,
                                    CollectorProperties.Raw::region, CollectorProperties.Raw::bucket,
                                    CollectorProperties.Raw::accessKey, CollectorProperties.Raw::secretKey)
                            .containsExactly("http://localhost:3999", "fake-region", "raw-fake", "fake-access",
                                    SECRET);
                });
    }

    @Test
    void bindsRegionsWithSpacesAroundComma() {
        withDefaults(withRegions("99999 , 99998")).run(context ->
                assertThat(context.getBean(CollectorProperties.class).regions()).containsExactly("99999", "99998"));
    }

    @Test
    void failsToStartOnNonDigitRegion() {
        withDefaults(withRegions("9999x")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(messagesOf(context.getStartupFailure())).contains("regions").contains("9999x");
        });
    }

    @Test
    void failsToStartOnFourDigitRegion() {
        withDefaults(withRegions("9999")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(messagesOf(context.getStartupFailure())).contains("regions").contains("9999");
        });
    }

    @Test
    void failsToStartOnDuplicateRegion() {
        withDefaults(withRegions("99999,99999")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(messagesOf(context.getStartupFailure())).contains("regions").contains("99999");
        });
    }

    @Test
    void failsToStartWhenRegionsExistButServiceKeyMissingWithoutLeakingValues() {
        String[] props = {
                "estate.collector.regions=99999",
                "estate.collector.raw.endpoint=http://localhost:3900",
                "estate.collector.raw.region=garage",
                "estate.collector.raw.bucket=raw-fake",
                "estate.collector.raw.access-key=fake-access",
                "estate.collector.raw.secret-key=" + SECRET};
        withDefaults(props).run(context -> {
            assertThat(context).hasFailed();
            String messages = messagesOf(context.getStartupFailure());
            assertThat(messages).contains("service-key");
            assertThat(messages).doesNotContain(SECRET);
        });
    }

    @Test
    void failsToStartWhenRawBucketSettingMissingWithoutLeakingServiceKey() {
        String[] props = {
                "estate.collector.regions=99999",
                "estate.collector.service-key=" + SERVICE_KEY,
                "estate.collector.raw.endpoint=http://localhost:3900",
                "estate.collector.raw.region=garage",
                "estate.collector.raw.access-key=fake-access",
                "estate.collector.raw.secret-key=" + SECRET};
        withDefaults(props).run(context -> {
            assertThat(context).hasFailed();
            String messages = messagesOf(context.getStartupFailure());
            assertThat(messages).contains("raw.bucket");
            assertThat(messages).doesNotContain(SERVICE_KEY).doesNotContain(SECRET);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"endpoint", "region", "bucket", "access-key", "secret-key"})
    void failsToStartWhenAnyRawSettingIsMissing(String missing) {
        String[] props = Arrays.stream(withRegions("99999"))
                .filter(p -> !p.startsWith("estate.collector.raw." + missing + "="))
                .toArray(String[]::new);
        withDefaults(props).run(context -> {
            assertThat(context).hasFailed();
            String messages = messagesOf(context.getStartupFailure());
            assertThat(messages).contains("raw." + missing).doesNotContain(SERVICE_KEY).doesNotContain(SECRET);
        });
    }

    @Test
    void toStringHidesSecrets() {
        withDefaults(withRegions("99999")).run(context -> {
            String text = context.getBean(CollectorProperties.class).toString();
            assertThat(text).contains("99999").doesNotContain(SERVICE_KEY).doesNotContain(SECRET)
                    .doesNotContain("fake-access");
        });
    }

    @Test
    void startsWithoutRegionsAndWithoutServiceKey() {
        withDefaults("estate.collector.regions=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CollectorProperties.class).regions()).isEmpty();
        });
        // 설정 항목 자체가 없어도 같다(001, 002 테스트가 영향받지 않음)
        withDefaults().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CollectorProperties.class).regions()).isEmpty();
        });
    }

    @Test
    void failsToStartWhenMarginIsNotBelowLimit() {
        withDefaults("estate.collector.self-stop-margin=10000").run(context -> assertThat(context).hasFailed());
        withDefaults("estate.collector.self-stop-margin=10001").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsToStartWhenLimitOrMarginIsNotPositive() {
        withDefaults("estate.collector.self-stop-margin=0").run(context -> assertThat(context).hasFailed());
        withDefaults("estate.collector.daily-call-limit=0", "estate.collector.self-stop-margin=1")
                .run(context -> assertThat(context).hasFailed());
        withDefaults("estate.collector.daily-call-limit=-1", "estate.collector.self-stop-margin=1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void acceptsMarginJustBelowLimitAndComputesSelfStopCalls() {
        withDefaults("estate.collector.self-stop-margin=9999").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CollectorProperties.class).selfStopCalls()).isEqualTo(1);
        });
        withDefaults().run(context ->
                assertThat(context.getBean(CollectorProperties.class).selfStopCalls()).isEqualTo(9500));
    }

    @Test
    void failsToStartOnNonPositiveTimeoutOrBadCron() {
        withDefaults("estate.collector.connect-timeout=0s").run(context -> assertThat(context).hasFailed());
        withDefaults("estate.collector.read-timeout=-1s").run(context -> assertThat(context).hasFailed());
        withDefaults("estate.collector.cron=not a cron").run(context -> assertThat(context).hasFailed());
        withDefaults("estate.collector.cron=-").run(context -> assertThat(context).hasNotFailed());
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
