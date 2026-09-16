package com.chavaillaz.jakarta.persistence.repository.rsql;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BOURBON_POINTU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.KONA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.coffee;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.sql.Time;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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

    /**
     * The visitor looks the origin reached through the blend up on the coffee, which has one too, and navigates the
     * blend with a plain path rather than a join, Hibernate then joining the collection table implicitly.
     */
    @Test
    @DisplayName("moves a query reaching through an element collection into a semi join, keeping the entities without elements")
    void semiJoinsAnElementCollection() {
        persist(coffee("Espresso Blend").addBlend("Brazil", 60).addBlend(ETHIOPIA, 40));

        PaginationResult<CoffeeEntity> blends = withCriteria("blend.origin=in=(Brazil," + ETHIOPIA + ")", (context, criteria) ->
                queries().search(context, null, criteria, Pageable.of(0, 10)));
        assertThat(namesOf(blends)).as("both origins of the blend match, and its coffee is returned once").containsExactly("Espresso Blend");
        assertThat(blends.totalItems()).isOne();

        PaginationResult<CoffeeEntity> alternatives = withCriteria("blend.origin==Brazil,name==" + GEISHA, (context, criteria) ->
                queries().search(context, null, criteria, Pageable.of(0, 10)));
        assertThat(namesOf(alternatives))
                .as("Geisha blends nothing, which must not keep its name from matching")
                .containsExactly("Espresso Blend", GEISHA);
        assertThat(alternatives.totalItems()).isEqualTo(2);
        assertThat(statements())
                .isNotEmpty()
                .as("the blend was moved into a correlated exists subquery")
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

    /**
     * The visitor parses a date leniently, rolling an impossible day over and ignoring whatever follows the pattern it
     * matches first, such as the minutes of a time written without its seconds.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"2024-02-30", "2024-01-01T10:00", "2024-01-01 10:00:00", "2024-01-01T10:00:00Z", "2024-1-1"})
    @DisplayName("rejects a date argument not written as a date or a date time, rather than silently reading another instant")
    void rejectsADateReadLeniently(String date) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withCriteria("packedAt=ge='" + date + "'", (context, criteria) -> queries().count(context, null, criteria)))
                .withMessage("Cannot cast '%s' to type class java.util.Date", date);
    }

    @Test
    @DisplayName("rejects a date argument whose year is written with more than four digits, rather than failing the statement on PostgreSQL")
    void rejectsADateBeyondTheYear9999() {
        persist(packed("Xigera", "9999-12-31T23:59:59"));

        assertThat((long) withCriteria("packedAt==9999-12-31T23:59:59", (context, criteria) -> queries().count(context, null, criteria))).isOne();
        assertThatIllegalArgumentException()
                .as("PostgreSQL failed the statement with a DataException, which an API layer answers with a 500")
                .isThrownBy(() -> withCriteria("packedAt=gt=300000-01-01", (context, criteria) -> queries().count(context, null, criteria)))
                .withMessage("Cannot cast '300000-01-01' to type class java.util.Date");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withCriteria("packedAt=in=(2024-01-01,10000-01-01T00:00:00)", (context, criteria) -> queries().count(context, null, criteria)));
    }

    @Test
    @DisplayName("reads a date argument written as a date or as a date time")
    void readsADateAsWritten() {
        persist(packed("Xigera", "2024-01-01T10:00:00"));

        assertThat((long) withCriteria("packedAt=ge=2024-01-01", (context, criteria) -> queries().count(context, null, criteria))).isOne();
        assertThat((long) withCriteria("packedAt==2024-01-01T10:00:00", (context, criteria) -> queries().count(context, null, criteria))).isOne();
    }

    @Test
    @DisplayName("reads a date argument in the Gregorian calendar whatever the default locale, rather than refusing every date under a Thai one")
    void readsADateWhateverTheDefaultLocale() {
        persist(packed("Xigera", "2024-01-01T10:00:00"));

        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.of("th", "TH"));
        try {
            assertThat((long) withCriteria("packedAt=ge=2024-01-01", (context, criteria) -> queries().count(context, null, criteria)))
                    .as("a Thai default locale reads 2024 as a Buddhist year, 1481 in the Gregorian calendar")
                    .isOne();
            assertThat((long) withCriteria("packedAt=gt=2024-01-01T09:59:59", (context, criteria) -> queries().count(context, null, criteria))).isOne();
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    @DisplayName("compares a date exclusively to the very instant of its argument, rather than to a whole day later or earlier")
    void comparesADateExclusively() {
        persist(packed("Xigera", "2024-01-01T10:00:00"), packed("Yirga Batch", "2024-01-01T11:00:00"), packed("Zambia AA", "2024-01-02T09:00:00"));

        PaginationResult<CoffeeEntity> after = withCriteria("packedAt=gt=2024-01-01T10:00:00", (context, criteria) ->
                queries().search(context, null, criteria, Pageable.UNPAGED));
        assertThat(namesOf(after)).containsExactly("Yirga Batch", "Zambia AA");

        PaginationResult<CoffeeEntity> before = withCriteria("packedAt<2024-01-02T09:00:00", (context, criteria) ->
                queries().search(context, null, criteria, Pageable.UNPAGED));
        assertThat(namesOf(before)).containsExactly("Xigera", "Yirga Batch");

        PaginationResult<CoffeeEntity> between = withCriteria("packedAt>2024-01-01;packedAt=lt=2024-01-02", (context, criteria) ->
                queries().search(context, null, criteria, Pageable.UNPAGED));
        assertThat(namesOf(between)).as("a date argument is midnight").containsExactly("Xigera", "Yirga Batch");

        assertThat((long) withCriteria("packedAt=gt=null", (context, criteria) -> queries().count(context, null, criteria)))
                .as("a comparison to null still matches nothing")
                .isZero();
    }

    /**
     * The visitor compares a date inclusively within bounds it sets once, the last day of the year 9999 and the first of
     * the year 5, each at the time of day it was loaded, which a time of day compares to as well.
     */
    @Test
    @DisplayName("compares a date inclusively to the very instant of its argument, rather than within bounds holding the time of day the visitor was loaded at")
    void comparesADateInclusively() {
        CoffeeEntity forever = packed("Xigera", "9999-12-31T23:59:59");
        forever.setServedUntil(Time.valueOf("23:59:59"));
        CoffeeEntity morning = packed("Yirga Batch", "2024-01-01T10:00:00");
        morning.setServedUntil(Time.valueOf("00:00:00"));
        persist(forever, morning);

        PaginationResult<CoffeeEntity> packed = withCriteria("packedAt=ge=2024-01-01T10:00:00", (context, criteria) ->
                queries().search(context, null, criteria, Pageable.UNPAGED));
        assertThat(namesOf(packed)).as("a date packed on the last day of 9999 falls past the time of day of the bound").containsExactly("Xigera", "Yirga Batch");

        PaginationResult<CoffeeEntity> served = withCriteria("servedUntil=ge=00:00:00;servedUntil=le=23:59:59", (context, criteria) ->
                queries().search(context, null, criteria, Pageable.UNPAGED));
        assertThat(namesOf(served)).as("a whole day of times, whatever the time of day the visitor was loaded at").containsExactly("Xigera", "Yirga Batch");

        assertThat((long) withCriteria("packedAt=le=null", (context, criteria) -> queries().count(context, null, criteria)))
                .as("a comparison to null still matches nothing")
                .isZero();
    }

    private static CoffeeEntity packed(String name, String dateTime) {
        CoffeeEntity coffee = coffee(name);
        coffee.setPackedAt(Date.from(LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant()));
        return coffee;
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
