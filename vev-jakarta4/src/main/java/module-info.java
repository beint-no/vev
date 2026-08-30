/** Defines Vev's deliberately narrow Jakarta Persistence 4 EntityAgent compatibility profile. */
module no.beint.vev.jakarta {
    requires transitive jakarta.persistence;
    requires transitive no.beint.vev.core;
    requires transitive no.beint.vev.postgres;

    exports no.beint.vev.jakarta;
}
