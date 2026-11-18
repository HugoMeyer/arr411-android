package dev.c411.qbittorrent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A text file that grows by whole writes and never beyond twice its ceiling: when it is full it becomes "name.1" and a new one starts. The log is the
 * one thing that must not fail, so nothing here throws.
 */
final class LogFile {
    private static final int TAIL_BYTES = 96 * 1024;

    private final File file;
    private final File previous;
    private final long limit;

    LogFile(File file, long limit) {
        this.file = file;
        this.previous = new File(file.getPath() + ".1");
        this.limit = limit;
    }

    synchronized void append(String text) {
        try {
            File folder = file.getParentFile();
            if (folder != null && !folder.isDirectory()) folder.mkdirs();
            if (file.length() > limit) {
                previous.delete();
                if (!file.renameTo(previous)) file.delete();
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                if (!text.endsWith("\n")) out.write('\n');
            }
        } catch (IOException | RuntimeException ignored) {
            // A log that cannot be written must not become a second failure.
        }
    }

    /** The last {@code maxLines} non-empty lines, oldest first, taken from the rotated file when the current one is short. */
    synchronized List<String> tail(int maxLines) {
        List<String> lines = lastLines(file, maxLines);
        if (lines.size() < maxLines) lines.addAll(0, lastLines(previous, maxLines - lines.size()));
        return lines;
    }

    /** The end of the log as one text, at most about {@code maxBytes}; the start can be cut in the middle of a line. */
    synchronized String tailText(int maxBytes) {
        String current = tailOf(file, maxBytes);
        if (current.length() >= maxBytes) return current;
        return tailOf(previous, maxBytes - current.length()) + current;
    }

    synchronized void clear() {
        file.delete();
        previous.delete();
    }

    synchronized long size() {
        return file.length() + previous.length();
    }

    private static String tailOf(File source, int maxBytes) {
        try (RandomAccessFile in = new RandomAccessFile(source, "r")) {
            long size = in.length();
            int length = (int) Math.min(size, Math.max(maxBytes, 0));
            byte[] bytes = new byte[length];
            in.seek(size - length);
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static List<String> lastLines(File source, int count) {
        List<String> lines = new ArrayList<>();
        if (count <= 0) return lines;
        long size = source.length();
        for (String line : tailOf(source, TAIL_BYTES).split("\n")) {
            String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }
        // Starting in the middle of the file, the first line is cut.
        if (size > TAIL_BYTES && !lines.isEmpty()) lines.remove(0);
        return lines.size() > count ? new ArrayList<>(lines.subList(lines.size() - count, lines.size())) : lines;
    }
}
