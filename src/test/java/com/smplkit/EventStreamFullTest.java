package com.smplkit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dispatch and SSE-parser tests for {@link EventStream}. The parser is fed
 * raw bytes exactly as the reader thread would, so line terminators, buffer
 * splits, and encoding edge cases are exercised for real.
 */
class EventStreamFullTest {

    // -------------------------------------------------------------- dispatch

    @Test
    void dispatch_firesListeners() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("test_event", received::set);

        Map<String, Object> data = Map.of("id", "hello");
        stream.dispatch("test_event", data);

        assertNotNull(received.get());
        assertEquals("hello", received.get().get("id"));
    }

    @Test
    void dispatch_doesNothingForUnregisteredEvent() {
        EventStream stream = new EventStream();
        assertDoesNotThrow(() -> stream.dispatch("unknown", Map.of()));
    }

    @Test
    void dispatch_catchesListenerExceptions() {
        EventStream stream = new EventStream();
        stream.on("test", data -> { throw new RuntimeException("boom"); });
        assertDoesNotThrow(() -> stream.dispatch("test", Map.of()));
    }

    @Test
    void onAndOff_multipleListeners() {
        EventStream stream = new EventStream();
        AtomicInteger count = new AtomicInteger();
        java.util.function.Consumer<Map<String, Object>> listener1 = data -> count.incrementAndGet();
        java.util.function.Consumer<Map<String, Object>> listener2 = data -> count.incrementAndGet();

        stream.on("test", listener1);
        stream.on("test", listener2);
        stream.dispatch("test", Map.of());
        assertEquals(2, count.get());

        stream.off("test", listener1);
        stream.dispatch("test", Map.of());
        assertEquals(3, count.get()); // Only listener2 fired
    }

    // -------------------------------------------------------- connected event

    @Test
    void connectedEvent_flipsStatusAndDispatches() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("connected", received::set);

        feed(stream, "event: connected\ndata: {}\n\n");

        assertEquals("connected", stream.connectionStatus());
        assertNotNull(received.get());
        assertTrue(received.get().isEmpty());
    }

    @Test
    void connectedEvent_countsDownConnectedLatch() throws Exception {
        EventStream stream = new EventStream();
        CountDownLatch latch = new CountDownLatch(1);
        stream.connectedLatch = latch;

        feed(stream, "event: connected\ndata: {}\n\n");

        assertEquals("connected", stream.connectionStatus());
        assertTrue(latch.await(1, TimeUnit.SECONDS), "connected latch should be counted down");
    }

    // -------------------------------------------------------------- parsing

    @Test
    void parse_singleResourceEvent_payloadIsIdOnly() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        feed(stream, "event: flag_changed\ndata: {\"id\": \"checkout-v2\"}\n\n");

        assertNotNull(received.get());
        assertEquals("checkout-v2", received.get().get("id"));
        assertEquals(1, received.get().size());
    }

    @Test
    void parse_crlfLineTerminators() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        feed(stream, "event: flag_changed\r\ndata: {\"id\": \"k\"}\r\n\r\n");

        assertNotNull(received.get());
        assertEquals("k", received.get().get("id"));
    }

    @Test
    void parse_bareCarriageReturnTerminators() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        feed(stream, "event: flag_changed\rdata: {\"id\": \"k\"}\r\r");

        assertNotNull(received.get());
        assertEquals("k", received.get().get("id"));
    }

    @Test
    void parse_mixedTerminators_crFollowedByContent() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        // \r immediately followed by a non-\n byte: the \r alone terminates
        feed(stream, "event: flag_changed\rdata: {\"id\": \"x\"}\n\n");

        assertNotNull(received.get());
        assertEquals("x", received.get().get("id"));
    }

    @Test
    void parse_fieldValueSplitAcrossReadBuffers() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        EventStream.Parser parser = stream.newParser();
        feedChunk(parser, "event: flag_ch");
        assertNull(received.get());
        feedChunk(parser, "anged\ndata: {\"id\":");
        assertNull(received.get());
        feedChunk(parser, " \"split\"}\n\n");

        assertNotNull(received.get());
        assertEquals("split", received.get().get("id"));
    }

    @Test
    void parse_crlfSplitAcrossReadBuffers() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        EventStream.Parser parser = stream.newParser();
        // The \r arrives at the end of one chunk, the \n at the start of the next
        feedChunk(parser, "event: flag_changed\r");
        feedChunk(parser, "\ndata: {\"id\": \"k\"}\r\n");
        feedChunk(parser, "\r\n");

        assertNotNull(received.get());
        assertEquals("k", received.get().get("id"));
    }

    @Test
    void parse_multiByteUtf8SplitAcrossReadBuffers() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        byte[] whole = "event: flag_changed\ndata: {\"id\": \"héllo\"}\n\n".getBytes(StandardCharsets.UTF_8);
        // Split inside the two-byte UTF-8 sequence for 'é'
        int splitAt = indexOf(whole, (byte) 0xC3) + 1;
        EventStream.Parser parser = stream.newParser();
        parser.feed(whole, splitAt);
        byte[] rest = java.util.Arrays.copyOfRange(whole, splitAt, whole.length);
        parser.feed(rest, rest.length);

        assertNotNull(received.get());
        assertEquals("héllo", received.get().get("id"));
    }

    @Test
    void parse_multipleDataLinesJoinWithNewline() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        // {"id":\n"multi"} — valid JSON only if the data lines join with \n
        feed(stream, "event: flag_changed\ndata: {\"id\":\ndata: \"multi\"}\n\n");

        assertNotNull(received.get());
        assertEquals("multi", received.get().get("id"));
    }

    @Test
    void parse_leadingBomIsStripped() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        feed(stream, "\uFEFFevent: flag_changed\ndata: {\"id\": \"k\"}\n\n");

        assertNotNull(received.get());
        assertEquals("k", received.get().get("id"));
    }

    @Test
    void parse_bomOnlyStrippedOnFirstLine() {
        EventStream stream = new EventStream();
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        stream.on("\uFEFFweird_event", received::add);

        // A BOM on a later line is NOT stripped — it stays part of the field name
        feed(stream, "event: e\ndata: {}\n\n\uFEFFevent: x\ndata: {}\n\n");
        // The second frame's "\uFEFFevent" is an unknown field, so its frame has
        // data but the default "message" event name — no listener, no crash.
        assertTrue(received.isEmpty());
    }

    @Test
    void parse_commentLinesAreIgnored() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        feed(stream, ": keepalive\n\n: another comment\nevent: flag_changed\ndata: {\"id\": \"k\"}\n\n");

        assertNotNull(received.get());
        assertEquals("k", received.get().get("id"));
    }

    @Test
    void parse_commentOnlyFrame_dispatchesNothing() {
        EventStream stream = new EventStream();
        AtomicInteger count = new AtomicInteger();
        stream.on("connected", d -> count.incrementAndGet());

        feed(stream, ": keepalive\n\n");

        assertEquals(0, count.get());
        assertEquals("disconnected", stream.connectionStatus());
    }

    @Test
    void parse_unknownFieldsAreIgnored() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        feed(stream, "id: 42\nfoo: bar\nevent: flag_changed\ndata: {\"id\": \"k\"}\n\n");

        assertNotNull(received.get());
        assertEquals("k", received.get().get("id"));
    }

    @Test
    void parse_unknownEventNamesAreIgnoredSilently() {
        EventStream stream = new EventStream();
        assertDoesNotThrow(() ->
                feed(stream, "event: brand_new_event\ndata: {\"id\": \"k\"}\n\n"));
    }

    @Test
    void parse_dataWithoutEventName_usesDefaultMessageName() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("message", received::set);

        feed(stream, "data: {\"id\": \"k\"}\n\n");

        assertNotNull(received.get());
        assertEquals("k", received.get().get("id"));
    }

    @Test
    void parse_eventWithoutData_isNotDispatched() {
        EventStream stream = new EventStream();
        AtomicInteger count = new AtomicInteger();
        stream.on("flag_changed", d -> count.incrementAndGet());

        feed(stream, "event: flag_changed\n\n");

        assertEquals(0, count.get());
    }

    @Test
    void parse_fieldWithNoColon_isTreatedAsFieldNameWithEmptyValue() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        // "event" with no colon sets an empty event name; the later real
        // event line wins. "data" with no colon appends an empty data line.
        feed(stream, "event\ndata\nevent: flag_changed\ndata: {\"id\": \"k\"}\n\n");

        // data joined: "\n{"id": "k"}" — leading newline keeps JSON valid? No:
        // "\n{...}" IS valid JSON (leading whitespace). Listener fires.
        assertNotNull(received.get());
        assertEquals("k", received.get().get("id"));
    }

    @Test
    void parse_valueWithoutLeadingSpace() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        stream.on("flag_changed", received::set);

        feed(stream, "event:flag_changed\ndata:{\"id\": \"k\"}\n\n");

        assertNotNull(received.get());
        assertEquals("k", received.get().get("id"));
    }

    @Test
    void parse_invalidJsonPayload_isDroppedWithoutDispatch() {
        EventStream stream = new EventStream();
        AtomicInteger count = new AtomicInteger();
        stream.on("flag_changed", d -> count.incrementAndGet());

        assertDoesNotThrow(() ->
                feed(stream, "event: flag_changed\ndata: not json\n\n"));
        assertEquals(0, count.get());
    }

    @Test
    void parse_retryFieldSeedsBackoffBase() {
        EventStream stream = new EventStream();
        assertEquals(EventStream.DEFAULT_RETRY_MS, stream.baseRetryMs);

        feed(stream, "retry: 1500\n\n");

        assertEquals(1500, stream.baseRetryMs);
    }

    @Test
    void parse_retryFieldNonNumeric_isIgnored() {
        EventStream stream = new EventStream();
        feed(stream, "retry: abc\n\n");
        assertEquals(EventStream.DEFAULT_RETRY_MS, stream.baseRetryMs);
    }

    @Test
    void parse_retryFieldEmpty_isIgnored() {
        EventStream stream = new EventStream();
        feed(stream, "retry:\n\n");
        assertEquals(EventStream.DEFAULT_RETRY_MS, stream.baseRetryMs);
    }

    @Test
    void parse_retryFieldTooLong_isIgnored() {
        EventStream stream = new EventStream();
        feed(stream, "retry: 12345678901\n\n");
        assertEquals(EventStream.DEFAULT_RETRY_MS, stream.baseRetryMs);
    }

    @Test
    void parse_retryFieldMixedDigits_isIgnored() {
        EventStream stream = new EventStream();
        feed(stream, "retry: 12a4\n\n");
        assertEquals(EventStream.DEFAULT_RETRY_MS, stream.baseRetryMs);
    }

    @Test
    void parse_stateResetsBetweenFrames() {
        EventStream stream = new EventStream();
        List<String> events = new CopyOnWriteArrayList<>();
        stream.on("flag_changed", d -> events.add("flag:" + d.get("id")));
        stream.on("config_changed", d -> events.add("config:" + d.get("id")));

        feed(stream, "event: flag_changed\ndata: {\"id\": \"a\"}\n\n"
                + "event: config_changed\ndata: {\"id\": \"b\"}\n\n");

        assertEquals(List.of("flag:a", "config:b"), events);
    }

    // -------------------------------------------------------- status helpers

    @Test
    void setConnectionStatus() {
        EventStream stream = new EventStream();
        stream.setConnectionStatus("connecting");
        assertEquals("connecting", stream.connectionStatus());
    }

    @Test
    void ensureConnected_noOp_whenAlreadyConnected() {
        EventStream stream = new EventStream();
        stream.setConnectionStatus("connected");
        assertDoesNotThrow(() -> stream.ensureConnected(Duration.ofMillis(100)));
    }

    @Test
    void close_multipleTimesIsSafe() {
        EventStream stream = new EventStream();
        stream.close();
        stream.close(); // second close should not throw
    }

    @Test
    void ensureConnected_restartsWhenThreadDied() throws Exception {
        // Drive the `streamThread != null && !streamThread.isAlive()` second
        // operand: inject an already-terminated thread, then call
        // ensureConnected while not connected. The no-arg test constructor's
        // start() is a safe no-op (null connector), so this is deterministic
        // and socket-free.
        EventStream stream = new EventStream();

        Thread dead = new Thread(() -> { });
        dead.start();
        dead.join(); // guaranteed terminated
        assertFalse(dead.isAlive());
        setStreamThread(stream, dead);

        assertDoesNotThrow(() -> stream.ensureConnected(Duration.ofMillis(10)));
    }

    // -------------------------------------------------------------- helpers

    /** Feeds raw SSE bytes through a fresh parser bound to the stream. */
    private static void feed(EventStream stream, String raw) {
        feedChunk(stream.newParser(), raw);
    }

    private static void feedChunk(EventStream.Parser parser, String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        parser.feed(bytes, bytes.length);
    }

    private static int indexOf(byte[] haystack, byte needle) {
        for (int i = 0; i < haystack.length; i++) {
            if (haystack[i] == needle) return i;
        }
        return -1;
    }

    /** Injects a thread into the private streamThread field. */
    private static void setStreamThread(EventStream stream, Thread t) throws Exception {
        var f = EventStream.class.getDeclaredField("streamThread");
        f.setAccessible(true);
        f.set(stream, t);
    }
}
