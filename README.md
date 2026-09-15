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
have to pull in the RSQL parser and its criteria visitor.

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

RSQL filtering and sorting share the very same `searchableProperties()` override of the repository: an RSQL
selector must either be one of the declared public names, or already be the entity attribute path one of them is
aliased to — so `roaster==...` works, and so does `roaster.name==...` since `roaster` already exposes it, but a path
that is the target of no declared property is rejected. When `searchableProperties()` is not overridden, every
attribute of the entity is reachable as is, nested and collection properties included, such as `notes.flavour`.

An RSQL query is translated into a `Criteria` of the base library, which the repository applies exactly as one
written by hand: a cursor query checks its ordering keys the same way, refusing a nullable one before the first page
is read. Each comparison reaching through an association, such as `notes.flavour==Citrus`, is evaluated in a
correlated `exists` subquery of its own rather than joined by the query itself. An entity with several matching
children is therefore returned and counted once, without the `distinct` PostgreSQL and Oracle refuse to order on a
joined attribute, and an entity with no associated row still matches the other alternatives of an OR, such as
`roaster.name=="Kaldi Roasting",origin==Ethiopia` for a coffee from Ethiopia having no roaster.

A malformed expression raises the `RSQLParserException` of the parser, and a selector that is not searchable or that
cannot be navigated, such as `name.roaster.name`, or an argument its property cannot be parsed from, such as
`strength==strong`, an `IllegalArgumentException`: both are mistakes of the API consumer, to be answered with a
`400 Bad Request`. So is an expression nesting its parentheses deeper than `AbstractRsqlRepository.MAX_NESTING_DEPTH`
levels, refused with an `IllegalArgumentException` before the parser recurses into them, since a few kilobytes of
parentheses are otherwise enough to overflow the stack. So is a decimal argument whose scale lies beyond
`RsqlQueries.MAX_DECIMAL_SCALE`, negative or positive, such as `price=lt=1e30000000`, which takes a few bytes to send
but seconds for the database to bind, or which is written with more than `RsqlQueries.MAX_DECIMAL_LENGTH` characters,
whose digits take the JDK seconds to parse by the hundred thousand. A boolean argument is read from `true` or `false`
only, whatever its case, so that `organic==yes` is refused rather than silently read as `false`.

## Combining with typed queries

`toCriteria(Node)` translates a non-blank RSQL query, parsed by `parse(String)` within that nesting limit, into that
very `Criteria`, so that a repository method can combine it with a `Restriction` or criteria of its own, such as a
scope the API consumers must not escape:

```java
public PaginationResult<CoffeeEntity> searchFromOrigin(String origin, String rsql, Pageable pageable) {
    return search(Restriction.equal(CoffeeEntity_.origin, origin), toCriteria(parse(rsql)), pageable);
}
```

## Overriding the visitor

`createPredicateVisitor()` builds the visitor converting an RSQL query node into a predicate on the entity; override
it to customize the property mapping, the argument parsing or the predicate building through its builder tools, for
instance to support a custom RSQL operator:

```java
@Override
protected JpaPredicateVisitor<CoffeeEntity> createPredicateVisitor() {
    JpaPredicateVisitor<CoffeeEntity> visitor = RsqlQueries.defaultPredicateVisitor(CoffeeEntity.class);
    // Customize the tools of the visitor here, such as with visitor.getBuilderTools().setPredicateBuilder(...)
    return visitor;
}
```

The visitor holds the root it builds its predicate on, and a query applies its criteria more than once, so return a
new visitor at each call. Customize the builder tools the default visitor holds rather than replacing them: its
argument parser refuses the arguments described above, which a parser of your own would have to refuse as well.

A custom `RSQLParser`, supporting additional operators, can be passed to the `AbstractRsqlRepository` constructor:

```java
public CoffeeRepositoryJpa(EntityManager entityManager) {
    super(entityManager, CoffeeEntity.class, new RSQLParser(customOperators));
}
```

## Logging

The rsql-jpa visitor translating the queries logs every node and every argument it handles at `INFO`, through
`java.util.logging`, whose default console handler prints them: raise the level of its loggers, so that the filters
sent by the API consumers are not written to the logs at every request, such as in the `logging.properties` of the
application:

```properties
com.github.tennaito.rsql.level = WARNING
```

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
