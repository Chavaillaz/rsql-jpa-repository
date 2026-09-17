package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.CascadeType.ALL;
import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.sql.Time;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;

@Getter
@Setter
@Entity(name = "Coffee")
@Table(name = "coffee")
public class CoffeeEntity implements Identifiable<Long> {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private @Nullable Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String origin;

    @Enumerated(STRING)
    @Column(nullable = false, length = 10)
    private Roast roast;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int strength;

    private boolean organic;

    private Instant roastedAt;

    /**
     * Deliberately a legacy date, the RSQL visitor parsing no argument into a java.time one.
     */
    private Date packedAt;

    /**
     * Deliberately a legacy time of day, which the RSQL visitor compares within bounds holding a time of day of their own.
     */
    private Time servedUntil;

    /**
     * Deliberately a large object, which Hibernate refuses to lower for the RSQL visitor to match against a pattern.
     */
    @Lob
    private String description;

    /**
     * Deliberately nullable, to prove that a nullable attribute cannot be used as a cursor key.
     */
    private String decafLabel;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "roaster_id")
    private RoasterEntity roaster;

    @OneToMany(mappedBy = "coffee", cascade = ALL, orphanRemoval = true)
    private List<TastingNoteEntity> notes = new ArrayList<>();

    /**
     * The origins of a blend, an element collection of embeddables, whose attributes are only reachable on the
     * elements themselves.
     */
    @ElementCollection
    @CollectionTable(name = "coffee_blend")
    private List<BlendComponent> blend = new ArrayList<>();

    /**
     * The labels of the coffee, an element collection of basic values, which a comparison matches one by one.
     */
    @ElementCollection
    @CollectionTable(name = "coffee_certification")
    private List<String> certifications = new ArrayList<>();

    /**
     * How the beans scored, an embeddable a selector reaches an association through.
     */
    @Embedded
    private Cupping cupping = new Cupping();

    public CoffeeEntity addNote(String flavour) {
        TastingNoteEntity note = new TastingNoteEntity();
        note.setFlavour(flavour);
        note.setCoffee(this);
        notes.add(note);
        return this;
    }

    public CoffeeEntity addCertification(String certification) {
        certifications.add(certification);
        return this;
    }

    public CoffeeEntity cupped(RoasterEntity roaster, int score) {
        cupping.setCuppedBy(roaster);
        cupping.setScore(score);
        return this;
    }

    public CoffeeEntity addBlend(String origin, int share) {
        BlendComponent component = new BlendComponent();
        component.setOrigin(origin);
        component.setShare(share);
        blend.add(component);
        return this;
    }

    @Override
    public String toString() {
        return "Coffee[%s]".formatted(name);
    }

}