package no.beint.vev.pg;

import no.beint.vev.VevModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Generated immediate unique constraint with PostgreSQL {@code NULLS DISTINCT} semantics.
 *
 * @param name exact constraint and backing-index name
 * @param columnIndexes ordered constructor positions, starting with the tenant column for tenant-owned mappings
 */
public record PgUnique(String name, List<Integer> columnIndexes) {
    /** PostgreSQL's maximum number of columns in a single index. */
    public static final int MAXIMUM_KEY_COLUMNS = 32;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    /**
     * Captures bounded, immutable constraint metadata.
     *
     * @param name explicit database constraint name
     * @param columnIndexes ordered mapped column positions, validated against the owning plan
     */
    public PgUnique {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsafe generated PostgreSQL unique-constraint identifier");
        }
        List<Integer> bounded = new ArrayList<>();
        for (Integer position : Objects.requireNonNull(columnIndexes, "columnIndexes")) {
            if (bounded.size() == MAXIMUM_KEY_COLUMNS || position == null
                    || position < 0 || position >= VevModel.MAXIMUM_COLUMNS) {
                throw new IllegalArgumentException("Unique-constraint columns exceed the generated row bounds");
            }
            bounded.add(position);
        }
        if (bounded.isEmpty() || new HashSet<>(bounded).size() != bounded.size()) {
            throw new IllegalArgumentException("Unique constraints require distinct mapped columns");
        }
        columnIndexes = List.copyOf(bounded);
    }
}
