package no.beint.vev.pg;

import no.beint.vev.pg.spi.PgVersionedEntityPlan;

import java.util.Objects;

final class PgVersionPlan<M, E, K, T, V> extends PgPlan<M, E, K, T> {
    private final PgVersionedEntityPlan<M, E, K, T, V> versionedSource;
    private final Class<V> versionType;
    private final PgCodec<V> versionCodec;

    PgVersionPlan(PgVersionedEntityPlan<M, E, K, T, V> source) {
        super(source);
        this.versionedSource = source;
        this.versionType = Objects.requireNonNull(source.versionType(), "versionType");
        this.versionCodec = Objects.requireNonNull(source.versionCodec(), "versionCodec");
    }

    Class<V> versionType() {
        return versionType;
    }

    PgCodec<V> versionCodec() {
        return versionCodec;
    }

    V versionOf(E entity) {
        return versionedSource.versionOf(entity);
    }

}
