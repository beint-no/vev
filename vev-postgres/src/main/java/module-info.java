/** Defines Vev's fail-closed PostgreSQL 18 runtime, generated plan metadata, and query factories. */
@SuppressWarnings("requires-automatic")
module no.beint.vev.postgres {
    requires transitive java.sql;
    requires transitive no.beint.vev.core;
    requires jdk.jfr;
    requires org.postgresql.jdbc;

    exports no.beint.vev.pg;
    exports no.beint.vev.pg.spi;
}
