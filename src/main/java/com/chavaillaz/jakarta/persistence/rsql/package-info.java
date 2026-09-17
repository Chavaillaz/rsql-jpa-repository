/**
 * Translation of an <a href="https://github.com/jirutka/rsql-parser">RSQL</a> filter expression into a JPA
 * criteria predicate, depending on nothing but the RSQL parser, the JPA criteria API and Hibernate.
 * <p>
 * {@link com.chavaillaz.jakarta.persistence.rsql.RsqlFilter} is the whole of it: it resolves a parsed query
 * against the metamodel of an entity and builds the predicate of that query on whichever root it is given,
 * evaluating each comparison reaching through an association or a collection in a correlated {@code exists}
 * subquery of its own. A selector reaches as deep as the model goes, each attribute being navigated as its kind
 * requires, and a malformed filter is refused as the {@link java.lang.IllegalArgumentException} an API consumer is
 * answered a {@code 400 Bad Request} to, never as a failed statement or a silently wrong result.
 * <p>
 * {@link com.chavaillaz.jakarta.persistence.rsql.RsqlDialect} is the RSQL a filter is written in: the comparison
 * operators it may use, the {@link com.chavaillaz.jakarta.persistence.rsql.ComparisonPredicate predicate} each of
 * them builds from the {@link com.chavaillaz.jakarta.persistence.rsql.RsqlComparison comparison} it is given, and
 * the {@link com.chavaillaz.jakarta.persistence.rsql.ArgumentParser parser} reading the arguments. It also parses
 * the queries, guarding the stack of the RSQL parser, and is where an operator or an argument type of an
 * application is registered.
 * <p>
 * The {@code rsql-jpa-repository} artifact ships the
 * {@link com.chavaillaz.jakarta.persistence.repository.rsql.RsqlRepository repositories} built on top of this
 * package, where a selector is additionally resolved against the searchable properties of a repository, but
 * nothing here depends on them: a filter applies to any criteria query, count, deletion or specification of a
 * framework of your own.
 * <p>
 * The package is {@link org.jspecify.annotations.NullMarked}: every type is non-null unless explicitly annotated
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package com.chavaillaz.jakarta.persistence.rsql;

import org.jspecify.annotations.NullMarked;
