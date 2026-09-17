package com.chavaillaz.jakarta.persistence.repository.rsql;

import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.repository.SortCriterion;

/**
 * Selector of an RSQL comparison resolved against the metamodel: the entity attributes it navigates, and how each
 * of them is navigated once a query hands its root over.
 * <p>
 * The whole path is resolved against the metamodel before anything is built, so that a selector an API consumer
 * cannot navigate, such as one reaching through a basic attribute, is refused as the illegal argument it is rather
 * than left to the persistence provider. Each attribute is then navigated as its kind requires: an association or
 * a collection is joined, since only a join can be navigated further and a collection has its elements compared
 * one by one, where a basic attribute or an embeddable is read as a plain path. An embeddable is joined as well
 * when an association or a collection behind it has to be, only a {@link From} being joinable, which costs no join
 * in the emitted SQL.
 *
 * @see RsqlQueries#compare(com.chavaillaz.jakarta.persistence.repository.RepositoryContext, cz.jirutka.rsql.parser.ast.ComparisonNode, RsqlDialect)
 */
final class PropertyPath {

    /**
     * Compiled form of the nesting separator of the base library, quoted since the dot is a regex metacharacter.
     */
    private static final Pattern NESTING_PATTERN = Pattern.compile(Pattern.quote(SortCriterion.NESTING_SEPARATOR));

    private final String selector;

    private final List<Step> steps;

    private final Class<?> javaType;

    private final boolean plural;

    private final boolean managed;

    private final boolean joins;

    private PropertyPath(String selector, List<Step> steps, Class<?> javaType, boolean plural, boolean managed) {
        this.selector = selector;
        this.steps = List.copyOf(steps);
        this.javaType = javaType;
        this.plural = plural;
        this.managed = managed;
        this.joins = steps.stream().anyMatch(Step::joined);
    }

    /**
     * Resolves the selector of a comparison against the metamodel.
     *
     * @param metamodel  The metamodel the attributes are looked up in
     * @param entityType The type of the managed entity, which the path starts from
     * @param selector   The selector as the API consumer sent it, which the error messages name
     * @param property   The entity attribute path the selector resolves to, dot separated
     * @return The corresponding resolved path
     * @throws IllegalArgumentException if the path names an attribute the entity does not have, or reaches through
     *                                  an attribute that has none, such as a basic one
     */
    static PropertyPath resolve(Metamodel metamodel, Class<?> entityType, String selector, String property) {
        String[] names = NESTING_PATTERN.split(property);
        if (names.length == 0) {
            // The selector was made of nothing but separators, such as a single dot
            throw unknown(selector);
        }

        List<Attribute<?, ?>> attributes = new ArrayList<>(names.length);
        ManagedType<?> owner = metamodel.managedType(entityType);
        for (String name : names) {
            if (owner == null) {
                // The previous attribute is basic, or holds basic elements, and has nothing to navigate
                throw unknown(selector);
            }
            Attribute<?, ?> attribute = attributeOf(owner, name, selector);
            attributes.add(attribute);
            owner = navigableType(attribute);
        }

        Attribute<?, ?> compared = attributes.get(attributes.size() - 1);
        return new PropertyPath(selector, steps(attributes), javaTypeOf(compared), compared instanceof PluralAttribute<?, ?, ?>, owner != null);
    }

    /**
     * Gets the attribute of the given name, as the illegal argument an unknown one is.
     *
     * @param owner     The type the attribute is looked up on
     * @param name      The name of the attribute
     * @param selector  The selector the error message names
     * @return The corresponding attribute
     * @throws IllegalArgumentException if the type has no such attribute
     */
    private static Attribute<?, ?> attributeOf(ManagedType<?> owner, String name, String selector) {
        try {
            return owner.getAttribute(name);
        } catch (IllegalArgumentException e) {
            // What the metamodel raises for an unknown attribute, which an API consumer is free to send
            throw unknown(selector, e);
        }
    }

    /**
     * Gets the type the rest of a path is resolved against, which is the type of a singular attribute and the
     * element type of a collection, a collection having its elements compared and navigated.
     *
     * @param attribute The attribute to navigate
     * @return The corresponding managed type, or {@code null} when nothing can be navigated behind the attribute
     */
    private static @Nullable ManagedType<?> navigableType(Attribute<?, ?> attribute) {
        Type<?> type = attribute instanceof PluralAttribute<?, ?, ?> plural
                ? plural.getElementType()
                : ((SingularAttribute<?, ?>) attribute).getType();
        return type instanceof ManagedType<?> managed ? managed : null;
    }

