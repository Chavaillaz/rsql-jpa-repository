package com.chavaillaz.jakarta.persistence.rsql;

import static org.apache.commons.lang3.ClassUtils.primitiveToWrapper;
import static org.apache.commons.lang3.StringUtils.abbreviate;
import static org.apache.commons.lang3.StringUtils.stripEnd;

import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.metamodel.Attribute;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import cz.jirutka.rsql.parser.ast.ComparisonNode;
import cz.jirutka.rsql.parser.ast.ComparisonOperator;
import org.hibernate.query.sqm.tree.domain.SqmPath;
import org.hibernate.type.ConvertedBasicType;
import org.jspecify.annotations.Nullable;

/**
 * A single comparison of an RSQL query being translated, such as the {@code strength=gt=5} of
 * {@code origin==Ethiopia;strength=gt=5}, handed over to the {@link ComparisonPredicate} of its operator with
 * everything the predicate is built from: the path of the compared property, its type, and the arguments read as
 * that type.
 * <p>
 * The property is navigated and the arguments are read on demand, so that an operator comparing something else,
 * such as the size of a collection or an argument of its own type, is free to do so; the arguments of a
 * comparison a query applies more than once are read once per application.
 * <p>
 * This is handed over, never built: a comparison is created by the translation for each application of the
 * criteria, and belongs to that application alone.
 *
 * @see RsqlDialect#withOperator(ComparisonOperator, ComparisonPredicate)
 */
public final class RsqlComparison {

    /**
     * The largest number of wildcards, {@code *} or {@code %}, the argument of a comparison matching a string as a
     * pattern may hold, not counting those ending it. A database may evaluate a {@code like} by trying every
     * position of the value each wildcard may stand for, so that each wildcard followed by more of the pattern
     * multiplies the work by up to the length of the value: the few bytes of {@code *e*e*e*e*x} would otherwise
     * take H2 some 24 seconds to match against a single string of 255 e.
     *
     * @see #pattern(String)
     */
    public static final int MAX_WILDCARDS = 3;

    /**
     * The wildcards of a {@code like} pattern, the {@code *} of the RSQL convention and the {@code %} of SQL,
     * which the databases treat alike.
     */
    private static final String WILDCARDS = "*%";

    /**
     * The number of characters of an argument or a selector an error message quotes, enough to recognize a filter
     * without writing the quarter of a megabyte a consumer is free to send into the logs of the application.
     */
    static final int MAX_QUOTED_LENGTH = 100;

    private final ComparisonNode node;

    private final PropertyPath property;

    private final RsqlDialect dialect;

    private final CriteriaBuilder criteriaBuilder;

    private final CommonAbstractCriteria query;

    private final From<?, ?> from;

    private @Nullable Path<?> parent;

    private @Nullable Path<?> path;

    /**
     * Creates the comparison handed over to the predicate of its operator.
     *
     * @param node            The comparison node, its selector being the one the API consumer sent
     * @param property        The selector resolved against the searchable properties and the metamodel
     * @param dialect         The dialect the query is translated with
     * @param criteriaBuilder The builder the predicate is created with
     * @param query           The query or subquery the predicate belongs to
     * @param from            The root or join the property is navigated from
     */
    RsqlComparison(ComparisonNode node, PropertyPath property, RsqlDialect dialect, CriteriaBuilder criteriaBuilder, CommonAbstractCriteria query, From<?, ?> from) {
        this.node = node;
        this.property = property;
        this.dialect = dialect;
        this.criteriaBuilder = criteriaBuilder;
        this.query = query;
        this.from = from;
    }

    /**
     * Gets the operator of the comparison, whose {@link ComparisonPredicate} is building its predicate.
     *
     * @return The operator of the comparison
     */
    public ComparisonOperator operator() {
        return node.getOperator();
    }

