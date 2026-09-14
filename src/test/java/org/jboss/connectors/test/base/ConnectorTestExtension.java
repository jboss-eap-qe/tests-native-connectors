package org.jboss.connectors.test.base;

import org.jboss.connectors.test.proxy.AjpProxy;
import org.jboss.connectors.test.utils.HttpClient;
import org.jboss.connectors.test.utils.WildFlyWorker;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * JUnit 5 Extension for native connector tests.
 *
 * <p>Manages the full test infrastructure lifecycle: creates and starts a WildFly worker,
 * creates an {@link AjpProxy} (platform-selected), and provides an {@link HttpClient}.
 * All three are injected as test method parameters.
 *
 * <p>After each test, archives all configs (WildFly standalone.xml + proxy configs) under
 * {@code target/archived-configs/{testName}/}, then stops proxy and worker.
 */
public class ConnectorTestExtension implements BeforeAllCallback, BeforeEachCallback,
        AfterEachCallback, ParameterResolver {

    private static final Logger log = LoggerFactory.getLogger(ConnectorTestExtension.class);

    private static final String WORKER_KEY = "worker";
    private static final String PROXY_KEY = "proxy";
    private static final String HTTP_CLIENT_KEY = "httpClient";

    @Override
    public void beforeAll(ExtensionContext context) {
        try {
            AjpProxy probe = AjpProxy.create();
            log.info("Connector version: {}", probe.getVersion());
        } catch (Exception e) {
            log.warn("Could not determine connector version: {}", e.getMessage());
        }
    }

    /** Create and start a WildFly worker, AJP proxy, and HTTP client for the test. */
    @Override
    public void beforeEach(ExtensionContext context) {
        log.info("=== Starting test: {} ===", context.getDisplayName());

        ExtensionContext.Store store = getStore(context);

        WildFlyWorker worker = new WildFlyWorker("worker1");
        store.put(WORKER_KEY, worker);
        worker.start();

        AjpProxy proxy = AjpProxy.create();
        store.put(PROXY_KEY, proxy);

        store.put(HTTP_CLIENT_KEY, new HttpClient());

        log.info("Worker started: {}", worker.getHttpUrl());
    }

    /** Archive proxy and worker configs for debugging, then stop both. */
    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store store = getStore(context);
        String testId = context.getRequiredTestClass().getSimpleName() + "/" + context.getRequiredTestMethod().getName();
        Path archiveDir = Path.of("target", "archived-configs", testId);

        AjpProxy proxy = store.get(PROXY_KEY, AjpProxy.class);
        if (proxy != null) {
            try {
                proxy.archiveConfigs(archiveDir);
            } catch (Exception e) {
                log.debug("Failed to archive proxy configs: {}", e.getMessage());
            }
            proxy.stop();
        }

        WildFlyWorker worker = store.get(WORKER_KEY, WildFlyWorker.class);
        if (worker != null) {
            try {
                worker.archiveConfigs(archiveDir);
            } catch (Exception e) {
                log.debug("Failed to archive worker configs: {}", e.getMessage());
            }
            try {
                worker.stop();
            } catch (Exception e) {
                log.debug("Ignoring error stopping worker: {}", e.getMessage());
            }
        }

        log.info("=== Finished test: {} ===", context.getDisplayName());
    }

    /**
     * Supports injection of {@link WildFlyWorker}, {@link AjpProxy},
     * and {@link HttpClient} test method parameters.
     */
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return type == WildFlyWorker.class || type == AjpProxy.class || type == HttpClient.class;
    }

    /** Resolves the requested parameter from the per-test store. */
    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        ExtensionContext.Store store = getStore(extensionContext);

        if (type == WildFlyWorker.class) {
            return store.get(WORKER_KEY, WildFlyWorker.class);
        } else if (type == AjpProxy.class) {
            return store.get(PROXY_KEY, AjpProxy.class);
        } else if (type == HttpClient.class) {
            return store.get(HTTP_CLIENT_KEY, HttpClient.class);
        }

        return null;
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getStore(ExtensionContext.Namespace.create(getClass(), context.getRequiredTestMethod()));
    }
}
