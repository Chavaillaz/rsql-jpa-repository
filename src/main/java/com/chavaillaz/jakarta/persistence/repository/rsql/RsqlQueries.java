package com.chavaillaz.jakarta.persistence.repository.rsql;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiFunction;

import cz.jirutka.rsql.parser.ast.AndNode;
import cz.jirutka.rsql.parser.ast.ComparisonNode;
import cz.jirutka.rsql.parser.ast.LogicalNode;
import cz.jirutka.rsql.parser.ast.NoArgRSQLVisitorAdapter;
import cz.jirutka.rsql.parser.ast.Node;
import cz.jirutka.rsql.parser.ast.OrNode;
import org.hibernate.query.sqm.produce.function.FunctionArgumentException;

import com.chavaillaz.jakarta.persistence.repository.Criteria;
import com.chavaillaz.jakarta.persistence.repository.EntityOrdering;
import com.chavaillaz.jakarta.persistence.repository.EntityQueries;
import com.chavaillaz.jakarta.persistence.repository.RepositoryContext;

/**
 * RSQL support of an entity type, translating a filter expression into {@link Criteria}, which the
 * {@link EntityQueries queries} of the entity apply like any other.
 * <p>
 * The predicate is built on the root each query hands over, so that a cursor query checks and selects its keys
 * exactly as for criteria written by hand. Every selector is resolved against the searchable properties of the
 * repository, so that the very same restriction and public naming govern the filtering and the sorting, and then
 * against the metamodel, so that a selector the entities cannot be filtered on is refused before anything is
 * built, see {@link #compare(RepositoryContext, ComparisonNode, RsqlDialect)}.
 * <p>
 * One instance is shared by every repository over the same entity, what belongs to a repository travelling with
 * the {@link RepositoryContext} of each call, and the operators of the queries with the {@link RsqlDialect}.
 *
 * @param <E> The type of the managed entity
 */
public class RsqlQueries<E> {

    /**
     * The largest {@link BigDecimal#scale() scale}, negative or positive, a decimal argument may have, as many digits
     * as the largest precision a PostgreSQL numeric column may be declared with. The database or its driver may spell
     * a decimal out digit by digit to bind it, as H2 does, so that the few bytes of an argument such as
     * {@code 1e30000000} would otherwise take seconds to bind, only for the database to refuse a value no column holds.
     */
    public static final int MAX_DECIMAL_SCALE = 1000;

    /**
     * The largest number of characters a decimal or integer argument may be written with, twice
     * {@link #MAX_DECIMAL_SCALE}, enough to spell out any value a numeric column may be declared to hold. The JDK
     * parses the digits of such a number in quadratic time, and the criteria of a query are applied more than once,
     * so that the quarter of a megabyte of digits a consumer is free to send in the body of a request would otherwise
     * take seconds to parse, only for H2 to refuse the value.
     */
    public static final int MAX_DECIMAL_LENGTH = 2 * MAX_DECIMAL_SCALE;

    /**
     * The largest number of wildcards, {@code *} or {@code %}, the argument of an {@code ==} or {@code !=} comparison
     * of a string may hold, not counting those ending it. Such a string is matched against its argument with a
     * {@code like}, which H2 evaluates by trying every position of the value each wildcard may stand for, so that each
     * wildcard followed by more of the pattern multiplies the work by up to the length of the value: the few bytes of
     * {@code *e*e*e*e*x} would otherwise take H2 some 24 seconds to match against a single string of 255 e.
     *
     * @see RsqlComparison#pattern(String)
     */
    public static final int MAX_WILDCARDS = 3;

    /**
     * The RSQL support of each entity type, held in a {@link ClassValue} rather than in a map keyed by the class, so
     * that the cache cannot keep a class loader alive after a redeployment.
     */
    private static final ClassValue<RsqlQueries<?>> QUERIES = new ClassValue<>() {

        @Override
        protected RsqlQueries<?> computeValue(Class<?> type) {
            return create(type);
        }

    };

    /**
     * The type of the managed entity.
     */
    protected final Class<E> entityType;

    /**
     * The ordering rules of the entity type, resolving the selectors against the searchable properties.
     */
    protected final EntityOrdering<E> ordering;

    /**
     * Creates the RSQL support of an entity type.
     * <p>
     * Prefer {@link #of(Class)}, which shares one instance per entity type.
     *
     * @param entityType The type of the managed entity
     * @param ordering   The ordering rules of the entity type
     */
    public RsqlQueries(Class<E> entityType, EntityOrdering<E> ordering) {
        this.entityType = entityType;
        this.ordering = ordering;
    }

    /**
     * Gets the RSQL support of the given entity type, shared by every repository over that entity.
     *
     * @param <E>        The type of the managed entity
     * @param entityType The type of the managed entity
     * @return The corresponding RSQL support
     */
    @SuppressWarnings("unchecked")
    public static <E> RsqlQueries<E> of(Class<E> entityType) {
        // A ClassValue loses the link between a class and the value computed from it, which holds by construction
        return (RsqlQueries<E>) QUERIES.get(entityType);
    }

