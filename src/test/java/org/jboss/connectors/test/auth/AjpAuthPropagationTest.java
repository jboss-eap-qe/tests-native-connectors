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

import java.io.File;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.connectors.test.auth.AuthTestUtils.awaitAjpAvailable;
import static org.jboss.connectors.test.auth.AuthTestUtils.basicAuthHeaders;

/**
 * Tests REMOTE_USER authentication propagation via AJP from httpd to WildFly/Elytron.
 *
 * httpd authenticates via Basic auth -> sets REMOTE_USER -> mod_proxy_ajp forwards
 * as AJP remote_user attribute -> Elytron EXTERNAL mechanism authenticates the user.
 */
@ExtendWith(ConnectorTestExtension.class)
public class AjpAuthPropagationTest {

    private static final Logger log = LoggerFactory.getLogger(AjpAuthPropagationTest.class);

    /**
     * Verifies that a user with a valid REMOTE_USER and the correct Elytron role
     * ({@code gooduser}) can access the secured servlet through the AJP proxy.
     * Expects HTTP 200 with {@code user=testuser} in the response body.
     */
    @Test
    public void testAuthenticatedUserCanAccessSecuredServlet(WildFlyWorker worker,
                                                             HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();
        configurator.configureElytron(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"));
        int ajpPort = AjpListenerSetup.addAjpListener(worker);

        File securedWar = SecuredAppBuilder.createSecuredApp();
        worker.deploy(securedWar);

        AjpProxy proxy = AjpProxy.create();
        proxy.configureAuth("testuser", "Password1!", "localhost", ajpPort);
        proxy.start();

        try {
            String url = proxy.getHttpUrl() + "/secured/secured";
            Map<String, String> authHeaders = basicAuthHeaders("testuser", "Password1!");
            awaitAjpAvailable(httpClient, url, authHeaders);

            HttpResponse response = httpClient.get(url, authHeaders);

            log.info("Response: status={}, body={}", response.getStatusCode(), response.getBody());
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.getBody()).contains("user=testuser");
        } finally {
            proxy.stop();
        }
    }

    /**
     * Verifies that a request without REMOTE_USER (proxy configured without auth)
     * is rejected by the Elytron EXTERNAL mechanism. Expects HTTP 403.
     */
    @Test
    public void testNoRemoteUserIsRejected(WildFlyWorker worker,
                                            HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();
        configurator.configureElytron(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"));
        int ajpPort = AjpListenerSetup.addAjpListener(worker);

        File securedWar = SecuredAppBuilder.createSecuredApp();
        worker.deploy(securedWar);

        AjpProxy proxy = AjpProxy.create();
        proxy.configureNoAuth("localhost", ajpPort);
        proxy.start();

        try {
            String url = proxy.getHttpUrl() + "/secured/secured";
            awaitAjpAvailable(httpClient, url, null);

            HttpResponse response = httpClient.get(url);

            log.info("Response (no REMOTE_USER): status={}", response.getStatusCode());
            assertThat(response.getStatusCode()).isEqualTo(403);
        } finally {
            proxy.stop();
        }
    }

    /**
     * Verifies that a user who exists in the Elytron realm but has the wrong role
     * ({@code badrole} instead of {@code gooduser}) is rejected. Expects HTTP 403.
     */
    @Test
    public void testUnauthorizedUserIsRejected(WildFlyWorker worker,
                                                HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();
        configurator.configureElytron(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"),
                new AjpAuthConfigurator.UserEntry("baduser", "badrole"));
        int ajpPort = AjpListenerSetup.addAjpListener(worker);

        File securedWar = SecuredAppBuilder.createSecuredApp();
        worker.deploy(securedWar);

        AjpProxy proxy = AjpProxy.create();
        proxy.configureAuth("baduser", "Password1!", "localhost", ajpPort);
        proxy.start();

        try {
            String url = proxy.getHttpUrl() + "/secured/secured";
            Map<String, String> authHeaders = basicAuthHeaders("baduser", "Password1!");
            awaitAjpAvailable(httpClient, url, authHeaders);

            HttpResponse response = httpClient.get(url, authHeaders);

            log.info("Response (wrong role): status={}", response.getStatusCode());
            assertThat(response.getStatusCode()).isEqualTo(403);
        } finally {
            proxy.stop();
        }
    }

}
