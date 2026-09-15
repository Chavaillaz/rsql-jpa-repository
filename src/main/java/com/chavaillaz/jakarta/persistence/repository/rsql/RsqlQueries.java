package com.chavaillaz.jakarta.persistence.repository.rsql;

import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

import com.github.tennaito.rsql.jpa.JpaPredicateVisitor;
import com.github.tennaito.rsql.misc.ArgumentFormatException;
import com.github.tennaito.rsql.misc.DefaultArgumentParser;
import com.github.tennaito.rsql.misc.EntityManagerAdapter;
import cz.jirutka.rsql.parser.ast.AndNode;
import cz.jirutka.rsql.parser.ast.ComparisonNode;
import cz.jirutka.rsql.parser.ast.LogicalNode;
import cz.jirutka.rsql.parser.ast.NoArgRSQLVisitorAdapter;
import cz.jirutka.rsql.parser.ast.Node;
import cz.jirutka.rsql.parser.ast.OrNode;
import org.hibernate.query.sqm.TerminalPathException;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.repository.Criteria;
import com.chavaillaz.jakarta.persistence.repository.EntityOrdering;
import com.chavaillaz.jakarta.persistence.repository.EntityQueries;
import com.chavaillaz.jakarta.persistence.repository.RepositoryContext;

/**
 * RSQL support of an entity type, translating a filter expression into {@link Criteria}, which the
 * {@link EntityQueries queries} of the entity apply like any other.
 * <p>
 * The predicate is built on the root each query hands over, so that a cursor query checks and selects its keys
 * exactly as for criteria written by hand, and each comparison reaching through an association is evaluated in a
 * correlated {@code exists} subquery of its own. Every selector is first
 * {@link #resolveProperties(RepositoryContext, Node) resolved} against the searchable properties of the repository,
 * so that the very same restriction and public naming govern the filtering and the sorting.
 * <p>
 * One instance is shared by every repository over the same entity, what belongs to a repository travelling with
 * the {@link RepositoryContext} of each call.
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
     * The largest number of characters a decimal argument may be written with, twice {@link #MAX_DECIMAL_SCALE}, enough
     * to spell out any value a numeric column may be declared to hold. The JDK parses the digits of a decimal in
     * quadratic time, and a query parses each argument four times, so that the quarter of a megabyte of digits a
     * consumer is free to send in the body of a request would otherwise take some 5 seconds to parse, only for H2 to
     * refuse the value.
     */
    public static final int MAX_DECIMAL_LENGTH = 2 * MAX_DECIMAL_SCALE;

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
     * The argument parser of the default visitor, shared since it holds no state.
     */
    private static final DefaultArgumentParser ARGUMENT_PARSER = new StrictArgumentParser();

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
     * Creates the default visitor converting an RSQL query node into a predicate on the managed entity.
     * <p>
     * Its argument parser refuses, as arguments their property cannot be parsed from, a decimal written with more
     * than {@value #MAX_DECIMAL_LENGTH} characters or whose scale lies beyond {@value #MAX_DECIMAL_SCALE}, negative or
     * positive, and a boolean other than {@code true} or {@code false}, whatever its case, which rsql-jpa silently
     * reads as {@code false}: customize the builder tools the visitor holds, rather than replacing them or their
     * argument parser, to keep refusing them.
     *
     * @param <E>        The type of the managed entity
     * @param entityType The type of the managed entity
     * @return The corresponding visitor
     */
    @SuppressWarnings("unchecked")
    public static <E> JpaPredicateVisitor<E> defaultPredicateVisitor(Class<E> entityType) {
        // The visitor guesses its entity type from a generic varargs array, which the explicit type then replaces
        JpaPredicateVisitor<E> visitor = new JpaPredicateVisitor<>();
        visitor.setEntityClass(entityType);
        visitor.getBuilderTools().setArgumentParser(ARGUMENT_PARSER);
        return visitor;
    }

    /**
     * Rewrites the selector of every comparison node of the given RSQL query into the entity attribute path it
     * resolves to, so that the very same searchable properties of the repository restrict both the ordering and
     * the RSQL filtering, and decouple the public naming from the entity one for both.
     *
     * @param context  The repository the query is written for
     * @param rsqlNode The RSQL query to resolve
     * @return The corresponding query, its selectors replaced by the resolved entity attribute paths
     * @throws IllegalArgumentException if the query refers to a property that is not searchable
     * @see EntityOrdering#resolveProperty(RepositoryContext, String)
     */
    public Node resolveProperties(RepositoryContext<E> context, Node rsqlNode) {
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
                return node.withSelector(ordering.resolveProperty(context, node.getSelector()));
            }

            private List<Node> rewrite(LogicalNode node) {
                return node.getChildren().stream().map(child -> child.accept(this)).toList();
            }
        });
    }

    /**
     * Translates an RSQL query into criteria, its selectors being resolved against the searchable properties on
     * the spot, and its predicate being built on whichever root a query applies the criteria to.
     * <p>
     * The visitor inner joins the association a selector reaches through, which drops the entities having no
     * associated row before any predicate is evaluated, keeping them from matching another alternative of an OR. A
     * comparison joining anything is therefore evaluated in a correlated {@code exists} subquery of its own, where
     * the join only restricts that comparison, the logical nodes being combined here rather than by the visitor.
     * <p>
     * A query applies criteria more than once, first to a throwaway root, see {@link Criteria#toPredicate}, and a
     * visitor holds the root it builds on: a visitor is therefore taken from the given provider for each comparison
     * at each application.
     * <p>
     * An argument the visitor cannot parse for the type of its property, or a selector it cannot navigate, such as
     * one reaching through a basic attribute, is a malformed filter sent by an API consumer: the criteria raise it
     * as an {@link IllegalArgumentException} when applied, which a query does before issuing any statement.
     *
     * @param context  The repository the query is written for
     * @param rsqlNode The parsed RSQL query
     * @param visitors The provider of the visitor building the predicate of a comparison, called for each
     *                 comparison at each application
     * @return The corresponding criteria
     * @throws IllegalArgumentException if the query refers to a property that is not searchable
     */
    public Criteria<E> toCriteria(RepositoryContext<E> context, Node rsqlNode, Supplier<? extends JpaPredicateVisitor<E>> visitors) {
        Node resolved = resolveProperties(context, rsqlNode);
        return (criteriaBuilder, query, root) -> {
            EntityManagerAdapter adapter = new EntityManagerAdapter(context.entityManager()::getMetamodel, () -> criteriaBuilder);
            return resolved.accept(new NoArgRSQLVisitorAdapter<Predicate>() {

                @Override
                public Predicate visit(AndNode node) {
                    return criteriaBuilder.and(combine(node));
                }

                @Override
                public Predicate visit(OrNode node) {
                    return criteriaBuilder.or(combine(node));
                }

                @Override
                public Predicate visit(ComparisonNode node) {
                    try {
                        return compare(node, criteriaBuilder, query, root, adapter, visitors);
                    } catch (ArgumentFormatException | TerminalPathException e) {
                        // An unparsable argument, or a selector reaching through a basic attribute such as name.origin,
                        // which the API layer answers with a 400 only as an IllegalArgumentException
                        throw new IllegalArgumentException(e.getMessage(), e);
                    } catch (ClassCastException e) {
                        // The visitor joins an association from whichever path it last stepped into, which is no join
                        // past a basic attribute, such as name.roaster.name, nor past the to-one association of a join,
                        // such as notes.coffee.roaster.name; the JVM omits the message of a cast failing that often
                        throw new IllegalArgumentException("Cannot filter on property " + node.getSelector(), e);
                    }
                }

                private Predicate[] combine(LogicalNode node) {
                    return node.getChildren().stream().map(child -> child.accept(this)).toArray(Predicate[]::new);
                }
            });
        };
    }

    /**
     * Builds the predicate of a single comparison, in a correlated {@code exists} subquery of its own when it joins
     * anything, see {@link #toCriteria(RepositoryContext, Node, Supplier)}.
     * <p>
     * What the visitor joins is only known once it is applied, so the comparison is first applied to a throwaway
     * root, which issues no query.
     *
     * @param node            The comparison to build the predicate of
     * @param criteriaBuilder The builder to use
     * @param query           The query being built, to create the subquery from
     * @param root            The root entity of the query
     * @param adapter         The metamodel and the builder the visitor works with
     * @param visitors        The provider of the visitor building the predicate
     * @return The corresponding predicate
     */
    private Predicate compare(ComparisonNode node, CriteriaBuilder criteriaBuilder, CommonAbstractCriteria query, Root<E> root, EntityManagerAdapter adapter, Supplier<? extends JpaPredicateVisitor<E>> visitors) {
        Root<E> probe = criteriaBuilder.createQuery(entityType).from(entityType);
        node.accept(visitors.get().defineRoot(probe), adapter);
        if (probe.getJoins().isEmpty()) {
            return node.accept(visitors.get().defineRoot(root), adapter);
        }

        // Comparing the two roots as entities correlates them on the identifier, whatever it is made of
        Subquery<Integer> matching = query.subquery(Integer.class);
        Root<E> matched = matching.from(entityType);
        Predicate predicate = node.accept(visitors.get().defineRoot(matched), adapter);
        return criteriaBuilder.exists(matching.select(criteriaBuilder.literal(1)).where(criteriaBuilder.equal(matched, root), predicate));
    }

    /**
     * Parses the arguments of the comparisons as rsql-jpa does, refusing those an API consumer can send but no query
     * should run with, as arguments their property cannot be parsed from.
     */
    private static final class StrictArgumentParser extends DefaultArgumentParser {

        @Override
        public <T> @Nullable T parse(String argument, Class<T> type) {
            if (type.equals(BigDecimal.class) && argument.length() > MAX_DECIMAL_LENGTH) {
                // Refused before being parsed, which takes quadratic time in its digits, see MAX_DECIMAL_LENGTH
                throw new ArgumentFormatException(argument, type);
            }
            T value = super.parse(argument, type);
            if (value instanceof Boolean && !"true".equalsIgnoreCase(argument) && !"false".equalsIgnoreCase(argument)) {
                // Boolean#valueOf reads any other text as false, turning organic==yes into its very opposite
                throw new ArgumentFormatException(argument, type);
            }
            if (value instanceof BigDecimal decimal && Math.abs((long) decimal.scale()) > MAX_DECIMAL_SCALE) {
                // The few bytes of 1e30000000 took H2 some 37 seconds to bind, see MAX_DECIMAL_SCALE
                throw new ArgumentFormatException(argument, type);
            }
            return value;
        }

    }

}
