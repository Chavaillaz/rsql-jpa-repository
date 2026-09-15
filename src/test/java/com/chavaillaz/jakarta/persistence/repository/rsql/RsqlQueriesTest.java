package com.chavaillaz.jakarta.persistence.repository.rsql;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BOURBON_POINTU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.KONA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import cz.jirutka.rsql.parser.RSQLParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.chavaillaz.jakarta.persistence.repository.Criteria;
import com.chavaillaz.jakarta.persistence.repository.Cursor;
import com.chavaillaz.jakarta.persistence.repository.CursorResult;
import com.chavaillaz.jakarta.persistence.repository.EntityQueries;
import com.chavaillaz.jakarta.persistence.repository.HibernateTest;
import com.chavaillaz.jakarta.persistence.repository.Pageable;
import com.chavaillaz.jakarta.persistence.repository.PaginationResult;
import com.chavaillaz.jakarta.persistence.repository.RepositoryContext;
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

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
    }

    private static EntityQueries<CoffeeEntity> queries() {
        return EntityQueries.of(CoffeeEntity.class);
    }

    private <T> T withCriteria(String rsql, BiFunction<RepositoryContext<CoffeeEntity>, Criteria<CoffeeEntity>, T> action) {
        return withCriteria(Map.of(), rsql, action);
    }

    /**
     * Translates the given query within a transaction, recording the statements from there on, and hands the
     * resulting criteria over to the given action along with the context it was translated for.
     */
    private <T> T withCriteria(Map<String, String> searchableProperties, String rsql, BiFunction<RepositoryContext<CoffeeEntity>, Criteria<CoffeeEntity>, T> action) {
        return inTransaction(entityManager -> {
            recordStatements();
            RepositoryContext<CoffeeEntity> context = new TestContext<>(entityManager, BY_NAME, searchableProperties);
            Criteria<CoffeeEntity> criteria = RsqlQueries.of(CoffeeEntity.class)
                    .toCriteria(context, PARSER.parse(rsql), () -> RsqlQueries.defaultPredicateVisitor(CoffeeEntity.class));
            return action.apply(context, criteria);
        });
    }

    @Test
    @DisplayName("translates a query into criteria the queries of the entity apply")
    void translatesIntoCriteria() {
        PaginationResult<CoffeeEntity> result = withCriteria("strength=ge=6", (context, criteria) ->
                queries().search(context, null, criteria, Pageable.UNPAGED));

        assertThat(namesOf(result)).containsExactly(HARRAR, KONA, SIDAMO);
    }

    @Test
    @DisplayName("orders the results with the ordering rules of the repository")
    void ordersWithTheRepositoryRules() {
        PaginationResult<CoffeeEntity> result = withCriteria("origin==" + ETHIOPIA, (context, criteria) ->
                queries().search(context, null, criteria, Pageable.of(0, 2, Sort.parse("-strength"))));

        assertThat(namesOf(result)).containsExactly(HARRAR, SIDAMO);
        assertThat(result.totalItems()).isEqualTo(3);
    }

    @Test
    @DisplayName("counts each entity once when the query joins a collection")
    void countsEachEntityOnce() {
        assertThat((long) withCriteria("notes.flavour==Citrus", (context, criteria) -> queries().count(context, null, criteria)))
                .isEqualTo(3);
        assertThat((long) withCriteria("notes.flavour==Citrus,notes.flavour==Floral", (context, criteria) -> queries().count(context, null, criteria)))
                .as("Yirgacheffe has both notes and must be counted once")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("moves a query joining a collection into a semi join, and not into a distinct")
    void semiJoinsACollection() {
        PaginationResult<CoffeeEntity> result = withCriteria("notes.flavour==Citrus,notes.flavour==Floral", (context, criteria) ->
                queries().search(context, null, criteria, Pageable.of(0, 10, Sort.parse("roaster.name,name"))));

        assertThat(namesOf(result))
                .as("Kaldi Roasting first, then Moka Brothers, each by name")
                .containsExactly(SIDAMO, YIRGACHEFFE, BOURBON_POINTU, GEISHA);
        assertThat(statements())
                .isNotEmpty()
                .as("a select distinct cannot be ordered on the joined roaster on PostgreSQL and Oracle")
                .noneMatch(sql -> sql.toLowerCase().contains("distinct"))
                .as("the join on the notes was moved into a correlated exists subquery")
                .allMatch(sql -> sql.toLowerCase().contains("exists"));
    }

    @Test
    @DisplayName("appends the seek predicate to the criteria")
    void appendsTheSeekPredicate() {
        CursorResult<CoffeeEntity> first = withCriteria("roast==LIGHT", (context, criteria) ->
                queries().scroll(context, null, criteria, Cursor.first(2, Sort.NONE)));
        assertThat(namesOf(first)).containsExactly(BOURBON_POINTU, GEISHA);

        CursorResult<CoffeeEntity> second = withCriteria("roast==LIGHT", (context, criteria) ->
                queries().scroll(context, null, criteria, Cursor.of(first.next(), 2, Sort.NONE)));
        assertThat(namesOf(second)).containsExactly(YIRGACHEFFE);
        assertThat(second.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("rejects a query on an unknown property")
    void rejectsAnUnknownProperty() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withCriteria("caffeine==high", (context, criteria) -> queries().count(context, null, criteria)));
    }

    @Test
    @DisplayName("rejects a selector reaching through a basic attribute, as the illegal argument a consumer sent")
    void rejectsASelectorThroughABasicAttribute() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withCriteria("name.origin==" + ETHIOPIA, (context, criteria) -> queries().count(context, null, criteria)))
                .withMessageContaining("has no attribute 'origin'");
    }

    /**
     * The visitor joins an association from the path it last stepped into, which is neither the basic attribute of
     * the first selector nor the to-one association of the notes the second one reaches the roaster through.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"name.roaster.name", "notes.coffee.roaster.name"})
    @DisplayName("rejects a selector the visitor cannot navigate, as the illegal argument a consumer sent")
    void rejectsASelectorTheVisitorCannotNavigate(String selector) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withCriteria(selector + "==Kaldi", (context, criteria) -> queries().count(context, null, criteria)))
                .withMessage("Cannot filter on property " + selector);
    }

    /**
     * The visitor navigates the coffees of the roaster without joining them, the roaster being joined already, and
     * reads a null argument before even looking at the type of the property, leaving Hibernate to refuse comparing a
     * collection, some of them only once rendering the statement.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"roaster.coffees==null", "roaster.coffees!=null", "roaster.coffees=in=(null)", "roaster.coffees=gt=null"})
    @DisplayName("rejects comparing a collection to an argument, even null, as the illegal argument a consumer sent")
    void rejectsComparingACollection(String rsql) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withCriteria(rsql, (context, criteria) -> queries().count(context, null, criteria)))
                .withMessage("Cannot cast 'null' to type interface java.util.List");
    }

    @Test
    @DisplayName("rejects a query on a property that is not declared searchable, as soon as it is translated")
    void rejectsANonSearchableProperty() {
        Map<String, String> searchable = Map.of("origin", "origin");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> withCriteria(searchable, "strength=ge=5", (context, criteria) -> criteria))
                .withMessage("Cannot sort or filter on unknown property strength");
    }

    @Test
    @DisplayName("resolves a searchable property to the entity attribute path it is aliased to")
    void resolvesASearchablePropertyAlias() {
        Map<String, String> searchable = Map.of("country", "origin");

        PaginationResult<CoffeeEntity> result = withCriteria(searchable, "country==" + ETHIOPIA, (context, criteria) ->
                queries().search(context, null, criteria, Pageable.UNPAGED));

        assertThat(namesOf(result)).containsExactly(HARRAR, SIDAMO, YIRGACHEFFE);
    }

    @Test
    @DisplayName("resolves a searchable property through a nested path, joining the association")
    void resolvesASearchablePropertyThroughANestedPath() {
        Map<String, String> searchable = Map.of("brewer", "roaster.name");

        long count = withCriteria(searchable, "brewer==\"Moka Brothers\"", (context, criteria) -> queries().count(context, null, criteria));

        assertThat(count).isEqualTo(4);
    }

}
