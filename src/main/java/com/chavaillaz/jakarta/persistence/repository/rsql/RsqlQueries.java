package com.chavaillaz.jakarta.persistence.repository.rsql;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.github.tennaito.rsql.jpa.JpaCriteriaCountQueryVisitor;
import com.github.tennaito.rsql.jpa.JpaCriteriaQueryVisitor;
import com.github.tennaito.rsql.misc.EntityManagerAdapter;
import cz.jirutka.rsql.parser.RSQLParser;
import cz.jirutka.rsql.parser.RSQLParserException;
import cz.jirutka.rsql.parser.ast.AndNode;
import cz.jirutka.rsql.parser.ast.ComparisonNode;
import cz.jirutka.rsql.parser.ast.LogicalNode;
import cz.jirutka.rsql.parser.ast.NoArgRSQLVisitorAdapter;
import cz.jirutka.rsql.parser.ast.Node;
import cz.jirutka.rsql.parser.ast.OrNode;

import com.chavaillaz.jakarta.persistence.repository.Cursor;
import com.chavaillaz.jakarta.persistence.repository.CursorCodec;
import com.chavaillaz.jakarta.persistence.repository.CursorKeyCodec;
import com.chavaillaz.jakarta.persistence.repository.CursorPosition;
import com.chavaillaz.jakarta.persistence.repository.CursorResult;
import com.chavaillaz.jakarta.persistence.repository.Cursors;
import com.chavaillaz.jakarta.persistence.repository.EntityOrdering;
import com.chavaillaz.jakarta.persistence.repository.Keysets;
import com.chavaillaz.jakarta.persistence.repository.Pageable;
import com.chavaillaz.jakarta.persistence.repository.Pageables;
import com.chavaillaz.jakarta.persistence.repository.PaginationResult;
import com.chavaillaz.jakarta.persistence.repository.Sort;

/**
 * RSQL support of a repository, converting the queries coming from the API consumers into criteria queries.
 * <p>
 * The visitors are provided by the owning repository, so that they stay overridable by its subclasses. Every
 * selector of the query is first {@link #resolveProperties(Node) resolved} against the searchable properties of
 * the {@link #ordering}, so that the very same restriction and public naming govern the RSQL filtering and the
 * sorting.
 *
 * @param <E> The type of the managed entity
 */
public class RsqlQueries<E> {

    /**
     * The entity manager the queries are created from.
     */
    protected final EntityManager entityManager;

    /**
     * The type of the managed entity.
     */
    protected final Class<E> entityType;

    /**
     * The parser converting the RSQL queries into nodes.
     */
    protected final RSQLParser rsqlParser;

    /**
     * The provider of the visitor building the search queries.
     */
    protected final Supplier<JpaCriteriaQueryVisitor<E>> queryVisitor;

    /**
     * The provider of the visitor building the count queries.
     */
    protected final Supplier<JpaCriteriaCountQueryVisitor<E>> countVisitor;

    /**
     * The ordering rules of the repository.
     */
    protected final EntityOrdering<E> ordering;

    /**
     * The codec of the cursor tokens.
     */
    protected final CursorCodec cursorCodec;

    /**
     * The codec of the cursor key values.
     */
    protected final CursorKeyCodec cursorKeyCodec;

    /**
     * Creates the RSQL support of a repository.
     *
     * @param entityManager  The entity manager to use
     * @param entityType     The type of the managed entity
     * @param rsqlParser     The parser used to build the RSQL query nodes
     * @param queryVisitor   The provider of the visitor building the search queries
     * @param countVisitor   The provider of the visitor building the count queries
     * @param ordering       The ordering rules of the repository
     * @param cursorCodec    The codec of the cursor tokens
     * @param cursorKeyCodec The codec of the cursor key values
     */
    public RsqlQueries(
            EntityManager entityManager,
            Class<E> entityType,
            RSQLParser rsqlParser,
            Supplier<JpaCriteriaQueryVisitor<E>> queryVisitor,
            Supplier<JpaCriteriaCountQueryVisitor<E>> countVisitor,
            EntityOrdering<E> ordering,
            CursorCodec cursorCodec,
            CursorKeyCodec cursorKeyCodec) {
        this.entityManager = entityManager;
        this.entityType = entityType;
        this.rsqlParser = rsqlParser;
        this.queryVisitor = queryVisitor;
        this.countVisitor = countVisitor;
        this.ordering = ordering;
        this.cursorCodec = cursorCodec;
        this.cursorKeyCodec = cursorKeyCodec;
    }

