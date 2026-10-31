package com.aresstack.argos.page;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadinessPolicyTest {
    @Test
    void defaultsToBalancedPreset() {
        ReadinessPolicy policy = ReadinessPolicy.from(name -> null);
        assertEquals(ReadinessPolicy.balanced(), policy);
        assertEquals("domcontentloaded", policy.waitUntil());
        assertTrue(policy.settles());
        assertFalse(policy.needsDomReads(), "balanced must not poll the DOM (user activation)");
        assertTrue(ReadinessPolicy.thorough().needsDomReads());
    }

    @Test
    void fastPresetKeepsPreviousBehaviour() {
        ReadinessPolicy policy = ReadinessPolicy.from(Map.of("PAGE_READINESS", "FAST")::get);
        assertFalse(policy.settles());
        assertEquals("domcontentloaded", policy.waitUntil());
    }

    @Test
    void appliesIndividualOverrides() {
        ReadinessPolicy policy = ReadinessPolicy.from(Map.of(
                "PAGE_READINESS", "thorough",
                "READY_TIMEOUT_MS", "4000",
                "READY_DOM_STABLE_MS", "300",
                "READY_LOADING_SELECTORS", ".busy | #overlay")::get);
        assertEquals("load", policy.waitUntil());
        assertEquals(4_000, policy.timeoutMillis());
        assertEquals(300, policy.domStableMillis());
        assertEquals(List.of(".busy", "#overlay"), policy.loadingSelectors());
        assertTrue(ReadinessPolicy.from(Map.of("READY_LOADING_SELECTORS", "none")::get)
                .loadingSelectors().isEmpty());
    }

    @Test
    void clampsAndRejectsInvalidValues() {
        assertEquals(60_000, ReadinessPolicy.from(Map.of("READY_TIMEOUT_MS", "999999")::get).timeoutMillis());
        assertThrows(IllegalArgumentException.class,
                () -> ReadinessPolicy.from(Map.of("PAGE_READINESS", "slow")::get));
        assertThrows(IllegalArgumentException.class,
                () -> ReadinessPolicy.from(Map.of("READY_WAIT_UNTIL", "networkidle")::get));
        assertThrows(IllegalArgumentException.class,
                () -> ReadinessPolicy.from(Map.of("READY_POLL_MS", "fast")::get));
    }
}
