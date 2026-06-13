package com.smplkit.internal;

import com.smplkit.Context;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ContextRegistrationBuffer} — dedup, LRU eviction, and the
 * null-attributes fallback in {@link ContextRegistrationBuffer#observe}.
 */
class ContextRegistrationBufferTest {

    @Test
    void observe_dedupesByCompositeKey() {
        ContextRegistrationBuffer buffer = new ContextRegistrationBuffer();
        Context ctx = new Context("user", "u-1", Map.of("plan", "pro"));

        buffer.observe(ctx);
        buffer.observe(ctx); // duplicate composite key -> ignored

        assertEquals(1, buffer.pendingCount());
        List<Map<String, Object>> drained = buffer.drain();
        assertEquals(1, drained.size());
        assertEquals("user", drained.get(0).get("type"));
        assertEquals("u-1", drained.get(0).get("key"));
        assertEquals(Map.of("plan", "pro"), drained.get(0).get("attributes"));
        // After draining, the queue is empty.
        assertEquals(0, buffer.pendingCount());
    }

    @Test
    void observe_nullAttributes_fallsBackToEmptyMap() {
        // The real Context never returns null attributes, so mock the (final)
        // class to drive the `ctx.attributes() != null ? ... : Map.of()` else arm.
        Context ctx = mock(Context.class);
        when(ctx.type()).thenReturn("service");
        when(ctx.key()).thenReturn("svc-1");
        when(ctx.attributes()).thenReturn(null);

        ContextRegistrationBuffer buffer = new ContextRegistrationBuffer();
        buffer.observe(ctx);

        List<Map<String, Object>> drained = buffer.drain();
        assertEquals(1, drained.size());
        assertEquals(Map.of(), drained.get(0).get("attributes"));
    }

    @Test
    void observeAll_buffersEachContext() {
        ContextRegistrationBuffer buffer = new ContextRegistrationBuffer();
        buffer.observeAll(List.of(
                new Context("user", "u-1"),
                new Context("account", "a-1")));
        assertEquals(2, buffer.pendingCount());
    }

    @Test
    void seenMap_evictsEldestBeyondMaxSize() {
        // The dedup `seen` map is an access-order-free LRU capped at MAX_SIZE
        // (10_000). Inserting more than that exercises removeEldestEntry's
        // size()>MAX_SIZE true arm. The eviction keeps the map bounded; we
        // assert the buffer still functions and never overflows unboundedly.
        ContextRegistrationBuffer buffer = new ContextRegistrationBuffer();
        int count = 10_005;
        for (int i = 0; i < count; i++) {
            buffer.observe(new Context("user", "u-" + i));
        }
        // Every distinct key was queued (pending is a separate unbounded queue).
        assertEquals(count, buffer.pendingCount());

        // The eldest entries were evicted from `seen`, so re-observing the very
        // first key is no longer deduped — it is queued again.
        buffer.drain();
        buffer.observe(new Context("user", "u-0"));
        assertEquals(1, buffer.pendingCount());
    }
}
