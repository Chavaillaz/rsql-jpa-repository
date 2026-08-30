package com.chavaillaz.jakarta.persistence.repository.rsql;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BOURBON_POINTU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.KONA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.rsql.RsqlQueries.defaultCountVisitor;
import static com.chavaillaz.jakarta.persistence.repository.rsql.RsqlQueries.defaultQueryVisitor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import cz.jirutka.rsql.parser.RSQLParser;
import cz.jirutka.rsql.parser.ast.Node;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.Cursor;
import com.chavaillaz.jakarta.persistence.repository.CursorCodec;
import com.chavaillaz.jakarta.persistence.repository.CursorKeyCodec;
import com.chavaillaz.jakarta.persistence.repository.CursorResult;
import com.chavaillaz.jakarta.persistence.repository.EntityOrdering;
import com.chavaillaz.jakarta.persistence.repository.HibernateTest;
import com.chavaillaz.jakarta.persistence.repository.Pageable;
import com.chavaillaz.jakarta.persistence.repository.PaginationResult;
import com.chavaillaz.jakarta.persistence.repository.Sort;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

@DisplayName("RsqlQueries")
class RsqlQueriesTest extends HibernateTest {

    private static final RSQLParser PARSER = new RSQLParser();

    private static final BiFunction<CriteriaBuilder, Root<CoffeeEntity>, List<Order>> BY_NAME =
            (builder, root) -> List.of(builder.asc(root.get("name")));

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    private static Node parse(String rsql) {
        return PARSER.parse(rsql);
    }

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
    }

    private <T> T withQueries(Function<RsqlQueries<CoffeeEntity>, T> action) {
        return withQueries(Map.of(), action);
    }

    private <T> T withQueries(Map<String, String> searchableProperties, Function<RsqlQueries<CoffeeEntity>, T> action) {
        return inTransaction(entityManager -> {
            EntityOrdering<CoffeeEntity> ordering =
                    new EntityOrdering<>(entityManager, CoffeeEntity.class, BY_NAME, () -> searchableProperties);
            return action.apply(new RsqlQueries<>(entityManager, CoffeeEntity.class, PARSER,
                    () -> defaultQueryVisitor(CoffeeEntity.class),
                    () -> defaultCountVisitor(CoffeeEntity.class),
                    ordering, CursorCodec.DEFAULT, CursorKeyCodec.DEFAULT));
        });
    }

    @Test
    @DisplayName("parses and executes a query")
    void parsesAndExecutes() {
        PaginationResult<CoffeeEntity> result = withQueries(queries -> queries.search(queries.parse("strength=ge=6"), Pageable.UNPAGED));

        assertThat(Coffees.namesOf(result)).containsExactly(HARRAR, KONA, SIDAMO);
    }

    @Test
    @DisplayName("orders the results with the ordering rules of the repository")
    void ordersWithTheRepositoryRules() {
        PaginationResult<CoffeeEntity> result = withQueries(queries -> queries.search(parse("origin==" + ETHIOPIA), Pageable.of(0, 2, Sort.parse("-strength"))));

        assertThat(Coffees.namesOf(result)).containsExactly(HARRAR, SIDAMO);
        assertThat(result.totalItems()).isEqualTo(3);
    }

    @Test
    @DisplayName("counts distinct entities when the query joins a collection")
    void countsDistinct() {
        assertThat((long) withQueries(queries -> queries.count(parse("notes.flavour==Citrus"))))
                .isEqualTo(3);
        assertThat((long) withQueries(queries -> queries.count(parse("notes.flavour==Citrus,notes.flavour==Floral"))))
                .as("Yirgacheffe has both notes and must be counted once")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("detects a collection join, directly or through another join")
    void detectsACollectionJoin() {
        inTransaction(entityManager -> {
            RsqlQueries<CoffeeEntity> queries = new RsqlQueries<>(
                    entityManager,
                    CoffeeEntity.class,
                    PARSER,
                    () -> defaultQueryVisitor(CoffeeEntity.class),
                    () -> defaultCountVisitor(CoffeeEntity.class),
                    new EntityOrdering<>(entityManager, CoffeeEntity.class, BY_NAME, Map::of),
                    CursorCodec.DEFAULT,
                    CursorKeyCodec.DEFAULT
            );
            CriteriaBuilder builder = entityManager.getCriteriaBuilder();

            CriteriaQuery<CoffeeEntity> plain = builder.createQuery(CoffeeEntity.class);
            plain.from(CoffeeEntity.class);
            assertThat(queries.hasCollectionJoin(plain)).isFalse();

            CriteriaQuery<CoffeeEntity> toOne = builder.createQuery(CoffeeEntity.class);
            toOne.from(CoffeeEntity.class).join("roaster");
            assertThat(queries.hasCollectionJoin(toOne)).isFalse();

            CriteriaQuery<CoffeeEntity> toMany = builder.createQuery(CoffeeEntity.class);
            toMany.from(CoffeeEntity.class).join("notes");
            assertThat(queries.hasCollectionJoin(toMany)).isTrue();

            CriteriaQuery<CoffeeEntity> nested = builder.createQuery(CoffeeEntity.class);
            nested.from(CoffeeEntity.class).join("roaster").join("coffees");
            assertThat(queries.hasCollectionJoin(nested)).as("a collection reached through a join counts").isTrue();

            CriteriaQuery<Long> rootless = builder.createQuery(Long.class);
            assertThat(queries.getRoot(rootless)).isEmpty();
            assertThat(queries.hasCollectionJoin(rootless)).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("applies the distinct on the search query")
    void appliesTheDistinct() {
        PaginationResult<CoffeeEntity> result = withQueries(queries -> queries.search(parse("notes.flavour==Citrus,notes.flavour==Floral"), Pageable.of(0, 10)));

        assertThat(Coffees.namesOf(result)).doesNotHaveDuplicates().hasSize(4);
    }

    @Test
    @DisplayName("appends the seek predicate to the restriction built by the visitor")
    void appendsTheSeekPredicate() {
        CursorResult<CoffeeEntity> first = withQueries(queries -> queries.scroll(parse("roast==LIGHT"), Cursor.first(2, Sort.NONE)));
        assertThat(Coffees.namesOf(first)).containsExactly(BOURBON_POINTU, GEISHA);

        CursorResult<CoffeeEntity> second = withQueries(queries -> queries.scroll(parse("roast==LIGHT"), Cursor.of(first.next(), 2, Sort.NONE)));
        assertThat(Coffees.namesOf(second)).containsExactly(YIRGACHEFFE);
        assertThat(second.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("rejects a query on an unknown property")
    void rejectsAnUnknownProperty() {
        assertThatThrownBy(() -> withQueries(queries -> queries.count(parse("caffeine==high"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a query on a property that is not declared searchable")
    void rejectsANonSearchableProperty() {
        Map<String, String> searchable = Map.of("origin", "origin");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> withQueries(searchable, queries -> queries.count(parse("strength=ge=5"))))
                .withMessage("Cannot sort or filter on the unknown property strength");
    }

    @Test
    @DisplayName("resolves a searchable property to the entity attribute path it is aliased to")
    void resolvesASearchablePropertyAlias() {
        Map<String, String> searchable = Map.of("country", "origin");

        PaginationResult<CoffeeEntity> result =
                withQueries(searchable, queries -> queries.search(parse("country==" + ETHIOPIA), Pageable.UNPAGED));

        assertThat(Coffees.namesOf(result)).containsExactly(HARRAR, SIDAMO, YIRGACHEFFE);
    }

    @Test
    @DisplayName("resolves a searchable property through a nested path, joining the association")
    void resolvesASearchablePropertyThroughANestedPath() {
        Map<String, String> searchable = Map.of("brewer", "roaster.name");

        long count = withQueries(searchable, queries -> queries.count(parse("brewer==\"Moka Brothers\"")));

        assertThat(count).isEqualTo(4);
    }

}
