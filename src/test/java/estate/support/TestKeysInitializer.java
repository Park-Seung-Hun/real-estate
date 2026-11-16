package estate.support;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 테스트 컨텍스트용 암호화 키를 실행 중에 무작위로 만든다(저장소에 키 값을 두지 않는다). */
public class TestKeysInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static String randomKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testKeys", Map.of(
                "ESTATE_COLUMN_KEYS", "1:" + randomKey(),
                "ESTATE_COLUMN_KEY_ACTIVE", "1",
                "ESTATE_LOGIN_COOKIE_KEY", randomKey())));
    }
}
