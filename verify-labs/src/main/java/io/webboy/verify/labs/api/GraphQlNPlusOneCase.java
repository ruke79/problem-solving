package io.webboy.verify.labs.api;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.jpa.Author;
import io.webboy.verify.labs.jpa.AuthorRepository;
import io.webboy.verify.labs.jpa.HibernateStats;
import io.webboy.verify.labs.jpa.SeedService;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Q11 — GraphQL 의 최대 함정인 N+1.
 *
 * <p>클라이언트가 필드를 고르는 구조라, 중첩 필드를 요청하면 <b>필드마다 데이터 페처가 호출된다</b>.
 * 순진하게 구현하면 작가 N 명의 책을 각각 조회해 1+N 쿼리가 나가고, DataLoader 로 묶으면
 * 한 번의 배치 조회로 줄어든다. {@code JPA-01}(REST/JPA 의 N+1)과 같은 병이지만 발생 지점이 다르다.
 */
@Component
public class GraphQlNPlusOneCase extends VerificationCase {

    private static final String SDL = """
            type Query { authors: [Author] }
            type Author { id: ID, name: String, books: [Book] }
            type Book { id: ID, title: String }
            """;

    private final AuthorRepository authors;
    private final SeedService seed;
    private final HibernateStats stats;
    private final TransactionTemplate tx;

    public GraphQlNPlusOneCase(AuthorRepository authors, SeedService seed,
                               HibernateStats stats, TransactionTemplate tx) {
        this.authors = authors;
        this.seed = seed;
        this.stats = stats;
        this.tx = tx;
    }

    @Override
    public String id() {
        return "API-05";
    }

    @Override
    public String category() {
        return "api";
    }

    @Override
    public String question() {
        return "GraphQL 과 REST 의 차이와 장단점을 설명해 주세요.";
    }

    @Override
    public String claim() {
        return "GraphQL 은 클라이언트가 필요한 필드만 고를 수 있어 오버페칭이 사라지지만, 중첩 필드마다 데이터 페처가 호출되어 N+1 이 구조적으로 발생한다. DataLoader 로 같은 depth 의 요청을 배치로 묶는 것이 사실상 전제 조건이다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 영속성 컨텍스트 캐시 상태에 따라 쿼리 수가 흔들릴 수 있다
    }

    @Override
    protected void verify(Evidence evidence) {
        seed.ensureSeeded();
        String query = "{ authors { name books { title } } }";

        long naiveStatements = stats.countStatements(() -> execute(build(false), query));
        long batchedStatements = stats.countStatements(() -> execute(build(true), query));

        ExecutionResult result = execute(build(true), query);
        int authorCount = ((List<?>) ((Map<?, ?>) result.getData()).get("authors")).size();

        evidence.fact("작가 수 / 작가당 책 수", SeedService.AUTHORS + " / " + SeedService.BOOKS_PER_AUTHOR);
        evidence.fact("GraphQL 질의", query);
        evidence.fact("[순진한 페처] 발생한 SQL 문 수", naiveStatements);
        evidence.fact("[DataLoader 배치] 발생한 SQL 문 수", batchedStatements);
        evidence.fact("반환된 작가 수", authorCount);
        evidence.fact("GraphQL 오류", result.getErrors().isEmpty() ? "(없음)" : result.getErrors().toString());

        evidence.expect("두 방식 모두 같은 결과를 돌려준다", authorCount == SeedService.AUTHORS);
        evidence.expect("순진한 구현은 작가 수만큼 추가 쿼리가 나간다(1+N)",
                naiveStatements >= SeedService.AUTHORS);
        evidence.expect("DataLoader 로 묶으면 쿼리 수가 줄어든다", batchedStatements < naiveStatements);

        evidence.note("REST 라면 엔드포인트마다 쿼리를 최적화하면 되지만, GraphQL 은 클라이언트가 조합을 정하므로 '어떤 조합이 올지 모른다'. 그래서 필드 단위 배치(DataLoader)가 선택이 아니라 기본 구조가 된다.");
        evidence.note("깊은 중첩을 허용하면 악의적 질의 하나로 서버를 무너뜨릴 수 있다 — 쿼리 깊이 제한과 복잡도(비용) 제한이 필수다. 이것도 REST 에는 없던 운영 항목이다.");
        evidence.note("HTTP 캐시가 잘 듣지 않는 것도 대가다. 단일 엔드포인트에 POST 로 질의하므로 CDN·브라우저 캐시 전략을 다시 짜야 한다.");
        evidence.note("JPA-01 의 N+1 과 원인은 같다 — '한 번에 가져올 수 있는 것을 건건이 가져온다'. 해결 도구가 fetch join/@BatchSize 냐 DataLoader 냐만 다르다.");
    }

    /** @param batched DataLoader 로 배치 조회할지, 작가마다 개별 조회할지 */
    private GraphQL build(boolean batched) {
        DataFetcher<List<Author>> authorsFetcher = env -> authors.findAll();

        DataFetcher<Object> booksFetcher = env -> {
            Author author = env.getSource();
            if (!batched) {
                // 순진한 구현: 작가마다 책을 따로 조회한다 → 1 + N
                return tx.execute(status -> authors.findById(author.getId())
                        .map(found -> found.getBooks().stream().map(BookView::of).toList())
                        .orElse(List.of()));
            }
            DataLoader<Long, List<BookView>> loader = env.getDataLoader("books");
            return loader.load(author.getId());
        };

        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .type("Query", builder -> builder.dataFetcher("authors", authorsFetcher))
                .type("Author", builder -> builder.dataFetcher("books", booksFetcher))
                .build();

        TypeDefinitionRegistry registry = new SchemaParser().parse(SDL);
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, wiring);
        return GraphQL.newGraphQL(schema).build();
    }

    private ExecutionResult execute(GraphQL graphQl, String query) {
        DataLoaderRegistry registry = new DataLoaderRegistry();
        registry.register("books", DataLoaderFactory.newDataLoader(this::loadBooksInBatch));

        return graphQl.execute(ExecutionInput.newExecutionInput(query)
                .dataLoaderRegistry(registry)
                .build());
    }

    /** 같은 depth 의 작가 ID 를 모아 한 번에 조회한다 — 이것이 DataLoader 의 전부다. */
    private CompletableFuture<List<List<BookView>>> loadBooksInBatch(List<Long> authorIds) {
        return CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            Map<Long, List<BookView>> byAuthor = authors.findAllWithBooks(authorIds).stream()
                    .collect(Collectors.toMap(Author::getId,
                            author -> author.getBooks().stream().map(BookView::of).toList()));
            return authorIds.stream().map(id -> byAuthor.getOrDefault(id, List.of())).toList();
        }));
    }

    /** GraphQL 이 읽는 최소 형태 — 엔티티를 그대로 노출하지 않는다. */
    public record BookView(String id, String title) {
        static BookView of(io.webboy.verify.labs.jpa.Book book) {
            return new BookView(String.valueOf(book.getId()), book.getTitle());
        }
    }
}
