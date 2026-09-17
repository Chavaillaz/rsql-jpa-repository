package com.chavaillaz.jakarta.persistence.rsql;

import jakarta.persistence.PersistenceException;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.Metamodel;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import cz.jirutka.rsql.parser.ast.AndNode;
import cz.jirutka.rsql.parser.ast.ComparisonNode;
import cz.jirutka.rsql.parser.ast.LogicalNode;
import cz.jirutka.rsql.parser.ast.NoArgRSQLVisitorAdapter;
import cz.jirutka.rsql.parser.ast.Node;
import cz.jirutka.rsql.parser.ast.OrNode;

/**
 * An RSQL query resolved against the model of an entity, ready to build its predicate on whichever root a query
 * hands over, as often as it hands one over.
 * <p>
 * Everything a malformed filter is refused for is refused while the query is resolved, before any predicate is
 * built: an operator the {@link RsqlDialect dialect} does not hold, and a selector that names no attribute of the
 * entity or that reaches through an attribute having none. The arguments are read and the properties are navigated
 * while a predicate is built, which happens before any statement is issued.
 * <p>
 * A comparison reaching through an association or a collection is evaluated in a correlated {@code exists}
 * subquery of its own: an association is joined with an inner join, and a collection has its elements compared one
 * by one, both of which would otherwise drop the entities having no associated row before any predicate is
 * evaluated, keeping them from matching another alternative of an OR, and repeat an entity once per matching
 * element. Within the subquery, the join only restricts that very comparison, so that two comparisons reaching
 * through the same collection are satisfied by two different elements.
 * <p>
 * A filter is immutable and holds nothing of a query, so that it can be built once per expression and applied to
 * any root of the entity it was resolved for:
 * <pre>{@code
 * Node node = RsqlDialect.DEFAULT.parse("origin==Ethiopia;notes.flavour==Citrus");
 * RsqlFilter<Coffee> filter = RsqlFilter.of(node, Coffee.class, entityManager.getMetamodel());
 *
 * CriteriaQuery<Coffee> query = criteriaBuilder.createQuery(Coffee.class);
 * Root<Coffee> root = query.from(Coffee.class);
 * query.where(filter.toPredicate(criteriaBuilder, query, root));
 * }</pre>
 *
 * @param <E> The type of the filtered entity
 */
public final class RsqlFilter<E> {

    private final Class<E> entityType;

    private final Filter<E> filter;

    private RsqlFilter(Class<E> entityType, Filter<E> filter) {
        this.entityType = entityType;
        this.filter = filter;
    }

    /**
     * Resolves an RSQL query against the model of an entity, its selectors naming the attributes of that entity.
     *
     * @param <E>        The type of the filtered entity
     * @param rsqlNode   The parsed RSQL query, see {@link RsqlDialect#parse(String)}
     * @param entityType The type of the filtered entity, which the selectors start from
     * @param metamodel  The metamodel the attributes of the selectors are resolved against
     * @return The corresponding filter
     * @throws IllegalArgumentException if the query uses an operator {@link RsqlDialect#DEFAULT} does not hold, or
     *                                  names a property the entities cannot be filtered on
     */
    public static <E> RsqlFilter<E> of(Node rsqlNode, Class<E> entityType, Metamodel metamodel) {
        return of(rsqlNode, entityType, metamodel, RsqlDialect.DEFAULT, UnaryOperator.identity());
    }

    /**
     * Resolves an RSQL query against the model of an entity, the selectors being resolved into entity attribute
     * paths beforehand, which is how the public naming of an API is decoupled from the entity one and how the
     * attributes its consumers may filter on are restricted.
     *
     * @param <E>        The type of the filtered entity
     * @param rsqlNode   The parsed RSQL query, see {@link RsqlDialect#parse(String)}
     * @param entityType The type of the filtered entity, which the selectors start from
     * @param metamodel  The metamodel the attributes of the selectors are resolved against
     * @param dialect    The dialect the operators of the query are translated with
     * @param selectors  The resolution of a selector into the entity attribute path it names, dot separated,
     *                   raising an {@link IllegalArgumentException} for a selector no property exposes, or
     *                   {@link UnaryOperator#identity()} to take the selectors as they are sent
     * @return The corresponding filter
     * @throws IllegalArgumentException if the query uses an operator the dialect does not hold, or names a
     *                                  property the entities cannot be filtered on
     */
    public static <E> RsqlFilter<E> of(Node rsqlNode, Class<E> entityType, Metamodel metamodel, RsqlDialect dialect, UnaryOperator<String> selectors) {
        return new RsqlFilter<>(entityType, resolve(rsqlNode, entityType, metamodel, dialect, selectors));
    }

