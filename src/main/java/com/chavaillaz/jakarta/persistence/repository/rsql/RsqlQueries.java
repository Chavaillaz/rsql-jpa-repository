package com.chavaillaz.jakarta.persistence.repository.rsql;

import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.PluralAttribute;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.github.tennaito.rsql.jpa.JpaPredicateVisitor;
import com.github.tennaito.rsql.jpa.PredicateBuilder;
import com.github.tennaito.rsql.misc.ArgumentFormatException;
import com.github.tennaito.rsql.misc.DefaultArgumentParser;
import com.github.tennaito.rsql.misc.EntityManagerAdapter;
import cz.jirutka.rsql.parser.ast.AndNode;
import cz.jirutka.rsql.parser.ast.ComparisonNode;
import cz.jirutka.rsql.parser.ast.ComparisonOperator;
import cz.jirutka.rsql.parser.ast.LogicalNode;
import cz.jirutka.rsql.parser.ast.NoArgRSQLVisitorAdapter;
import cz.jirutka.rsql.parser.ast.Node;
import cz.jirutka.rsql.parser.ast.OrNode;
import cz.jirutka.rsql.parser.ast.RSQLOperators;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.query.sqm.TerminalPathException;
import org.hibernate.query.sqm.produce.function.FunctionArgumentException;
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
 * exactly as for criteria written by hand, and each comparison joining an association or reaching through a
 * collection is evaluated in a correlated {@code exists} subquery of its own. Every selector is first
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
     * The largest number of wildcards, {@code *} or {@code %}, the argument of an {@code ==} or {@code !=} comparison
     * of a string may hold, not counting those ending it. rsql-jpa matches such a string against the argument with a
     * {@code like}, which H2 evaluates by trying every position of the value each wildcard may stand for, so that each
     * wildcard followed by more of the pattern multiplies the work by up to the length of the value: the few bytes of
     * {@code *e*e*e*e*x} would otherwise take H2 some 24 seconds to match against a single string of 255 e.
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
     * The argument parser of the default visitor, shared since it holds no state.
     */
    private static final DefaultArgumentParser ARGUMENT_PARSER = new StrictArgumentParser();

    /**
     * The ordering comparisons of a date, built here rather than by the visitor, keyed by their operator, see
     * {@link #compare(ComparisonNode, CriteriaBuilder, CommonAbstractCriteria, Root, EntityManagerAdapter, Supplier)}.
     */
    private static final Map<ComparisonOperator, DateComparison> DATE_COMPARISONS = Map.of(
            RSQLOperators.GREATER_THAN, CriteriaBuilder::greaterThan,
            RSQLOperators.GREATER_THAN_OR_EQUAL, CriteriaBuilder::greaterThanOrEqualTo,
            RSQLOperators.LESS_THAN, CriteriaBuilder::lessThan,
            RSQLOperators.LESS_THAN_OR_EQUAL, CriteriaBuilder::lessThanOrEqualTo);

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
     * Its argument parser refuses, as arguments their property cannot be parsed from, any argument compared to a
     * collection as a whole, {@code null} included, which rsql-jpa reads before even looking at the type of the
     * property, any argument holding a NUL character, which PostgreSQL refuses in any text, and a decimal written
     * with more than {@value #MAX_DECIMAL_LENGTH} characters or whose scale lies beyond {@value #MAX_DECIMAL_SCALE},
     * negative or positive. It also refuses a boolean other than {@code true} or {@code false}, whatever its case,
     * which rsql-jpa silently reads as {@code false}, a {@link Date} not written as {@code yyyy-MM-dd} or
     * {@code yyyy-MM-dd'T'HH:mm:ss}, which rsql-jpa reads leniently, and a date past the year 9999, which PostgreSQL
     * refuses to bind beyond the year 294276. It reads a date in the Gregorian calendar whatever the default locale,
     * rather than in a Buddhist year under a Thai one, as rsql-jpa does.
     * <p>
     * Customize the builder tools the visitor holds, rather than replacing them or their argument parser, so that
     * those arguments stay refused.
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
     * The visitor inner joins the association a selector reaches through, and Hibernate implicitly joins a collection
     * the visitor reaches through with a plain path, such as an element collection, both of which drop the entities
     * having no associated row before any predicate is evaluated, keeping them from matching another alternative of
     * an OR, and repeat an entity for each matching element. A comparison joining anything or reaching through a
     * collection is therefore evaluated in a correlated {@code exists} subquery of its own, where the join only
     * restricts that comparison, the logical nodes being combined here rather than by the visitor.
     * <p>
     * A query applies criteria more than once, first to a throwaway root, see {@link Criteria#toPredicate}, and a
     * visitor holds the root it builds on: a visitor is therefore taken from the given provider for each comparison
     * at each application.
     * <p>
     * An argument the visitor cannot parse for the type of its property, a pattern holding more wildcards than
     * {@link #MAX_WILDCARDS} allows or matched against a string not held as text, such as a large object, or a selector
     * the visitor cannot navigate, such as one reaching through a basic attribute, is a malformed filter sent by an API
     * consumer: the criteria raise it as an {@link IllegalArgumentException} when applied, which a query does before
     * issuing any statement.
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
                    } catch (FunctionArgumentException e) {
                        // The visitor matches a string against a pattern by lowering both, which Hibernate refuses for a
                        // string it does not hold as text, such as a large object or a number behind a converter
                        throw new IllegalArgumentException("Cannot filter on property %s with %s".formatted(node.getSelector(), node.getOperator()), e);
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
     * anything or reaches through a collection, see {@link #toCriteria(RepositoryContext, Node, Supplier)}.
     * <p>
     * What the visitor joins is only known once it is applied, so the comparison is first applied to a throwaway
     * root, which issues no query. A collection it reaches through without joining it only shows in the path it
     * navigates on that root, as the type of the property it compares does, which tells whether it matches a string
     * against a pattern, see {@link #MAX_WILDCARDS}.
     * <p>
     * rsql-jpa compares a date with a between, whose bound it moves a whole day away for an exclusive comparison, the
     * other bound being the first day of the year 5 or the last one of the year 9999, in the calendar of the default
     * locale and at the time of day rsql-jpa was loaded, which leaves part of the day out of the comparison of a time:
     * an ordering comparison of a date is therefore built here instead, on the path and with the argument the visitor
     * navigates and parses.
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
        JpaPredicateVisitor<E> visitor = visitors.get();
        node.accept(visitor.defineRoot(probe), adapter);
        Path<?> path = PredicateBuilder.findPropertyPath(node.getSelector(), probe, adapter, visitor.getBuilderTools());
        requireWildcards(node, path);
        Date bound = dateBound(node, path, visitor);
        if (probe.getJoins().isEmpty() && !reachesThroughCollection(path)) {
            return apply(node, bound, root, criteriaBuilder, adapter, visitors);
        }

        // Comparing the two roots as entities correlates them on the identifier, whatever it is made of
        Subquery<Integer> matching = query.subquery(Integer.class);
        Root<E> matched = matching.from(entityType);
        Predicate predicate = apply(node, bound, matched, criteriaBuilder, adapter, visitors);
        return criteriaBuilder.exists(matching.select(criteriaBuilder.literal(1)).where(criteriaBuilder.equal(matched, root), predicate));
    }

    /**
     * Applies a comparison to the given root, through the visitor unless it is an ordering comparison of a date.
     *
     * @param node            The comparison to apply
     * @param bound           The date an ordering comparison compares its property to, or {@code null} to apply the
     *                        comparison through the visitor
     * @param target          The root to apply the comparison to
     * @param criteriaBuilder The builder to use
     * @param adapter         The metamodel and the builder the visitor works with
     * @param visitors        The provider of the visitor building the predicate
     * @return The corresponding predicate
     */
    @SuppressWarnings("unchecked")
    private Predicate apply(ComparisonNode node, @Nullable Date bound, Root<E> target, CriteriaBuilder criteriaBuilder, EntityManagerAdapter adapter, Supplier<? extends JpaPredicateVisitor<E>> visitors) {
        JpaPredicateVisitor<E> visitor = visitors.get().defineRoot(target);
        if (bound == null) {
            return node.accept(visitor, adapter);
        }
        // The property is a date, see dateBound, reached through a single navigation of its path
        Path<Date> path = (Path<Date>) PredicateBuilder.findPropertyPath(node.getSelector(), target, adapter, visitor.getBuilderTools());
        return DATE_COMPARISONS.get(node.getOperator()).compare(criteriaBuilder, path, bound);
    }

    /**
     * Checks that the argument of an {@code ==} or {@code !=} comparison of a string, which rsql-jpa matches the string
     * against with a {@code like}, holds no more wildcards than {@link #MAX_WILDCARDS}.
     *
     * @param node The comparison
     * @param path The path the visitor navigates for the comparison
     * @throws IllegalArgumentException if the comparison matches a string against a pattern holding too many wildcards
     */
    private static void requireWildcards(ComparisonNode node, Path<?> path) {
        boolean like = node.getOperator().equals(RSQLOperators.EQUAL) || node.getOperator().equals(RSQLOperators.NOT_EQUAL);
        if (!like || !String.class.equals(path.getJavaType())) {
            return;
        }
        // Those ending the pattern match the rest of a value at once, whatever its length
        String pattern = StringUtils.stripEnd(node.getArguments().get(0), "*%");
        if (pattern.chars().filter(character -> character == '*' || character == '%').count() > MAX_WILDCARDS) {
            throw new IllegalArgumentException("Cannot filter on property %s with a pattern of more than %d wildcards".formatted(node.getSelector(), MAX_WILDCARDS));
        }
    }

    /**
     * Gets the date an ordering comparison of a date compares its property to, parsed as the visitor parses it.
     *
     * @param node    The comparison
     * @param path    The path the visitor navigates for the comparison
     * @param visitor The visitor, whose argument parser the date is parsed with
     * @return The date to compare the property to, or {@code null} when the comparison is no ordering comparison of
     *         a date, or compares it to {@code null}
     */
    private static @Nullable Date dateBound(ComparisonNode node, Path<?> path, JpaPredicateVisitor<?> visitor) {
        if (!DATE_COMPARISONS.containsKey(node.getOperator()) || !Date.class.isAssignableFrom(path.getJavaType())) {
            return null;
        }
        return (Date) visitor.getBuilderTools().getArgumentParser().parse(node.getArguments().get(0), path.getJavaType());
    }

    /**
     * Checks whether a path is reached through a collection, which the visitor navigates with a plain path rather
     * than a join when it is no association, such as an element collection, Hibernate then joining it implicitly.
     *
     * @param path The path a comparison is made on
     * @return {@code true} if one of the paths it is reached through is a collection, {@code false} otherwise
     */
    private static boolean reachesThroughCollection(Path<?> path) {
        for (Path<?> parent = path.getParentPath(); parent != null; parent = parent.getParentPath()) {
            if (parent.getModel() instanceof PluralAttribute) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the predicate of an ordering comparison of a date, such as {@link CriteriaBuilder#greaterThan}.
     */
    @FunctionalInterface
    private interface DateComparison {

        /**
         * Builds the predicate comparing a date to its bound.
         *
         * @param criteriaBuilder The builder to use
         * @param date            The date to compare
         * @param bound           The date to compare it to
         * @return The corresponding predicate
         */
        Predicate compare(CriteriaBuilder criteriaBuilder, Expression<Date> date, Date bound);

    }

}
