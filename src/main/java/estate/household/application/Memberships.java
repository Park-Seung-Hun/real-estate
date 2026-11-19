package estate.household.application;

import org.springframework.stereotype.Component;

import estate.household.domain.HouseholdMemberRepository;

/** 다른 모듈이 부르는 멤버십 조회 공개 인터페이스. */
@Component
public class Memberships {

    private final HouseholdMemberRepository members;

    Memberships(HouseholdMemberRepository members) {
        this.members = members;
    }

    /** 엔티티를 읽지 않는 존재 확인이라, 호출 트랜잭션 안에서 부른 시점에 커밋된 멤버십을 본다. */
    public boolean hasMembership(long userId) {
        return members.existsByUserId(userId);
    }
}
