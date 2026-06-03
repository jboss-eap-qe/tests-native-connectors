package org.jboss.connectors.test.auth;

import org.jboss.connectors.test.apps.SecuredAppBuilder;
import org.jboss.connectors.test.base.ConnectorTestExtension;
import org.jboss.connectors.test.proxy.AjpProxy;
import org.jboss.connectors.test.utils.EmbeddedLdapServer;
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

    /**
     * Verifies that a user whose LDAP group membership grants the correct role
     * ({@code gooduser}) can access the secured servlet through the AJP proxy.
     * Expects HTTP 200 with {@code user=testuser} in the response body.
     */
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

    /**
     * Verifies that a request without REMOTE_USER (proxy configured without auth)
     * is rejected by the Elytron EXTERNAL mechanism. Expects HTTP 403.
     */
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

    /**
     * Verifies that a user whose LDAP group membership grants the wrong role
     * ({@code badrole} instead of {@code gooduser}) is rejected. Expects HTTP 403.
     */
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

}
