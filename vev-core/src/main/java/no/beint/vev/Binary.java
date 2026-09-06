package no.beint.vev;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable, bounded binary value which never exposes its writable backing array. */
public final class Binary implements Comparable<Binary> {
    /** Largest value accepted by this value type; mapped columns and row budgets may require a smaller bound. */
    public static final int MAXIMUM_LENGTH = 32 * 1024 * 1024;
    private static final Binary EMPTY = new Binary(new byte[0]);
    private final byte[] bytes;

    private Binary(byte[] ownedBytes) {
        bytes = ownedBytes;
    }

    /**
     * Returns the shared empty value, distinct from a nullable column's {@code null}.
     *
     * @return empty immutable binary value
     */
    public static Binary empty() {
        return EMPTY;
    }

    /**
     * Copies application bytes into an immutable snapshot.
     *
     * @param value source array, which must not be concurrently modified during the copy
     * @return independently owned value
     */
    public static Binary copyOf(byte[] value) {
        Objects.requireNonNull(value, "value");
        requireLength(value.length);
        return value.length == 0 ? EMPTY : new Binary(value.clone());
    }

    /**
     * Parses a bounded sequence of hexadecimal byte pairs without separators.
     *
     * @param value hexadecimal text
     * @return immutable decoded bytes
     */
    public static Binary fromHex(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() % 2 != 0 || value.length() / 2 > MAXIMUM_LENGTH) {
            throw new IllegalArgumentException("Binary hexadecimal text must contain bounded complete byte pairs");
        }
        return value.isEmpty() ? EMPTY : new Binary(HexFormat.of().parseHex(value));
    }

    /**
     * Returns the number of bytes.
     *
     * @return byte count
     */
    public int size() {
        return bytes.length;
    }

    /**
     * Reads one byte without exposing the backing array.
     *
     * @param index zero-based position
     * @return signed byte value
     */
    public byte byteAt(int index) {
        return bytes[index];
    }

    /**
     * Copies this value into an application-owned array.
     *
     * @return independent writable copy
     */
    public byte[] toByteArray() {
        return bytes.clone();
    }

    /**
     * Copies all bytes into an existing application-owned array.
     *
     * @param target destination array
     * @param offset destination offset
     */
    public void copyTo(byte[] target, int offset) {
        Objects.requireNonNull(target, "target");
        Objects.checkFromIndexSize(offset, bytes.length, target.length);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    /**
     * Creates an independent read-only buffer view without copying the value.
     *
     * @return read-only view; neither it nor its derived views expose a writable backing array
     */
    public ByteBuffer asReadOnlyBuffer() {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    /**
     * Creates an independent in-memory stream without exposing the backing array to stream consumers.
     *
     * @return stream with its own cursor; closing it requires no external resource cleanup
     */
    public InputStream openStream() {
        return new BinaryInput(bytes);
    }

    @Override
    public int compareTo(Binary other) {
        return Arrays.compareUnsigned(bytes, Objects.requireNonNull(other, "other").bytes);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Binary value && Arrays.equals(bytes, value.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /**
     * Describes the value's size without rendering potentially sensitive contents.
     *
     * @return size-only description
     */
    @Override
    public String toString() {
        return "Binary[" + bytes.length + " bytes]";
    }

    private static void requireLength(int length) {
        if (length > MAXIMUM_LENGTH) throw new IllegalArgumentException("Binary value exceeds the 32 MiB value-type bound");
    }

    // ByteArrayInputStream.transferTo can hand its backing array to an arbitrary OutputStream.
    // Implement copying reads so inherited InputStream helpers can expose only their own temporary buffers.
    private static final class BinaryInput extends InputStream {
        private final byte[] bytes;
        private int position;
        private int mark;

        private BinaryInput(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() {
            return position == bytes.length ? -1 : bytes[position++] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) return 0;
            if (position == bytes.length) return -1;
            int count = Math.min(length, bytes.length - position);
            System.arraycopy(bytes, position, target, offset, count);
            position += count;
            return count;
        }

        @Override
        public long skip(long count) {
            int skipped = (int) Math.min(Math.max(0, count), bytes.length - position);
            position += skipped;
            return skipped;
        }

        @Override
        public int available() {
            return bytes.length - position;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int readLimit) {
            mark = position;
        }

        @Override
        public void reset() {
            position = mark;
        }
    }
}
