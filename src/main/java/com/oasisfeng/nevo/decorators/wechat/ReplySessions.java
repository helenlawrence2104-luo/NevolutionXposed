package com.oasisfeng.nevo.decorators.wechat;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded per-notification capabilities. Unknown, expired and superseded actions fail closed. */
final class ReplySessions {
    private static final int MAX_ENTRIES = 50;
    private static final long TTL_MILLIS = 30 * 60 * 1000L;
    private final LongSupplier clock;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>(16, .75f, true);

    ReplySessions(LongSupplier clock) { this.clock = clock; }

    synchronized String issue(int id, String identity) {
        long now = clock.getAsLong();
        prune(now);
        Entry entry = entries.get(id);
        if (entry == null || !entry.identity.equals(identity)) {
            entry = new Entry(identity, UUID.randomUUID().toString(), now);
            entries.put(id, entry);
        }
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        return entry.token;
    }

    synchronized boolean accepts(int id, String token) {
        prune(clock.getAsLong());
        Entry entry = entries.get(id);
        return entry != null && token != null && entry.token.equals(token);
    }

    synchronized void remove(int id) { entries.remove(id); }

    synchronized void clear() { entries.clear(); }

    private void prune(long now) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            long age = now - iterator.next().created;
            if (age < 0 || age >= TTL_MILLIS) iterator.remove();
        }
    }

    private static final class Entry {
        final String identity, token;
        final long created;
        Entry(String identity, String token, long created) {
            this.identity = identity; this.token = token; this.created = created;
        }
    }
}
