package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BLUE_MOUNTAIN;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BOURBON_POINTU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.KONA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.MENU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.coffee;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Function;

import cz.jirutka.rsql.parser.ast.Node;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;
import com.chavaillaz.jakarta.persistence.repository.rsql.AbstractRsqlRepository;

@DisplayName("Searching the coffee menu with an RSQL query")
class CoffeeRsqlSearchTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
    }

    private <T> T withRepository(Function<CoffeeRepositoryJpa, T> action) {
        return inTransaction(entityManager -> action.apply(new CoffeeRepositoryJpa(entityManager)));
    }

    private List<CoffeeEntity> searchAll(String rsql) {
        return withRepository(repository -> repository.search(rsql));
    }

    private long countAll(String rsql) {
        return withRepository(repository -> repository.count(rsql));
    }

    @Test
    @DisplayName("counts everything when the query is null or blank")
    void countsEverythingWhenBlank() {
        assertThat(countAll(null)).as("a null query counts everything").isEqualTo(7);
        assertThat(countAll("")).isEqualTo(7);
        assertThat(countAll("   ")).isEqualTo(7);
    }

    @ParameterizedTest(name = "{0} matches {1}")
    @CsvSource(delimiter = ':', value = {
            "origin==Ethiopia                : 3",
            "origin!=Ethiopia                : 4",
            "roast==LIGHT                    : 3",
            "strength=gt=5                   : 3",
            "strength=ge=5                   : 4",
            "price=lt=30                     : 3",
            "origin==Ethiopia;roast==LIGHT   : 1",
            "origin==Ethiopia,origin==Panama : 4",
            "name==Geisha                    : 1",
            "origin=in=(Ethiopia,Panama)     : 4",
    })
    @DisplayName("filters the menu")
    void filters(String rsql, long expected) {
        assertThat(countAll(rsql)).isEqualTo(expected);
        assertThat(searchAll(rsql)).hasSize((int) expected);
    }

    @Test
    @DisplayName("falls back on findAll when the query is blank")
    void fallsBackOnFindAll() {
        assertThat(namesOf(searchAll("   "))).containsExactlyElementsOf(MENU);
        assertThat(namesOf(searchAll(null))).containsExactlyElementsOf(MENU);
    }

    @Test
    @DisplayName("applies the default ordering even when unpaged, regardless of the insertion order")
    void ordersAnUnpagedQueryWithTheDefault() {
        // Inserted in reverse alphabetical order, opposite of the default one, so that a query relying on the
        // insertion or identifier order instead of an actual ORDER BY would be caught red handed
        persist(
                coffee("Zambia AA", ETHIOPIA, Roast.LIGHT, "10.00", 5),
                coffee("Yirga Batch", ETHIOPIA, Roast.LIGHT, "10.00", 5),
                coffee("Xigera", ETHIOPIA, Roast.LIGHT, "10.00", 5));

        assertThat(namesOf(searchAll("origin==" + ETHIOPIA)))
                .containsExactly(HARRAR, SIDAMO, "Xigera", "Yirga Batch", YIRGACHEFFE, "Zambia AA");
    }

    @Test
    @DisplayName("paginates and orders the filtered results")
    void paginatesTheResults() {
        PaginationResult<CoffeeEntity> result = withRepository(repository ->
                repository.search("origin==" + ETHIOPIA, 0, 2, Sort.parse("-price")));

        assertThat(namesOf(result)).containsExactly(YIRGACHEFFE, SIDAMO);
        assertThat(result.totalItems()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("returns distinct entities and a consistent count when the query joins a collection")
    void deduplicatesTheCollectionJoins() {
        // Yirgacheffe has both notes, so a plain join would return it twice
        PaginationResult<CoffeeEntity> result = withRepository(repository ->
                repository.search("notes==Citrus,notes==Floral", 0, 10));

        assertThat(namesOf(result)).containsExactly(BOURBON_POINTU, GEISHA, SIDAMO, YIRGACHEFFE);
        assertThat(result.totalItems())
                .as("the count is distinct too, otherwise it would drift from the results")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("filters on an association")
    void filtersOnAnAssociation() {
        assertThat(namesOf(searchAll("roaster==\"Moka Brothers\"")))
                .containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA, KONA);
    }

    @Test
    @DisplayName("rejects a malformed query")
    void rejectsAMalformedQuery() {
        assertThatThrownBy(() -> withRepository(repository -> repository.count("origin=!=")))
                .isInstanceOf(cz.jirutka.rsql.parser.RSQLParserException.class);
    }

    @Test
    @DisplayName("rejects a query on a property that is not searchable")
    void rejectsANonSearchableProperty() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withRepository(repository -> repository.count("roastedAt=gt=0")))
                .withMessageContaining("Cannot sort or filter on the unknown property roastedAt");
    }

    @Test
    @DisplayName("resolves a searchable property to the entity attribute path it is aliased to")
    void resolvesAnAliasedPropertyForFiltering() {
        assertThat(countAll("roaster==\"Moka Brothers\"")).isEqualTo(4);
    }

    @Test
    @DisplayName("routes count(String) through the overridable count(Node) hook")
    void routesCountThroughTheNodeHook() {
        long count = withRepository(CountingRepositoryJpa.class,
                repository -> repository.count("origin==" + ETHIOPIA));

        assertThat(count).as("the override adds 1000 to whatever the real count is").isEqualTo(1003);
    }

    /**
     * Overrides {@code count(Node)} to prove {@link AbstractRsqlRepository#count(String)} actually routes through
     * it, rather than bypassing it by calling {@link com.chavaillaz.jakarta.persistence.repository.rsql.RsqlQueries#count(Node)}
     * directly.
     */
    private static class CountingRepositoryJpa extends CoffeeRepositoryJpa {

        CountingRepositoryJpa(EntityManager entityManager) {
            super(entityManager);
        }

        @Override
        protected long count(Node rsqlNode) {
            return super.count(rsqlNode) + 1000;
        }

    }

}
