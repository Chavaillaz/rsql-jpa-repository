package com.chavaillaz.jakarta.persistence.repository.rsql;

import jakarta.persistence.criteria.Predicate;

import cz.jirutka.rsql.parser.ast.ComparisonOperator;

/**
 * Meaning of an RSQL comparison operator: builds the predicate keeping the entities a comparison using it
 * matches, such as the {@code lower(name) like ?} of {@code name==Geisha*}.
 * <p>
 * The predicates of the RSQL operators are the ones {@link RsqlDialect#DEFAULT} holds. Register one of your own,
 * for an operator of your own or in place of a default one, with
 * {@link RsqlDialect#withOperator(ComparisonOperator, ComparisonPredicate)}:
 * <pre>{@code
 * private static final ComparisonOperator LIKE = new ComparisonOperator("=like=", Arity.nary(1));
 *
 * private static final RsqlDialect DIALECT = RsqlDialect.DEFAULT.withOperator(LIKE, comparison ->
 *         comparison.criteriaBuilder().like(comparison.path(), comparison.pattern(comparison.arguments().get(0))));
 * }</pre>
 * Everything a predicate is built from comes from the {@link RsqlComparison} it is given, which navigates the
 * compared property and reads its arguments on demand.
 */
@FunctionalInterface
public interface ComparisonPredicate {

    /**
     * Builds the predicate of a comparison.
     *
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     * @throws IllegalArgumentException if the comparison cannot be built, its arguments or the type of its
     *                                 property not being ones the operator compares, this being a filter an API
     *                                 consumer sent, see {@link RsqlComparison#unsupported()}
     */
    Predicate toPredicate(RsqlComparison comparison);

}
