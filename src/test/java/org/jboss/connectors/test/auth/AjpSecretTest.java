package org.jboss.connectors.test.auth;

import org.jboss.connectors.test.apps.SecuredAppBuilder;
import org.jboss.connectors.test.base.ConnectorTestExtension;
import org.jboss.connectors.test.proxy.AjpProxy;
import org.jboss.connectors.test.utils.HttpClient;
import org.jboss.connectors.test.utils.HttpClient.HttpResponse;
import org.jboss.connectors.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

import java.io.File;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jboss.connectors.test.auth.AuthTestUtils.awaitAjpAvailable;
import static org.jboss.connectors.test.auth.AuthTestUtils.basicAuthHeaders;

/**
 * Tests AJP secret enforcement: verifies that a mismatched secret is rejected
 * and that disabling the secret requirement allows connections without one.
 */
@ExtendWith(ConnectorTestExtension.class)
public class AjpSecretTest {

    private static final Logger log = LoggerFactory.getLogger(AjpSecretTest.class);

    /**
     * Verifies that a proxy configured with a wrong AJP secret cannot reach
     * the WildFly backend. The AJP listener enforces the correct secret, so
     * the proxy should receive an error response (not 2xx).
     */
    @Test
    public void testBadSecretIsRejected(WildFlyWorker worker,
                                         AjpProxy proxy,
                                         HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();
        configurator.configureElytron(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"));
        int ajpPort = AjpListenerSetup.addAjpListener(worker);

        File securedWar = SecuredAppBuilder.createSecuredApp();
        worker.deploy(securedWar);

        proxy.configureAuth("testuser", "Password1!", "localhost", ajpPort, "wrong-secret");
        proxy.start();

        String url = proxy.getHttpUrl() + "/secured/secured";
        Map<String, String> authHeaders = basicAuthHeaders("testuser", "Password1!");

        HttpResponse response = httpClient.get(url, authHeaders);

        log.info("Response (bad secret): status={}, body={}", response.getStatusCode(), response.getBody());
        assertThat(response.getStatusCode())
                .as("AJP connection with wrong secret should be rejected")
                .matches(status -> status < 200 || status >= 300, "non-2xx status");
    }

    /**
     * Verifies that AJP works without a secret when the WildFly listener
     * does not enforce one (no {@code io.undertow.ajp.AJP_SECRET} system property).
     */
    @Test
    public void testNoSecretRequired(WildFlyWorker worker,
                                      AjpProxy proxy,
                                      HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();
        configurator.configureElytron(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"));
        int ajpPort = AjpListenerSetup.addAjpListenerNoSecret(worker);

        File securedWar = SecuredAppBuilder.createSecuredApp();
        worker.deploy(securedWar);

        proxy.configureAuth("testuser", "Password1!", "localhost", ajpPort, null);
        proxy.start();

        String url = proxy.getHttpUrl() + "/secured/secured";
        Map<String, String> authHeaders = basicAuthHeaders("testuser", "Password1!");
        awaitAjpAvailable(httpClient, url, authHeaders);

        HttpResponse response = httpClient.get(url, authHeaders);

        log.info("Response (no secret): status={}, body={}", response.getStatusCode(), response.getBody());
        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("user=testuser");
    }

    /**
     * Verifies that the AJP listener fails to start when
     * {@code REQUIRE_AJP_SECRET} is true (default) but no
     * {@code AJP_SECRET} is defined. Expects UT000220.
     */
    @Test
    public void testListenerFailsWithoutSecretWhenRequired(WildFlyWorker worker) throws Exception {
        AjpListenerSetup.ensureSocketBinding(worker);

        Operations ops = worker.getOperations();
        assertThatThrownBy(() ->
                ops.add(AjpListenerSetup.getListenerAddress(),
                        Values.of("socket-binding", "ajp-test")).assertSuccess()
        ).hasMessageContaining("UT000220");
    }

    /**
     * Verifies that the AJP secret is still enforced at runtime even when
     * {@code REQUIRE_AJP_SECRET=false}, as long as {@code AJP_SECRET} is defined.
     * {@code REQUIRE_AJP_SECRET} only controls startup validation, not runtime checks.
     */
    @Test
    public void testSecretEnforcedWhenOptional(WildFlyWorker worker,
                                               AjpProxy proxy,
                                               HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();
        configurator.configureElytron(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"));
        int ajpPort = AjpListenerSetup.addAjpListenerOptionalSecret(worker);

        File securedWar = SecuredAppBuilder.createSecuredApp();
        worker.deploy(securedWar);

        proxy.configureAuth("testuser", "Password1!", "localhost", ajpPort, "wrong-secret");
        proxy.start();

        String url = proxy.getHttpUrl() + "/secured/secured";
        Map<String, String> authHeaders = basicAuthHeaders("testuser", "Password1!");

        HttpResponse response = httpClient.get(url, authHeaders);

        log.info("Response (optional secret, wrong value): status={}, body={}",
                response.getStatusCode(), response.getBody());
        assertThat(response.getStatusCode())
                .as("Secret should still be enforced at runtime even with REQUIRE_AJP_SECRET=false")
                .matches(status -> status < 200 || status >= 300, "non-2xx status");
    }
}
