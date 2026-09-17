package com.chavaillaz.jakarta.persistence.repository.rsql;

import static org.apache.commons.lang3.ClassUtils.primitiveToWrapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import cz.jirutka.rsql.parser.RSQLParser;
import cz.jirutka.rsql.parser.ast.ComparisonOperator;
import cz.jirutka.rsql.parser.ast.RSQLOperators;
import org.jspecify.annotations.Nullable;

/**
 * The RSQL a repository accepts: the comparison operators its queries may use, the predicate each of them builds
 * and how the arguments of a comparison are read.
 * <p>
 * {@link #DEFAULT} holds the operators of the RSQL specification, {@code ==}, {@code !=}, {@code =gt=},
 * {@code =ge=}, {@code =lt=}, {@code =le=}, {@code =in=} and {@code =out=}, with their alternatives {@code >},
 * {@code >=}, {@code <} and {@code <=}, along with {@code =null=} and {@code =notnull=}, and reads the arguments
 * with {@link ArgumentParser#DEFAULT}. A dialect is immutable: each {@code with} method returns a new one, so that
 * a repository holds it in a constant and hands it over to every query:
 * <pre>{@code
 * private static final ComparisonOperator LIKE = new ComparisonOperator("=like=", Arity.nary(1));
 *
 * private static final RsqlDialect DIALECT = RsqlDialect.DEFAULT
 *         .withOperator(LIKE, comparison -> ...)
 *         .withArgumentType(Money.class, Money::parse);
 *
 * @Override
 * protected RsqlDialect rsqlDialect() {
 *     return DIALECT;
 * }
 * }</pre>
 * The {@link #parser()} accepts exactly the operators of the dialect, so that an operator it does not know is
 * refused while the query is parsed, before anything is translated.
 *
 * @see AbstractRsqlRepository#rsqlDialect()
 */
public final class RsqlDialect {

    /**
     * The RSQL of the specification, whose arguments are read by {@link ArgumentParser#DEFAULT}.
     */
    public static final RsqlDialect DEFAULT = new RsqlDialect(defaultPredicates(), ArgumentParser.DEFAULT);

    private final Map<ComparisonOperator, ComparisonPredicate> predicates;

    private final ArgumentParser argumentParser;

    private final RSQLParser parser;

    private RsqlDialect(Map<ComparisonOperator, ComparisonPredicate> predicates, ArgumentParser argumentParser) {
        this.predicates = Map.copyOf(predicates);
        this.argumentParser = argumentParser;
        // Built once, a parser being immutable, and from the very operators this dialect translates
        this.parser = new RSQLParser(Set.copyOf(predicates.keySet()));
    }

    /**
     * Builds the predicates of the RSQL operators, {@code =null=} and {@code =notnull=} included, which the
     * parser of the specification also accepts.
     *
     * @return The predicate of each operator
     */
    private static Map<ComparisonOperator, ComparisonPredicate> defaultPredicates() {
        Map<ComparisonOperator, ComparisonPredicate> predicates = new LinkedHashMap<>();
        predicates.put(RSQLOperators.EQUAL, ComparisonPredicates::equal);
        predicates.put(RSQLOperators.NOT_EQUAL, ComparisonPredicates::notEqual);
        predicates.put(RSQLOperators.GREATER_THAN, ComparisonPredicates::greaterThan);
        predicates.put(RSQLOperators.GREATER_THAN_OR_EQUAL, ComparisonPredicates::greaterThanOrEqual);
        predicates.put(RSQLOperators.LESS_THAN, ComparisonPredicates::lessThan);
        predicates.put(RSQLOperators.LESS_THAN_OR_EQUAL, ComparisonPredicates::lessThanOrEqual);
        predicates.put(RSQLOperators.IN, ComparisonPredicates::in);
        predicates.put(RSQLOperators.NOT_IN, ComparisonPredicates::notIn);
        predicates.put(RSQLOperators.IS_NULL, ComparisonPredicates::isNull);
        predicates.put(RSQLOperators.NOT_NULL, ComparisonPredicates::isNotNull);
        return predicates;
    }

