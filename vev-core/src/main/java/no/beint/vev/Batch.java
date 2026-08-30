package no.beint.vev;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;

/**
 * Immutable bounded input or output for one bulk database operation.
 *
 * @param <T> element type
 */
public final class Batch<T> implements Iterable<T> {
    /** Maximum elements accepted by one bulk call. */
    public static final int MAX_SIZE = 1_000;

    private final List<T> values;

    private Batch(List<T> values) {
        this.values = values;
    }

    /**
     * Copies a collection into a bounded immutable value.
     *
     * @param values values to copy
     * @param <T> element type
     * @return immutable batch
     */
    public static <T> Batch<T> copyOf(Collection<? extends T> values) {
        Objects.requireNonNull(values, "values");
        int declaredSize = values.size();
        if (declaredSize < 0 || declaredSize > MAX_SIZE) {
            throw new IllegalArgumentException("A batch must not exceed " + MAX_SIZE + " values");
        }
        List<T> copiedValues = new ArrayList<>(declaredSize);
        for (T value : values) {
            if (copiedValues.size() == MAX_SIZE) {
                throw new IllegalArgumentException("A batch must not exceed " + MAX_SIZE + " values");
            }
            copiedValues.add(Objects.requireNonNull(value, "value"));
        }
        return new Batch<>(List.copyOf(copiedValues));
    }

    /**
     * Creates an empty batch.
     *
     * @param <T> element type
     * @return an empty immutable batch
     */
    public static <T> Batch<T> empty() {
        return new Batch<>(List.of());
    }

    /**
     * Creates a single-value batch.
     *
     * @param value sole non-null value
     * @param <T> element type
     * @return single-value batch
     */
    public static <T> Batch<T> one(T value) {
        return new Batch<>(List.of(Objects.requireNonNull(value, "value")));
    }

    /**
     * Returns the values in input order.
     *
     * @return immutable ordered values
     */
    public List<T> values() {
        return values;
    }

    /**
     * Returns the number of values.
     *
     * @return number of values
     */
    public int size() {
        return values.size();
    }

    /**
     * Reports whether this batch is empty.
     *
     * @return whether this batch contains no values
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Returns the value at an index.
     *
     * @param index zero-based index
     * @return value at the index
     */
    public T get(int index) {
        return values.get(index);
    }

    @Override
    public Iterator<T> iterator() {
        return values.iterator();
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        values.forEach(action);
    }

    @Override
    public Spliterator<T> spliterator() {
        return values.spliterator();
    }

    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof Batch<?> batch && values.equals(batch.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
