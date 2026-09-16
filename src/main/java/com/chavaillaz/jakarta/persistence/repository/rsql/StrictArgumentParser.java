package com.chavaillaz.jakarta.persistence.repository.rsql;

import static com.chavaillaz.jakarta.persistence.repository.rsql.RsqlQueries.MAX_DECIMAL_LENGTH;
import static com.chavaillaz.jakarta.persistence.repository.rsql.RsqlQueries.MAX_DECIMAL_SCALE;

import java.math.BigDecimal;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.tennaito.rsql.misc.ArgumentFormatException;
import com.github.tennaito.rsql.misc.DefaultArgumentParser;
import org.jspecify.annotations.Nullable;

/**
 * Parses the arguments of the comparisons as rsql-jpa does, refusing those an API consumer can send but no query
 * should run with, as arguments their property cannot be parsed from.
 *
 * @see RsqlQueries#defaultPredicateVisitor(Class)
 */
final class StrictArgumentParser extends DefaultArgumentParser {

    @Override
    public <T> @Nullable T parse(String argument, Class<T> type) {
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            // No text is parsed into a collection, but rsql-jpa reads null before even looking at the type, which
            // left Hibernate to refuse comparing roaster.coffees to it, some operators only once rendering it
            throw new ArgumentFormatException(argument, type);
        }
        if (argument.indexOf(Character.MIN_VALUE) >= 0) {
            // PostgreSQL refuses a NUL character in any text, failing the statement with a DataException
            throw new ArgumentFormatException(argument, type);
        }
        if (type.equals(BigDecimal.class) && argument.length() > MAX_DECIMAL_LENGTH) {
            // Refused before being parsed, which takes quadratic time in its digits, see MAX_DECIMAL_LENGTH
            throw new ArgumentFormatException(argument, type);
        }
        T value = super.parse(argument, type);
        if (value instanceof Boolean && !"true".equalsIgnoreCase(argument) && !"false".equalsIgnoreCase(argument)) {
            // Boolean#valueOf reads any other text as false, turning organic==yes into its very opposite
            throw new ArgumentFormatException(argument, type);
        }
        if (value instanceof BigDecimal decimal && Math.abs((long) decimal.scale()) > MAX_DECIMAL_SCALE) {
            // The few bytes of 1e30000000 took H2 some 37 seconds to bind, see MAX_DECIMAL_SCALE
            throw new ArgumentFormatException(argument, type);
        }
        if (value instanceof Date && type.equals(Date.class)) {
            // Parsed again, rsql-jpa reading a date in the calendar of the default locale, 2024 being a Buddhist
            // year under a Thai one, and with a lenient pattern, which rolls 2024-02-30 over to March 1st and
            // ignores whatever follows it, such as the minutes of 2024-01-01T10:00, read as midnight, or the offset
            // of 2024-01-01T10:00:00Z
            return type.cast(parseDate(argument));
        }
        return value;
    }

    /**
     * Parses a date that reads back as the very argument it is parsed from, in one of the patterns the default
     * parser reads a date with, in the default time zone but with the digits and calendar of no locale, its year
     * being written with four digits.
     *
     * @param argument The argument to parse
     * @return The corresponding date
     * @throws ArgumentFormatException if the argument is written in none of those patterns, or with a year past 9999
     */
    private static Date parseDate(String argument) {
        if (argument.indexOf('-') != 4) {
            // A year past 9999 reads back as written too, with more digits, but lies past the years of the SQL
            // standard, and PostgreSQL fails the statement with a DataException past the year 294276
            throw new ArgumentFormatException(argument, Date.class);
        }
        for (String pattern : List.of("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd")) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
            Date date = format.parse(argument, new ParsePosition(0));
            if (date != null && format.format(date).equals(argument)) {
                return date;
            }
        }
        throw new ArgumentFormatException(argument, Date.class);
    }

}
