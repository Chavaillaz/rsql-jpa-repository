/**
 * Dynamic RSQL filtering, exposing an <a href="https://github.com/jirutka/rsql-parser">RSQL</a> filter expression
 * to the API consumers on top of a {@link com.chavaillaz.jakarta.persistence.repository.AbstractRepository}.
 * <p>
 * {@link com.chavaillaz.jakarta.persistence.repository.rsql.RsqlRepository} extends the base
 * {@link com.chavaillaz.jakarta.persistence.repository.Repository} contract with the RSQL search and count
 * methods, {@link com.chavaillaz.jakarta.persistence.repository.rsql.AbstractRsqlRepository} implementing them on
 * top of {@link com.chavaillaz.jakarta.persistence.repository.AbstractRepository}.
 * <p>
 * {@link com.chavaillaz.jakarta.persistence.repository.rsql.RsqlQueries} is the actual plumbing, translating an
 * RSQL query into a JPA criteria query; it is considered an implementation detail of
 * {@code AbstractRsqlRepository} rather than a public API, and is documented for the maintainers of this package
 * rather than for the authors of a repository, who are only expected to use the {@code protected} methods
 * {@code AbstractRsqlRepository} exposes. Every RSQL selector is resolved against the very same
 * {@link com.chavaillaz.jakarta.persistence.repository.EntityOrdering searchable properties} the base library
 * uses for sorting, so that the two share a single restriction and public naming.
 * <p>
 * The package is {@link org.jspecify.annotations.NullMarked}: every type is non-null unless explicitly annotated
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package com.chavaillaz.jakarta.persistence.repository.rsql;

import org.jspecify.annotations.NullMarked;
