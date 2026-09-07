package no.beint.vev;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class BinaryTest {
    @Test
    void ownsItsBytesAndExposesOnlyCopiesOrReadOnlyViews() {
        byte[] source = {0, 1, -1, 127};
        Binary value = Binary.copyOf(source);
        source[1] = 99;
        byte[] copy = value.toByteArray();
        copy[2] = 0;
        assertEquals(Binary.fromHex("0001ff7f"), value);
        var buffer = value.asReadOnlyBuffer();
        assertTrue(buffer.isReadOnly());
        assertFalse(buffer.hasArray());
        assertThrows(ReadOnlyBufferException.class, buffer::array);
        assertThrows(ReadOnlyBufferException.class, () -> buffer.put(0, (byte) 8));
        assertThrows(ReadOnlyBufferException.class, () -> buffer.slice().put(0, (byte) 8));
        buffer.position(2);
        assertEquals(0, value.asReadOnlyBuffer().position());
        byte[] target = new byte[6];
        value.copyTo(target, 1);
        assertArrayEquals(new byte[]{0, 0, 1, -1, 127, 0}, target);
        assertThrows(IndexOutOfBoundsException.class, () -> value.copyTo(target, 3));
        assertEquals("Binary[4 bytes]", value.toString());
    }

    @Test
    void streamsCannotLeakWritableStorageEvenToAMutatingTransferSink() throws IOException {
        byte[] bytes = new byte[20000];
        Arrays.fill(bytes, (byte) 19);
        Binary value = Binary.copyOf(bytes);
        try (var input = value.openStream()) {
            assertEquals(bytes.length, input.transferTo(new OutputStream() {
                @Override
                public void write(int value) {
                    fail("Bulk stream transfer should use its temporary buffer");
                }

                @Override
                public void write(byte[] received, int offset, int length) {
                    Arrays.fill(received, (byte) 0);
                }
            }));
        }
        assertArrayEquals(bytes, value.toByteArray());
        try (var first = value.openStream(); var second = value.openStream()) {
            assertEquals(19, first.read());
            first.mark(10);
            assertEquals(100, first.skip(100));
            first.reset();
            assertEquals(19999, first.available());
            assertEquals(20000, second.available());
            byte[] copied = second.readAllBytes();
            Arrays.fill(copied, (byte) 0);
            assertEquals(-1, second.read());
            assertEquals(0, second.read(new byte[0], 0, 0));
            assertEquals(0, second.skip(Long.MAX_VALUE));
            assertEquals(0, first.skip(-1));
        }
        assertArrayEquals(bytes, value.toByteArray());
    }

    @Test
    void usesContentEqualityAndUnsignedLexicographicOrder() {
        Binary a = Binary.fromHex("7f");
        Binary b = Binary.fromHex("80");
        assertTrue(a.compareTo(b) < 0);
        assertTrue(a.compareTo(Binary.fromHex("7f00")) < 0);
        assertEquals(Binary.fromHex("FF"), Binary.fromHex("ff"));
        assertEquals(Binary.fromHex("FF").hashCode(), Binary.fromHex("ff").hashCode());
        assertNotEquals(Binary.empty(), null);
        assertNotEquals(Binary.fromHex("00"), new byte[]{0});
        assertSame(Binary.empty(), Binary.copyOf(new byte[0]));
        assertSame(Binary.empty(), Binary.fromHex(""));
        assertThrows(IllegalArgumentException.class, () -> Binary.fromHex("0"));
        assertThrows(IllegalArgumentException.class, () -> Binary.fromHex("xx"));
        assertThrows(IllegalArgumentException.class, () -> Binary.copyOf(new byte[Binary.MAXIMUM_LENGTH + 1]));
        assertThrows(NullPointerException.class, () -> Binary.copyOf(null));
    }
}
