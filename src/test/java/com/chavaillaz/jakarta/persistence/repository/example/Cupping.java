package com.chavaillaz.jakarta.persistence.repository.example;

import static jakarta.persistence.FetchType.LAZY;

import jakarta.persistence.Embeddable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * How the beans scored when they were tasted, deliberately an embeddable holding an association, which an RSQL
 * selector can only reach the attributes of the roaster behind by joining the embeddable itself.
 */
@Getter
@Setter
@Embeddable
public class Cupping {

    private @Nullable Integer score;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "cupped_by_id")
    private @Nullable RoasterEntity cuppedBy;

}
