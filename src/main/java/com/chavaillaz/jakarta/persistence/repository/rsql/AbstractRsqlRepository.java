package com.chavaillaz.jakarta.persistence.repository.rsql;

import static org.apache.commons.lang3.StringUtils.isBlank;

import jakarta.persistence.EntityManager;

import com.github.tennaito.rsql.jpa.JpaPredicateVisitor;
import cz.jirutka.rsql.parser.RSQLParser;
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
     * The number of levels the parentheses of an RSQL query may be nested to, far more than any filter written by
     * hand or by a query builder needs: the parser, the translation into criteria and the persistence provider all
     * recurse into each level, so that a few kilobytes of parentheses would otherwise overflow the stack.
     */
    public static final int MAX_NESTING_DEPTH = 64;

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
     *                                  holding too many wildcards
     */
    protected long count(Node rsqlNode) {
        return queries().count(context(), null, toCriteria(rsqlNode));
    }

    /**
     * Parses an RSQL query into the nodes {@link #toCriteria(Node)} translates, refusing a query nesting its
     * parentheses deeper than {@value #MAX_NESTING_DEPTH} levels before the parser recurses into them.
     * <p>
     * Parse the queries sent by the API consumers with this method rather than with the {@link #rsqlParser} itself,
     * whose stack a few kilobytes of parentheses are enough to overflow.
     *
     * @param rsql The RSQL query to parse
     * @return The corresponding nodes
     * @throws RSQLParserException      if the query is not valid RSQL
     * @throws IllegalArgumentException if the query nests its parentheses deeper than {@value #MAX_NESTING_DEPTH}
     *                                  levels
     */
    protected Node parse(String rsql) {
        requireNestingDepth(rsql);
        return rsqlParser.parse(rsql);
    }

    /**
     * Checks how deep the parentheses of an RSQL query are nested, counting them as the parser tokenizes them: a
     * parenthesis within a quoted argument, where a backslash escapes the next character, nests nothing.
     *
     * @param rsql The RSQL query to check
     * @throws IllegalArgumentException if the query nests its parentheses deeper than {@value #MAX_NESTING_DEPTH}
     *                                  levels
     */
    private static void requireNestingDepth(String rsql) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < rsql.length(); index++) {
            char character = rsql.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (quote != 0) {
                escaped = character == '\\';
                if (character == quote) {
                    quote = 0;
                }
            } else if (character == '"' || character == '\'') {
                quote = character;
            } else if (character == '(') {
                depth++;
                if (depth > MAX_NESTING_DEPTH) {
                    throw new IllegalArgumentException("Cannot filter with an RSQL query nesting its parentheses deeper than %d levels".formatted(MAX_NESTING_DEPTH));
                }
            } else if (character == ')') {
                depth--;
            }
        }
    }

    /**
     * Translates an RSQL query into criteria, which a repository method can also combine with a restriction or
     * criteria of its own, such as {@code search(restriction, toCriteria(parse(rsql)), pageable)}.
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
     * Creates the visitor converting an RSQL comparison into a predicate on the managed entity, called for each
     * comparison every time the criteria of a query are applied, the logical nodes being combined by
     * {@link RsqlQueries#toCriteria(com.chavaillaz.jakarta.persistence.repository.RepositoryContext, Node, java.util.function.Supplier)}.
     * <p>
     * Override to customize the property mapping, the argument parsing or the predicate building, through the
     * builder tools of the visitor. Start from {@link RsqlQueries#defaultPredicateVisitor(Class)} and customize the
     * tools it holds rather than replacing them, so that the arguments its parser refuses stay refused.
     *
     * @return The visitor to use to build the predicate
     */
    protected JpaPredicateVisitor<E> createPredicateVisitor() {
        return RsqlQueries.defaultPredicateVisitor(entityType);
    }

}
