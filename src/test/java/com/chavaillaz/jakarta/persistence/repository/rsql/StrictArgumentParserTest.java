package com.chavaillaz.jakarta.persistence.repository.rsql;

import static com.chavaillaz.jakarta.persistence.repository.rsql.RsqlQueries.MAX_DECIMAL_LENGTH;
import static com.chavaillaz.jakarta.persistence.repository.rsql.RsqlQueries.MAX_DECIMAL_SCALE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.tennaito.rsql.misc.ArgumentFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.chavaillaz.jakarta.persistence.repository.example.Roast;

@DisplayName("StrictArgumentParser")
class StrictArgumentParserTest {

    private final StrictArgumentParser parser = new StrictArgumentParser();

    private static Date at(String dateTime) {
        return Date.from(LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    @DisplayName("reads any other argument as rsql-jpa does, null included")
    void readsAsRsqlJpa() {
        assertThat(parser.parse("Kona", String.class)).isEqualTo("Kona");
        assertThat(parser.parse("8", Integer.class)).isEqualTo(8);
        assertThat(parser.parse("LIGHT", Roast.class)).isEqualTo(Roast.LIGHT);
        assertThat(parser.parse("null", Date.class)).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"null", "Kona"})
    @DisplayName("rejects any argument compared to a collection or a map, null included")
    void rejectsACollection(String argument) {
        assertThatExceptionOfType(ArgumentFormatException.class).isThrownBy(() -> parser.parse(argument, List.class));
        assertThatExceptionOfType(ArgumentFormatException.class).isThrownBy(() -> parser.parse(argument, Map.class));
    }

    @Test
    @DisplayName("rejects an argument holding a NUL character")
    void rejectsANulCharacter() {
        assertThatExceptionOfType(ArgumentFormatException.class)
                .isThrownBy(() -> parser.parse("Gei" + Character.MIN_VALUE + "sha", String.class))
                .withMessageStartingWith("Cannot cast 'Gei");
    }

    @Test
    @DisplayName("rejects a decimal written with more characters than the limit")
    void rejectsADecimalTooLongToParse() {
        String digits = "9".repeat(MAX_DECIMAL_LENGTH);

        assertThat(parser.parse(digits, BigDecimal.class)).isEqualTo(new BigDecimal(digits));
        assertThatExceptionOfType(ArgumentFormatException.class).isThrownBy(() -> parser.parse(digits + "9", BigDecimal.class));
    }

    @ParameterizedTest(name = "1e{0}" + (MAX_DECIMAL_SCALE + 1))
    @ValueSource(strings = {"", "-"})
    @DisplayName("rejects a decimal whose scale lies beyond the limit, negative or positive")
    void rejectsADecimalWithAnOutsizedScale(String sign) {
        String limit = "1e" + sign + MAX_DECIMAL_SCALE;
        String beyond = "1e" + sign + (MAX_DECIMAL_SCALE + 1);

        assertThat(parser.parse(limit, BigDecimal.class)).isEqualTo(new BigDecimal(limit));
        assertThatExceptionOfType(ArgumentFormatException.class).isThrownBy(() -> parser.parse(beyond, BigDecimal.class));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"true", "FALSE", "True"})
    @DisplayName("reads a boolean from true or false, whatever its case")
    void readsABoolean(String argument) {
        assertThat(parser.parse(argument, Boolean.class)).isEqualTo(Boolean.parseBoolean(argument));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"yes", "1", "on", ""})
    @DisplayName("rejects a boolean other than true or false, rather than reading it as false")
    void rejectsABooleanOtherThanTrueOrFalse(String argument) {
        assertThatExceptionOfType(ArgumentFormatException.class).isThrownBy(() -> parser.parse(argument, Boolean.class));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "2024-01-01T10:00:00, 2024-01-01T10:00:00",
            "2024-01-01,          2024-01-01T00:00:00",
            "9999-12-31T23:59:59, 9999-12-31T23:59:59"})
    @DisplayName("reads a date written as a date or as a date time, in the default time zone")
    void readsADateAsWritten(String argument, String dateTime) {
        assertThat(parser.parse(argument, Date.class)).isEqualTo(at(dateTime));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"2024-02-30", "2024-01-01T10:00", "2024-01-01 10:00:00", "2024-01-01T10:00:00Z", "2024-1-1", "20240101"})
    @DisplayName("rejects a date not written as a date or a date time, rather than reading another instant leniently")
    void rejectsADateReadLeniently(String argument) {
        assertThatExceptionOfType(ArgumentFormatException.class).isThrownBy(() -> parser.parse(argument, Date.class));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"10000-01-01", "300000-01-01T00:00:00"})
    @DisplayName("rejects a date past the year 9999, which reads back as written with more digits")
    void rejectsADateBeyondTheYear9999(String argument) {
        assertThatExceptionOfType(ArgumentFormatException.class).isThrownBy(() -> parser.parse(argument, Date.class));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"th-TH", "ja-JP-u-ca-japanese"})
    @DisplayName("reads a date in the Gregorian calendar whatever the calendar of the default locale")
    void readsADateWhateverTheDefaultLocale(String languageTag) {
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag(languageTag));
        try {
            assertThat(parser.parse("2024-01-01T10:00:00", Date.class)).isEqualTo(at("2024-01-01T10:00:00"));
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    @DisplayName("leaves a java.sql timestamp to its own valueOf, which requires a year of four digits")
    void leavesATimestampToItsValueOf() {
        assertThat(parser.parse("2024-01-01 10:00:00", Timestamp.class)).isEqualTo(Timestamp.valueOf("2024-01-01 10:00:00"));
        assertThatExceptionOfType(ArgumentFormatException.class).isThrownBy(() -> parser.parse("10000-01-01 00:00:00", Timestamp.class));
    }

}
