package estate.household.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 컨트롤러 메서드가 요구하는 권한 키와 멤버십 규칙(research R2). 계약의 x-permission과 같은 값을 적는다. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Access {

    Permission value();

    MembershipRule membership() default MembershipRule.REQUIRED;
}
