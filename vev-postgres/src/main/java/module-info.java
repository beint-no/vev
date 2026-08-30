/** Defines Vev's fail-closed PostgreSQL 18 runtime, generated plan metadata, and query factories. */
module no.beint.vev.postgres {
    requires transitive java.sql;
    requires transitive no.beint.vev.core;
    requires jdk.jfr;

    exports no.beint.vev.pg;
    exports no.beint.vev.pg.spi;
}
