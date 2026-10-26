package com.aresstack.argos.batch;

import com.aresstack.argos.fetch.FetchFailure;
import com.aresstack.argos.web.UrlPolicy;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BatchOpenerPlanTest {
    // No DNS in unit tests: private-network checks are off except where a test turns them on.
    private static final BatchOpener OPEN = new BatchOpener(UrlPolicy.off(), false);

    @Test
    void reportsAddressesThePolicyRefusesAsBlocked() {
        BatchEntry[] planned = new BatchOpener(UrlPolicy.allowing("10.1.0.0/16"), false)
                .plan(List.of("http://127.0.0.1/admin", "http://10.1.2.3/wiki", "http://169.254.169.254/"));
        assertEquals(BatchEntry.Status.BLOCKED, planned[0].status());
        assertNull(planned[1]);
        assertEquals(BatchEntry.Status.BLOCKED, planned[2].status());
    }

    @Test
    void marksDuplicatesAndInvalidAddressesBeforeTheBrowserIsUsed() {
        BatchEntry[] planned = OPEN.plan(List.of("https://example.org/a", "https://example.org/a#part",
                "file:///etc/passwd", "not a url", "https://example.org/b"));
        assertNull(planned[0]);
        assertEquals(BatchEntry.Status.DUPLICATE, planned[1].status());
        assertEquals(BatchEntry.Status.INVALID_REQUEST, planned[2].status());
        assertEquals(BatchEntry.Status.INVALID_REQUEST, planned[3].status());
        assertNull(planned[4]);
    }

    @Test
    void limitsTheNumberOfAddresses() {
        assertThrows(FetchFailure.class, () -> OPEN.plan(List.of()));
        assertThrows(FetchFailure.class,
                () -> OPEN.plan(Collections.nCopies(BatchOpener.MAX_URLS + 1, "https://example.org/")));
    }

    @Test
    void boundsParallelism() {
        assertEquals(BatchOpener.MAX_PARALLEL, new BatchOpener.Options(99, 1, 1, 1000, 1).parallel());
        assertEquals(1, new BatchOpener.Options(0, 1, 1, 1000, 1).parallel());
    }
}
