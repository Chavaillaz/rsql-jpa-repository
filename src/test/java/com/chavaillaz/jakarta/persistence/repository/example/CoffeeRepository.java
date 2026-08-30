package com.chavaillaz.jakarta.persistence.repository.example;

import java.util.List;
import java.util.Optional;

import com.chavaillaz.jakarta.persistence.repository.Cursor;
import com.chavaillaz.jakarta.persistence.repository.CursorResult;
import com.chavaillaz.jakarta.persistence.repository.Pageable;
import com.chavaillaz.jakarta.persistence.repository.PaginationResult;
import com.chavaillaz.jakarta.persistence.repository.Sort;
import com.chavaillaz.jakarta.persistence.repository.rsql.RsqlRepository;

public interface CoffeeRepository extends RsqlRepository<CoffeeEntity, Long> {

    List<CoffeeEntity> findByOrigin(String origin);

    List<CoffeeEntity> findByOrigin(String origin, Sort sort);

    PaginationResult<CoffeeEntity> findStrongerThan(int strength, Pageable pageable);

    Optional<CoffeeEntity> findStrongest();

    List<CoffeeEntity> findTasting(String flavour);

    long countTasting(String flavour);

    List<CoffeeEntity> findRoastedOrStrong(Roast roast, int strength);

    CursorResult<CoffeeEntity> scrollByRoast(Roast roast, Cursor cursor);

    List<TastingNoteEntity> findNotesOf(CoffeeEntity coffee);

}
