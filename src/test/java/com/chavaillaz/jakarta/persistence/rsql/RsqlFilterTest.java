package com.chavaillaz.jakarta.persistence.rsql;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import cz.jirutka.rsql.parser.RSQLParserException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.HibernateTest;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

/**
 * Filters with nothing but this package and the criteria API, as an application having no repository of the base
 * library around would: the filter is applied to a criteria query written by hand.
 */
@DisplayName("RsqlFilter")
class RsqlFilterTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
    }

    private List<String> names(String rsql) {
        return names(rsql, UnaryOperator.identity());
    }

    /**
     * Selects the coffees matching the given query, ordered by name, through a criteria query of its own.
     */
    private List<String> names(String rsql, UnaryOperator<String> selectors) {
        return inTransaction(entityManager -> {
            RsqlFilter<CoffeeEntity> filter = RsqlFilter.of(
                    RsqlDialect.DEFAULT.parse(rsql),
                    CoffeeEntity.class,
                    entityManager.getMetamodel(),
                    RsqlDialect.DEFAULT,
                    selectors);
            assertThat(filter.entityType()).isEqualTo(CoffeeEntity.class);

            CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
            CriteriaQuery<CoffeeEntity> query = criteriaBuilder.createQuery(CoffeeEntity.class);
            Root<CoffeeEntity> root = query.from(CoffeeEntity.class);
            query.where(filter.toPredicate(criteriaBuilder, query, root))
                    .orderBy(criteriaBuilder.asc(root.get("name")));

            return namesOf(entityManager.createQuery(query).getResultList());
        });
    }

    @Test
    @DisplayName("builds the predicate of a query on the root of a criteria query")
    void buildsThePredicateOnARoot() {
        assertThat(names("origin==" + ETHIOPIA + ";strength=gt=5")).containsExactly(HARRAR, SIDAMO);
        assertThat(names("name==g*")).containsExactly(GEISHA);
    }

    @Test
    @DisplayName("evaluates a comparison reaching through a collection in a subquery of its own")
    void evaluatesACollectionInASubquery() {
        assertThat(names("notes.flavour==Citrus;notes.flavour==Floral")).containsExactly(YIRGACHEFFE);
        assertThat(names("cupping.cuppedBy.name==\"Moka Brothers\"")).containsExactly(HARRAR, SIDAMO, YIRGACHEFFE);
    }

    @Test
    @DisplayName("resolves the selectors of the consumers into the attribute paths of the entity")
    void resolvesTheSelectors() {
        Map<String, String> properties = Map.of("country", "origin");
        UnaryOperator<String> selectors = selector -> {
            String property = properties.get(selector);
            if (property == null) {
                throw new IllegalArgumentException("Cannot filter on unknown property " + selector);
            }
            return property;
        };

        assertThat(names("country==" + ETHIOPIA, selectors)).containsExactly(HARRAR, SIDAMO, YIRGACHEFFE);
        assertThatIllegalArgumentException()
                .as("a selector no property exposes is refused by the resolution itself")
                .isThrownBy(() -> names("origin==" + ETHIOPIA, selectors))
                .withMessage("Cannot filter on unknown property origin");
        assertThatIllegalArgumentException()
                .as("a resolution returning nothing rather than raising, as a map lookup does")
                .isThrownBy(() -> names("origin==" + ETHIOPIA, properties::get))
                .withMessage("Cannot filter on unknown property origin");
    }

    @Test
    @DisplayName("quotes an unknown selector abbreviated, a whole one flooding the logs of the application")
    void abbreviatesAnUnknownSelector() {
        String selector = "caffeine".repeat(25_000);

        assertThatIllegalArgumentException()
                .as("a selector is consumer sent and unbounded wherever the selectors are taken as they are sent")
                .isThrownBy(() -> names(selector + "==high"))
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .startsWith("Cannot filter on unknown property caffeine")
                        .endsWith("...")
                        .hasSizeLessThan(200));
    }

    @Test
    @DisplayName("refuses a malformed query, an unknown property and a query nested too deeply")
    void refusesAMalformedQuery() {
        assertThatThrownBy(() -> names("origin=!=")).isInstanceOf(RSQLParserException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> names("caffeine==high"))
                .withMessage("Cannot filter on unknown property caffeine");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> names("(".repeat(10_000) + "origin==" + ETHIOPIA + ")".repeat(10_000)))
                .withMessage("Cannot filter with an RSQL query nesting its parentheses deeper than %d levels", RsqlDialect.MAX_NESTING_DEPTH);
    }

}
