package com.chavaillaz.jakarta.persistence.repository.rsql;

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
     * The default parser, reading the types of the base library's cursor keys, the legacy dates and whatever else
     * exposes a static {@code valueOf(String)} method.
     */
    ArgumentParser DEFAULT = StrictArgumentParser::parse;

    /**
     * Parses an argument into the given type.
     *
     * @param argument The argument to parse, never the {@code null} literal
     * @param type     The Java type of the compared property, a wrapper rather than a primitive
     * @return The corresponding value
     * @throws RuntimeException if the argument is not a valid value of that type, or if no argument is read as
     *                          that type, which the caller reports as the illegal argument an API consumer sent
     */
    @Nullable Object parse(String argument, Class<?> type);

}
