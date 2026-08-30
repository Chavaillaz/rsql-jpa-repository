package com.chavaillaz.jakarta.persistence.repository.example;

import static org.hibernate.query.restriction.Restriction.equal;
import static org.hibernate.query.restriction.Restriction.greaterThan;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NullMarked;

import com.chavaillaz.jakarta.persistence.repository.Criteria;
import com.chavaillaz.jakarta.persistence.repository.Cursor;
import com.chavaillaz.jakarta.persistence.repository.CursorResult;
import com.chavaillaz.jakarta.persistence.repository.Pageable;
import com.chavaillaz.jakarta.persistence.repository.PaginationResult;
import com.chavaillaz.jakarta.persistence.repository.Sort;
import com.chavaillaz.jakarta.persistence.repository.SortCriterion;
import com.chavaillaz.jakarta.persistence.repository.rsql.AbstractRsqlRepository;

/**
 * Repository of the coffees, exercising every hook of the {@link AbstractRsqlRepository}: the searchable
 * properties, the default ordering, the restrictions, the criteria, the cursor and the RSQL filtering.
 */
@NullMarked
public class CoffeeRepositoryJpa extends AbstractRsqlRepository<CoffeeEntity, Long> implements CoffeeRepository {

    /**
     * Test constructor, replacing the field injection performed by the CDI container.
     */
    @Inject
    public CoffeeRepositoryJpa(EntityManager entityManager) {
        super(entityManager, CoffeeEntity.class);
    }

    /**
     * A correlated subquery, which is exactly what a {@link org.hibernate.query.restriction.Restriction} cannot
     * express, and which does not duplicate the rows as a join on the collection would.
     */
    public static Criteria<CoffeeEntity> tasting(String flavour) {
        return Criteria.exists(TastingNoteEntity.class, TastingNoteEntity_.COFFEE, (criteriaBuilder, note) -> criteriaBuilder.equal(criteriaBuilder.lower(note.get(TastingNoteEntity_.flavour)), flavour.toLowerCase()));
    }

    /**
     * The public naming is deliberately decoupled from the entity one for the roaster and the tasting notes, so
     * that the resolution of a nested path is covered, both for sorting and for RSQL filtering.
     */
    @Override
    protected Map<String, String> searchableProperties() {
        return Map.of(
                "name", "name",
                "origin", "origin",
                "roast", "roast",
                "price", "price",
                "strength", "strength",
                "decaf", "decafLabel",
                "roaster", "roaster.name",
                "notes", "notes.flavour");
    }

    @Override
    protected List<Order> getDefaultOrders(CriteriaBuilder criteriaBuilder, Root<CoffeeEntity> root) {
        return List.of(criteriaBuilder.asc(root.get(CoffeeEntity_.name)));
    }

    @Override
    public List<CoffeeEntity> findByOrigin(String origin) {
        return search(equal(CoffeeEntity_.origin, origin));
    }

    @Override
    public List<CoffeeEntity> findByOrigin(String origin, Sort sort) {
        return search(equal(CoffeeEntity_.origin, origin), sort);
    }

    @Override
    public PaginationResult<CoffeeEntity> findStrongerThan(int strength, Pageable pageable) {
        return search(greaterThan(CoffeeEntity_.strength, strength), pageable);
    }

    @Override
    public Optional<CoffeeEntity> findStrongest() {
        return first(null, null, Sort.of(SortCriterion.desc("strength")));
    }

    @Override
    public List<CoffeeEntity> findTasting(String flavour) {
        return search(tasting(flavour));
    }

    @Override
    public long countTasting(String flavour) {
        return count(tasting(flavour));
    }

    @Override
    public List<CoffeeEntity> findRoastedOrStrong(Roast roast, int strength) {
        return search(Criteria.anyOf(
                Criteria.of(equal(CoffeeEntity_.roast, roast)),
                Criteria.of(greaterThan(CoffeeEntity_.strength, strength))));
    }

    @Override
    public CursorResult<CoffeeEntity> scrollByRoast(Roast roast, Cursor cursor) {
        return scroll(equal(CoffeeEntity_.roast, roast), cursor);
    }

    @Override
    public List<TastingNoteEntity> findNotesOf(CoffeeEntity coffee) {
        return search(TastingNoteEntity.class, equal(TastingNoteEntity_.coffee, coffee));
    }

}
