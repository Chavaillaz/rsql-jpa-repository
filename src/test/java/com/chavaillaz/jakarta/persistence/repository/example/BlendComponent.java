package com.chavaillaz.jakarta.persistence.repository.example;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.Getter;
import lombok.Setter;

/**
 * One of the origins a blend is made of, deliberately naming its origin as the coffee does, since the RSQL visitor
 * looks an attribute reached through an element collection up on the entity owning the collection.
 */
@Getter
@Setter
@Embeddable
public class BlendComponent {

    @Column(nullable = false)
    private String origin;

    /**
     * Deliberately named as the reserved word SHARE of Oracle is, its column being named otherwise, an RSQL
     * selector naming the attribute rather than the column.
     */
    @Column(name = "blend_share")
    private int share;

}
