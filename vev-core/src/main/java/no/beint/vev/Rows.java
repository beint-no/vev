package no.beint.vev;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable materialized query page which cannot outlive database resources.
 *
 * @param values detached result snapshots
 * @param limit requested upper bound
 * @param hasMore whether at least one additional matching row exists
 * @param <R> result type
 */
public record Rows<R>(List<R> values, QueryLimit limit, boolean hasMore) {
    /**
     * Copies and validates a bounded result page.
     *
     * @param values detached result snapshots
     * @param limit requested upper bound
     * @param hasMore whether at least one additional matching row exists
     */
    public Rows {
        Objects.requireNonNull(values, "values");
        limit = Objects.requireNonNull(limit, "limit");
        List<R> boundedValues = new ArrayList<>(limit.value());
        for (R value : values) {
            if (boundedValues.size() == limit.value()) {
                throw new IllegalArgumentException("Rows must not exceed the requested query limit");
            }
            boundedValues.add(Objects.requireNonNull(value, "value"));
        }
        values = List.copyOf(boundedValues);
        if (hasMore && values.size() < limit.value()) {
            throw new IllegalArgumentException("A partial page cannot report more rows");
        }
    }
}
