package com.chavaillaz.jakarta.persistence.rsql;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Predicates of the RSQL comparison operators, as {@link RsqlDialect#DEFAULT} holds them.
 * <p>
 * A string compared with {@code ==} or {@code !=} is matched against its argument as a pattern, where {@code *}
 * stands for any characters, ignoring the case, which is the RSQL convention; anything else is compared to the
 * very value its argument spells, the {@code null} literal comparing a property to nothing.
 */
final class ComparisonPredicates {

    private ComparisonPredicates() {
        // This utility class should not be instantiated
    }

    /**
     * Matches the entities whose property equals the argument, a string being matched against it as a pattern and
     * the {@code null} literal matching those having none.
     *
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     */
    static Predicate equal(RsqlComparison comparison) {
        CriteriaBuilder criteriaBuilder = comparison.criteriaBuilder();
        Object value = comparison.value();
        if (value == null) {
            return criteriaBuilder.isNull(comparison.path());
        }
        if (value instanceof String text) {
            return criteriaBuilder.like(criteriaBuilder.lower(comparison.path()), comparison.pattern(text).toLowerCase());
        }
        return criteriaBuilder.equal(comparison.path(), value);
    }

    /**
     * Matches the entities whose property differs from the argument, a string being matched against it as a
     * pattern and the {@code null} literal matching those having one.
     *
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     */
    static Predicate notEqual(RsqlComparison comparison) {
        CriteriaBuilder criteriaBuilder = comparison.criteriaBuilder();
        Object value = comparison.value();
        if (value == null) {
            return criteriaBuilder.isNotNull(comparison.path());
        }
        if (value instanceof String text) {
            return criteriaBuilder.notLike(criteriaBuilder.lower(comparison.path()), comparison.pattern(text).toLowerCase());
        }
        return criteriaBuilder.notEqual(comparison.path(), value);
    }

    /**
     * Matches the entities whose property is strictly greater than the argument.
     *
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     */
    static Predicate greaterThan(RsqlComparison comparison) {
        return order(comparison, CriteriaBuilder::greaterThan);
    }

    /**
     * Matches the entities whose property is greater than or equal to the argument.
     *
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     */
    static Predicate greaterThanOrEqual(RsqlComparison comparison) {
        return order(comparison, CriteriaBuilder::greaterThanOrEqualTo);
    }

    /**
     * Matches the entities whose property is strictly less than the argument.
     *
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     */
    static Predicate lessThan(RsqlComparison comparison) {
        return order(comparison, CriteriaBuilder::lessThan);
    }

    /**
     * Matches the entities whose property is less than or equal to the argument.
     *
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     */
    static Predicate lessThanOrEqual(RsqlComparison comparison) {
        return order(comparison, CriteriaBuilder::lessThanOrEqualTo);
    }

    /**
     * Matches the entities whose property is one of the arguments, none matching nothing.
     *
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     */
    static Predicate in(RsqlComparison comparison) {
        return comparison.path().in(comparison.values());
    }

    /**
     * Matches the entities whose property is none of the arguments, none matching everything.
     *
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     */
    static Predicate notIn(RsqlComparison comparison) {
        return comparison.criteriaBuilder().not(in(comparison));
    }

    /**
     * Matches the entities having no such property value, as {@code ==null} does.
     *
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     */
    static Predicate isNull(RsqlComparison comparison) {
        return comparison.criteriaBuilder().isNull(comparison.path());
    }

    /**
     * Matches the entities having such a property value, as {@code !=null} does.
     *
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     */
    static Predicate isNotNull(RsqlComparison comparison) {
        return comparison.criteriaBuilder().isNotNull(comparison.path());
    }

    /**
     * Orders the property of a comparison against its argument, the path being refused for a property nothing
     * orders and the argument being read as the very type of that property.
     *
     * @param comparison The comparison to build the predicate of
     * @param ordering   The ordering comparison to build
     * @return The corresponding predicate
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Predicate order(RsqlComparison comparison, Ordering ordering) {
        // Both sides hold the very type of the compared property, which the ordered path checked to be comparable
        return ordering.compare(comparison.criteriaBuilder(), comparison.orderedPath(), (Comparable) comparison.value());
    }

    /**
     * An ordering comparison of the criteria builder, such as {@link CriteriaBuilder#greaterThan}.
     */
    @FunctionalInterface
    private interface Ordering {

        /**
         * Builds the predicate ordering an expression against a value.
         *
         * @param criteriaBuilder The builder to use
         * @param path            The path of the compared property
         * @param value           The value to compare it to, {@code null} matching nothing
         * @return The corresponding predicate
         */
        Predicate compare(CriteriaBuilder criteriaBuilder, Expression<Comparable<Object>> path, @Nullable Comparable<Object> value);

    }

}
