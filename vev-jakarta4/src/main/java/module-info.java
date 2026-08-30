/** Defines Vev's experimental, deliberately nonconforming Jakarta Persistence 4 EntityAgent-shaped facade. */
module no.beint.vev.jakarta {
    requires transitive jakarta.persistence;
    requires transitive no.beint.vev.core;
    requires transitive no.beint.vev.postgres;

    exports no.beint.vev.jakarta;
}