    /**
     * Gets the selector of the comparison as the API consumer sent it, which the error messages name rather than
     * the entity attribute path it resolves to, the searchable properties of a repository decoupling the two.
     *
     * @return The selector of the comparison
     */
    public String selector() {
        return node.getSelector();
    }

    /**
     * Gets the arguments of the comparison as the API consumer sent them, {@link #values()} reading them as the
     * type of the compared property.
     *
     * @return The arguments of the comparison, at least one unless its operator takes none
     */
    public List<String> arguments() {
        return node.getArguments();
    }

    /**
     * Gets the builder the predicate of the comparison is created with.
     *
     * @return The builder of the query
     */
    public CriteriaBuilder criteriaBuilder() {
        return criteriaBuilder;
    }

    /**
     * Gets the query the predicate belongs to, to create a subquery of a comparison from. This is the correlated
     * subquery of the comparison itself when it reaches through an association or a collection, see
     * {@link RsqlFilter}.
     *
     * @return The query or subquery the predicate belongs to
     */
    public CommonAbstractCriteria query() {
        return query;
    }

    /**
     * Gets the Java type of the compared values, the element type when a collection is compared, as the metamodel
     * reports it, a primitive being reported as its wrapper so that a value read for it fits.
     *
     * @return The Java type of the compared values
     */
    public Class<?> type() {
        return primitiveToWrapper(property.javaType());
    }

    /**
     * Gets the path of the compared property, its type being inferred from the call site as
     * {@link Path#get(String)} infers it, the path having been resolved against the metamodel beforehand.
     * <p>
     * An association or a collection the property is reached through is joined, and so is the compared property
     * itself when it is a collection, its elements being compared one by one within the correlated subquery of the
     * comparison.
     *
     * @param <T> The type of the compared values, inferred from the call site
     * @return The path of the compared property
     * @throws IllegalArgumentException if the compared property is a collection of entities or embeddables, which
     *                                  no argument compares to
     */
    @SuppressWarnings("unchecked")
    public <T> Path<T> path() {
        if (property.plural() && property.managed()) {
            // Only the attributes of such an element compare to an argument, as in notes.flavour, and an element
            // reached through an inner join is never null, so that a comparison to null would match nothing
            throw new IllegalArgumentException("Cannot filter on collection property " + selector());
        }
        if (path == null) {
            path = property.navigate(parent());
        }
        return (Path<T>) path;
    }

    /**
     * Gets the path of the compared property as an ordered expression, for a comparison ordering it.
     *
     * @param <Y> The type of the compared values, inferred from the call site
     * @return The path of the compared property
     * @throws IllegalArgumentException if the compared property is of a type nothing orders, such as an
     *                                  association or an embeddable, or is a collection of them
     */
    @SuppressWarnings("unchecked")
    public <Y extends Comparable<? super Y>> Path<Y> orderedPath() {
        // Navigated first, so that a collection is refused as one whatever the operator compares it with
        Path<?> ordered = path();
        if (!Comparable.class.isAssignableFrom(type())) {
            throw unsupported();
        }
        return (Path<Y>) ordered;
    }

    /**
     * Gets the path the compared property is read or joined from, which is the root or join of the query for a
     * property of the entity itself, so that an operator comparing something else than the property, such as the
     * size of a collection, can navigate it itself.
     *
     * @return The path of the owner of the compared property
     */
    public Path<?> parent() {
        if (parent == null) {
            parent = property.parent(from);
        }
        return parent;
    }

    /**
     * Gets the compared attribute as the metamodel describes it, the collection itself when its elements are
     * compared.
     *
     * @return The compared attribute
     */
    public Attribute<?, ?> attribute() {
        return property.attribute();
    }

