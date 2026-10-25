package com.aresstack.argos.batch;

import com.aresstack.argos.fetch.FetchFailure;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BatchOpenerPlanTest {
    @Test
    void marksDuplicatesAndInvalidAddressesBeforeTheBrowserIsUsed() {
        BatchEntry[] planned = BatchOpener.plan(List.of("https://example.org/a", "https://example.org/a#part",
                "file:///etc/passwd", "not a url", "https://example.org/b"));
        assertNull(planned[0]);
        assertEquals(BatchEntry.Status.DUPLICATE, planned[1].status());
        assertEquals(BatchEntry.Status.INVALID_REQUEST, planned[2].status());
        assertEquals(BatchEntry.Status.INVALID_REQUEST, planned[3].status());
        assertNull(planned[4]);
    }

    @Test
    void limitsTheNumberOfAddresses() {
        assertThrows(FetchFailure.class, () -> BatchOpener.plan(List.of()));
        assertThrows(FetchFailure.class,
                () -> BatchOpener.plan(Collections.nCopies(BatchOpener.MAX_URLS + 1, "https://example.org/")));
    }

    @Test
    void boundsParallelism() {
        assertEquals(BatchOpener.MAX_PARALLEL, new BatchOpener.Options(99, 1, 1, 1000, 1).parallel());
        assertEquals(1, new BatchOpener.Options(0, 1, 1, 1000, 1).parallel());
    }
}