    /**
     * Appends a predicate to the restriction of a query, the existing one being possibly absent.
     *
     * @param criteriaBuilder The builder to combine the predicates with
     * @param query           The query to restrict
     * @param predicate       The predicate to append
     */
    private static void restrict(CriteriaBuilder criteriaBuilder, CriteriaQuery<?> query, Predicate predicate) {
        Predicate existing = query.getRestriction();
        query.where(existing == null ? predicate : criteriaBuilder.and(existing, predicate));
    }

    /**
     * Creates the default visitor converting an RSQL query node into a JPA criteria query returning the matching
     * entities.
     *
     * @param <E>        The type of the managed entity
     * @param entityType The class the criteria query is rooted on
     * @return The corresponding visitor
     */
    public static <E> JpaCriteriaQueryVisitor<E> defaultQueryVisitor(Class<E> entityType) {
        JpaCriteriaQueryVisitor<E> visitor = new JpaCriteriaQueryVisitor<>();
        visitor.setEntityClass(entityType);
        return visitor;
    }

    /**
     * Creates the default visitor converting an RSQL query node into a JPA criteria query counting the matching
     * entities.
     *
     * @param <E>        The type of the managed entity
     * @param entityType The class the criteria query is rooted on
     * @return The corresponding visitor
     */
    public static <E> JpaCriteriaCountQueryVisitor<E> defaultCountVisitor(Class<E> entityType) {
        JpaCriteriaCountQueryVisitor<E> visitor = new JpaCriteriaCountQueryVisitor<>();
        visitor.setEntityClass(entityType);
        return visitor;
    }

    /**
     * Parses the given RSQL query.
     *
     * @param rsql The RSQL query
     * @return The corresponding node
     * @throws RSQLParserException if the query is not valid RSQL
     */
    public Node parse(String rsql) {
        return rsqlParser.parse(rsql);
    }

    /**
     * Rewrites the selector of every comparison node of the given RSQL query into the entity attribute path it
     * resolves to, so that the very same searchable properties of the repository restrict both the ordering and
     * the RSQL filtering, and decouple the public naming from the entity one for both.
     *
     * @param rsqlNode The RSQL query to resolve
     * @return The corresponding query, its selectors replaced by the resolved entity attribute paths
     * @throws IllegalArgumentException if the query refers to a property that is not searchable
     * @see EntityOrdering#resolveProperty(String)
     */
    protected Node resolveProperties(Node rsqlNode) {
        return rsqlNode.accept(new NoArgRSQLVisitorAdapter<>() {

            @Override
            public Node visit(AndNode node) {
                return node.withChildren(rewrite(node));
            }

            @Override
            public Node visit(OrNode node) {
                return node.withChildren(rewrite(node));
            }

            @Override
            public Node visit(ComparisonNode node) {
                return node.withSelector(ordering.resolveProperty(node.getSelector()));
            }

            private List<Node> rewrite(LogicalNode node) {
                return node.getChildren().stream().map(child -> child.accept(this)).toList();
            }
        });
    }

    /**
     * Searches for the entities matching the given RSQL query.
     *
     * @param rsqlNode The parsed RSQL query
     * @param pageable The requested page and ordering, {@link Pageable#UNPAGED} to return all the matching
     *                 entities with the default ordering of the repository
     * @return The entities of the requested page with the total number of matching entities
     * @throws IllegalArgumentException if the query refers to a property that is not searchable, or if the
     *                                  requested ordering refers to an unknown property or to a collection
     */
    public PaginationResult<E> search(Node rsqlNode, Pageable pageable) {
        CriteriaQuery<E> query = resolveProperties(rsqlNode).accept(queryVisitor.get(), new EntityManagerAdapter(entityManager));
        applyDistinct(query);
        getRoot(query).ifPresent(root -> ordering.applyOrder(query, root, pageable));

        TypedQuery<E> typedQuery = entityManager.createQuery(query);
        Pageables.apply(typedQuery, pageable);

        return Pageables.toResult(typedQuery.getResultList(), pageable, () -> count(rsqlNode));
    }

