package org.jboss.connectors.test.utils;

import java.time.Duration;

/**
 * Centralized timeout constants for the test suite.
 * Each value can be overridden via a system property (e.g. {@code -Dtest.timeout.boot.ms=240000})
 * to accommodate slow CI nodes without code changes.
 */
public final class TestTimeouts {

    private TestTimeouts() {
    }

    public static final int BOOT_TIMEOUT_MS = intProp("test.timeout.boot.ms", 180_000);

    public static final int CONNECTION_TIMEOUT_MS = intProp("test.timeout.connection.ms", 10_000);

    public static final Duration EXEC_COMMAND = durationSeconds("test.timeout.exec.command", 120);

    public static final Duration WORKER_STARTUP = durationMinutes("test.timeout.worker.startup.minutes", 5);

    public static final Duration PROXY_STARTUP = durationSeconds("test.timeout.proxy.startup", 30);

    public static final Duration AJP_AVAILABLE = durationSeconds("test.timeout.ajp.available", 120);

    private static Duration durationSeconds(String prop, int defaultSeconds) {
        return Duration.ofSeconds(Integer.getInteger(prop, defaultSeconds));
    }

    private static Duration durationMinutes(String prop, int defaultMinutes) {
        return Duration.ofMinutes(Integer.getInteger(prop, defaultMinutes));
    }

    private static int intProp(String prop, int defaultValue) {
        return Integer.getInteger(prop, defaultValue);
    }
}
