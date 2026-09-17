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

import cz.jirutka.rsql.parser.ast.Arity;
import cz.jirutka.rsql.parser.ast.ComparisonOperator;
import cz.jirutka.rsql.parser.ast.Node;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;
import com.chavaillaz.jakarta.persistence.repository.rsql.AbstractRsqlRepository;
import com.chavaillaz.jakarta.persistence.rsql.ArgumentParser;
import com.chavaillaz.jakarta.persistence.rsql.RsqlComparison;
import com.chavaillaz.jakarta.persistence.rsql.RsqlDialect;

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
    @DisplayName("returns each entity once and a consistent count when the query joins a collection")
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
    @DisplayName("orders on a nested property while filtering through a collection, which a distinct would reject")
    void ordersOnANestedPropertyWhileJoining() {
        recordStatements();

        PaginationResult<CoffeeEntity> result = withRepository(repository ->
                repository.search("notes==Citrus,notes==Floral", 0, 4, Sort.parse("roaster,name")));

        assertThat(namesOf(result))
                .as("Kaldi Roasting first, then Moka Brothers, each by name")
                .containsExactly(SIDAMO, YIRGACHEFFE, BOURBON_POINTU, GEISHA);
        assertThat(result.totalItems()).isEqualTo(4);
        assertThat(statements())
                .as("select distinct ... order by roaster.name is rejected by PostgreSQL and Oracle")
                .noneMatch(sql -> sql.toLowerCase().contains("distinct"));
    }

    @Test
    @DisplayName("filters on an association")
    void filtersOnAnAssociation() {
        assertThat(namesOf(searchAll("roaster==\"Moka Brothers\"")))
                .containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA, KONA);
    }

    @Test
    @DisplayName("keeps an entity whose association is not set in an alternative reaching through it")
    void keepsAnUnsetAssociationInAnAlternative() {
        persist(coffee("Xigera"));
        String rsql = "roaster==\"Moka Brothers\",origin==" + ETHIOPIA;

        assertThat(namesOf(searchAll(rsql)))
                .as("Xigera has no roaster, which must not keep its origin from matching")
                .containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA, HARRAR, KONA, SIDAMO, "Xigera", YIRGACHEFFE);
        assertThat(countAll(rsql)).isEqualTo(8);
    }

    @Test
    @DisplayName("keeps an entity without any child in an alternative reaching through a collection")
    void keepsAnEntityWithoutChildrenInAnAlternative() {
        persist(coffee("Xigera"));
        String rsql = "notes==Citrus,name==Xigera";

        assertThat(namesOf(searchAll(rsql)))
                .as("Xigera has no tasting note, which must not keep its name from matching")
                .containsExactly(GEISHA, SIDAMO, "Xigera", YIRGACHEFFE);
        assertThat(countAll(rsql)).isEqualTo(4);
    }

    @Test
    @DisplayName("filters on the attributes of the entity itself without any subquery")
    void filtersOnOwnAttributesWithoutSubquery() {
        recordStatements();
        searchAll("origin==" + ETHIOPIA + ";strength=gt=5");

        assertThat(statements()).singleElement()
                .satisfies(sql -> assertThat(sql.toLowerCase()).doesNotContain("exists"));
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
                .withMessageContaining("Cannot sort or filter on unknown property roastedAt");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"strength==strong", "roast==BURNT", "price=lt=cheap"})
    @DisplayName("rejects an argument its property cannot be parsed from, as the illegal argument a consumer sent")
    void rejectsAnUnparsableArgument(String rsql) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> countAll(rsql))
                .withMessageStartingWith("Cannot filter on property");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> searchAll(rsql));
    }

    @Test
    @DisplayName("rejects a decimal argument the database would take seconds to bind, as the illegal argument a consumer sent")
    void rejectsADecimalWithAnOutsizedScale() {
        int limit = ArgumentParser.MAX_DECIMAL_SCALE;

        assertThat(countAll("price=lt=1e" + limit)).isEqualTo(7);
        assertThat(countAll("price=gt=1e-" + limit)).isEqualTo(7);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> countAll("price=lt=1e" + (limit + 1)))
                .withMessage("Cannot filter on property price with argument '1e%d', not a valid BigDecimal", limit + 1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> countAll("price=gt=1e-" + (limit + 1)))
                .withMessage("Cannot filter on property price with argument '1e-%d', not a valid BigDecimal", limit + 1);
        assertThatIllegalArgumentException()
                .as("H2 spent some 37 seconds binding such a decimal, only to refuse it")
                .isThrownBy(() -> searchAll("price=in=(10,1e30000000)"));
    }

    @Test
    @DisplayName("rejects a decimal argument too long for its digits to be parsed in reasonable time, as the illegal argument a consumer sent")
    void rejectsADecimalTooLongToParse() {
        String digits = "9".repeat(ArgumentParser.MAX_DECIMAL_LENGTH);

        assertThat(countAll("price=lt=" + digits)).isEqualTo(7);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> countAll("price=lt=" + digits + "9"))
                .as("the argument is quoted abbreviated, a whole one flooding the logs of the application")
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .startsWith("Cannot filter on property price with argument '999")
                        .endsWith("...', not a valid BigDecimal")
                        .hasSizeLessThan(200));
        assertThatIllegalArgumentException()
                .as("the JDK parses the digits of a decimal in quadratic time, and a search parses its arguments more than once")
                .isThrownBy(() -> searchAll("price=gt=" + "1".repeat(100_000)));
    }

    @Test
    @DisplayName("rejects a string pattern holding too many wildcards to be matched in reasonable time, as the illegal argument a consumer sent")
    void rejectsAPatternWithTooManyWildcards() {
        int limit = RsqlComparison.MAX_WILDCARDS;
        String pattern = "*o".repeat(limit);

        assertThat(countAll("name==" + pattern + "*"))
                .as("Bourbon Pointu holds three o, and the wildcard ending the pattern is not counted")
                .isOne();
        assertThat(countAll("name=in=(" + pattern + "*o)")).as("a list holds exact strings rather than patterns").isZero();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> countAll("name==" + pattern + "*o"))
                .withMessage("Cannot filter on property name with a pattern of more than %d wildcards", limit);
        assertThatIllegalArgumentException()
                .as("H2 took some 24 seconds to match such a pattern against a single string of 255 e")
                .isThrownBy(() -> searchAll("notes!=%e%e%e%e%x"));
    }

    @Test
    @DisplayName("rejects an argument holding a NUL character, which PostgreSQL refuses in any text, as the illegal argument a consumer sent")
    void rejectsANulCharacter() {
        String nul = String.valueOf(Character.MIN_VALUE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> countAll("name==Gei" + nul + "sha"))
                .withMessage("Cannot filter on property name with an argument holding a NUL character");
        assertThatIllegalArgumentException()
                .as("PostgreSQL failed the statement with a DataException, which an API layer answers with a 500")
                .isThrownBy(() -> searchAll("origin=in=(" + ETHIOPIA + ",Pan" + nul + "ama)"));
    }

    @Test
    @DisplayName("reads a boolean argument from true or false only, rather than silently reading anything else as false")
    void rejectsABooleanOtherThanTrueOrFalse() {
        assertThat(countAll("organic==true")).isEqualTo(3);
        assertThat(countAll("organic==FALSE")).as("whatever its case").isEqualTo(4);
        assertThatIllegalArgumentException()
                .as("Boolean#valueOf would read it as false, matching the coffees that are not organic")
                .isThrownBy(() -> countAll("organic==yes"))
                .withMessage("Cannot filter on property organic with argument 'yes', not a valid Boolean");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> searchAll("organic=in=(true,1)"));
    }

    @Test
    @DisplayName("rejects a query nesting its parentheses too deeply, before the parser recurses into them")
    void rejectsADeeplyNestedQuery() {
        int limit = RsqlDialect.MAX_NESTING_DEPTH;
        String origin = "origin==" + ETHIOPIA;

        assertThat(countAll("(".repeat(limit) + origin + ")".repeat(limit))).isEqualTo(3);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> countAll("(".repeat(limit + 1) + origin + ")".repeat(limit + 1)))
                .withMessage("Cannot filter with an RSQL query nesting its parentheses deeper than %d levels", limit);
        assertThatIllegalArgumentException()
                .as("a few thousand parentheses would overflow the stack of the parser")
                .isThrownBy(() -> searchAll("(".repeat(10_000) + origin + ")".repeat(10_000)));
    }

    @Test
    @DisplayName("counts no parenthesis of a quoted argument towards the nesting of a query")
    void ignoresTheParenthesesOfAQuotedArgument() {
        String parentheses = "(".repeat(RsqlDialect.MAX_NESTING_DEPTH + 1);

        assertThat(countAll("name=='" + parentheses + "'")).isZero();
        assertThat(countAll("name==\"\\\"" + parentheses + "\""))
                .as("a quote escaped within a quoted argument closes nothing")
                .isZero();
    }

    @Test
    @DisplayName("resolves a searchable property to the entity attribute path it is aliased to")
    void resolvesAnAliasedPropertyForFiltering() {
        assertThat(countAll("roaster==\"Moka Brothers\"")).isEqualTo(4);
    }

    @Test
    @DisplayName("resolves a searchable property aliased to a path reaching through an embeddable and an association")
    void resolvesADeepAliasedProperty() {
        assertThat(namesOf(searchAll("cupper==\"Moka Brothers\"")))
                .as("the Ethiopian coffees are roasted by Kaldi Roasting but cupped by Moka Brothers")
                .containsExactly(HARRAR, SIDAMO, YIRGACHEFFE);
    }

    @Test
    @DisplayName("parses and translates the queries with the dialect of the repository")
    void honoursTheDialectOfTheRepository() {
        List<CoffeeEntity> coffees = withRepository(LikeRepositoryJpa.class, repository -> repository.search("name=like=G*"));

        assertThat(namesOf(coffees)).containsExactly(GEISHA);
        assertThatThrownBy(() -> withRepository(repository -> repository.count("name=like=G*")))
                .as("where the default dialect knows no such operator, and its parser refuses it")
                .isInstanceOf(cz.jirutka.rsql.parser.RSQLParserException.class);
    }

    /**
     * Adds an operator to the RSQL of the repository, which its parser has to accept and its translation to
     * build, deliberately matching a pattern without ignoring the case as {@code ==} does.
     */
    private static class LikeRepositoryJpa extends CoffeeRepositoryJpa {

        private static final ComparisonOperator LIKE = new ComparisonOperator("=like=", Arity.nary(1));

        private static final RsqlDialect DIALECT = RsqlDialect.DEFAULT.withOperator(LIKE, comparison -> comparison.criteriaBuilder()
                .like(comparison.path(), comparison.pattern(comparison.arguments().get(0))));

        LikeRepositoryJpa(EntityManager entityManager) {
            super(entityManager);
        }

        @Override
        protected RsqlDialect rsqlDialect() {
            return DIALECT;
        }

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
     * it, rather than bypassing it by calling {@link EntityQueries#count} directly.
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