    /**
     * Creates the RSQL support of an entity type, its type parameter being captured so that the entity type and
     * its ordering rules agree.
     *
     * @param <T>        The type of the managed entity
     * @param entityType The type of the managed entity
     * @return The corresponding RSQL support
     */
    private static <T> RsqlQueries<T> create(Class<T> entityType) {
        return new RsqlQueries<>(entityType, EntityOrdering.of(entityType));
    }

    /**
     * Translates an RSQL query into criteria, its selectors being resolved on the spot and its predicate being
     * built on whichever root a query applies the criteria to.
     * <p>
     * The logical nodes are combined here, each comparison building a predicate of its own, so that a comparison
     * reaching through an association or a collection can be evaluated in a correlated {@code exists} subquery of
     * its own, see {@link #compare(RepositoryContext, ComparisonNode, RsqlDialect)}.
     *
     * @param context  The repository the query is written for
     * @param rsqlNode The parsed RSQL query
     * @param dialect  The dialect the operators of the query are translated with
     * @return The corresponding criteria
     * @throws IllegalArgumentException if the query uses an operator the dialect does not hold, or refers to a
     *                                  property that is neither searchable nor one the entities can be filtered on
     */
    public Criteria<E> toCriteria(RepositoryContext<E> context, Node rsqlNode, RsqlDialect dialect) {
        return rsqlNode.accept(new NoArgRSQLVisitorAdapter<Criteria<E>>() {

            @Override
            public Criteria<E> visit(AndNode node) {
                return combine(node, CriteriaBuilder::and);
            }

            @Override
            public Criteria<E> visit(OrNode node) {
                return combine(node, CriteriaBuilder::or);
            }

            @Override
            public Criteria<E> visit(ComparisonNode node) {
                return compare(context, node, dialect);
            }

            private Criteria<E> combine(LogicalNode node, BiFunction<CriteriaBuilder, Predicate[], Predicate> operator) {
                List<Criteria<E>> children = node.getChildren().stream()
                        .map(child -> child.<Criteria<E>, Void>accept(this))
                        .toList();
                return (criteriaBuilder, query, root) -> operator.apply(criteriaBuilder, children.stream()
                        .map(child -> child.toPredicate(criteriaBuilder, query, root))
                        .toArray(Predicate[]::new));
            }

        });
    }

    /**
     * Translates a single comparison of an RSQL query into criteria, in a correlated {@code exists} subquery of
     * its own when the compared property is reached through an association or a collection.
     * <p>
     * An association is joined with an inner join, and a collection has its elements compared one by one, both of
     * which would otherwise drop the entities having no associated row before any predicate is evaluated, keeping
     * them from matching another alternative of an OR, and repeat an entity once per matching element. Within the
     * subquery, the join only restricts that very comparison.
     * <p>
     * The selector is resolved and the operator is looked up here, before any criteria are built, so that a
     * malformed filter is refused as the {@link IllegalArgumentException} an API consumer is answered a
     * {@code 400 Bad Request} to, whether or not a query is ever run with it. The arguments are read and the
     * property is navigated once the criteria are applied, which a query does before issuing any statement.
     *
     * @param context The repository the query is written for
     * @param node    The comparison to translate
     * @param dialect The dialect the operator of the comparison is translated with
     * @return The corresponding criteria
     * @throws IllegalArgumentException if the dialect does not hold the operator of the comparison, or if its
     *                                  selector is neither searchable nor one the entities can be filtered on
     */
    protected Criteria<E> compare(RepositoryContext<E> context, ComparisonNode node, RsqlDialect dialect) {
        ComparisonPredicate predicate = dialect.predicate(node.getOperator());
        if (predicate == null) {
            throw new IllegalArgumentException("Cannot filter on property %s with unknown operator %s".formatted(node.getSelector(), node.getOperator()));
        }

        PropertyPath property = PropertyPath.resolve(
                context.entityManager().getMetamodel(),
                entityType,
                node.getSelector(),
                ordering.resolveProperty(context, node.getSelector()));

        return (criteriaBuilder, query, root) -> {
            if (!property.joins()) {
                return toPredicate(predicate, new RsqlComparison(node, property, dialect, criteriaBuilder, query, root));
            }

            // Comparing the two roots as entities correlates them on the identifier, whatever it is made of
            Subquery<Integer> matching = query.subquery(Integer.class);
            Root<E> matched = matching.from(entityType);
            Predicate comparison = toPredicate(predicate, new RsqlComparison(node, property, dialect, criteriaBuilder, matching, matched));
            return criteriaBuilder.exists(matching.select(criteriaBuilder.literal(1))
                    .where(criteriaBuilder.equal(matched, root), comparison));
        };
    }

    /**
     * Builds the predicate of a comparison, as the illegal argument a filter the persistence provider refuses is.
     *
     * @param predicate  The predicate of the operator of the comparison
     * @param comparison The comparison to build the predicate of
     * @return The corresponding predicate
     * @throws IllegalArgumentException if the provider refuses the comparison
     */
    private static Predicate toPredicate(ComparisonPredicate predicate, RsqlComparison comparison) {
        try {
            return predicate.toPredicate(comparison);
        } catch (FunctionArgumentException e) {
            // A string matched against a pattern is lowered, which Hibernate refuses for a string it does not hold
            // as text, such as a large object or a number behind a converter, whatever the argument
            throw comparison.unsupported(e);
        }
    }

}
