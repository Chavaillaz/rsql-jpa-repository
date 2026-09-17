package com.chavaillaz.jakarta.persistence.repository.rsql;

import static com.chavaillaz.jakarta.persistence.repository.rsql.RsqlQueries.MAX_DECIMAL_LENGTH;
import static com.chavaillaz.jakarta.persistence.repository.rsql.RsqlQueries.MAX_DECIMAL_SCALE;
import static java.util.Map.entry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Argument parsing of {@link ArgumentParser#DEFAULT}, reading the types a JPA attribute is usually mapped to and
 * refusing what an API consumer can send but no query should run with.
 * <p>
 * The types are the ones the base library reads a cursor key as, so that whatever can be sorted and sought on can
 * be filtered on, plus the legacy dates and whatever else exposes a static {@code valueOf(String)} method, which
 * covers the value types of an application.
 * <p>
 * A value is read as it is written or not at all: a date time missing its seconds, an impossible day, a year of
 * more than four digits, a boolean spelled {@code yes} or a decimal no column could hold are refused rather than
 * silently compared to something else, or to something the database takes seconds to bind.
 *
 * @see RsqlDialect#DEFAULT
 */
final class StrictArgumentParser {

    /**
     * The patterns a {@link Date} argument may be written in, a date time first so that the time of day of an
     * argument holding one is not left out.
     */
    private static final List<String> DATE_PATTERNS = List.of("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd");

    /**
     * The number of digits the year of a date argument is written with, as the SQL standard and the {@code valueOf}
     * of the {@code java.sql} dates require, and as the databases hold: PostgreSQL fails a statement binding a
     * timestamp past the year 294276, and no column holds the year 300000 a consumer is free to send.
     */
    private static final int YEAR_LENGTH = 4;

    /**
     * The parser of each supported type, keyed by the type itself rather than by an assignable one, so that a
     * value is only ever read as the very type the compared property holds.
     */
    private static final Map<Class<?>, Function<String, ?>> PARSERS = Map.ofEntries(
            entry(String.class, argument -> argument),
            entry(Boolean.class, StrictArgumentParser::parseBoolean),
            entry(Character.class, StrictArgumentParser::parseCharacter),
            entry(Byte.class, Byte::valueOf),
            entry(Short.class, Short::valueOf),
            entry(Integer.class, Integer::valueOf),
            entry(Long.class, Long::valueOf),
            entry(Float.class, Float::valueOf),
            entry(Double.class, Double::valueOf),
            entry(BigInteger.class, argument -> new BigInteger(requireDigits(argument))),
            entry(BigDecimal.class, StrictArgumentParser::parseDecimal),
            entry(UUID.class, StrictArgumentParser::parseUuid),
            // The legacy dates of java.sql are left to their own valueOf, lenient as it is, a column of such a
            // type being written and read that way anywhere else in an application as well
            entry(Date.class, StrictArgumentParser::parseDate),
            entry(java.sql.Date.class, java.sql.Date::valueOf),
            entry(Time.class, Time::valueOf),
            entry(Timestamp.class, Timestamp::valueOf),
            entry(Instant.class, argument -> Instant.parse(requireYear(argument))),
            entry(LocalDate.class, argument -> LocalDate.parse(requireYear(argument))),
            entry(LocalDateTime.class, argument -> LocalDateTime.parse(requireYear(argument))),
            entry(OffsetDateTime.class, argument -> OffsetDateTime.parse(requireYear(argument))),
            entry(ZonedDateTime.class, argument -> ZonedDateTime.parse(requireYear(argument))),
            entry(LocalTime.class, LocalTime::parse),
            entry(OffsetTime.class, OffsetTime::parse),
            entry(Year.class, StrictArgumentParser::parseYear),
            entry(Duration.class, StrictArgumentParser::parseDuration));

    private StrictArgumentParser() {
        // This utility class should not be instantiated
    }

    /**
     * Parses an argument into the given type.
     *
     * @param argument The argument to parse, never the {@code null} literal
     * @param type     The Java type of the compared property, a wrapper rather than a primitive
     * @return The corresponding value
     * @throws RuntimeException if the argument is not a valid value of that type, or if no argument is read as
     *                          that type
     */
    static Object parse(String argument, Class<?> type) {
        Function<String, ?> parser = PARSERS.get(type);
        if (parser != null) {
            return parser.apply(argument);
        }
        if (type.isEnum()) {
            return parseConstant(argument, type);
        }
        return parseValueOf(argument, type);
    }

    /**
     * Reads a boolean from {@code true} or {@code false} only, whatever its case, where
     * {@link Boolean#valueOf(String)} reads any other text as {@code false}: {@code organic==yes} would otherwise
     * match the very entities a consumer asked to leave out.
     *
     * @param argument The argument to parse
     * @return The corresponding value
     * @throws IllegalArgumentException if the argument spells neither
     */
    private static Boolean parseBoolean(String argument) {
        if (!"true".equalsIgnoreCase(argument) && !"false".equalsIgnoreCase(argument)) {
            throw new IllegalArgumentException("Expected true or false, got " + argument);
        }
        return Boolean.parseBoolean(argument);
    }

    private static Character parseCharacter(String argument) {
        if (argument.length() != 1) {
            throw new IllegalArgumentException("Expected a single character, got " + argument.length());
        }
        return argument.charAt(0);
    }

    /**
     * Reads a decimal, refusing one no column could hold long before the database is asked to bind it: the JDK
     * parses the digits of a decimal in quadratic time, and a database or its driver may spell a negative scale
     * out digit by digit, as H2 does, so that the few bytes of {@code 1e30000000} take it some 37 seconds.
     *
     * @param argument The argument to parse
     * @return The corresponding value
     * @throws IllegalArgumentException if the argument is written with too many characters or holds an outsized
     *                                  scale, positive or negative
     */
    private static BigDecimal parseDecimal(String argument) {
        BigDecimal decimal = new BigDecimal(requireDigits(argument));
        if (Math.abs((long) decimal.scale()) > MAX_DECIMAL_SCALE) {
            throw new IllegalArgumentException("Expected a scale within %d, got %d".formatted(MAX_DECIMAL_SCALE, decimal.scale()));
        }
        return decimal;
    }

    /**
     * Checks that a number is written with few enough characters to be parsed in reasonable time, before its
     * digits are read, see {@link RsqlQueries#MAX_DECIMAL_LENGTH}.
     *
     * @param argument The argument to check
     * @return The very same argument
     * @throws IllegalArgumentException if it is written with too many characters
     */
    private static String requireDigits(String argument) {
        if (argument.length() > MAX_DECIMAL_LENGTH) {
            throw new IllegalArgumentException("Expected at most %d characters, got %d".formatted(MAX_DECIMAL_LENGTH, argument.length()));
        }
        return argument;
    }

    /**
     * Reads an identifier written in the canonical form {@link UUID#toString()} gives it, whatever its case, where
     * {@link UUID#fromString(String)} also reads a shorter group as the number it spells.
     *
     * @param argument The argument to parse
     * @return The corresponding value
     * @throws IllegalArgumentException if the argument is written in any other form
     */
    private static UUID parseUuid(String argument) {
        UUID uuid = UUID.fromString(argument);
        if (!uuid.toString().equalsIgnoreCase(argument)) {
            throw new IllegalArgumentException("Expected an identifier written as " + uuid);
        }
        return uuid;
    }

    /**
     * Parses a date that reads back as the very argument it is parsed from, in one of the two patterns of the RSQL
     * convention, in the default time zone but with the digits and calendar of no locale, its year being written
     * with four digits.
     * <p>
     * A {@link SimpleDateFormat} is lenient and ignores whatever follows the pattern it matches, so that
     * {@code 2024-01-01T10:00} would otherwise be read as midnight, {@code 2024-01-01T10:00:00Z} in the time zone
     * of the server whatever its offset, and {@code 2024-02-30} as March 1st, and it reads a year in the calendar
     * of the default locale, 2024 being the Buddhist year 1481 under a Thai one.
     *
     * @param argument The argument to parse
     * @return The corresponding date
     * @throws IllegalArgumentException if the argument is written in none of those patterns, or with a year of
     *                                  more than four digits
     */
    private static Date parseDate(String argument) {
        for (String pattern : DATE_PATTERNS) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
            Date date = format.parse(requireYear(argument), new ParsePosition(0));
            if (date != null && format.format(date).equals(argument)) {
                return date;
            }
        }
        throw new IllegalArgumentException("Expected a date written as " + String.join(" or ", DATE_PATTERNS));
    }

    private static Year parseYear(String argument) {
        return Year.parse(requireYearLength(argument, argument.length()));
    }

    /**
     * Checks that the year of a date argument is written with four digits and no sign, the ISO formats of
     * {@code java.time} and the {@code yyyy} of a {@link SimpleDateFormat} writing a later year with as many
     * digits as it takes, see {@link #YEAR_LENGTH}.
     *
     * @param argument The argument to check
     * @return The very same argument
     * @throws IllegalArgumentException if its year is written otherwise
     */
    private static String requireYear(String argument) {
        return requireYearLength(argument, argument.indexOf('-'));
    }

    private static String requireYearLength(String argument, int length) {
        if (length != YEAR_LENGTH) {
            throw new IllegalArgumentException("Expected a year of %d digits, got %s".formatted(YEAR_LENGTH, argument));
        }
        return argument;
    }

    /**
     * Reads a duration, refusing one longer than the nanoseconds of a {@code long}, which is how a database or its
     * driver may hold or bind it, and far beyond any duration an application measures.
     *
     * @param argument The argument to parse
     * @return The corresponding value
     * @throws RuntimeException if the argument is no ISO 8601 duration, or holds a longer one
     */
    private static Duration parseDuration(String argument) {
        Duration duration = Duration.parse(argument);
        duration.toNanos();
        return duration;
    }

    /**
     * Reads the constant of an enumeration, by its very name as the base library reads a cursor key.
     *
     * @param argument The argument to parse
     * @param type     The type of the enumeration
     * @return The corresponding constant
     * @throws IllegalArgumentException if the enumeration has no such constant
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object parseConstant(String argument, Class<?> type) {
        // The type is an enumeration, which its own constants are read from
        return Enum.valueOf((Class<Enum>) type, argument);
    }

    /**
     * Reads a value type of the application through its static {@code valueOf(String)} method, the convention the
     * JDK value types follow, so that an attribute of such a type can be filtered on without teaching this parser
     * anything, see {@link RsqlDialect#withArgumentType(Class, Function)}.
     *
     * @param argument The argument to parse
     * @param type     The Java type of the compared property
     * @return The corresponding value
     * @throws IllegalArgumentException if the type declares no such method, or if it refused the argument
     */
    private static Object parseValueOf(String argument, Class<?> type) {
        Method valueOf;
        try {
            valueOf = type.getMethod("valueOf", String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No argument is read as " + type.getName(), e);
        }
        if (!Modifier.isStatic(valueOf.getModifiers()) || !type.isAssignableFrom(valueOf.getReturnType())) {
            throw new IllegalArgumentException("No argument is read as " + type.getName());
        }
        try {
            return valueOf.invoke(null, argument);
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException("Expected a value of " + type.getName(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("No argument is read as " + type.getName(), e);
        }
    }

}
