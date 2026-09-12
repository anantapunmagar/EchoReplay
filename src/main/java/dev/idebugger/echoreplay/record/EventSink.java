package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.model.TimelineEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe buffer between Bukkit/PacketEvents listeners (their threads) and
 * the async IO writer. Events are buffered in memory; the periodic checkpoint
 * flush and the final save pull them out via {@link #drainAll()}.
 */
public final class EventSink {

    private final ConcurrentLinkedQueue<TimelineEvent> queue = new ConcurrentLinkedQueue<>();
    private volatile boolean closed = false;
    /**
     * Max non-critical events per wall-clock second (0 = unlimited).
     * Non-critical = Move/Particle/Sound — safe to sample under load.
     * Critical events (spawns, blocks, inventory, markers) are never dropped.
     */
    private volatile int maxEventsPerSecond = 0;
    private final java.util.concurrent.atomic.AtomicInteger windowCount = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long windowSec = -1;
    private final java.util.concurrent.atomic.AtomicLong droppedEvents = new java.util.concurrent.atomic.AtomicLong();

    public void setMaxEventsPerSecond(int n) {
        this.maxEventsPerSecond = Math.max(0, n);
    }

    public int getMaxEventsPerSecond() {
        return maxEventsPerSecond;
    }

    public long getDroppedEvents() {
        return droppedEvents.get();
    }

    public void add(TimelineEvent e) {
        if (closed || e == null) return;
        if (maxEventsPerSecond > 0 && isDroppable(e) && !withinBudget()) {
            droppedEvents.incrementAndGet();
            return;
        }
        queue.add(e);
    }

    /** True for high-volume, loss-tolerant events safe to sample. */
    public static boolean isDroppable(TimelineEvent e) {
        return e instanceof TimelineEvent.Move
                || e instanceof TimelineEvent.Particle
                || e instanceof TimelineEvent.Sound;
    }

    private boolean withinBudget() {
        long sec = System.currentTimeMillis() / 1000L;
        if (sec != windowSec) {
            windowSec = sec;
            windowCount.set(0);
        }
        return windowCount.incrementAndGet() <= maxEventsPerSecond;
    }

    public TimelineEvent poll() {
        return queue.poll();
    }

    /** Buffered (not yet flushed) event count — for /er stats. */
    public int size() {
        return queue.size();
    }

    /** Take ownership of every buffered event (checkpoint flush / final save). */
    public List<TimelineEvent> drainAll() {
        List<TimelineEvent> out = new ArrayList<>();
        TimelineEvent e;
        while ((e = queue.poll()) != null) {
            out.add(e);
        }
        return out;
    }

    public void close() {
        closed = true;
    }
}
