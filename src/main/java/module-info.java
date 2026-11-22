/**
 * Dynamic RSQL filtering for the repositories of light-jpa-repository, an RSQL query being translated into the
 * criteria of Jakarta Persistence.
 * <ul>
 *     <li>{@link com.chavaillaz.jakarta.persistence.rsql}: the translation of a query, the dialect of its operators and
 *     how their arguments are read</li>
 *     <li>{@link com.chavaillaz.jakarta.persistence.repository.rsql}: the repositories filtering their entities with an
 *     RSQL query</li>
 * </ul>
 * The parser of RSQL declares no module name: it is required by the one Java derives from its jar, {@code rsql.parser},
 * which the jar keeps as long as it is not renamed, and transitively, the API exposing the nodes and operators it
 * parses. Hibernate, which the implementation reads the query tree of, is required by the automatic module name of its
 * jar. The warnings of javac against requiring such automatic modules, and against requiring one transitively, are
 * suppressed: neither offers another module to require. The application brings Hibernate along, as it brings JSpecify,
 * which is only required at compile time.
 */
@SuppressWarnings({"requires-automatic", "requires-transitive-automatic"})
module com.chavaillaz.jakarta.persistence.rsql {

    requires transitive com.chavaillaz.jakarta.persistence;
    requires transitive jakarta.persistence;
    requires transitive rsql.parser;
    requires static transitive org.jspecify;
    requires org.apache.commons.lang3;
    requires org.hibernate.orm.core;

    exports com.chavaillaz.jakarta.persistence.rsql;
    exports com.chavaillaz.jakarta.persistence.repository.rsql;

}
