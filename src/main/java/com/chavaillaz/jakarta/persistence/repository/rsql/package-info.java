/**
 * Dynamic RSQL filtering, exposing an <a href="https://github.com/jirutka/rsql-parser">RSQL</a> filter expression
 * to the API consumers on top of a {@link com.chavaillaz.jakarta.persistence.repository.AbstractRepository}.
 * <p>
 * {@link com.chavaillaz.jakarta.persistence.repository.rsql.RsqlRepository} extends the base
 * {@link com.chavaillaz.jakarta.persistence.repository.Repository} contract with the RSQL search and count
 * methods, {@link com.chavaillaz.jakarta.persistence.repository.rsql.AbstractRsqlRepository} implementing them on
 * top of {@link com.chavaillaz.jakarta.persistence.repository.AbstractRepository}.
 * <p>
 * {@link com.chavaillaz.jakarta.persistence.repository.rsql.RsqlQueries} translates an RSQL query into
 * {@link com.chavaillaz.jakarta.persistence.repository.Criteria}, which the
 * {@link com.chavaillaz.jakarta.persistence.repository.EntityQueries queries} of the base library apply like any
 * other. It is an implementation detail of {@code AbstractRsqlRepository}, whose {@code protected} methods are what
 * a repository is written with. Every RSQL selector is resolved against the very same
 * {@link com.chavaillaz.jakarta.persistence.repository.EntityOrdering searchable properties} the base library
 * uses for sorting, so that the two share a single restriction and public naming, and then against the metamodel,
 * which decides how the attributes it names are navigated.
 * <p>
 * {@link com.chavaillaz.jakarta.persistence.repository.rsql.RsqlDialect} is the RSQL a repository accepts: the
 * comparison operators its queries may use, the {@link
 * com.chavaillaz.jakarta.persistence.repository.rsql.ComparisonPredicate predicate} each of them builds from the
 * {@link com.chavaillaz.jakarta.persistence.repository.rsql.RsqlComparison comparison} it is given, and the
 * {@link com.chavaillaz.jakarta.persistence.repository.rsql.ArgumentParser parser} reading the arguments of a
 * comparison. It is where an operator or an argument type of an application is registered.
 * <p>
 * The package is {@link org.jspecify.annotations.NullMarked}: every type is non-null unless explicitly annotated
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package com.chavaillaz.jakarta.persistence.repository.rsql;

import org.jspecify.annotations.NullMarked;
