package org.jboss.connectors.test.utils;

import java.util.Map;

/**
 * Static port allocation for native test mode.
 *
 * <p>All processes share the host network namespace, so each worker must use
 * unique ports. WildFly's {@code -Djboss.socket.binding.port-offset=N} shifts
 * every socket-binding port by N.
 *
 * <table>
 *   <caption>Port assignments</caption>
 *   <tr><th>Instance</th><th>Offset</th><th>HTTP</th><th>HTTPS</th><th>Management</th></tr>
 *   <tr><td>worker1</td><td>100</td><td>8180</td><td>8543</td><td>10090</td></tr>
 *   <tr><td>worker2</td><td>200</td><td>8280</td><td>8643</td><td>10190</td></tr>
 *   <tr><td>worker3</td><td>300</td><td>8380</td><td>8743</td><td>10290</td></tr>
 *   <tr><td>worker4</td><td>400</td><td>8480</td><td>8843</td><td>10390</td></tr>
 * </table>
 *
 * <p>The httpd proxy listens on port {@value #HTTPD_PORT} (no offset — single process).
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

    /**
     * Get the port offset for a named instance.
     *
     * @param name instance name (e.g. "worker1")
     * @return the offset value to pass to {@code -Djboss.socket.binding.port-offset}
     */
    public static int resolvePortOffset(String name) {
        Integer offset = OFFSETS.get(name);
        if (offset == null) {
            throw new IllegalArgumentException("Unknown instance name: '" + name
                    + "'. Known instances: " + OFFSETS.keySet());
        }
        return offset;
    }

    /** Get the HTTP port for a named instance (base 8080 + offset). */
    public static int resolveHttpPort(String name) {
        return BASE_HTTP + resolvePortOffset(name);
    }

    /** Get the HTTPS port for a named instance (base 8443 + offset). */
    public static int resolveHttpsPort(String name) {
        return BASE_HTTPS + resolvePortOffset(name);
    }

    /** Get the management port for a named instance (base 9990 + offset). */
    public static int resolveManagementPort(String name) {
        return BASE_MANAGEMENT + resolvePortOffset(name);
    }
}
