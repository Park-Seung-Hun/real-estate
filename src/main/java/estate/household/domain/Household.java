package estate.household.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/** 가구(data-model `household`). 이름 열이 없어 기본 키만 있다. */
@Entity
@Table(name = "household")
public class Household {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "household_id_seq")
    @SequenceGenerator(name = "household_id_seq", sequenceName = "household_id_seq", allocationSize = 1)
    private Long id;

    public Long getId() {
        return id;
    }
}
