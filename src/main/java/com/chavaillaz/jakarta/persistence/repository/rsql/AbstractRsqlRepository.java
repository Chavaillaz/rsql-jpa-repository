package com.chavaillaz.jakarta.persistence.repository.rsql;

import static org.apache.commons.lang3.StringUtils.isBlank;

import jakarta.persistence.EntityManager;

import cz.jirutka.rsql.parser.RSQLParserException;
import cz.jirutka.rsql.parser.ast.Node;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;
import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;
import com.chavaillaz.jakarta.persistence.repository.Criteria;
import com.chavaillaz.jakarta.persistence.repository.Cursor;
import com.chavaillaz.jakarta.persistence.repository.CursorResult;
import com.chavaillaz.jakarta.persistence.repository.Pageable;
import com.chavaillaz.jakarta.persistence.repository.PaginationResult;
import com.chavaillaz.jakarta.persistence.rsql.RsqlDialect;

/**
 * Base implementation of the {@link RsqlRepository} contract, adding dynamic RSQL filtering on top of the
 * {@link AbstractRepository} collaborators.
 * <p>
 * An RSQL query is translated into {@link Criteria}, which the queries of the repository apply like any other, so
 * that the RSQL selectors, the ordering and the searchable properties of the repository stay in a single place.
 *
 * @param <E> The type of the managed entity
 * @param <I> The type of the entity identifier
 */
public abstract class AbstractRsqlRepository<E extends Identifiable<I>, I> extends AbstractRepository<E, I> implements RsqlRepository<E, I> {

    /**
     * Creates a repository.
     *
     * @param entityManager The entity manager the repository operates on
     * @param entityType    The type of the managed entity
     */
    protected AbstractRsqlRepository(EntityManager entityManager, Class<E> entityType) {
        super(entityManager, entityType);
    }

    /**
     * Gets the RSQL support of the managed entity, translating the RSQL queries into criteria, shared by every
     * repository over that entity.
     *
     * @return The RSQL support of the managed entity
     */
    protected RsqlQueries<E> rsqlQueries() {
        return RsqlQueries.of(entityType);
    }

    /**
     * Gets the RSQL the API consumers of this repository write their filters in: the comparison operators the
     * queries may use, the predicate each of them builds and how the arguments of a comparison are read,
     * {@link RsqlDialect#DEFAULT} by default.
     * <p>
     * Override to support an operator or an argument type of its own, holding the dialect in a constant since it
     * is immutable:
     * <pre>{@code
     * @Override
     * protected RsqlDialect rsqlDialect() {
     *     return DIALECT;
     * }
     * }</pre>
     *
     * @return The dialect the queries are parsed and translated with
     */
    protected RsqlDialect rsqlDialect() {
        return RsqlDialect.DEFAULT;
    }

    @Override
    public PaginationResult<E> search(@Nullable String rsql, Pageable pageable) {
        if (isBlank(rsql)) {
            return findAll(pageable);
        }
        return queries().search(context(), null, toCriteria(parse(rsql)), pageable);
    }

    @Override
    public CursorResult<E> search(@Nullable String rsql, Cursor cursor) {
        if (isBlank(rsql)) {
            return findAll(cursor);
        }
        return queries().scroll(context(), null, toCriteria(parse(rsql)), cursor);
    }

    @Override
    public long count(@Nullable String rsql) {
        if (isBlank(rsql)) {
            return count();
        }
        return count(parse(rsql));
    }

    /**
     * Counts the entities matching the given RSQL query, without any pagination applied.
     *
     * @param rsqlNode The parsed RSQL query
     * @return The total number of matching entities
     * @throws IllegalArgumentException if the query refers to a property that is not searchable, or compares a
     *                                  property to an argument its type cannot be parsed from or to a pattern
     *                                  holding too many wildcards or it cannot be matched against
     */
    protected long count(Node rsqlNode) {
        return queries().count(context(), null, toCriteria(rsqlNode));
    }

    /**
     * Parses an RSQL query into the nodes {@link #toCriteria(Node)} translates, with the
     * {@link RsqlDialect#parse(String) dialect of the repository}, which refuses a query nesting its parentheses
     * deeper than {@link RsqlDialect#MAX_NESTING_DEPTH} levels before the parser recurses into them.
     * <p>
     * Parse the queries sent by the API consumers with this method rather than with the
     * {@link RsqlDialect#parser() parser} itself, whose stack a few kilobytes of parentheses are enough to
     * overflow.
     *
     * @param rsql The RSQL query to parse
     * @return The corresponding nodes
     * @throws RSQLParserException      if the query is not valid RSQL, or uses an operator the dialect does not hold
     * @throws IllegalArgumentException if the query nests its parentheses too deeply
     */
    protected Node parse(String rsql) {
        return rsqlDialect().parse(rsql);
    }

    /**
     * Translates an RSQL query into criteria, which a repository method can also combine with a restriction or
     * criteria of its own, such as {@code search(restriction, toCriteria(parse(rsql)), pageable)}.
     *
     * @param rsqlNode The parsed RSQL query
     * @return The corresponding criteria
     * @throws IllegalArgumentException if the query refers to a property that is neither searchable nor one the
     *                                  entities can be filtered on, or uses an operator the dialect does not hold
     * @see RsqlQueries#toCriteria(com.chavaillaz.jakarta.persistence.repository.RepositoryContext, Node, RsqlDialect)
     */
    protected Criteria<E> toCriteria(Node rsqlNode) {
        return rsqlQueries().toCriteria(context(), rsqlNode, rsqlDialect());
    }

}
