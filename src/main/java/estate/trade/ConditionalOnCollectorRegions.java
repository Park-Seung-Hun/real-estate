package estate.trade;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 수집할 지역이 하나라도 설정되어 있을 때만 빈을 만든다(research R9).
 * 지역이 없으면 인증키와 S3 설정 없이도 애플리케이션이 시작해야 하기 때문이다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnCollectorRegions.Check.class)
public @interface ConditionalOnCollectorRegions {

    class Check implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return Binder.get(context.getEnvironment())
                    .bind("estate.collector.regions", Bindable.listOf(String.class))
                    .map(regions -> regions.stream().anyMatch(region -> !region.isBlank()))
                    .orElse(false);
        }
    }
}