    /**
     * Adds an operator to this dialect, or replaces the predicate of one it already holds, such as to compare a
     * string without ignoring its case.
     *
     * @param operator  The operator, whose symbol the queries use and whose arity the parser checks
     * @param predicate The predicate a comparison using it builds
     * @return The corresponding dialect
     * @throws IllegalArgumentException if another operator of this dialect shares one of its symbols
     */
    public RsqlDialect withOperator(ComparisonOperator operator, ComparisonPredicate predicate) {
        Map<ComparisonOperator, ComparisonPredicate> operators = new LinkedHashMap<>(predicates);
        // Removed first, an operator being keyed by its primary symbol alone, so that its alternatives are the
        // ones of the given operator rather than those of the replaced one
        operators.remove(operator);
        operators.put(operator, predicate);
        return new RsqlDialect(operators, argumentParser);
    }

    /**
     * Removes an operator from this dialect, the parser refusing a query using it from there on, such as to keep
     * the API consumers from sending the {@code =out=} a database cannot answer with an index.
     *
     * @param operator The operator to remove, identified by its primary symbol
     * @return The corresponding dialect
     * @throws IllegalArgumentException if it is the last operator of this dialect, a parser accepting none being
     *                                  of no use
     */
    public RsqlDialect withoutOperator(ComparisonOperator operator) {
        Map<ComparisonOperator, ComparisonPredicate> operators = new LinkedHashMap<>(predicates);
        operators.remove(operator);
        return new RsqlDialect(operators, argumentParser);
    }

    /**
     * Teaches this dialect a type of its own, such as a value type of the application or one behind an attribute
     * converter, whose arguments the given parser reads.
     * <p>
     * The type is matched as the very type of the compared property, a primitive being matched as its wrapper,
     * and nothing else is changed: the arguments of every other type are still read as they were, and the checks
     * applying to any argument still apply, see {@link RsqlComparison#parse(String, Class)}.
     *
     * @param <T>    The type to read
     * @param type   The type to read, as the metamodel reports it for the compared property
     * @param parser The parser reading an argument as that type, raising a {@link RuntimeException} for an
     *               argument that is no valid value of it
     * @return The corresponding dialect
     */
    public <T> RsqlDialect withArgumentType(Class<T> type, Function<String, ? extends T> parser) {
        Class<?> target = primitiveToWrapper(type);
        ArgumentParser others = argumentParser;
        return withArgumentParser((argument, parsed) -> parsed.equals(target) ? parser.apply(argument) : others.parse(argument, parsed));
    }

    /**
     * Replaces the parser reading the arguments of the comparisons.
     * <p>
     * Prefer {@link #withArgumentType(Class, Function)}, which teaches the default parser a type of its own:
     * that parser refuses the arguments {@link ArgumentParser#DEFAULT} documents, which a parser replacing it
     * would have to refuse as well.
     *
     * @param argumentParser The parser to read the arguments with
     * @return The corresponding dialect
     */
    public RsqlDialect withArgumentParser(ArgumentParser argumentParser) {
        return new RsqlDialect(predicates, argumentParser);
    }

    /**
     * Gets the operators of this dialect, which its {@link #parser()} accepts.
     *
     * @return The operators of this dialect
     */
    public Set<ComparisonOperator> operators() {
        return predicates.keySet();
    }

    /**
     * Gets the predicate an operator builds, to reuse the meaning of a default operator for one of your own, such
     * as an alternative symbol.
     *
     * @param operator The operator, identified by its primary symbol
     * @return The corresponding predicate, or {@code null} if this dialect does not hold that operator
     */
    public @Nullable ComparisonPredicate predicate(ComparisonOperator operator) {
        return predicates.get(operator);
    }

    /**
     * Gets the parser reading the arguments of the comparisons as the type of their property.
     *
     * @return The parser of the arguments
     */
    public ArgumentParser argumentParser() {
        return argumentParser;
    }

    /**
     * Gets the parser converting the RSQL queries into nodes, accepting exactly the operators of this dialect.
     *
     * @return The parser of this dialect, shared since it is immutable
     * @see AbstractRsqlRepository#parse(String)
     */
    public RSQLParser parser() {
        return parser;
    }

}
