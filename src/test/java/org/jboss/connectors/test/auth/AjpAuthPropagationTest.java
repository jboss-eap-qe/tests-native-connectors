package org.jboss.connectors.test.auth;

import org.jboss.connectors.test.apps.SecuredAppBuilder;
import org.jboss.connectors.test.base.ConnectorTestExtension;
import org.jboss.connectors.test.proxy.AjpProxy;
import org.jboss.connectors.test.utils.HttpClient;
import org.jboss.connectors.test.utils.HttpClient.HttpResponse;
import org.jboss.connectors.test.utils.TestTimeouts;
import org.jboss.connectors.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests REMOTE_USER authentication propagation via AJP from httpd to WildFly/Elytron.
 *
 * httpd authenticates via Basic auth -> sets REMOTE_USER -> mod_proxy_ajp forwards
 * as AJP remote_user attribute -> Elytron EXTERNAL mechanism authenticates the user.
 */
@ExtendWith(ConnectorTestExtension.class)
public class AjpAuthPropagationTest {

    private static final Logger log = LoggerFactory.getLogger(AjpAuthPropagationTest.class);

    @Test
    public void testAuthenticatedUserCanAccessSecuredServlet(WildFlyWorker worker,
                                                             HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();
        configurator.configureElytron(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"));
        int ajpPort = configurator.addAjpListener(worker);

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

    @Test
    public void testNoRemoteUserIsRejected(WildFlyWorker worker,
                                            HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();
        configurator.configureElytron(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"));
        int ajpPort = configurator.addAjpListener(worker);

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

    @Test
    public void testUnauthorizedUserIsRejected(WildFlyWorker worker,
                                                HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();
        configurator.configureElytron(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"),
                new AjpAuthConfigurator.UserEntry("baduser", "badrole"));
        int ajpPort = configurator.addAjpListener(worker);

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

    private void awaitAjpAvailable(HttpClient httpClient, String url, Map<String, String> headers) {
        await().atMost(TestTimeouts.AJP_AVAILABLE)
                .pollInterval(ofSeconds(2))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    HttpResponse response = headers != null
                            ? httpClient.get(url, headers) : httpClient.get(url);
                    assertThat(response.getStatusCode()).isLessThan(500);
                });
        log.info("AJP proxy responding at {}", url);
    }

    private static Map<String, String> basicAuthHeaders(String username, String password) {
        String credentials = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes());
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Basic " + credentials);
        return headers;
    }
}
