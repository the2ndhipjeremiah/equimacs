package org.equimacs.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ConsoleOutputCoreTest {

    @Test
    void splitsChunksPreservingLineEndingsAndConcatenatesVerbatim() {
        ConsoleOutputStore store = new ConsoleOutputStore();
        store.append(1000L, "Hello", "Hello [Java Application]", ConsoleOutputStore.StreamKind.STDOUT, "line1\nline2\n");
        store.append(1005L, "Hello", "Hello [Java Application]", ConsoleOutputStore.StreamKind.STDOUT, "part-");
        store.append(1010L, "Hello", "Hello [Java Application]", ConsoleOutputStore.StreamKind.STDOUT, "end\r\n");

        ConsoleOutputStore.ConsolePage page = store.snapshot(null, ConsoleOutputStore.StreamFilter.ALL, 50);
        assertEquals(4, page.count());
        assertEquals(4L, page.totalRecorded());
        assertEquals("line1\nline2\npart-end\r\n", page.text());
        assertEquals(
            List.of("line1\n", "line2\n", "part-", "end\r\n"),
            page.entries().stream().map(ConsoleOutputStore.ConsoleEntry::text).toList());
        assertEquals(
            List.of(1L, 2L, 3L, 4L),
            page.entries().stream().map(ConsoleOutputStore.ConsoleEntry::seq).toList());
    }

    @Test
    void filtersByLaunchNameAndStreamKind() {
        ConsoleOutputStore store = new ConsoleOutputStore();
        store.append(100L, "Main", "MainProc", ConsoleOutputStore.StreamKind.STDOUT, "m-out\n");
        store.append(101L, "Main", "MainProc", ConsoleOutputStore.StreamKind.STDERR, "m-err\n");
        store.append(102L, "Other", "OtherProc", ConsoleOutputStore.StreamKind.STDOUT, "o-out\n");

        ConsoleOutputStore.ConsolePage all = store.snapshot("  ", ConsoleOutputStore.StreamFilter.ALL, 0);
        assertEquals(3, all.count());
        assertEquals("m-out\nm-err\no-out\n", all.text());

        ConsoleOutputStore.ConsolePage mainAll = store.snapshot("Main", ConsoleOutputStore.StreamFilter.ALL, 50);
        assertEquals(2, mainAll.count());
        assertEquals("m-out\nm-err\n", mainAll.text());

        ConsoleOutputStore.ConsolePage mainOut = store.snapshot("Main", ConsoleOutputStore.StreamFilter.STDOUT, 50);
        assertEquals(1, mainOut.count());
        assertEquals("m-out\n", mainOut.text());
        assertEquals(ConsoleOutputStore.StreamKind.STDOUT, mainOut.entries().getFirst().stream());

        ConsoleOutputStore.ConsolePage mainErr = store.snapshot("Main", ConsoleOutputStore.StreamFilter.STDERR, 50);
        assertEquals(1, mainErr.count());
        assertEquals("m-err\n", mainErr.text());
        assertEquals(ConsoleOutputStore.StreamKind.STDERR, mainErr.entries().getFirst().stream());
    }

    @Test
    void tailsLatestEntriesByLimitAndEvictsOldestBeyondCapacity() {
        ConsoleOutputStore store = new ConsoleOutputStore(3);
        store.append(10L, "Main", "MainProc", ConsoleOutputStore.StreamKind.STDOUT, "l1\nl2\nl3\nl4\nl5\n");

        ConsoleOutputStore.ConsolePage allRetained = store.snapshot(null, ConsoleOutputStore.StreamFilter.ALL, 10);
        assertEquals(3, allRetained.count());
        assertEquals(5L, allRetained.totalRecorded());
        assertEquals("l3\nl4\nl5\n", allRetained.text());
        assertEquals(
            List.of(3L, 4L, 5L),
            allRetained.entries().stream().map(ConsoleOutputStore.ConsoleEntry::seq).toList());

        ConsoleOutputStore.ConsolePage tailTwo = store.snapshot(null, ConsoleOutputStore.StreamFilter.ALL, 2);
        assertEquals(2, tailTwo.count());
        assertEquals("l4\nl5\n", tailTwo.text());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearsBufferParsesStreamFiltersAndFormatsPageMap() {
        assertEquals(ConsoleOutputStore.StreamFilter.ALL, ConsoleOutputStore.parseStreamFilter(null));
        assertEquals(ConsoleOutputStore.StreamFilter.ALL, ConsoleOutputStore.parseStreamFilter("  "));
        assertEquals(ConsoleOutputStore.StreamFilter.STDOUT, ConsoleOutputStore.parseStreamFilter("OUT"));
        assertEquals(ConsoleOutputStore.StreamFilter.STDERR, ConsoleOutputStore.parseStreamFilter("stderr"));
        assertThrows(IllegalArgumentException.class, () -> ConsoleOutputStore.parseStreamFilter("bogus"));

        ConsoleOutputStore store = new ConsoleOutputStore();
        store.append(10L, "Main", "MainProc", ConsoleOutputStore.StreamKind.STDERR, "boom\n");
        store.clear();

        ConsoleOutputStore.ConsolePage empty = store.snapshot(null, ConsoleOutputStore.StreamFilter.ALL, 10);
        assertEquals(0, empty.count());
        assertEquals(1L, empty.totalRecorded());
        assertEquals("", empty.text());

        store.append(20L, "Main", "MainProc", ConsoleOutputStore.StreamKind.STDOUT, "after\n");
        ConsoleOutputStore.ConsolePage after = store.snapshot(null, ConsoleOutputStore.StreamFilter.ALL, 10);
        Map<String, Object> map = ConsoleOutputStore.toPageMap(after);
        assertEquals(1, map.get("count"));
        assertEquals(2L, map.get("totalRecorded"));
        assertEquals("after\n", map.get("text"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) map.get("entries");
        assertEquals(1, rows.size());
        assertEquals(2L, rows.getFirst().get("seq"));
        assertEquals("stdout", rows.getFirst().get("stream"));
        assertTrue(((String) rows.getFirst().get("launch")).equals("Main"));
    }
}
