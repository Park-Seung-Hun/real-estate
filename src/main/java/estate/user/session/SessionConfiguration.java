package estate.user.session;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.SessionIdGenerator;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 세션 ID 해시 변환과 세션 쿠키, 세션 저장소 트랜잭션(research R3, R4). */
@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {

    @Bean
    SessionIdGenerator sessionIdGenerator() {
        return new HashedSessionIdGenerator();
    }

    @Bean
    HttpSessionIdResolver httpSessionIdResolver() {
        return new HashedCookieSessionIdResolver();
    }

    // 기본은 REQUIRES_NEW라 로그인 콜백의 사용자 처리와 한 트랜잭션이 되지 않는다.
    // 이 빈이 TransactionOperations 타입이라 Boot 기본 transactionTemplate(같은 REQUIRED)은 만들어지지 않는다
    @Bean
    TransactionTemplate springSessionTransactionOperations(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }
}