    /**
     * Reads the only argument of the comparison as the type of the compared property, for an operator taking one,
     * which is every operator but those of an {@link cz.jirutka.rsql.parser.ast.Arity arity} of zero.
     *
     * @return The corresponding value, or {@code null} when the argument is the {@code null} literal
     * @throws IllegalArgumentException if the argument is no valid value of that type, or one the mapping of the
     *                                  property cannot hold, or if the operator of the comparison carries none
     * @see #parse(String, Class)
     */
    public @Nullable Object value() {
        List<String> arguments = arguments();
        if (arguments.isEmpty()) {
            // The predicate of an operator declared to take no argument asked for one, which only the dialect
            // registering the two can tell apart, the parser having accepted the comparison as written
            throw new IllegalArgumentException("Cannot filter on property %s with %s, which carries no argument".formatted(selector(), operator()));
        }
        return read(arguments.get(0));
    }

    /**
     * Reads every argument of the comparison as the type of the compared property, for an operator taking a list
     * of them, such as {@code =in=}.
     *
     * @return The corresponding values, in order, a {@code null} standing for the {@code null} literal
     * @throws IllegalArgumentException if one of the arguments is no valid value of that type, or one the mapping
     *                                  of the property cannot hold
     * @see #parse(String, Class)
     */
    public List<@Nullable Object> values() {
        return arguments().stream()
                .map(this::read)
                .toList();
    }

    /**
     * Reads an argument as the type of the compared property and checks the mapping of that property can convert
     * it, an attribute converter being free to refuse a value of the very type it converts.
     *
     * @param argument The argument to read
     * @return The corresponding value, or {@code null} when the argument is the {@code null} literal
     * @throws IllegalArgumentException if the argument is no valid value of that type, or one the mapping of the
     *                                  property cannot hold
     */
    private @Nullable Object read(String argument) {
        Object value = parse(argument, type());
        if (value != null) {
            requireConvertible(argument, value);
        }
        return value;
    }

