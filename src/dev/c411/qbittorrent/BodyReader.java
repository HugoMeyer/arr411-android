package dev.c411.qbittorrent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads an HTTP body into a string without holding more than it needs, and refuses what is larger than a ceiling. A body that is too big has to fail
 * with a message: running out of memory on a pool thread takes the whole process down, which is what a list of tens of thousands of torrents did.
 */
final class BodyReader {
    /** The body is larger than the ceiling it was read with. */
    static final class TooLargeException extends IOException {
        final long limit;

        TooLargeException(long limit) {
            super("Response larger than " + limit + " bytes");
            this.limit = limit;
        }
    }

    private static final int FIRST_CHUNK = 64 * 1024;

    private final int limit;
    private byte[] data;
    private int length;

    private BodyReader(long declaredLength, int limit) throws TooLargeException {
        if (declaredLength > limit) throw new TooLargeException(limit);
        this.limit = limit;
        // The length the server announced is exact for a plain body: one allocation, no copy while growing.
        this.data = new byte[declaredLength > 0 ? (int) declaredLength : Math.min(FIRST_CHUNK, limit)];
    }

    /** The body of {@code in} decoded as UTF-8; {@code declaredLength} is the announced size, or a negative number when unknown. */
    static String readText(InputStream in, long declaredLength, int limit) throws IOException {
        BodyReader body = new BodyReader(declaredLength, limit);
        body.fill(in);
        return new String(body.data, 0, body.length, StandardCharsets.UTF_8);
    }

    private void fill(InputStream in) throws IOException {
        while (true) {
            if (length == data.length) {
                // One more byte tells whether the body really goes on, so that a body that ends exactly here does not grow the buffer for nothing.
                int next = in.read();
                if (next < 0) return;
                if (length >= limit) throw new TooLargeException(limit);
                data = Arrays.copyOf(data, grown(data.length));
                data[length++] = (byte) next;
                continue;
            }
            int read = in.read(data, length, data.length - length);
            if (read < 0) return;
            length += read;
        }
    }

    private int grown(int size) {
        long bigger = Math.max((long) size + (size >> 1), (long) size + FIRST_CHUNK);
        return (int) Math.min(bigger, limit);
    }
}
