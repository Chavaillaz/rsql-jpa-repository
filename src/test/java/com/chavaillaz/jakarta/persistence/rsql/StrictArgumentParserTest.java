package com.chavaillaz.jakarta.persistence.rsql;

import static com.chavaillaz.jakarta.persistence.rsql.ArgumentParser.MAX_DECIMAL_LENGTH;
import static com.chavaillaz.jakarta.persistence.rsql.ArgumentParser.MAX_DECIMAL_SCALE;
import static com.chavaillaz.jakarta.persistence.rsql.StrictArgumentParser.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.chavaillaz.jakarta.persistence.repository.example.Roast;

@DisplayName("StrictArgumentParser")
class StrictArgumentParserTest {

    private static Date at(String dateTime) {
        return Date.from(LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    @DisplayName("reads the plain types of an attribute")
    void readsThePlainTypes() {
        assertThat(parse("Kona", String.class)).isEqualTo("Kona");
        assertThat(parse("8", Integer.class)).isEqualTo(8);
        assertThat(parse("8", Short.class)).isEqualTo((short) 8);
        assertThat(parse("8.5", Double.class)).isEqualTo(8.5);
        assertThat(parse("8.50", BigDecimal.class)).isEqualTo(new BigDecimal("8.50"));
        assertThat(parse("850", BigInteger.class)).isEqualTo(BigInteger.valueOf(850));
        assertThat(parse("LIGHT", Roast.class)).isEqualTo(Roast.LIGHT);
    }

    @Test
    @DisplayName("reads a value type of the application through its valueOf method")
    void readsAValueType() {
        assertThat(parse("Ethiopian", Blend.class)).isEqualTo(new Blend("Ethiopian"));
    }

    @Test
    @DisplayName("refuses a type no argument is read as")
    void refusesAnUnsupportedType() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parse("2024", Calendar.class))
                .withMessage("No argument is read as " + Calendar.class.getName());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"true", "FALSE", "True"})
    @DisplayName("reads a boolean from true or false, whatever its case")
    void readsABoolean(String argument) {
        assertThat(parse(argument, Boolean.class)).isEqualTo(Boolean.parseBoolean(argument));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"yes", "1", "on", ""})
    @DisplayName("rejects a boolean other than true or false, rather than reading it as false")
    void rejectsABooleanOtherThanTrueOrFalse(String argument) {
        assertThatIllegalArgumentException().isThrownBy(() -> parse(argument, Boolean.class));
    }

    @Test
    @DisplayName("reads a single character only")
    void readsACharacter() {
        assertThat(parse("K", Character.class)).isEqualTo('K');
        assertThatIllegalArgumentException().isThrownBy(() -> parse("Kona", Character.class));
    }

    @Test
    @DisplayName("rejects a decimal or an integer written with more characters than the limit")
    void rejectsANumberTooLongToParse() {
        String digits = "9".repeat(MAX_DECIMAL_LENGTH);

        assertThat(parse(digits, BigDecimal.class)).isEqualTo(new BigDecimal(digits));
        assertThat(parse(digits, BigInteger.class)).isEqualTo(new BigInteger(digits));
        assertThatIllegalArgumentException().isThrownBy(() -> parse(digits + "9", BigDecimal.class));
        assertThatIllegalArgumentException().isThrownBy(() -> parse(digits + "9", BigInteger.class));
    }

    @ParameterizedTest(name = "1e{0}" + (MAX_DECIMAL_SCALE + 1))
    @ValueSource(strings = {"", "-"})
    @DisplayName("rejects a decimal whose scale lies beyond the limit, negative or positive")
    void rejectsADecimalWithAnOutsizedScale(String sign) {
        String limit = "1e" + sign + MAX_DECIMAL_SCALE;
        String beyond = "1e" + sign + (MAX_DECIMAL_SCALE + 1);

        assertThat(parse(limit, BigDecimal.class)).isEqualTo(new BigDecimal(limit));
        assertThatIllegalArgumentException().isThrownBy(() -> parse(beyond, BigDecimal.class));
    }

    @Test
    @DisplayName("reads an identifier written in its canonical form only")
    void readsAnIdentifier() {
        UUID identifier = UUID.fromString("7ca1d0a6-1f1e-4c9f-9a6d-3e3f0b8a0f11");

        assertThat(parse(identifier.toString(), UUID.class)).isEqualTo(identifier);
        assertThat(parse(identifier.toString().toUpperCase(Locale.ROOT), UUID.class)).isEqualTo(identifier);
        assertThatIllegalArgumentException()
                .as("UUID#fromString also reads a shorter group as the number it spells")
                .isThrownBy(() -> parse("1-2-3-4-5", UUID.class));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "2024-01-01T10:00:00, 2024-01-01T10:00:00",
            "2024-01-01,          2024-01-01T00:00:00",
            "9999-12-31T23:59:59, 9999-12-31T23:59:59"})
    @DisplayName("reads a date written as a date or as a date time, in the default time zone")
    void readsADateAsWritten(String argument, String dateTime) {
        assertThat(parse(argument, Date.class)).isEqualTo(at(dateTime));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"2024-02-30", "2024-01-01T10:00", "2024-01-01 10:00:00", "2024-01-01T10:00:00Z", "2024-1-1", "20240101"})
    @DisplayName("rejects a date not written as a date or a date time, rather than reading another instant leniently")
    void rejectsADateReadLeniently(String argument) {
        assertThatIllegalArgumentException().isThrownBy(() -> parse(argument, Date.class));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"10000-01-01", "300000-01-01T00:00:00"})
    @DisplayName("rejects a date past the year 9999, which reads back as written with more digits")
    void rejectsADateBeyondTheYear9999(String argument) {
        assertThatIllegalArgumentException().isThrownBy(() -> parse(argument, Date.class));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"th-TH", "ja-JP-u-ca-japanese"})
    @DisplayName("reads a date in the Gregorian calendar whatever the calendar of the default locale")
    void readsADateWhateverTheDefaultLocale(String languageTag) {
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag(languageTag));
        try {
            assertThat(parse("2024-01-01T10:00:00", Date.class)).isEqualTo(at("2024-01-01T10:00:00"));
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    @DisplayName("leaves a java.sql timestamp to its own valueOf, which requires a year of four digits")
    void leavesATimestampToItsValueOf() {
        assertThat(parse("2024-01-01 10:00:00", Timestamp.class)).isEqualTo(Timestamp.valueOf("2024-01-01 10:00:00"));
        assertThatIllegalArgumentException().isThrownBy(() -> parse("10000-01-01 00:00:00", Timestamp.class));
    }

    @Test
    @DisplayName("reads the java.time types in their ISO 8601 form")
    void readsTheJavaTimeTypes() {
        assertThat(parse("2024-01-01", LocalDate.class)).isEqualTo(LocalDate.parse("2024-01-01"));
        assertThat(parse("2024-01-01T10:00", LocalDateTime.class)).isEqualTo(LocalDateTime.parse("2024-01-01T10:00"));
        assertThat(parse("2024-01-01T10:00:00Z", Instant.class)).isEqualTo(Instant.parse("2024-01-01T10:00:00Z"));
        assertThat(parse("2024-01-01T10:00:00+01:00", OffsetDateTime.class)).isEqualTo(OffsetDateTime.parse("2024-01-01T10:00:00+01:00"));
        assertThat(parse("2024-01-01T10:00:00+01:00[Europe/Zurich]", ZonedDateTime.class)).isEqualTo(ZonedDateTime.parse("2024-01-01T10:00:00+01:00[Europe/Zurich]"));
        assertThat(parse("10:00:00", LocalTime.class)).isEqualTo(LocalTime.parse("10:00:00"));
        assertThat(parse("10:00:00+01:00", OffsetTime.class)).isEqualTo(OffsetTime.parse("10:00:00+01:00"));
        assertThat(parse("2024", Year.class)).isEqualTo(Year.parse("2024"));
        assertThat(parse("PT1H30M", Duration.class)).isEqualTo(Duration.parse("PT1H30M"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"2024-02-30", "2024-1-1", "2024-01-01T10:00:00"})
    @DisplayName("rejects a java.time argument written in any other form")
    void rejectsAJavaTimeArgumentWrittenOtherwise(String argument) {
        assertThatExceptionOfType(DateTimeParseException.class).isThrownBy(() -> parse(argument, LocalDate.class));
    }

    @Test
    @DisplayName("rejects a java.time argument whose year is not written with four digits, which no column holds")
    void rejectsAJavaTimeYearOfAnyOtherLength() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parse("+10000-01-01", LocalDate.class))
                .withMessageStartingWith("Expected a year of 4 digits");
        assertThatIllegalArgumentException().isThrownBy(() -> parse("-2024-01-01", LocalDate.class));
        assertThatIllegalArgumentException().isThrownBy(() -> parse("+10000-01-01T00:00:00", LocalDateTime.class));
        assertThatIllegalArgumentException().isThrownBy(() -> parse("+10000-01-01T00:00:00Z", Instant.class));
        assertThatIllegalArgumentException().isThrownBy(() -> parse("+10000-01-01T00:00:00+01:00", OffsetDateTime.class));
        assertThatIllegalArgumentException().isThrownBy(() -> parse("+10000", Year.class));
        assertThatIllegalArgumentException().isThrownBy(() -> parse("01/01/2024", LocalDate.class));
        assertThatIllegalArgumentException()
                .as("an epoch second count is no date either")
                .isThrownBy(() -> parse("1704088800", Instant.class));
    }

    @Test
    @DisplayName("rejects a duration longer than the nanoseconds a long holds, which no database binds")
    void rejectsAnOutsizedDuration() {
        assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() -> parse("PT2562047788015216S", Duration.class));
    }

    /**
     * A value type of the application, read through the {@code valueOf(String)} convention of the JDK types.
     */
    record Blend(String origins) {

        public static Blend valueOf(String argument) {
            return new Blend(argument);
        }

    }

}