    /**
     * Resolves every node of an RSQL query, the logical ones being combined here so that each comparison builds a
     * predicate of its own, possibly within a subquery.
     *
     * @param <E>        The type of the filtered entity
     * @param rsqlNode   The parsed RSQL query
     * @param entityType The type of the filtered entity
     * @param metamodel  The metamodel the attributes of the selectors are resolved against
     * @param dialect    The dialect the operators of the query are translated with
     * @param selectors  The resolution of a selector into the entity attribute path it names
     * @return The resolved filter of the query
     */
    private static <E> Filter<E> resolve(Node rsqlNode, Class<E> entityType, Metamodel metamodel, RsqlDialect dialect, UnaryOperator<String> selectors) {
        return rsqlNode.accept(new NoArgRSQLVisitorAdapter<Filter<E>>() {

            @Override
            public Filter<E> visit(AndNode node) {
                return combine(node, CriteriaBuilder::and);
            }

            @Override
            public Filter<E> visit(OrNode node) {
                return combine(node, CriteriaBuilder::or);
            }

            @Override
            public Filter<E> visit(ComparisonNode node) {
                return compare(node, entityType, metamodel, dialect, selectors);
            }

            private Filter<E> combine(LogicalNode node, BiFunction<CriteriaBuilder, Predicate[], Predicate> operator) {
                List<Filter<E>> children = node.getChildren().stream()
                        .map(child -> child.<Filter<E>, Void>accept(this))
                        .toList();
                return (criteriaBuilder, query, root) -> operator.apply(criteriaBuilder, children.stream()
                        .map(child -> child.toPredicate(criteriaBuilder, query, root))
                        .toArray(Predicate[]::new));
            }

        });
    }

    /**
     * Resolves a single comparison of an RSQL query, looking its operator up and resolving its selector against
     * the model, so that a malformed filter is refused whether or not a query is ever run with it.
     *
     * @param <E>        The type of the filtered entity
     * @param node       The comparison to resolve
     * @param entityType The type of the filtered entity
     * @param metamodel  The metamodel the attributes of the selector are resolved against
     * @param dialect    The dialect the operator of the comparison is translated with
     * @param selectors  The resolution of a selector into the entity attribute path it names
     * @return The resolved filter of the comparison
     * @throws IllegalArgumentException if the dialect does not hold the operator of the comparison, or if its
     *                                  selector names a property the entities cannot be filtered on
     */
    private static <E> Filter<E> compare(ComparisonNode node, Class<E> entityType, Metamodel metamodel, RsqlDialect dialect, UnaryOperator<String> selectors) {
        ComparisonPredicate predicate = dialect.predicate(node.getOperator());
        if (predicate == null) {
            throw new IllegalArgumentException("Cannot filter on property %s with unknown operator %s".formatted(node.getSelector(), node.getOperator()));
        }

        PropertyPath property = PropertyPath.resolve(metamodel, entityType, node.getSelector(), selectors.apply(node.getSelector()));

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
        } catch (PersistenceException e) {
            // What the persistence provider raises for a comparison it cannot build, such as the lowering of a
            // string it does not hold as text, a large object or a number behind a converter, whatever the
            // argument: nothing here has reached the database yet, so that a refusal can only be the filter
            throw comparison.unsupported(e);
        }
    }

    /**
     * Gets the type of the filtered entity, which the roots a predicate is built on hold.
     *
     * @return The type of the filtered entity
     */
    public Class<E> entityType() {
        return entityType;
    }

    /**
     * Builds the predicate of this filter on the given root, reading the arguments of its comparisons and
     * navigating their properties, which may be called as often as a query applies its criteria.
     *
     * @param criteriaBuilder The builder to create the predicate with
     * @param query           The query or deletion the predicate restricts, which the subquery of a comparison
     *                        reaching through an association or a collection is created from
     * @param root            The root of the filtered entity to build the predicate on
     * @return The corresponding predicate
     * @throws IllegalArgumentException if an argument of the query is no valid value of the property it is
     *                                 compared to, or if a comparison is one the persistence provider refuses
     */
    public Predicate toPredicate(CriteriaBuilder criteriaBuilder, CommonAbstractCriteria query, Root<E> root) {
        return filter.toPredicate(criteriaBuilder, query, root);
    }

    /**
     * The resolved form of a node of an RSQL query, which builds its predicate on a root.
     *
     * @param <E> The type of the filtered entity
     */
    @FunctionalInterface
    private interface Filter<E> {

        /**
         * Builds the predicate of the node on the given root.
         *
         * @param criteriaBuilder The builder to create the predicate with
         * @param query           The query the predicate restricts
         * @param root            The root of the filtered entity
         * @return The corresponding predicate
         */
        Predicate toPredicate(CriteriaBuilder criteriaBuilder, CommonAbstractCriteria query, Root<E> root);

    }

}
