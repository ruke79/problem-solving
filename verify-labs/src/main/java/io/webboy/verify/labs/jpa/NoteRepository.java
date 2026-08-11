package io.webboy.verify.labs.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NoteRepository extends JpaRepository<Note, Long> {

    long countByText(String text);

    /** JPA-07: SELECT ... FOR UPDATE 로 행을 선점한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Note n where n.id = :id")
    Optional<Note> findByIdForUpdate(@Param("id") Long id);
}
