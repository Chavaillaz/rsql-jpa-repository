package com.chavaillaz.jakarta.persistence.repository.rsql;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BLUE_MOUNTAIN;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

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

import cz.jirutka.rsql.parser.RSQLParserException;
import cz.jirutka.rsql.parser.ast.Arity;
import cz.jirutka.rsql.parser.ast.ComparisonOperator;
import cz.jirutka.rsql.parser.ast.RSQLOperators;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import com.chavaillaz.jakarta.persistence.repository.TestDatabase;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.Roast;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;
import com.chavaillaz.jakarta.persistence.rsql.RsqlComparison;
import com.chavaillaz.jakarta.persistence.rsql.RsqlDialect;

@DisplayName("RsqlQueries")
class RsqlQueriesTest extends HibernateTest {

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
        return withCriteria(Map.of(), RsqlDialect.DEFAULT, rsql, action);
    }

    private <T> T withCriteria(Map<String, String> searchableProperties, String rsql, BiFunction<RepositoryContext<CoffeeEntity>, Criteria<CoffeeEntity>, T> action) {
        return withCriteria(searchableProperties, RsqlDialect.DEFAULT, rsql, action);
    }

    /**
     * Translates the given query within a transaction, recording the statements from there on, and hands the
     * resulting criteria over to the given action along with the context it was translated for.
     */
    private <T> T withCriteria(Map<String, String> searchableProperties, RsqlDialect dialect, String rsql, BiFunction<RepositoryContext<CoffeeEntity>, Criteria<CoffeeEntity>, T> action) {
        return inTransaction(entityManager -> {
            recordStatements();
            RepositoryContext<CoffeeEntity> context = new TestContext<>(entityManager, BY_NAME, searchableProperties);
            Criteria<CoffeeEntity> criteria = RsqlQueries.of(CoffeeEntity.class)
                    .toCriteria(context, dialect.parser().parse(rsql), dialect);
            return action.apply(context, criteria);
        });
    }

    private long count(String rsql) {
        return withCriteria(rsql, (context, criteria) -> queries().count(context, null, criteria));
    }

    private PaginationResult<CoffeeEntity> search(String rsql) {
        return search(rsql, Pageable.UNPAGED);
    }

    private PaginationResult<CoffeeEntity> search(String rsql, Pageable pageable) {
        return withCriteria(rsql, (context, criteria) -> queries().search(context, null, criteria, pageable));
    }

    private List<String> names(String rsql) {
        return namesOf(search(rsql));
    }

    @Test
    @DisplayName("translates a query into criteria the queries of the entity apply")
    void translatesIntoCriteria() {
        assertThat(names("strength=ge=6")).containsExactly(HARRAR, KONA, SIDAMO);
    }

    @Test
    @DisplayName("orders the results with the ordering rules of the repository")
    void ordersWithTheRepositoryRules() {
        PaginationResult<CoffeeEntity> result = search("origin==" + ETHIOPIA, Pageable.of(0, 2, Sort.parse("-strength")));

        assertThat(namesOf(result)).containsExactly(HARRAR, SIDAMO);
        assertThat(result.totalItems()).isEqualTo(3);
    }

    @Test
    @DisplayName("counts each entity once when the query joins a collection")
    void countsEachEntityOnce() {
        assertThat(count("notes.flavour==Citrus")).isEqualTo(3);
        assertThat(count("notes.flavour==Citrus,notes.flavour==Floral"))
                .as("Yirgacheffe has both notes and must be counted once")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("moves a query joining a collection into a semi join, and not into a distinct")
    void semiJoinsACollection() {
        PaginationResult<CoffeeEntity> result = search("notes.flavour==Citrus,notes.flavour==Floral", Pageable.of(0, 10, Sort.parse("roaster.name,name")));

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
     * Each comparison gets a subquery of its own, where its join only restricts that very comparison, so that two
     * of them reaching through the same collection are satisfied by two different elements.
     */
    @Test
    @DisplayName("satisfies two comparisons on the same collection with two different elements")
    void satisfiesTwoComparisonsWithTwoElements() {
        assertThat(names("notes.flavour==Citrus;notes.flavour==Floral"))
                .as("Yirgacheffe is the only coffee having both notes")
                .containsExactly(YIRGACHEFFE);
    }

    @Test
    @DisplayName("moves a query reaching through an element collection into a semi join, keeping the entities without elements")
    void semiJoinsAnElementCollection() {
        persist(coffee("Espresso Blend").addBlend("Brazil", 60).addBlend(ETHIOPIA, 40));

        PaginationResult<CoffeeEntity> blends = search("blend.origin=in=(Brazil," + ETHIOPIA + ")", Pageable.of(0, 10));
        assertThat(namesOf(blends)).as("both origins of the blend match, and its coffee is returned once").containsExactly("Espresso Blend");
        assertThat(blends.totalItems()).isOne();

        PaginationResult<CoffeeEntity> alternatives = search("blend.origin==Brazil,name==" + GEISHA, Pageable.of(0, 10));
        assertThat(namesOf(alternatives))
                .as("Geisha blends nothing, which must not keep its name from matching")
                .containsExactly("Espresso Blend", GEISHA);
        assertThat(alternatives.totalItems()).isEqualTo(2);
        assertThat(statements())
                .isNotEmpty()
                .as("the blend was moved into a correlated exists subquery")
                .allMatch(sql -> sql.toLowerCase().contains("exists"));
    }

    /**
     * Every attribute of an element is reachable on the element itself, whether or not the entity owning the
     * collection happens to declare an attribute of the same name, as the share of a blend component shows.
     */
    @Test
    @DisplayName("filters on an attribute of the elements of an element collection")
    void filtersOnAnElementAttribute() {
        persist(coffee("Espresso Blend").addBlend("Brazil", 60).addBlend(ETHIOPIA, 40));

        assertThat(names("blend.share=ge=60")).containsExactly("Espresso Blend");
        assertThat(count("blend.share=gt=60")).isZero();
    }

    @Test
    @DisplayName("filters on the elements of an element collection of basic values, one by one")
    void filtersOnBasicElements() {
        assertThat(names("certifications==Fairtrade")).containsExactly(HARRAR, SIDAMO);
        assertThat(names("certifications=in=(Organic)")).containsExactly(SIDAMO);
        assertThat(names("certifications==Fairtrade,name==" + GEISHA))
                .as("Geisha holds no certification, which must not keep its name from matching")
                .containsExactly(GEISHA, HARRAR, SIDAMO);
        assertThat(count("certifications!=Fairtrade"))
                .as("Sidamo also holds an Organic certification, which differs from it")
                .isEqualTo(1);
    }

    /**
     * The selector of a comparison is navigated attribute by attribute, each of them joined as its kind requires,
     * which a comparison reaching an association through another one or through a collection depends on.
     */
    @ParameterizedTest(name = "{0} matches {1}")
    @CsvSource(delimiter = ':', value = {
            "notes.coffee.name==Geisha                       : 1",
            "notes.coffee.roaster.name==\"Kaldi Roasting\"   : 3",
            "roaster.coffees.name==Geisha                    : 4",
            "roaster.coffees.notes.flavour==Jasmine          : 4",
            "notes.coffee.roaster.coffees.notes.flavour==Nutty : 4",
    })
    @DisplayName("filters through a selector of any depth")
    void filtersThroughAnyDepth(String rsql, long expected) {
        assertThat(count(rsql)).isEqualTo(expected);
        assertThat(search(rsql).items()).hasSize((int) expected);
    }

    @Test
    @DisplayName("filters through an embeddable, joining the association it holds")
    void filtersThroughAnEmbeddable() {
        assertThat(names("cupping.score=ge=85")).containsExactly(BLUE_MOUNTAIN, HARRAR, KONA, SIDAMO);
        assertThat(names("cupping.cuppedBy.name==\"Moka Brothers\""))
                .as("the Ethiopian coffees are roasted by Kaldi Roasting but cupped by Moka Brothers")
                .containsExactly(HARRAR, SIDAMO, YIRGACHEFFE);
        assertThat(names("cupping.cuppedBy.country==" + ETHIOPIA)).containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA, KONA);
    }

    @Test
    @DisplayName("compares an association itself to null, which matches the entities having none")
    void comparesAnAssociationToNull() {
        persist(coffee("Xigera"));

        assertThat(names("roaster==null")).as("Xigera has no roaster").containsExactly("Xigera");
        assertThat(count("roaster!=null")).isEqualTo(7);
        assertThat(count("roaster=notnull=")).isEqualTo(7);
    }

    @ParameterizedTest(name = "{0} matches {1}")
    @CsvSource(delimiter = ':', value = {
            "decafLabel=null=    : 8",
            "decafLabel=notnull= : 0",
            "notes.flavour=null= : 0",
            "roaster.name=notnull= : 7",
    })
    @DisplayName("matches the entities having a property or not with the null operators")
    void matchesWithTheNullOperators(String rsql, long expected) {
        persist(coffee("Xigera"));

        assertThat(count(rsql)).isEqualTo(expected);
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
    @DisplayName("matches nothing with an empty list of arguments, and everything with an empty exclusion")
    void matchesWithAnEmptyList() {
        assertThat(count("origin=in=()")).isZero();
        assertThat(count("origin=out=()")).isEqualTo(7);
        assertThat(count("origin=in=(" + ETHIOPIA + ",null)")).as("a null argument of a list matches nothing").isEqualTo(3);
        assertThat(count("strength==null")).as("a primitive attribute is never null").isZero();
    }

    @Test
    @DisplayName("rejects a query on an unknown property")
    void rejectsAnUnknownProperty() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> count("caffeine==high"))
                .withMessage("Cannot filter on unknown property caffeine");
    }

    /**
     * A selector made of separators alone names no attribute, where a trailing one is ignored as the base library
     * ignores it when it sorts.
     */
    @Test
    @DisplayName("rejects a selector naming no attribute at all")
    void rejectsASelectorNamingNothing() {
        assertThat(count("name.==" + GEISHA)).as("a trailing separator names nothing more").isOne();
        assertThatIllegalArgumentException().isThrownBy(() -> count(".==null")).withMessage("Cannot filter on unknown property .");
        assertThatIllegalArgumentException().isThrownBy(() -> count("..==null")).withMessage("Cannot filter on unknown property ..");
        assertThatIllegalArgumentException().isThrownBy(() -> count(".name==" + GEISHA)).withMessage("Cannot filter on unknown property .name");
        assertThatIllegalArgumentException().isThrownBy(() -> count("notes..flavour==Citrus")).withMessage("Cannot filter on unknown property notes..flavour");
    }

    /**
     * A basic attribute has nothing to dereference, and neither has an element of a collection of basic values,
     * so that the rest of such a selector names no attribute at all.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"name.origin", "name.roaster.name", "certifications.length", "blend.name", "notes.flavour.length"})
    @DisplayName("rejects a selector reaching through a property that has no attributes, as the illegal argument a consumer sent")
    void rejectsASelectorThroughABasicAttribute(String selector) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> count(selector + "==Kaldi"))
                .withMessage("Cannot filter on unknown property " + selector);
    }

    /**
     * Only the attributes of the elements of such a collection compare to an argument, as in {@code notes.flavour},
     * and an element is never null within the correlated subquery of the comparison, so that a comparison to null
     * would silently match nothing.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"roaster.coffees==null", "roaster.coffees!=null", "roaster.coffees=in=(null)", "roaster.coffees=gt=null", "notes=null=", "blend==null"})
    @DisplayName("rejects comparing a collection of entities or embeddables, as the illegal argument a consumer sent")
    void rejectsComparingACollection(String rsql) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> count(rsql))
                .withMessageStartingWith("Cannot filter on collection property ");
    }

    /**
     * Nothing orders an association or an embeddable, which the databases refuse to compare with an inequality.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"roaster=gt=null", "cupping=le=null", "notes.coffee=ge=null"})
    @DisplayName("rejects ordering a property nothing orders, as the illegal argument a consumer sent")
    void rejectsOrderingAnAssociation(String rsql) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> count(rsql))
                .withMessageStartingWith("Cannot filter on property ");
    }

    /**
     * A string matched against a pattern is lowered, which Hibernate refuses for a string it does not hold as
     * text, such as a large object, whatever the argument.
     */
    @Test
    @DisplayName("rejects matching a pattern against a large object, as the illegal argument a consumer sent")
    void rejectsAPatternAgainstALargeObject() {
        assertThat(count("description==null"))
                .as("a comparison to null matches no pattern")
                .isEqualTo(7);
        assertThatIllegalArgumentException()
                .as("Hibernate refused it with a SemanticException, which an API layer answers with a 500")
                .isThrownBy(() -> count("description==*floral*"))
                .withMessage("Cannot filter on property description with ==");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> count("notes.flavour==Citrus;description!=Citrus"))
                .withMessage("Cannot filter on property description with !=");
    }

    /**
     * A converter is free to refuse a value of the very type it converts, and Hibernate applies it while binding
     * the statement, where the exception it raises is no illegal argument at all.
     */
    @Test
    @DisplayName("rejects an argument the converter of its property refuses, rather than failing the statement while binding it")
    void rejectsAnArgumentTheConverterRefuses() {
        assertThat(names("lot=gt=50")).as("a lot is stored as the number it spells").containsExactly(HARRAR, KONA, SIDAMO);
        assertThat(count("lot==null")).isZero();
        assertThatIllegalArgumentException()
                .as("Hibernate raised a PersistenceException while binding it, which an API layer answers with a 500")
                .isThrownBy(() -> count("lot=gt=" + GEISHA))
                .withMessage("Cannot filter on property lot with argument 'Geisha', which the property cannot hold");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> count("lot=in=(50," + GEISHA + ")"))
                .withMessage("Cannot filter on property lot with argument 'Geisha', which the property cannot hold");
        assertThatIllegalArgumentException()
                .as("a pattern spells no number either")
                .isThrownBy(() -> count("lot==*5*"))
                .withMessage("Cannot filter on property lot with argument '*5*', which the property cannot hold");
        assertThatIllegalArgumentException()
                .as("and a pattern is matched by lowering the property, which Hibernate refuses for a converted one")
                .isThrownBy(() -> count("lot==50"))
                .withMessage("Cannot filter on property lot with ==");
    }

    /**
     * A date is read as it is written or not at all, where a lenient parsing rolls an impossible day over and
     * ignores whatever follows the pattern it matches first, such as the minutes of a time written without its
     * seconds.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"2024-02-30", "2024-01-01T10:00", "2024-01-01 10:00:00", "2024-01-01T10:00:00Z", "2024-1-1"})
    @DisplayName("rejects a date argument not written as a date or a date time, rather than silently reading another instant")
    void rejectsADateReadLeniently(String date) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> count("packedAt=ge='" + date + "'"))
                .withMessage("Cannot filter on property packedAt with argument '%s', not a valid Date", date);
    }

    @Test
    @DisplayName("rejects a date argument whose year is written with more than four digits, rather than failing the statement on PostgreSQL")
    void rejectsADateBeyondTheYear9999() {
        persist(packed("Xigera", "9999-12-31T23:59:59"));

        assertThat(count("packedAt==9999-12-31T23:59:59")).isOne();
        assertThatIllegalArgumentException()
                .as("PostgreSQL failed the statement with a DataException, which an API layer answers with a 500")
                .isThrownBy(() -> count("packedAt=gt=300000-01-01"))
                .withMessage("Cannot filter on property packedAt with argument '300000-01-01', not a valid Date");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> count("packedAt=in=(2024-01-01,10000-01-01T00:00:00)"));
    }

    @Test
    @DisplayName("reads a date argument written as a date or as a date time")
    void readsADateAsWritten() {
        persist(packed("Xigera", "2024-01-01T10:00:00"));

        assertThat(count("packedAt=ge=2024-01-01")).isOne();
        assertThat(count("packedAt==2024-01-01T10:00:00")).isOne();
    }

    @Test
    @DisplayName("reads a date argument in the Gregorian calendar whatever the default locale, rather than refusing every date under a Thai one")
    void readsADateWhateverTheDefaultLocale() {
        persist(packed("Xigera", "2024-01-01T10:00:00"));

        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.of("th", "TH"));
        try {
            assertThat(count("packedAt=ge=2024-01-01"))
                    .as("a Thai default locale reads 2024 as a Buddhist year, 1481 in the Gregorian calendar")
                    .isOne();
            assertThat(count("packedAt=gt=2024-01-01T09:59:59")).isOne();
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    @DisplayName("matches a string pattern whatever the default locale, rather than lowering its I into one no database spells")
    void matchesAPatternWhateverTheDefaultLocale() {
        // H2 runs in this very JVM and lowers a column in the default locale, where a database server lowers in
        // its own, so that only a server ever agrees with the root locale the pattern is lowered in
        assumeThat(TestDatabase.NAME).as("a database lowering in its own process").isNotEqualTo("h2");
        persist(coffee("Istanbul Roast"));

        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
        try {
            assertThat(names("name==Istanbul*"))
                    .as("a Turkish default locale lowers the I of Istanbul into a dotless one")
                    .containsExactly("Istanbul Roast");
            assertThat(count("name!=ISTANBUL*")).isEqualTo(7);
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    @DisplayName("compares a date exclusively to the very instant of its argument, rather than to a whole day later or earlier")
    void comparesADateExclusively() {
        persist(packed("Xigera", "2024-01-01T10:00:00"), packed("Yirga Batch", "2024-01-01T11:00:00"), packed("Zambia AA", "2024-01-02T09:00:00"));

        assertThat(names("packedAt=gt=2024-01-01T10:00:00")).containsExactly("Yirga Batch", "Zambia AA");
        assertThat(names("packedAt<2024-01-02T09:00:00")).containsExactly("Xigera", "Yirga Batch");
        assertThat(names("packedAt>2024-01-01;packedAt=lt=2024-01-02")).as("a date argument is midnight").containsExactly("Xigera", "Yirga Batch");
        assertThat(count("packedAt=gt=null")).as("a comparison to null still matches nothing").isZero();
    }

    /**
     * A date is compared to the very instant of its argument, where rsql-jpa compared it within bounds it set
     * once, the last day of the year 9999 and the first of the year 5, each at the time of day it was loaded,
     * which a time of day compared to as well.
     */
    @Test
    @DisplayName("compares a date inclusively to the very instant of its argument, whatever the time of day")
    void comparesADateInclusively() {
        CoffeeEntity forever = packed("Xigera", "9999-12-31T23:59:59");
        forever.setServedUntil(Time.valueOf("23:59:59"));
        CoffeeEntity morning = packed("Yirga Batch", "2024-01-01T10:00:00");
        morning.setServedUntil(Time.valueOf("00:00:00"));
        persist(forever, morning);

        assertThat(names("packedAt=ge=2024-01-01T10:00:00"))
                .as("a date packed on the last day of 9999 is greater than any bound of 2024")
                .containsExactly("Xigera", "Yirga Batch");
        assertThat(names("servedUntil=ge=00:00:00;servedUntil=le=23:59:59"))
                .as("a whole day of times")
                .containsExactly("Xigera", "Yirga Batch");
        assertThat(count("packedAt=le=null")).as("a comparison to null still matches nothing").isZero();
    }

    private static CoffeeEntity packed(String name, String dateTime) {
        CoffeeEntity coffee = coffee(name);
        coffee.setPackedAt(Date.from(LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant()));
        return coffee;
    }

    @Test
    @DisplayName("compares a java.time instant, written as the ISO 8601 instant it is")
    void comparesAnInstant() {
        assertThat(count("roastedAt==2024-01-01T06:00:00Z")).isEqualTo(7);
        assertThat(count("roastedAt=gt=2024-01-01T05:59:59Z")).isEqualTo(7);
        assertThat(count("roastedAt=le=2024-01-01T05:59:59Z")).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"2024-01-01T06:00:00", "2024-01-01", "+10000-01-01T00:00:00Z", "1704088800"})
    @DisplayName("rejects an instant argument written otherwise, rather than comparing another instant")
    void rejectsAnInstantWrittenOtherwise(String argument) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> count("roastedAt=gt='" + argument + "'"))
                .withMessage("Cannot filter on property roastedAt with argument '%s', not a valid Instant", argument);
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

    /**
     * An error message names the selector as the API consumer sent it, the searchable properties of a repository
     * decoupling the public naming from the entity one.
     */
    @Test
    @DisplayName("names the selector of the consumer when it refuses a comparison")
    void namesTheSelectorOfTheConsumer() {
        Map<String, String> searchable = Map.of("brewer", "roaster.name");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> withCriteria(searchable, "brewer==*a*b*c*d*", (context, criteria) -> queries().count(context, null, criteria)))
                .withMessage("Cannot filter on property brewer with a pattern of more than %d wildcards", RsqlComparison.MAX_WILDCARDS);
    }

    @Test
    @DisplayName("translates an operator of a dialect of its own")
    void translatesACustomOperator() {
        ComparisonOperator like = new ComparisonOperator("=like=", Arity.nary(1));
        RsqlDialect dialect = RsqlDialect.DEFAULT.withOperator(like, comparison -> comparison.criteriaBuilder()
                .like(comparison.path(), comparison.pattern(comparison.arguments().get(0))));

        assertThat(countWith(dialect, "name=like=G*")).as("a case sensitive pattern").isOne();
        assertThat(countWith(dialect, "name=like=g*")).isZero();
        assertThat(countWith(dialect, "name==g*")).as("where == keeps ignoring the case").isOne();
        assertThatIllegalArgumentException()
                .as("the wildcards of a pattern stay limited")
                .isThrownBy(() -> countWith(dialect, "name=like=*a*b*c*d*e"))
                .withMessage("Cannot filter on property name with a pattern of more than %d wildcards", RsqlComparison.MAX_WILDCARDS);
    }

    @Test
    @DisplayName("reads the arguments of a type of its own with the parser the dialect registers")
    void readsACustomArgumentType() {
        RsqlDialect dialect = RsqlDialect.DEFAULT.withArgumentType(Roast.class, argument -> Roast.valueOf(argument.toUpperCase(Locale.ROOT)));

        assertThat(countWith(dialect, "roast==light")).isEqualTo(3);
        assertThat(count("roast==LIGHT")).as("where the default parser reads a constant by its very name").isEqualTo(3);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> count("roast==light"))
                .withMessage("Cannot filter on property roast with argument 'light', not a valid Roast");
    }

    @Test
    @DisplayName("rejects the predicate of a dialect of its own asking for the argument of an operator carrying none")
    void rejectsTheValueOfAnOperatorWithoutArgument() {
        ComparisonOperator blank = new ComparisonOperator("=blank=", Arity.nary(0));
        RsqlDialect dialect = RsqlDialect.DEFAULT.withOperator(blank, comparison -> comparison.criteriaBuilder()
                .equal(comparison.path(), comparison.value()));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> countWith(dialect, "name=blank="))
                .withMessage("Cannot filter on property name with =blank=, which carries no argument");
    }

    @Test
    @DisplayName("rejects a comparison whose operator the dialect does not hold")
    void rejectsAnUnknownOperator() {
        RsqlDialect dialect = RsqlDialect.DEFAULT.withoutOperator(RSQLOperators.NOT_IN);
        String rsql = "origin=out=(" + ETHIOPIA + ")";

        assertThatThrownBy(() -> dialect.parser().parse(rsql))
                .as("the parser of a dialect only accepts the operators it translates")
                .isInstanceOf(RSQLParserException.class);
        assertThatIllegalArgumentException()
                .as("and a node parsed elsewhere is refused as well")
                .isThrownBy(() -> inTransaction(entityManager -> RsqlQueries.of(CoffeeEntity.class).toCriteria(
                        new TestContext<>(entityManager, BY_NAME, Map.of()),
                        RsqlDialect.DEFAULT.parser().parse(rsql),
                        dialect)))
                .withMessage("Cannot filter on property origin with unknown operator =out=");
    }

    private long countWith(RsqlDialect dialect, String rsql) {
        return withCriteria(Map.of(), dialect, rsql, (context, criteria) -> queries().count(context, null, criteria));
    }

}
