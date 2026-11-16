package estate.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * {@code @IntegrationTest}에 가짜 구글 서버, 옮길 수 있는 Clock, SessionRows를 더한다. MockMvc는 {@code @AutoConfigureMockMvc}를 직접 붙인다.
 * 테스트마다 Clock, 가짜 서버 예약, 삭제 실패 트리거를 되돌리고 spring_session 행을 모두 지운다(사용자 행은 그대로).
 * 가짜 서버: {@code FakeGoogleServer.willReturn(...)}, 로그인: {@code new LoginClient(mockMvc)}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@IntegrationTest
@Import(TestSupportConfiguration.class)
@ExtendWith(LoginIntegrationTest.Reset.class)
public @interface LoginIntegrationTest {

    class Reset implements BeforeEachCallback, AfterEachCallback {
        @Override
        public void beforeEach(ExtensionContext context) {
            reset(context);
        }

        @Override
        public void afterEach(ExtensionContext context) {
            reset(context);
        }

        private void reset(ExtensionContext context) {
            var ctx = SpringExtension.getApplicationContext(context);
            ctx.getBean(MutableClock.class).reset();
            FakeGoogleServer.reset();
            ctx.getBean(SessionRows.class).clearDeleteFailures();
            ctx.getBean(SessionRows.class).deleteAll();
        }
    }
}
