package xyz.melodylsp.codec.mono;

import java.util.HashMap;
import java.util.Map;

/** Cross-thread connection tombstones. Record boundaries before posting work to a Handler. */
public final class WearEventOrder {
    private final Map<String, Entry> entries = new HashMap<>();

    public synchronized boolean boundary(String mac, long order, long marker, boolean connected) {
        if (AutoMonoIpc.normalizeMac(mac) == null || order <= 0) return false;
        Entry entry = entries.computeIfAbsent(mac, ignored -> new Entry());
        if (order <= entry.boundary || order <= entry.accepted) return false;
        entry.boundary = order;
        entry.closed = !connected;
        entry.marker = Math.max(entry.marker, marker);
        return true;
    }

    public synchronized boolean currentBoundary(String mac, long order) {
        Entry entry = entries.get(mac);
        return entry != null && entry.boundary == order && entry.accepted < order;
    }

    public synchronized long boundaryOrder(String mac) {
        Entry entry = entries.get(mac);
        return entry == null ? 0L : entry.boundary;
    }

    public synchronized boolean hasUnappliedBoundary(String mac, long appliedOrder) {
        return boundaryOrder(mac) > appliedOrder;
    }

    /** The boundary seen when this packet was admitted, unaffected by a concurrent new boundary. */
    public synchronized long acceptedBoundaryOrder(String mac, long reportOrder) {
        Entry entry = entries.get(mac);
        return entry != null && entry.accepted == reportOrder ? entry.acceptedBoundary : -1L;
    }

    public synchronized boolean accept(String mac, long order, long marker) {
        if (AutoMonoIpc.normalizeMac(mac) == null || order <= 0) return false;
        Entry entry = entries.computeIfAbsent(mac, ignored -> new Entry());
        if (entry.closed || order <= entry.boundary || order <= entry.accepted
                || (entry.marker > 0 && marker < entry.marker)) return false;
        entry.accepted = order;
        entry.acceptedBoundary = entry.boundary;
        entry.marker = Math.max(entry.marker, marker);
        return true;
    }

    private static final class Entry {
        long boundary, accepted, acceptedBoundary, marker;
        boolean closed;
    }
}
