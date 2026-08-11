package io.webboy.verify.labs.jpa;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/** {@code @BatchSize} 효과 측정용. Author 와 구조는 같고 배치 힌트만 다르다. */
@Entity
public class Publisher {

    static final int BATCH_SIZE = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @BatchSize(size = BATCH_SIZE)
    @OneToMany(mappedBy = "publisher", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Magazine> magazines = new ArrayList<>();

    protected Publisher() {
    }

    public Publisher(String name) {
        this.name = name;
    }

    public void addMagazine(Magazine magazine) {
        magazines.add(magazine);
        magazine.setPublisher(this);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<Magazine> getMagazines() {
        return magazines;
    }
}
