package com.chavaillaz.jakarta.persistence.repository.rsql;

import static org.apache.commons.lang3.StringUtils.isBlank;

import jakarta.persistence.EntityManager;

import com.github.tennaito.rsql.jpa.JpaPredicateVisitor;
import cz.jirutka.rsql.parser.RSQLParser;
import cz.jirutka.rsql.parser.ast.Node;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;
import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;
import com.chavaillaz.jakarta.persistence.repository.Criteria;
import com.chavaillaz.jakarta.persistence.repository.Cursor;
import com.chavaillaz.jakarta.persistence.repository.CursorResult;
import com.chavaillaz.jakarta.persistence.repository.Pageable;
import com.chavaillaz.jakarta.persistence.repository.PaginationResult;

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
     * The parser converting the RSQL queries into nodes.
     */
    protected final RSQLParser rsqlParser;

    /**
     * Creates a repository using the default RSQL parser.
     *
     * @param entityManager The entity manager the repository operates on
     * @param entityType    The type of the managed entity
     */
    protected AbstractRsqlRepository(EntityManager entityManager, Class<E> entityType) {
        this(entityManager, entityType, new RSQLParser());
    }

    /**
     * Creates a repository using the given RSQL parser, to support custom operators for instance.
     *
     * @param entityManager The entity manager the repository operates on
     * @param entityType    The type of the managed entity
     * @param rsqlParser    The parser used to build the RSQL query nodes
     */
    protected AbstractRsqlRepository(EntityManager entityManager, Class<E> entityType, RSQLParser rsqlParser) {
        super(entityManager, entityType);
        this.rsqlParser = rsqlParser;
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

    @Override
    public PaginationResult<E> search(@Nullable String rsql, Pageable pageable) {
        if (isBlank(rsql)) {
            return findAll(pageable);
        }
        return queries().search(context(), null, toCriteria(rsqlParser.parse(rsql)), pageable);
    }

    @Override
    public CursorResult<E> search(@Nullable String rsql, Cursor cursor) {
        if (isBlank(rsql)) {
            return findAll(cursor);
        }
        return queries().scroll(context(), null, toCriteria(rsqlParser.parse(rsql)), cursor);
    }

    @Override
    public long count(@Nullable String rsql) {
        if (isBlank(rsql)) {
            return count();
        }
        return count(rsqlParser.parse(rsql));
    }

    /**
     * Counts the entities matching the given RSQL query, without any pagination applied.
     *
     * @param rsqlNode The parsed RSQL query
     * @return The total number of matching entities
     * @throws IllegalArgumentException if the query refers to a property that is not searchable
     */
    protected long count(Node rsqlNode) {
        return queries().count(context(), null, toCriteria(rsqlNode));
    }

    /**
     * Translates an RSQL query into criteria, which a repository method can also combine with a restriction or
     * criteria of its own, such as {@code search(restriction, toCriteria(rsqlParser.parse(rsql)), pageable)}.
     *
     * @param rsqlNode The parsed RSQL query
     * @return The corresponding criteria
     * @throws IllegalArgumentException if the query refers to a property that is not searchable
     * @see RsqlQueries#toCriteria(com.chavaillaz.jakarta.persistence.repository.RepositoryContext, Node, java.util.function.Supplier)
     */
    protected Criteria<E> toCriteria(Node rsqlNode) {
        return rsqlQueries().toCriteria(context(), rsqlNode, this::createPredicateVisitor);
    }

    /**
     * Creates the visitor converting an RSQL query node into a predicate on the managed entity, called each time
     * the criteria of a query are applied.
     * <p>
     * Override to customize the property mapping, the argument parsing or the predicate building, through the
     * builder tools of the visitor.
     *
     * @return The visitor to use to build the predicate
     */
    protected JpaPredicateVisitor<E> createPredicateVisitor() {
        return RsqlQueries.defaultPredicateVisitor(entityType);
    }

}
