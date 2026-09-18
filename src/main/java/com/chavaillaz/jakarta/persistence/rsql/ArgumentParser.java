package com.chavaillaz.jakarta.persistence.rsql;

import org.jspecify.annotations.Nullable;

/**
 * Reads the argument of an RSQL comparison into the Java type of the property it is compared to, such as the
 * {@code 5} of {@code strength=gt=5} into an {@link Integer}.
 * <p>
 * The {@link #DEFAULT} parser reads the types a JPA attribute is usually mapped to, refusing whatever an API
 * consumer can send but no query should run with, see {@link RsqlDialect#DEFAULT}. Teach it a type of your own
 * with {@link RsqlDialect#withArgumentType(Class, java.util.function.Function)} rather than replacing it, so that
 * those refusals stand; a parser replacing it entirely would have to refuse the same arguments.
 * <p>
 * Whatever a parser is given has already been checked for what no type may hold, and the {@code null} literal has
 * already been read, so that a parser never sees it, see
 * {@link RsqlComparison#parse(String, Class) RsqlComparison#parse}.
 */
@FunctionalInterface
public interface ArgumentParser {

    /**
     * The default parser, reading the usual types of a JPA attribute, the legacy dates and whatever else exposes
     * a static {@code valueOf(String)} method.
     */
    ArgumentParser DEFAULT = StrictArgumentParser::parse;

    /**
     * The largest {@link java.math.BigDecimal#scale() scale}, negative or positive, a decimal argument may have,
     * as many digits as the largest precision a PostgreSQL numeric column may be declared with. The database or
     * its driver may spell a decimal out digit by digit to bind it, as H2 does, so that the few bytes of an
     * argument such as {@code 1e30000000} would otherwise take seconds to bind, only for the database to refuse a
     * value no column holds.
     */
    int MAX_DECIMAL_SCALE = 1000;

    /**
     * The largest number of characters a decimal or integer argument may be written with, twice
     * {@link #MAX_DECIMAL_SCALE}, enough to spell out any value a numeric column may be declared to hold. The JDK
     * parses the digits of such a number in quadratic time, and a query reads the arguments of its filter more
     * than once, so that the quarter of a megabyte of digits a consumer is free to send in the body of a request
     * would otherwise take seconds to parse, only for the database to refuse the value.
     */
    int MAX_DECIMAL_LENGTH = 2 * MAX_DECIMAL_SCALE;

    /**
     * Parses an argument into the given type.
     *
     * @param argument The argument to parse, never the {@code null} literal
     * @param type     The Java type of the compared property, a wrapper rather than a primitive
     * @return The corresponding value, of that very type, one of another being refused by the caller
     * @throws RuntimeException if the argument is not a valid value of that type, or if no argument is read as
     *                          that type, which the caller reports as the illegal argument an API consumer sent
     */
    @Nullable Object parse(String argument, Class<?> type);

}
