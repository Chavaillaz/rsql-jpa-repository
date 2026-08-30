package com.chavaillaz.jakarta.persistence.repository.rsql;

import java.util.List;

import cz.jirutka.rsql.parser.RSQLParserException;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;
import com.chavaillaz.jakarta.persistence.repository.Cursor;
import com.chavaillaz.jakarta.persistence.repository.CursorResult;
import com.chavaillaz.jakarta.persistence.repository.Pageable;
import com.chavaillaz.jakarta.persistence.repository.PaginationResult;
import com.chavaillaz.jakarta.persistence.repository.Repository;
import com.chavaillaz.jakarta.persistence.repository.Sort;

/**
 * Contract of the repositories additionally exposing dynamic filtering, letting the API consumers combine
 * conditions on the searchable properties of the repository with an
 * <a href="https://github.com/jirutka/rsql-parser">RSQL</a> filter expression, such as
 * {@code origin==Ethiopia;strength=gt=5}.
 * <p>
 * The RSQL selectors and the {@link Sort} criteria share the very same searchable properties of the repository,
 * so that the very same restriction and public naming govern both.
 *
 * @param <E> The type of the managed entity
 * @param <I> The type of the entity identifier
 * @see Repository
 */
public interface RsqlRepository<E extends Identifiable<I>, I> extends Repository<E, I> {

    /**
     * Counts the entities matching the given RSQL filter expression.
     *
     * @param rsql The RSQL filter expression, {@code null} or blank to count all the entities
     * @return The number of matching entities
     * @throws RSQLParserException      if the expression is not valid RSQL
     * @throws IllegalArgumentException if the expression refers to a property that is not searchable
     */
    long count(@Nullable String rsql);

    /**
     * Searches for entities matching the given RSQL filter expression, with no pagination and the default
     * ordering.
     *
     * @param rsql The RSQL filter expression, {@code null} or blank to match all the entities
     * @return The corresponding entities
     * @see #search(String, Pageable)
     */
    default List<E> search(@Nullable String rsql) {
        return search(rsql, Pageable.UNPAGED).items();
    }

    /**
     * Searches for entities matching the given RSQL filter expression, with no pagination.
     *
     * @param rsql The RSQL filter expression, {@code null} or blank to match all the entities
     * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding entities
     * @see Pageable#sortedBy(Sort)
     * @see #search(String, Pageable)
     */
    default List<E> search(@Nullable String rsql, Sort sort) {
        return search(rsql, Pageable.sortedBy(sort)).items();
    }

    /**
     * Searches for entities matching the given RSQL filter expression, ordered by the default ordering of the
     * repository.
     *
     * @param rsql The RSQL filter expression, {@code null} or blank to match all the entities
     * @param page The page number, starting at zero, or {@code null} to disable the pagination
     * @param size The number of items per page, or {@code null} to disable the pagination
     * @return The entities of the requested page with the total number of matching entities
     * @see Pageable#of(Integer, Integer)
     * @see #search(String, Pageable)
     */
    default PaginationResult<E> search(@Nullable String rsql, @Nullable Integer page, @Nullable Integer size) {
        return search(rsql, Pageable.of(page, size));
    }

    /**
     * Searches for entities matching the given RSQL filter expression.
     *
     * @param rsql The RSQL filter expression, {@code null} or blank to match all the entities
     * @param page The page number, starting at zero, or {@code null} to disable the pagination
     * @param size The number of items per page, or {@code null} to disable the pagination
     * @param sort The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The entities of the requested page with the total number of matching entities
     * @see Pageable#of(Integer, Integer, Sort)
     * @see #search(String, Pageable)
     */
    default PaginationResult<E> search(@Nullable String rsql, @Nullable Integer page, @Nullable Integer size, Sort sort) {
        return search(rsql, Pageable.of(page, size, sort));
    }

    /**
     * Searches for entities matching the given RSQL filter expression.
     *
     * @param rsql     The RSQL filter expression, {@code null} or blank to match all the entities
     * @param pageable The requested page and ordering, {@link Pageable#UNPAGED} to disable the pagination
     * @return The corresponding page, never {@code null}
     * @throws RSQLParserException      if the expression is not valid RSQL
     * @throws IllegalArgumentException if the expression refers to a property that is not searchable, or if the
     *                                  requested ordering is not usable
     */
    PaginationResult<E> search(@Nullable String rsql, Pageable pageable);

    /**
     * Scrolls through the entities matching the given RSQL filter expression, seeking to the requested position
     * instead of skipping the preceding rows.
     *
     * @param rsql   The RSQL filter expression, {@code null} or blank to match all the entities
     * @param cursor The requested position, size and ordering
     * @return The corresponding page with the tokens of the surrounding ones
     * @throws RSQLParserException      if the expression is not valid RSQL
     * @throws IllegalArgumentException if the expression refers to a property that is not searchable, if the
     *                                  ordering refers to an unknown property, to a collection, or if the cursor
     *                                  is malformed or was issued for another ordering
     */
    CursorResult<E> search(@Nullable String rsql, Cursor cursor);

    /**
     * Scrolls through the entities matching the given RSQL filter expression, seeking to the requested position
     * instead of skipping the preceding rows.
     *
     * @param rsql   The RSQL query, all the entities being scrolled through when blank
     * @param cursor The opaque position of the previous page, {@code null} or blank to request the first page
     * @param size   The number of items per page, or {@code null} to apply {@link Cursor#DEFAULT_SIZE}
     * @param sort   The requested ordering, {@link Sort#NONE} to apply the default ordering of the repository
     * @return The corresponding page with the tokens of the surrounding ones
     * @see Cursor#of(String, Integer, Sort)
     * @see #search(String, Cursor)
     */
    default CursorResult<E> search(@Nullable String rsql, @Nullable String cursor, @Nullable Integer size, Sort sort) {
        return search(rsql, Cursor.of(cursor, size, sort));
    }

}
