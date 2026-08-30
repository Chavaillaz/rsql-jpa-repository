package com.chavaillaz.jakarta.persistence.repository;

import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BLUE_MOUNTAIN;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.BOURBON_POINTU;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.ETHIOPIA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.GEISHA;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.HARRAR;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.SIDAMO;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.YIRGACHEFFE;
import static com.chavaillaz.jakarta.persistence.repository.example.Coffees.namesOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.persistence.repository.example.CoffeeEntity;
import com.chavaillaz.jakarta.persistence.repository.example.CoffeeRepositoryJpa;
import com.chavaillaz.jakarta.persistence.repository.example.Coffees;
import com.chavaillaz.jakarta.persistence.repository.example.RoasterEntity;
import com.chavaillaz.jakarta.persistence.repository.example.TastingNoteEntity;

@DisplayName("Scrolling through the coffee menu with an RSQL query")
class CoffeeRsqlCursorTest extends HibernateTest {

    @BeforeAll
    static void setupAll() {
        setupSessionFactory(CoffeeEntity.class, RoasterEntity.class, TastingNoteEntity.class);
    }

    @BeforeEach
    void brewTheMenu() {
        runInTransaction(Coffees::persistMenu);
    }

    private <T> T withRepository(Function<CoffeeRepositoryJpa, T> action) {
        return inTransaction(entityManager -> action.apply(new CoffeeRepositoryJpa(entityManager)));
    }

    private CursorResult<CoffeeEntity> searchPage(String rsql, String token, int size, Sort sort) {
        return withRepository(repository -> repository.search(rsql, token, size, sort));
    }

    @Test
    @DisplayName("scrolls through an RSQL query")
    void scrollsThroughAnRsqlQuery() {
        CursorResult<CoffeeEntity> first = searchPage("origin==" + ETHIOPIA, null, 2, Sort.NONE);
        assertThat(namesOf(first)).containsExactly(HARRAR, SIDAMO);

        CursorResult<CoffeeEntity> second = searchPage("origin==" + ETHIOPIA, first.next(), 2, Sort.NONE);
        assertThat(namesOf(second)).containsExactly(YIRGACHEFFE);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("scrolls through an RSQL query joining a collection, without duplicating the rows")
    void scrollsThroughACollectionJoin() {
        String rsql = "notes==Citrus,notes==Floral";

        CursorResult<CoffeeEntity> first = searchPage(rsql, null, 3, Sort.NONE);
        assertThat(namesOf(first)).containsExactly(BOURBON_POINTU, GEISHA, SIDAMO);

        CursorResult<CoffeeEntity> second = searchPage(rsql, first.next(), 3, Sort.NONE);
        assertThat(namesOf(second)).containsExactly(YIRGACHEFFE);
    }

    @Test
    @DisplayName("falls back on findAll when the RSQL query is blank")
    void fallsBackOnFindAll() {
        assertThat(namesOf(searchPage("  ", null, 2, Sort.NONE)))
                .containsExactly(BLUE_MOUNTAIN, BOURBON_POINTU);
    }

}
