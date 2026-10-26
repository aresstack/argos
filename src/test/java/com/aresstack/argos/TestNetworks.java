package com.aresstack.argos;

import com.aresstack.argos.network.NetworkSettings;

/** Network settings for opt-in browser tests whose fixture servers listen on loopback. */
public final class TestNetworks {
    /** Strict boundary, with loopback admitted by rule as an intranet deployment would admit its hosts. */
    public static final NetworkSettings LOOPBACK = NetworkSettings.parse("strict", "127.0.0.1,::1", null, null);

    private TestNetworks() {
    }
}
