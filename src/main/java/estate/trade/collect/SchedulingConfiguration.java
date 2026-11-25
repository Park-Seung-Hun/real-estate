package estate.trade.collect;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 예약 실행을 켠다. 겹침은 막지 않는다: 기본 스케줄러가 스레드 하나라 같은 작업이 겹치지 않는다(research R3). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {
}
