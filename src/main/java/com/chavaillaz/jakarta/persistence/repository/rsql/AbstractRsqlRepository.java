package com.chavaillaz.jakarta.persistence.repository.rsql;

import static org.apache.commons.lang3.StringUtils.isBlank;

import jakarta.persistence.EntityManager;

import com.github.tennaito.rsql.jpa.JpaCriteriaCountQueryVisitor;
import com.github.tennaito.rsql.jpa.JpaCriteriaQueryVisitor;
import cz.jirutka.rsql.parser.RSQLParser;
import cz.jirutka.rsql.parser.ast.Node;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;
import com.chavaillaz.jakarta.persistence.repository.AbstractRepository;
import com.chavaillaz.jakarta.persistence.repository.Cursor;
import com.chavaillaz.jakarta.persistence.repository.CursorResult;
import com.chavaillaz.jakarta.persistence.repository.Pageable;
import com.chavaillaz.jakarta.persistence.repository.PaginationResult;

/**
 * Base implementation of the {@link RsqlRepository} contract, adding dynamic RSQL filtering on top of the
 * {@link AbstractRepository} collaborators, so that the RSQL selectors, the ordering and the searchable
 * properties of the repository stay in a single place.
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
     * @see #rsqlQueries()
     */
    private @Nullable RsqlQueries<E> rsqlQueries;

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
     * Gets the RSQL support of the repository.
     *
     * @return The RSQL support, translating the filter expressions into predicates over the searchable properties
     */
    protected RsqlQueries<E> rsqlQueries() {
        if (rsqlQueries == null) {
            // The hooks are passed as method references, so that the overriding subclasses stay in charge of them.
            rsqlQueries = new RsqlQueries<>(entityManager, entityType, rsqlParser, this::createQueryVisitor, this::createCountVisitor, ordering(), cursorCodec(), cursorKeyCodec());
        }
        return rsqlQueries;
    }

    @Override
    public PaginationResult<E> search(@Nullable String rsql, Pageable pageable) {
        if (isBlank(rsql)) {
            return findAll(pageable);
        }

        return rsqlQueries().search(rsqlQueries().parse(rsql), pageable);
    }

    @Override
    public CursorResult<E> search(@Nullable String rsql, Cursor cursor) {
        if (isBlank(rsql)) {
            return findAll(cursor);
        }
        return rsqlQueries().scroll(rsqlQueries().parse(rsql), cursor);
    }

    @Override
    public long count(@Nullable String rsql) {
        if (isBlank(rsql)) {
            return count();
        }

        return count(rsqlQueries().parse(rsql));
    }

    /**
     * Counts the entities matching the given RSQL query, without any pagination applied.
     *
     * @param rsqlNode The parsed RSQL query
     * @return The total number of matching entities
     * @see RsqlQueries#count(Node)
     */
    protected long count(Node rsqlNode) {
        return rsqlQueries().count(rsqlNode);
    }

    /**
     * Creates the visitor converting an RSQL query node into a JPA criteria query returning the matching entities.
     * <p>
     * Override to customize the property mapping, the argument parsing or the predicate building.
     *
     * @return The visitor to use to build the search query
     */
    protected JpaCriteriaQueryVisitor<E> createQueryVisitor() {
        return RsqlQueries.defaultQueryVisitor(entityType);
    }

    /**
     * Creates the visitor converting an RSQL query node into a JPA criteria query counting the matching entities.
     * <p>
     * Override to customize the property mapping, the argument parsing or the predicate building.
     *
     * @return The visitor to use to build the count query
     */
    protected JpaCriteriaCountQueryVisitor<E> createCountVisitor() {
        return RsqlQueries.defaultCountVisitor(entityType);
    }

}