    /**
     * Gets the Java type of the compared values, which is the element type of a collection.
     *
     * @param attribute The compared attribute
     * @return The corresponding type
     */
    private static Class<?> javaTypeOf(Attribute<?, ?> attribute) {
        return attribute instanceof PluralAttribute<?, ?, ?> plural ? plural.getBindableJavaType() : attribute.getJavaType();
    }

    /**
     * Decides how each attribute of a path is navigated, walking it backwards since an embeddable only has to be
     * joined when something behind it is.
     *
     * @param attributes The attributes of the path, in order
     * @return The corresponding steps, in the same order
     */
    private static List<Step> steps(List<Attribute<?, ?>> attributes) {
        Step[] steps = new Step[attributes.size()];
        boolean joinsBehind = false;
        for (int index = attributes.size() - 1; index >= 0; index--) {
            Attribute<?, ?> attribute = attributes.get(index);
            boolean last = index == attributes.size() - 1;
            boolean joined = attribute instanceof PluralAttribute<?, ?, ?>
                    || (attribute.isAssociation() && !last)
                    || (isEmbedded(attribute) && joinsBehind);
            steps[index] = new Step(attribute, joined);
            joinsBehind = joinsBehind || joined;
        }
        return List.of(steps);
    }

    private static boolean isEmbedded(Attribute<?, ?> attribute) {
        return attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.EMBEDDED;
    }

    private static IllegalArgumentException unknown(String selector) {
        return unknown(selector, null);
    }

    private static IllegalArgumentException unknown(String selector, @Nullable Throwable cause) {
        return new IllegalArgumentException("Cannot filter on unknown property " + selector, cause);
    }

    /**
     * Gets the selector of the comparison as the API consumer sent it, which the error messages name.
     *
     * @return The selector of the comparison
     */
    String selector() {
        return selector;
    }

    /**
     * Gets the Java type of the compared values, the element type when a collection is compared.
     *
     * @return The Java type of the compared values
     */
    Class<?> javaType() {
        return javaType;
    }

    /**
     * Tells whether the compared values are the elements of a collection.
     *
     * @return {@code true} for the elements of a collection, {@code false} otherwise
     */
    boolean plural() {
        return plural;
    }

    /**
     * Tells whether the compared values are entities or embeddables rather than basic values.
     *
     * @return {@code true} for a managed type, {@code false} for a basic one
     */
    boolean managed() {
        return managed;
    }

    /**
     * Tells whether the navigation joins anything, an association or a collection, in which case the comparison
     * belongs in a correlated subquery of its own rather than in the query itself, see
     * {@link RsqlQueries#compare(com.chavaillaz.jakarta.persistence.repository.RepositoryContext, cz.jirutka.rsql.parser.ast.ComparisonNode, RsqlDialect)}.
     *
     * @return {@code true} if the navigation joins anything, {@code false} otherwise
     */
    boolean joins() {
        return joins;
    }

    /**
     * Gets the compared attribute, the collection itself when its elements are compared.
     *
     * @return The compared attribute
     */
    Attribute<?, ?> attribute() {
        return steps.get(steps.size() - 1).attribute();
    }

    /**
     * Navigates every attribute but the compared one, from the root or join a query hands over.
     *
     * @param from The root or join the path starts from
     * @return The path the compared attribute is read or joined from
     */
    Path<?> parent(From<?, ?> from) {
        Path<?> path = from;
        for (Step step : steps.subList(0, steps.size() - 1)) {
            path = step.navigate(path);
        }
        return path;
    }

    /**
     * Navigates the compared attribute itself, from the path its {@link #parent(From) parent} resolved to, which
     * is a join when its elements are compared.
     *
     * @param parent The path the compared attribute is read or joined from
     * @return The path of the compared values
     */
    Path<?> navigate(Path<?> parent) {
        return steps.get(steps.size() - 1).navigate(parent);
    }

    /**
     * A single attribute of a resolved path, and how it is navigated.
     *
     * @param attribute The attribute to navigate
     * @param joined    {@code true} to join it, {@code false} to read it as a plain path
     */
    private record Step(Attribute<?, ?> attribute, boolean joined) {

        /**
         * Navigates this attribute from the given path.
         * <p>
         * A joined attribute is always reached from a {@link From}, by construction: the root of a query is one,
         * a join is one, and an embeddable carrying a joined attribute is joined as well.
         *
         * @param parent The path to navigate from
         * @return The corresponding path
         */
        private Path<?> navigate(Path<?> parent) {
            return joined ? ((From<?, ?>) parent).join(attribute.getName()) : parent.get(attribute.getName());
        }

    }

}
