package org.jboss.connectors.test.auth;

import org.jboss.connectors.test.utils.NativePortAllocator;
import org.jboss.connectors.test.utils.WildFlyWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.commands.socketbindings.AddSocketBinding;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

/**
 * Adds an AJP listener to a WildFly worker.
 * Creates a socket binding on port 8019 (+ worker port offset) and an
 * Undertow AJP listener bound to it. Reloads the server if changes are made.
 */
final class AjpListenerSetup {

    private static final Logger log = LoggerFactory.getLogger(AjpListenerSetup.class);

    private static final int AJP_PORT = 8019;
    private static final String AJP_SOCKET_BINDING = "ajp-test";
    private static final String AJP_LISTENER = "ajp-test-listener";
    static final String AJP_SECRET = "test-ajp-secret";

    private AjpListenerSetup() {
    }

    /**
     * Add an AJP listener with secret enforcement to the worker if not already present.
     *
     * @return the AJP port (base port + worker's port offset)
     */
    static int addAjpListener(WildFlyWorker worker) throws Exception {
        return addAjpListener(worker, true, true);
    }

    /**
     * Add an AJP listener without secret enforcement.
     * Sets {@code REQUIRE_AJP_SECRET=false} and does not define {@code AJP_SECRET}.
     *
     * @return the AJP port (base port + worker's port offset)
     */
    static int addAjpListenerNoSecret(WildFlyWorker worker) throws Exception {
        return addAjpListener(worker, false, false);
    }

    /**
     * Add an AJP listener with the secret defined but startup requirement disabled.
     * Sets {@code REQUIRE_AJP_SECRET=false} and {@code AJP_SECRET} to the test value.
     *
     * @return the AJP port (base port + worker's port offset)
     */
    static int addAjpListenerOptionalSecret(WildFlyWorker worker) throws Exception {
        return addAjpListener(worker, true, false);
    }

    /**
     * Create the AJP socket binding if it does not exist.
     * Exposed for tests that need the binding without a full listener setup
     * (e.g. testing UT000220 startup failure).
     */
    static void ensureSocketBinding(WildFlyWorker worker) throws Exception {
        Operations ops = worker.getOperations();
        Address sbAddr = Address.of("socket-binding-group", "standard-sockets")
                .and("socket-binding", AJP_SOCKET_BINDING);
        if (!ops.exists(sbAddr)) {
            worker.getManagementClient().apply(
                    new AddSocketBinding.Builder(AJP_SOCKET_BINDING).port(AJP_PORT).build());
        }
    }

    static Address getListenerAddress() {
        return Address.subsystem("undertow")
                .and("server", "default-server")
                .and("ajp-listener", AJP_LISTENER);
    }

    private static int addAjpListener(WildFlyWorker worker, boolean setSecret, boolean requireSecret) throws Exception {
        Operations ops = worker.getOperations();

        ensureSocketBinding(worker);

        if (setSecret) {
            Address secretProp = Address.of("system-property", "io.undertow.ajp.AJP_SECRET");
            if (!ops.exists(secretProp)) {
                ops.add(secretProp, Values.of("value", AJP_SECRET)).assertSuccess();
                worker.reload();
            }
        }
        if (!requireSecret) {
            Address requireProp = Address.of("system-property", "io.undertow.ajp.REQUIRE_AJP_SECRET");
            if (!ops.exists(requireProp)) {
                ops.add(requireProp, Values.of("value", "false")).assertSuccess();
                worker.reload();
            }
        }

        Address listenerAddr = getListenerAddress();
        if (!ops.exists(listenerAddr)) {
            ops.add(listenerAddr, Values.of("socket-binding", AJP_SOCKET_BINDING)).assertSuccess();
            worker.reload();
        }

        int ajpPort = AJP_PORT + NativePortAllocator.resolvePortOffset(worker.getName());
        log.info("AJP listener on port {} ready (secret={}, require={})", ajpPort, setSecret, requireSecret);
        return ajpPort;
    }
}
