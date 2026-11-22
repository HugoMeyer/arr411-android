package dev.c411.qbittorrent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Copies a stream and computes its SHA-256 on the way. It never takes more than a ceiling: the byte that goes past it stops the copy before anything
 * of that chunk is written, so a server that sends more than it announced cannot fill the disk.
 */
final class HashedCopy {
    private static final int CHUNK = 32 * 1024;
    private static final int MAX_EMPTY_READS = 100;

    private HashedCopy() {}

    /** Told about the progress, and able to stop the copy. */
    interface Listener {
        /** After each chunk, with the number of bytes copied so far. */
        void progress(long copied);

        /** True to stop: the copy ends with a {@link CancelledException}. */
        boolean cancelled();
    }

    /** The stream has more bytes than the ceiling. */
    static final class LimitException extends IOException {
        LimitException(long limit) {
            super("More than " + limit + " bytes");
        }
    }

    /** The listener asked to stop. */
    static final class CancelledException extends IOException {
        CancelledException() {
            super("Cancelled");
        }
    }

    static final class Result {
        final long bytes;
        /** Lowercase hexadecimal SHA-256. */
        final String sha256;

        Result(long bytes, String sha256) {
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    /** Copies {@code in} to {@code out} (null to only hash it), at most {@code maxBytes}. The streams are neither flushed nor closed. */
    static Result copy(InputStream in, OutputStream out, long maxBytes, Listener listener) throws IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
        byte[] buffer = new byte[CHUNK];
        long total = 0;
        int empty = 0;
        while (true) {
            if (listener != null && listener.cancelled()) throw new CancelledException();
            long room = maxBytes - total;
            // One byte more than the room tells that the stream goes past the ceiling.
            int wanted = room >= buffer.length ? buffer.length : (int) room + 1;
            int read = in.read(buffer, 0, wanted);
            if (read < 0) break;
            if (read == 0) {
                if (++empty >= MAX_EMPTY_READS) throw new IOException("The stream gives nothing");
                continue;
            }
            empty = 0;
            if (read > room) throw new LimitException(maxBytes);
            digest.update(buffer, 0, read);
            if (out != null) out.write(buffer, 0, read);
            total += read;
            if (listener != null) listener.progress(total);
        }
        return new Result(total, UpdateIntegrity.hex(digest.digest()));
    }
}
