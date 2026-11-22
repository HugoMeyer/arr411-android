package dev.c411.qbittorrent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers when each message was last let through, to log a repeating message once per interval. The table is bounded:
 * when it is full the key seen least recently is forgotten, instead of everything.
 */
final class LruThrottle {
    private final Map<String, long[]> table;

    LruThrottle(final int maxKeys) {
        table = new LinkedHashMap<String, long[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                return size() > maxKeys;
            }
        };
    }

    /** -1 when the key was let through less than intervalMs ago; otherwise how many times it was skipped since then. */
    synchronized int allow(String key, long now, long intervalMs) {
        long[] seen = table.get(key);
        if (seen == null) {
            table.put(key, new long[] {now, 0});
            return 0;
        }
        if (now - seen[0] < intervalMs) {
            seen[1]++;
            return -1;
        }
        int skipped = (int) Math.min(seen[1], Integer.MAX_VALUE);
        seen[0] = now;
        seen[1] = 0;
        return skipped;
    }

    synchronized int size() {
        return table.size();
    }
}