    /**
     * Checks that the mapping of the compared property can convert a value into the one its column holds, which
     * an attribute converter is free to refuse: a string a converter stores as the number it spells would
     * otherwise fail the statement while binding it, with the very exception raised here, which an API layer
     * answers with a 500.
     *
     * @param argument The argument the value was read from, which the message quotes
     * @param value    The value to convert
     * @throws IllegalArgumentException if the converter of the property refuses the value
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void requireConvertible(String argument, Object value) {
        if (!(path() instanceof SqmPath<?> path) || !(path.getNodeType() instanceof ConvertedBasicType converted)) {
            return;
        }
        try {
            converted.getValueConverter().toRelationalValue(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot filter on property %s with argument '%s', which the property cannot hold"
                    .formatted(selector(), abbreviate(argument, MAX_QUOTED_LENGTH)), e);
        }
    }

    /**
     * Reads an argument as the given type, with the {@link ArgumentParser} of the dialect, refusing beforehand
     * what no query should run with, whatever that parser is:
     * <ul>
     *     <li>a collection or a map as a whole, {@code null} included, which no argument compares to and which
     *     the persistence provider may only refuse once it renders the statement;</li>
     *     <li>an argument holding a NUL character, which PostgreSQL refuses in any text, failing the statement.</li>
     * </ul>
     * The {@code null} literal of the RSQL convention, whatever its case, is read as {@code null} rather than
     * handed over to the parser, so that {@code ==} and {@code !=} compare the property to nothing.
     * <p>
     * Whatever the parser gives back is checked to be a value of the very type it was asked to read, and to be a
     * value at all, which a parser of its own is free to get wrong: a value of another type would be compared to
     * the property anyway, silently by an ordering comparison, and bound as a parameter no column holds, where
     * nothing at all would compare the property to {@code null} as the {@code null} literal does.
     *
     * @param argument The argument to read
     * @param type     The type to read it as, a primitive being read as its wrapper
     * @return The corresponding value, or {@code null} when the argument is the {@code null} literal
     * @throws IllegalArgumentException if the argument is no valid value of that type, this being a filter an API
     *                                  consumer sent, or if the parser of the dialect read it as another type or
     *                                  as no value at all
     */
    public @Nullable Object parse(String argument, Class<?> type) {
        Class<?> target = primitiveToWrapper(type);
        if (Collection.class.isAssignableFrom(target) || Map.class.isAssignableFrom(target)) {
            throw new IllegalArgumentException("Cannot filter on collection property " + selector());
        }
        requireNoNul(argument);
        if ("null".equalsIgnoreCase(argument.trim())) {
            return null;
        }
        Object value;
        try {
            value = dialect.argumentParser().parse(argument, target);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot filter on property %s with argument '%s', not a valid %s"
                    .formatted(selector(), abbreviate(argument, MAX_QUOTED_LENGTH), target.getSimpleName()), e);
        }
        if (value == null) {
            // Only a parser of its own gives nothing back, the null literal having been read above: the argument
            // would otherwise mean the very literal it is not, so that name!=Geisha would match every named
            // coffee, Geisha included, rather than telling the consumer their argument was read as no value
            throw new IllegalArgumentException("Cannot filter on property %s with argument '%s', read as no value at all"
                    .formatted(selector(), abbreviate(argument, MAX_QUOTED_LENGTH)));
        }
        if (!target.isInstance(value)) {
            throw new IllegalArgumentException("Cannot filter on property %s with argument '%s', read as %s rather than %s"
                    .formatted(selector(), abbreviate(argument, MAX_QUOTED_LENGTH), value.getClass().getSimpleName(), target.getSimpleName()));
        }
        return value;
    }

    /**
     * Refuses an argument holding a NUL character, which PostgreSQL refuses in any text, failing the statement
     * with the very exception an API layer answers with a 500.
     *
     * @param argument The argument to check
     * @throws IllegalArgumentException if it holds a NUL character
     */
    private void requireNoNul(String argument) {
        if (argument.indexOf(Character.MIN_VALUE) >= 0) {
            throw new IllegalArgumentException("Cannot filter on property %s with an argument holding a NUL character".formatted(selector()));
        }
    }

    /**
     * Turns an argument into the pattern of a {@code like}, where the {@code *} of the RSQL convention stands for
     * any characters as the {@code %} of SQL does, refusing a pattern holding more wildcards than
     * {@link #MAX_WILDCARDS}, which a database may take seconds to match. Those ending a pattern match the rest of
     * a value at once, whatever its length, and are not counted.
     * <p>
     * A pattern holding a NUL character is refused as well: the predicate of an operator of its own builds its
     * {@code like} from a raw argument, which no {@link #parse(String, Class)} has read.
     *
     * @param argument The argument to turn into a pattern
     * @return The corresponding pattern
     * @throws IllegalArgumentException if the argument holds more wildcards than {@link #MAX_WILDCARDS}, or holds
     *                                  a NUL character
     */
    public String pattern(String argument) {
        requireNoNul(argument);
        long wildcards = stripEnd(argument, WILDCARDS).chars()
                .filter(character -> WILDCARDS.indexOf(character) >= 0)
                .count();
        if (wildcards > MAX_WILDCARDS) {
            throw new IllegalArgumentException("Cannot filter on property %s with a pattern of more than %d wildcards".formatted(selector(), MAX_WILDCARDS));
        }
        return argument.replace('*', '%');
    }

    /**
     * Builds the exception refusing this comparison, for a property its operator cannot compare, such as an
     * association ordered by {@code =gt=} or a string the persistence provider does not hold as text.
     *
     * @return The exception to throw
     */
    public IllegalArgumentException unsupported() {
        return unsupported(null);
    }

    /**
     * Builds the exception refusing this comparison, carrying what the persistence provider raised for it.
     *
     * @param cause The exception to carry, or {@code null}
     * @return The exception to throw
     * @see #unsupported()
     */
    public IllegalArgumentException unsupported(@Nullable Throwable cause) {
        return new IllegalArgumentException("Cannot filter on property %s with %s".formatted(selector(), operator()), cause);
    }

}
