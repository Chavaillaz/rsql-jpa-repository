package com.chavaillaz.jakarta.persistence.repository.example;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores the lot of a coffee, a string, as the number it spells, deliberately refusing any other string: a
 * converter is free to refuse a value of the very type it converts.
 */
@Converter
public class LotConverter implements AttributeConverter<String, Integer> {

    @Override
    public Integer convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : Integer.valueOf(attribute);
    }

    @Override
    public String convertToEntityAttribute(Integer column) {
        return column == null ? null : String.valueOf(column);
    }

}
