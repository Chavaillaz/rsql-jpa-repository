package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.Hibernate;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.persistence.Identifiable;

@Getter
@Setter
@Entity(name = "TastingNote")
@Table(name = "tasting_note")
public class TastingNoteEntity implements Identifiable<Long> {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private @Nullable Long id;

    @Column(nullable = false)
    private String flavour;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "coffee_id")
    private CoffeeEntity coffee;

    @Override
    public String toString() {
        return "Note[%s,%s]".formatted(id, flavour);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o))
            return false;
        return id != null && id.equals(((TastingNoteEntity) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();   // stable across the transient→persistent transition
    }

}