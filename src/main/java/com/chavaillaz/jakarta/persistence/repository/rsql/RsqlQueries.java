package com.chavaillaz.jakarta.persistence.repository.rsql;

import cz.jirutka.rsql.parser.ast.Node;

import com.chavaillaz.jakarta.persistence.repository.Criteria;
import com.chavaillaz.jakarta.persistence.repository.EntityOrdering;
import com.chavaillaz.jakarta.persistence.repository.EntityQueries;
import com.chavaillaz.jakarta.persistence.repository.RepositoryContext;
import com.chavaillaz.jakarta.persistence.rsql.RsqlDialect;
import com.chavaillaz.jakarta.persistence.rsql.RsqlFilter;

/**
 * RSQL support of an entity type, translating a filter expression into {@link Criteria}, which the
 * {@link EntityQueries queries} of the entity apply like any other.
 * <p>
 * This is where the {@link RsqlFilter filtering} and the base library meet: a selector is resolved against the
 * searchable properties of the repository, so that the very same restriction and public naming govern the
 * filtering and the sorting, and the resolved filter builds its predicate on whichever root a query hands over, so
 * that a cursor query checks and selects its keys exactly as for criteria written by hand.
 * <p>
 * One instance is shared by every repository over the same entity, what belongs to a repository travelling with
 * the {@link RepositoryContext} of each call, and the operators of the queries with the {@link RsqlDialect}.
 *
 * @param <E> The type of the managed entity
 */
public class RsqlQueries<E> {

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
     * A selector is resolved against the searchable properties of the repository and then against the metamodel,
     * and the operator of every comparison is looked up in the dialect, so that a malformed filter is refused as
     * the {@link IllegalArgumentException} an API consumer is answered a {@code 400 Bad Request} to, whether or
     * not a query is ever run with it. The arguments are read and the properties are navigated once the criteria
     * are applied, which a query does before issuing any statement.
     *
     * @param context  The repository the query is written for
     * @param rsqlNode The parsed RSQL query
     * @param dialect  The dialect the operators of the query are translated with
     * @return The corresponding criteria
     * @throws IllegalArgumentException if the query uses an operator the dialect does not hold, or refers to a
     *                                  property that is neither searchable nor one the entities can be filtered on
     * @see RsqlFilter#of(Node, Class, jakarta.persistence.metamodel.Metamodel, RsqlDialect, java.util.function.UnaryOperator)
     */
    public Criteria<E> toCriteria(RepositoryContext<E> context, Node rsqlNode, RsqlDialect dialect) {
        RsqlFilter<E> filter = RsqlFilter.of(
                rsqlNode,
                entityType,
                context.entityManager().getMetamodel(),
                dialect,
                selector -> ordering.resolveProperty(context, selector));

        // Criteria build their predicate on the very same arguments as a filter
        return filter::toPredicate;
    }

}
