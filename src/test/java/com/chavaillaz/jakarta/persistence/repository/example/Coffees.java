package com.chavaillaz.jakarta.persistence.repository.example;

import static java.util.stream.Collectors.toMap;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.chavaillaz.jakarta.persistence.repository.CursorResult;
import com.chavaillaz.jakarta.persistence.repository.PaginationResult;

/**
 * Coffee fixtures, deliberately deterministic: the menu is always the very same seven coffees, so that the
 * expected pages can be written by hand.
 */
public final class Coffees {

    public static final String ETHIOPIA = "Ethiopia";
    public static final String PANAMA = "Panama";
    public static final String JAMAICA = "Jamaica";
    public static final String BLUE_MOUNTAIN = "Blue Mountain";
    public static final String BOURBON_POINTU = "Bourbon Pointu";
    public static final String GEISHA = "Geisha";
    public static final String HARRAR = "Harrar";
    public static final String KONA = "Kona";
    public static final String SIDAMO = "Sidamo";
    public static final String YIRGACHEFFE = "Yirgacheffe";
    /**
     * The whole menu, ordered by name, which is the default ordering of the repository.
     */
    public static final List<String> MENU = List.of(
            BLUE_MOUNTAIN, BOURBON_POINTU, GEISHA, HARRAR, KONA, SIDAMO, YIRGACHEFFE);

    private Coffees() {
        // Private constructor to prevent instantiation
    }

    public static RoasterEntity roaster(String name, String country) {
        RoasterEntity roaster = new RoasterEntity();
        roaster.setName(name);
        roaster.setCountry(country);
        return roaster;
    }

    public static CoffeeEntity coffee(String name, String origin, Roast roast, String price, int strength) {
        CoffeeEntity coffee = new CoffeeEntity();
        coffee.setName(name);
        coffee.setOrigin(origin);
        coffee.setRoast(roast);
        coffee.setPrice(new BigDecimal(price));
        coffee.setStrength(strength);
        coffee.setRoastedAt(Instant.parse("2024-01-01T06:00:00Z"));
        return coffee;
    }

    public static CoffeeEntity coffee(String name) {
        return coffee(name, ETHIOPIA, Roast.MEDIUM, "10.00", 5);
    }

    /**
     * Persists the whole menu, its two roasters and its tasting notes.
     *
     * @param entityManager The entity manager to persist with
     * @return The persisted coffees, indexed by name
     */
    public static Map<String, CoffeeEntity> persistMenu(EntityManager entityManager) {
        RoasterEntity moka = roaster("Moka Brothers", "France");
        RoasterEntity kaldi = roaster("Kaldi Roasting", "Ethiopia");
        entityManager.persist(moka);
        entityManager.persist(kaldi);

        List<CoffeeEntity> coffees = List.of(
                coffee(BLUE_MOUNTAIN, JAMAICA, Roast.MEDIUM, "42.00", 5).addNote("Chocolate"),
                coffee(BOURBON_POINTU, "Reunion", Roast.LIGHT, "55.50", 4).addNote("Floral"),
                coffee(GEISHA, PANAMA, Roast.LIGHT, "80.00", 3).addNote("Jasmine").addNote("Citrus"),
                coffee(HARRAR, ETHIOPIA, Roast.DARK, "18.00", 8).addNote("Blueberry"),
                coffee(KONA, "Hawaii", Roast.MEDIUM, "35.00", 6).addNote("Nutty"),
                coffee(SIDAMO, ETHIOPIA, Roast.MEDIUM, "22.50", 7).addNote("Citrus"),
                coffee(YIRGACHEFFE, ETHIOPIA, Roast.LIGHT, "25.00", 4).addNote("Citrus").addNote("Floral"));

        coffees.forEach(coffee -> {
            coffee.setRoaster(ETHIOPIA.equals(coffee.getOrigin()) ? kaldi : moka);
            entityManager.persist(coffee);
        });
        entityManager.flush();

        return coffees.stream().collect(toMap(CoffeeEntity::getName, coffee -> coffee));
    }

    public static List<String> namesOf(Collection<CoffeeEntity> coffees) {
        return coffees.stream().map(CoffeeEntity::getName).toList();
    }

    public static List<String> namesOf(PaginationResult<CoffeeEntity> result) {
        return namesOf(result.items());
    }

    public static List<String> namesOf(CursorResult<CoffeeEntity> result) {
        return namesOf(result.items());
    }

}