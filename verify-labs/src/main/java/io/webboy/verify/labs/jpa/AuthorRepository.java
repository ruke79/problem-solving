package io.webboy.verify.labs.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    @Query("select distinct a from Author a left join fetch a.books")
    List<Author> findAllWithBooks();

    /** API-05: DataLoader 가 모아 온 ID 들을 한 번에 조회한다. */
    @Query("select distinct a from Author a left join fetch a.books where a.id in :ids")
    List<Author> findAllWithBooks(@org.springframework.data.repository.query.Param("ids") List<Long> ids);
}
