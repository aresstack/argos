package com.aresstack.argos.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Host name resolution for the network policy, replaceable in tests. */
@FunctionalInterface
public interface HostResolver {
    HostResolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

    List<InetAddress> resolve(String host) throws UnknownHostException;
}
