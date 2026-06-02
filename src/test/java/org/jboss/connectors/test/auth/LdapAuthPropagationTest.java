package org.jboss.connectors.test.auth;

import org.jboss.connectors.test.apps.SecuredAppBuilder;
import org.jboss.connectors.test.base.ConnectorTestExtension;
import org.jboss.connectors.test.proxy.AjpProxy;
import org.jboss.connectors.test.utils.EmbeddedLdapServer;
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
 * Tests REMOTE_USER authentication propagation with LDAP-backed role resolution.
 *
 * <p>Validates the end-to-end path: proxy authenticates user → REMOTE_USER over AJP
 * → Elytron EXTERNAL mechanism → ldap-realm resolves roles from group membership.
 * This is the typical setup for environments with many users where per-user
 * filesystem-realm entries are impractical (e.g. Active Directory).</p>
 *
 * <p>Uses an embedded LDAP server (UnboundID InMemoryDirectoryServer) with
 * users in ou=People and role groups in ou=Groups.</p>
 */
@ExtendWith(ConnectorTestExtension.class)
public class LdapAuthPropagationTest {

    private static final Logger log = LoggerFactory.getLogger(LdapAuthPropagationTest.class);

    @Test
    public void testAuthenticatedUserCanAccessSecuredServlet(WildFlyWorker worker,
                                                             HttpClient httpClient) throws Exception {
        EmbeddedLdapServer ldapServer = new EmbeddedLdapServer();
        ldapServer.start();

        try {
            LdapAuthConfigurator configurator = new LdapAuthConfigurator();
            configurator.configureElytron(worker, ldapServer.getPort());
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
        } finally {
            ldapServer.stop();
        }
    }

    @Test
    public void testNoRemoteUserIsRejected(WildFlyWorker worker,
                                            HttpClient httpClient) throws Exception {
        EmbeddedLdapServer ldapServer = new EmbeddedLdapServer();
        ldapServer.start();

        try {
            LdapAuthConfigurator configurator = new LdapAuthConfigurator();
            configurator.configureElytron(worker, ldapServer.getPort());
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
        } finally {
            ldapServer.stop();
        }
    }

    @Test
    public void testUnauthorizedUserIsRejected(WildFlyWorker worker,
                                                HttpClient httpClient) throws Exception {
        EmbeddedLdapServer ldapServer = new EmbeddedLdapServer();
        ldapServer.start();

        try {
            LdapAuthConfigurator configurator = new LdapAuthConfigurator();
            configurator.configureElytron(worker, ldapServer.getPort());
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
        } finally {
            ldapServer.stop();
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
