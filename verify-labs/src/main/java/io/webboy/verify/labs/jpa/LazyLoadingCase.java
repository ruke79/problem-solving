package io.webboy.verify.labs.jpa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.hibernate.LazyInitializationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Component
public class LazyLoadingCase extends VerificationCase {

    private final AuthorRepository authors;
    private final SeedService seed;
    private final TransactionTemplate tx;

    public LazyLoadingCase(AuthorRepository authors, SeedService seed, TransactionTemplate tx) {
        this.authors = authors;
        this.seed = seed;
        this.tx = tx;
    }

    @Override
    public String id() {
        return "JPA-03";
    }

    @Override
    public String category() {
        return "jpa";
    }

    @Override
    public String question() {
        return "LazyInitializationException 은 언제 발생하고 OSIV 와 어떤 관계입니까?";
    }

    @Override
    public String claim() {
        return "영속성 컨텍스트가 닫힌 뒤 지연 로딩 프록시를 건드리면 예외가 난다. OSIV=false 면 트랜잭션 밖에서 재현된다";
    }

    @Override
    protected void verify(Evidence evidence) {
        seed.ensureSeeded();

        Author detached = tx.execute(status -> authors.findAll().get(0));

        String lazyOutcome;
        try {
            int size = detached.getBooks().size();
            lazyOutcome = "로딩 성공 (size=" + size + ")";
        } catch (LazyInitializationException e) {
            lazyOutcome = "LazyInitializationException";
        }

        List<Author> fetched = tx.execute(status -> authors.findAllWithBooks());
        String fetchJoinOutcome;
        try {
            int size = fetched.get(0).getBooks().size();
            fetchJoinOutcome = "로딩 성공 (size=" + size + ")";
        } catch (LazyInitializationException e) {
            fetchJoinOutcome = "LazyInitializationException";
        }

        evidence.fact("트랜잭션 밖 지연 컬렉션 접근", lazyOutcome);
        evidence.fact("fetch join 으로 미리 로딩한 뒤 접근", fetchJoinOutcome);

        evidence.expectEquals("준영속 상태에서 지연 로딩은 실패한다", "LazyInitializationException", lazyOutcome);
        evidence.expect("fetch join 으로 로딩해 두면 트랜잭션 밖에서도 안전하다",
                fetchJoinOutcome.startsWith("로딩 성공"));

        evidence.note("spring.jpa.open-in-view=true(기본값)면 이 예외가 숨겨지는 대신 커넥션을 뷰 렌더링까지 붙잡는다.");
        evidence.note("이 랩은 open-in-view=false 로 설정해 두었다 — 그래서 예외가 그대로 드러난다.");
    }
}
