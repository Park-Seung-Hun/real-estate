package estate.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 속성 이름은 환경 변수와 같은 ESTATE_COLUMN_KEYS, ESTATE_COLUMN_KEY_ACTIVE, ESTATE_LOGIN_COOKIE_KEY. 없으면 시작하지 않는다. */
@Configuration(proxyBeanMethods = false)
public class CryptoConfiguration {

    @Bean
    ColumnCipher columnCipher(@Value("${ESTATE_COLUMN_KEYS}") String keys,
            @Value("${ESTATE_COLUMN_KEY_ACTIVE}") String active) {
        return new ColumnCipher(keys, active);
    }

    @Bean
    LoginCookieKey loginCookieKey(@Value("${ESTATE_LOGIN_COOKIE_KEY}") String key) {
        return new LoginCookieKey(key);
    }
}
