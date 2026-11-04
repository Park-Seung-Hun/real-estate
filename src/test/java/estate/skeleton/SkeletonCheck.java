package estate.skeleton;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 기본 키를 저장 전에 시퀀스로 받아 넣는 방식을 확인하기 위한 엔티티. */
@Entity
@Table(name = "skeleton_check")
public class SkeletonCheck {

    @Id
    private Long id;

    private String name;

    protected SkeletonCheck() {
    }

    public SkeletonCheck(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
