package org.jboss.connectors.test.utils;

import java.util.Map;

/**
 * Static port allocation for native test mode.
 * All processes share the host network namespace, so each worker uses unique ports
 * via {@code -Djboss.socket.binding.port-offset=N}.
 */
public final class NativePortAllocator {

    private static final int BASE_HTTP = 8080;
    private static final int BASE_HTTPS = 8443;
    private static final int BASE_MANAGEMENT = 9990;

    /** Default httpd listen port for the AJP proxy. */
    public static final int HTTPD_PORT = 8090;

    private static final Map<String, Integer> OFFSETS = Map.of(
            "worker1", 100,
            "worker2", 200,
            "worker3", 300,
            "worker4", 400
    );

    private NativePortAllocator() {
    }

    public static int offset(String name) {
        Integer offset = OFFSETS.get(name);
        if (offset == null) {
            throw new IllegalArgumentException("Unknown instance name: '" + name
                    + "'. Known instances: " + OFFSETS.keySet());
        }
        return offset;
    }

    public static int httpPort(String name) {
        return BASE_HTTP + offset(name);
    }

    public static int httpsPort(String name) {
        return BASE_HTTPS + offset(name);
    }

    public static int managementPort(String name) {
        return BASE_MANAGEMENT + offset(name);
    }
}
