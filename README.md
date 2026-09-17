# RSQL JPA Repository

![Quality Gate](https://github.com/chavaillaz/rsql-jpa-repository/actions/workflows/sonarcloud.yml/badge.svg)
![Dependency Check](https://github.com/chavaillaz/rsql-jpa-repository/actions/workflows/snyk.yml/badge.svg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/rsql-jpa-repository/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/rsql-jpa-repository)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Dynamic [RSQL](https://github.com/jirutka/rsql-parser) filtering extension for
[light-jpa-repository](https://github.com/chavaillaz/light-jpa-repository).

It lets the API consumers of a repository combine filter conditions dynamically as a query string, such as
`origin==Ethiopia;strength=gt=5`, on top of the pagination, sorting and type-safe filtering already provided by the
base library. It is a separate artifact so that a consumer only needing the base library's typed queries does not
have to pull in the RSQL parser and the translation of its expressions into criteria.

## Installation

The dependency is available in maven central (see badge for version), alongside the base library:

```xml
<dependency>
    <groupId>com.chavaillaz</groupId>
    <artifactId>light-jpa-repository</artifactId>
</dependency>
<dependency>
    <groupId>com.chavaillaz</groupId>
    <artifactId>rsql-jpa-repository</artifactId>
</dependency>
```

See the base library's [installation instructions](https://github.com/chavaillaz/light-jpa-repository#installation)
for the Hibernate and static metamodel setup both artifacts rely on.

## Usage

Extend `RsqlRepository` instead of the base `Repository`, and `AbstractRsqlRepository` instead of
`AbstractRepository`. Everything the base library documents — the searchable properties, the default ordering, the
`Restriction`/`Criteria` based typed queries, offset and cursor pagination — stays available unchanged, since
`AbstractRsqlRepository` extends `AbstractRepository`:

```java
public interface CoffeeRepository extends RsqlRepository<CoffeeEntity, Long> {

    List<CoffeeEntity> findByOrigin(String origin);

}
```

```java
@Transactional
@JpaRepository
@ApplicationScoped
public class CoffeeRepositoryJpa extends AbstractRsqlRepository<CoffeeEntity, Long> implements CoffeeRepository {

    @Inject
    public CoffeeRepositoryJpa(EntityManager entityManager) {
        super(entityManager, CoffeeEntity.class);
    }

    @Override
    public List<CoffeeEntity> findByOrigin(String origin) {
        return search(Restriction.equal(CoffeeEntity_.origin, origin));
    }

}
```

`RsqlRepository` adds `search(String rsql, ...)` and `count(String rsql)` to the contract, with the same no, offset
and cursor pagination overloads as the base library's `findAll`:

```java
coffeeRepository.search("origin==Ethiopia;strength=gt=5", Pageable.of(0, 20, Sort.parse("-price")));
coffeeRepository.search("notes.flavour==Citrus,notes.flavour==Floral", Cursor.first(20, Sort.NONE));
coffeeRepository.count("origin==Ethiopia");
```

A blank or `null` query falls back to `findAll`/`count()`.

## Filtering rules

### Selectors

RSQL filtering and sorting share the very same `searchableProperties()` override of the repository: an RSQL
selector must either be one of the declared public names, or already be the entity attribute path one of them is
aliased to — so `roaster==...` works, and so does `roaster.name==...` since `roaster` already exposes it, but a path
that is the target of no declared property is rejected. When `searchableProperties()` is not overridden, every
attribute of the entity is reachable as is, nested and collection properties included, such as `notes.flavour`.

A selector is then resolved against the metamodel, attribute by attribute, and each of them is navigated as its
kind requires: an association is joined, a collection has its elements compared one by one, and an embeddable is
read on the spot, or joined when an association behind it has to be. A selector therefore reaches as deep as the
model goes, such as `notes.coffee.roaster.name` through a collection and two associations, or `cupping.cuppedBy.name`
through an embeddable, and every attribute of the elements of a collection is named on the elements themselves, such
as the `blend.share` of a blend component. A selector naming an attribute the entity does not have, or reaching
through an attribute that has none, such as `name.origin`, is rejected.

An association or an embeddable named as the last attribute of a selector is compared as a whole, which only the
`null` argument and the null operators do: `roaster==null` matches the entities having no roaster and
`roaster=notnull=` those having one. A collection of entities or embeddables, on the other hand, is rejected as
one, `roaster.coffees==null` comparing nothing an argument can be read as; compare an attribute of its elements
instead, as `roaster.coffees.name==Geisha` does.

### Operators

- **`==` and `!=`** compare a string to its argument as a pattern, ignoring the case, where `*` stands for any
  characters as the `%` and `_` of SQL do, such as `name==*geisha*`. Anything else is compared to the very value
  its argument spells, `==null` matching the entities having no such value and `!=null` those having one.
- **`=gt=`, `=ge=`, `=lt=` and `=le=`**, also written `>`, `>=`, `<` and `<=`, compare a property to the very value
  its argument spells, dates and times included: `packedAt=gt=2024-01-01T10:00:00` matches what was packed strictly
  after that instant.
- **`=in=` and `=out=`** compare a property to a list of exact values, no pattern involved, such as
  `origin=in=(Ethiopia,Panama)`. An empty list matches nothing, and an empty exclusion everything.
- **`=null=` and `=notnull=`** take no argument and match the entities having no such value, or one, as `==null`
  and `!=null` do.

Conditions are combined with `;` (and), `,` (or) and parentheses, as the RSQL specification describes.

### Arguments

An argument is read as the very type of the property it is compared to, as the metamodel reports it:

- **A string**, a boolean spelled `true` or `false` whatever its case, a single character, a `byte`, a `short`, an
  `int`, a `long`, a `float`, a `double`, a `BigInteger` or a `BigDecimal`.
- **A constant of an enumeration**, by its very name, such as `roast==LIGHT`.
- **A `UUID`**, written in its canonical form.
- **A `java.time` value** in its ISO 8601 form: an `Instant` as `2024-01-01T10:00:00Z`, a `LocalDate` as
  `2024-01-01`, a `LocalDateTime` as `2024-01-01T10:00:00`, a `LocalTime` as `10:00:00`, an `OffsetDateTime`,
  an `OffsetTime` or a `ZonedDateTime` with their offset, a `Year` as `2024` and a `Duration` as `PT1H30M`.
- **A legacy date**: a `java.util.Date` written as `2024-01-01` or `2024-01-01T10:00:00`, in the time zone of the
  server, and a `java.sql` date, time or timestamp as its own `valueOf` reads it.
- **A value type of the application** exposing a static `valueOf(String)` method, the convention the JDK value
  types follow; teach the dialect any other one, see [Extending the dialect](#extending-the-dialect).

The `null` literal, whatever its case, stands for the absence of a value rather than for the text `null`, so that
`decafLabel==null` matches the entities having none.

### Queries

An RSQL query is translated into a `Criteria` of the base library, which the repository applies exactly as one
written by hand: a cursor query checks its ordering keys the same way, refusing a nullable one before the first page
is read. Each comparison reaching through an association or a collection, such as `notes.flavour==Citrus`, is
evaluated in a correlated `exists` subquery of its own rather than joined by the query itself. An entity with several
matching children is therefore returned and counted once, without the `distinct` PostgreSQL and Oracle refuse to
order on a joined attribute, and an entity with no associated row still matches the other alternatives of an OR,
such as `roaster.name=="Kaldi Roasting",origin==Ethiopia` for a coffee from Ethiopia having no roaster. Each
comparison is evaluated in a subquery of its own, so that two of them reaching through the same collection are
satisfied by two different elements, as `notes.flavour==Citrus;notes.flavour==Floral` is by a coffee having both.

### Refused as a bad request

A malformed expression raises the `RSQLParserException` of the parser, and everything below an
`IllegalArgumentException`, both before any statement is issued: they are mistakes of the API consumer, to be
answered with a `400 Bad Request` rather than with a `500`, a stack overflow, a multi-second bind or a silently
wrong result.

- **A selector that is not searchable**, that names an attribute the entity does not have, or that reaches through
  an attribute having none, such as `name.origin`.
- **An operator the dialect does not hold**, which its parser refuses while the query is parsed.
- **An argument its property cannot be read as**, such as `strength==strong`, `organic==yes`, which would otherwise
  be read as `false`, `roast==light`, a constant being named as it is declared, or an argument of a type no value is
  read as, such as a `Calendar` or a `byte[]`.
- **An argument the mapping of its property cannot hold**, such as a string an attribute converter stores as the
  number it spells, which Hibernate would otherwise refuse while binding the statement.
- **A date or time argument written otherwise than described above**, so that `2024-01-01T10:00` is refused rather
  than silently read as midnight, `2024-02-30` rather than read as March 1st, and a year of more than four digits,
  such as `300000-01-01`, rather than refused by PostgreSQL once executing the statement. A `Duration` longer than
  the nanoseconds of a `long` is refused for the same reason.
- **A comparison ordering a property nothing orders**, such as `roaster=gt=null` on an association.
- **A collection of entities or embeddables compared as a whole**, `null` included, such as `roaster.coffees==null`.
- **An expression nesting its parentheses deeper than `AbstractRsqlRepository.MAX_NESTING_DEPTH`** levels, refused
  before the parser recurses into them, since a few kilobytes of parentheses are otherwise enough to overflow the
  stack of the parser, of the translation and of the persistence provider alike.
- **A string pattern holding more than `RsqlQueries.MAX_WILDCARDS` wildcards**, `*` or `%`, not counting those
  ending it, such as `name==*e*e*e*e*x`, which can take H2 seconds to match against a single value, and **any
  pattern matched against a string not held as text**, such as a `@Lob` one, which Hibernate would otherwise refuse
  to lower for the comparison.
- **A decimal or integer argument whose scale lies beyond `RsqlQueries.MAX_DECIMAL_SCALE`**, negative or positive,
  such as `price=lt=1e30000000`, which takes a few bytes to send but seconds for the database to bind, **or written
  with more than `RsqlQueries.MAX_DECIMAL_LENGTH` characters**, whose digits take the JDK seconds to parse by the
  hundred thousand.
- **An argument holding a NUL character**, which PostgreSQL would otherwise refuse once executing the statement.

## Combining with typed queries

`toCriteria(Node)` translates a non-blank RSQL query, parsed by `parse(String)` within that nesting limit, into that
very `Criteria`, so that a repository method can combine it with a `Restriction` or criteria of its own, such as a
scope the API consumers must not escape:

```java
public PaginationResult<CoffeeEntity> searchFromOrigin(String origin, String rsql, Pageable pageable) {
    return search(Restriction.equal(CoffeeEntity_.origin, origin), toCriteria(parse(rsql)), pageable);
}
```

## Extending the dialect

`RsqlDialect` is the RSQL a repository accepts: the comparison operators its queries may use, the predicate each of
them builds and how the arguments of a comparison are read. It is immutable, each `with` method returning a new one,
and `rsqlDialect()` is the hook handing it over to the parsing and the translation of every query:

```java
private static final ComparisonOperator LIKE = new ComparisonOperator("=like=", Arity.nary(1));

private static final RsqlDialect DIALECT = RsqlDialect.DEFAULT
        // A pattern matched without ignoring the case, its wildcards staying limited
        .withOperator(LIKE, comparison -> comparison.criteriaBuilder()
                .like(comparison.path(), comparison.pattern(comparison.arguments().get(0))))
        // A value type of the application, which no valueOf(String) reads
        .withArgumentType(Money.class, Money::parse)
        // An operator the API consumers are not offered
        .withoutOperator(RSQLOperators.NOT_IN);

@Override
protected RsqlDialect rsqlDialect() {
    return DIALECT;
}
```

The `RsqlComparison` a `ComparisonPredicate` is given holds everything the predicate is built from: the criteria
builder, the query the predicate belongs to, the path of the compared property, its type, the arguments as the
consumer sent them and the values they are read as. The property is navigated and the arguments are read on demand,
so that an operator comparing something else, such as the size of the collection its `parent()` and `attribute()`
name, is free to do so. Building a `like` through `pattern(String)` keeps the limit on the wildcards a pattern may
hold, and `unsupported()` refuses a comparison as the bad request it is.

The parser of the dialect accepts exactly its operators, so that one it does not know is refused while the query is
parsed. Registering an argument type leaves the reading of every other one untouched, along with the checks applying
to any argument; replacing the parser as a whole, with `withArgumentParser`, gives up the refusals documented above,
which a parser of your own would have to apply as well.

## Contributing

If you have a feature request or found a bug, you can:

- Write an issue
- Create a pull request

If you want to contribute then

- Please write tests covering all your changes
- Ensure you didn't break the build by running `mvn test`
- Fork the repo and create a pull request

## License

This project is under Apache 2.0 License.