    /**
     * Counts the entities matching the given RSQL query, without any pagination applied.
     *
     * @param rsqlNode The parsed RSQL query
     * @return The total number of matching entities
     * @throws IllegalArgumentException if the query refers to a property that is not searchable
     */
    public long count(Node rsqlNode) {
        CriteriaQuery<Long> query = resolveProperties(rsqlNode).accept(countVisitor.get(), new EntityManagerAdapter(entityManager));
        applyCountDistinct(query);
        return entityManager.createQuery(query).getSingleResult();
    }

    /**
     * Scrolls through the entities matching the given RSQL query.
     * <p>
     * The seek predicate is appended to the restriction built by the visitor, and the ordering is rebuilt from
     * the resolved one rather than delegated to {@link EntityOrdering#applyOrder}, so that the {@code ORDER BY}
     * clause matches the seek predicate key for key.
     *
     * @param rsqlNode The parsed RSQL query
     * @param cursor   The requested position, size and ordering
     * @return The corresponding page with the tokens of the surrounding ones
     * @throws IllegalArgumentException if the query refers to a property that is not searchable, if the ordering
     *                                  is not usable as a cursor key, if an ordering key of the boundary row is
     *                                  {@code null}, or if the cursor is malformed or was issued for another
     *                                  ordering
     */
    public CursorResult<E> scroll(Node rsqlNode, Cursor cursor) {
        Sort resolvedSort = ordering.resolveSort(cursor.sort());
        CursorPosition position = Cursors.position(cursorCodec, cursor, resolvedSort);
        Sort direction = Cursors.direction(resolvedSort, position);

        CriteriaQuery<E> query = resolveProperties(rsqlNode).accept(queryVisitor.get(), new EntityManagerAdapter(entityManager));
        applyDistinct(query);

        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        getRoot(query).ifPresent(root -> {
            if (position != null) {
                restrict(criteriaBuilder, query, Keysets.seek(criteriaBuilder, root, direction, position.values(), cursorKeyCodec));
            }
            query.orderBy(Keysets.toOrders(criteriaBuilder, root, direction));
        });

        List<E> fetched = entityManager.createQuery(query)
                .setMaxResults(cursor.limit())
                .getResultList();

        return Cursors.toResult(cursorCodec, fetched, cursor, resolvedSort, position, cursorKeyCodec);
    }

    /**
     * Makes the given query return distinct entities when it joins a collection, an entity being otherwise
     * duplicated in the results as many times as it has matching children, which also breaks the pagination.
     *
     * @param query The search query to adjust
     */
    public void applyDistinct(CriteriaQuery<?> query) {
        if (hasCollectionJoin(query)) {
            query.distinct(true);
        }
    }

    /**
     * Makes the given query count distinct entities when it joins a collection, so that the total number of items
     * stays consistent with the returned results.
     *
     * @param query The count query to adjust
     */
    public void applyCountDistinct(CriteriaQuery<Long> query) {
        if (hasCollectionJoin(query)) {
            CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
            getRoot(query).ifPresent(root -> query.select(criteriaBuilder.countDistinct(root)));
        }
    }

    /**
     * Checks if the given query joins a to-many association, directly or through another join.
     *
     * @param query The query to inspect
     * @return {@code true} if the query may return duplicated root entities, {@code false} otherwise
     */
    public boolean hasCollectionJoin(CriteriaQuery<?> query) {
        return query.getRoots().stream()
                .anyMatch(this::hasCollectionJoin);
    }

    /**
     * Checks if the given query element joins a to-many association, directly or through another join.
     *
     * @param from The root or join to inspect
     * @return {@code true} if the joins may produce duplicated rows, {@code false} otherwise
     */
    public boolean hasCollectionJoin(From<?, ?> from) {
        return from.getJoins().stream()
                .anyMatch(join -> join.getAttribute().isCollection() || hasCollectionJoin(join));
    }

    /**
     * Gets the root entity of the given query, which is the one the criteria are built on.
     *
     * @param query The query to inspect
     * @return The root entity, or {@link Optional#empty()} if the query has none
     */
    @SuppressWarnings("unchecked")
    public Optional<Root<E>> getRoot(CriteriaQuery<?> query) {
        return query.getRoots().stream()
                .findFirst()
                .map(root -> (Root<E>) root);
    }

}
