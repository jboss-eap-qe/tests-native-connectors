package org.jboss.connectors.test.base;

import org.jboss.connectors.test.utils.HttpClient;
import org.jboss.connectors.test.utils.WildFlyWorker;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit 5 Extension for native connector tests.
 * Manages WildFly worker lifecycle and provides parameter injection.
 * The AjpProxy is NOT managed by this extension — tests create and manage
 * their own proxy instances since different tests may use different proxy types.
 */
public class ConnectorTestExtension implements BeforeEachCallback, AfterEachCallback,
        ParameterResolver {

    private static final Logger log = LoggerFactory.getLogger(ConnectorTestExtension.class);

    private static final String WORKER_KEY = "worker";
    private static final String HTTP_CLIENT_KEY = "httpClient";

    @Override
    public void beforeEach(ExtensionContext context) {
        log.info("=== Starting test: {} ===", context.getDisplayName());

        ExtensionContext.Store store = getStore(context);

        WildFlyWorker worker = new WildFlyWorker("worker1");
        store.put(WORKER_KEY, worker);
        worker.start();

        store.put(HTTP_CLIENT_KEY, new HttpClient());

        log.info("Worker started: {}", worker.getHttpUrl());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store store = getStore(context);

        WildFlyWorker worker = store.get(WORKER_KEY, WildFlyWorker.class);
        if (worker != null) {
            try {
                worker.stop();
            } catch (Exception e) {
                log.debug("Ignoring error stopping worker: {}", e.getMessage());
            }
        }

        log.info("=== Finished test: {} ===", context.getDisplayName());
    }

    /** Supports injection of {@link WildFlyWorker} and {@link HttpClient} test method parameters. */
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return type == WildFlyWorker.class || type == HttpClient.class;
    }

    /** Resolves the requested parameter from the per-test store (worker or HTTP client). */
    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        ExtensionContext.Store store = getStore(extensionContext);

        if (type == WildFlyWorker.class) {
            return store.get(WORKER_KEY, WildFlyWorker.class);
        } else if (type == HttpClient.class) {
            return store.get(HTTP_CLIENT_KEY, HttpClient.class);
        }

        return null;
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getStore(ExtensionContext.Namespace.create(getClass(), context.getRequiredTestMethod()));
    }
}
