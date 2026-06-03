package org.jboss.connectors.test.utils;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory LDAP server for testing Elytron ldap-realm integration.
 *
 * <p>Simulates an Active Directory-style directory with:
 * <ul>
 *   <li>{@code ou=People,dc=test,dc=local} — user entries ({@code inetOrgPerson})</li>
 *   <li>{@code ou=Groups,dc=test,dc=local} — role groups ({@code groupOfNames} with {@code member} attribute)</li>
 * </ul>
 *
 * <p>Pre-populated with two users: {@code testuser} (member of group {@code gooduser})
 * and {@code baduser} (member of group {@code badrole}). The group {@code cn} maps to
 * the Elytron role name via {@code attribute-mapping} in the ldap-realm.
 *
 * <p>Uses port 0 by default (OS-assigned) to avoid conflicts between tests.
 * The actual port is available via {@link #getPort()} after {@link #start()}.
 *
 * @see org.jboss.connectors.test.auth.LdapAuthConfigurator
 */
public class EmbeddedLdapServer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedLdapServer.class);

    public static final String BASE_DN = "dc=test,dc=local";
    public static final String PEOPLE_DN = "ou=People," + BASE_DN;
    public static final String GROUPS_DN = "ou=Groups," + BASE_DN;
    public static final String BIND_DN = "cn=admin," + BASE_DN;
    public static final String BIND_PASSWORD = "admin";
    public static final int DEFAULT_PORT = 10389;

    private final int port;
    private InMemoryDirectoryServer server;

    public EmbeddedLdapServer() {
        this(0);
    }

    public EmbeddedLdapServer(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", port));

        server = new InMemoryDirectoryServer(config);
        server.startListening();

        populateDirectory();
        log.info("Embedded LDAP server started on port {}", getPort());
    }

    public void stop() {
        if (server != null) {
            server.shutDown(true);
            log.info("Embedded LDAP server stopped");
        }
    }

    public int getPort() {
        return server != null ? server.getListenPort() : port;
    }

    private void populateDirectory() throws Exception {
        try (LDAPConnection conn = server.getConnection()) {
            conn.add(new Entry(BASE_DN,
                    new Attribute("objectClass", "domain"),
                    new Attribute("dc", "test")));

            conn.add(new Entry(PEOPLE_DN,
                    new Attribute("objectClass", "organizationalUnit"),
                    new Attribute("ou", "People")));

            conn.add(new Entry(GROUPS_DN,
                    new Attribute("objectClass", "organizationalUnit"),
                    new Attribute("ou", "Groups")));

            conn.add(new Entry("uid=testuser," + PEOPLE_DN,
                    new Attribute("objectClass", "inetOrgPerson"),
                    new Attribute("uid", "testuser"),
                    new Attribute("cn", "Test User"),
                    new Attribute("sn", "User")));

            conn.add(new Entry("uid=baduser," + PEOPLE_DN,
                    new Attribute("objectClass", "inetOrgPerson"),
                    new Attribute("uid", "baduser"),
                    new Attribute("cn", "Bad User"),
                    new Attribute("sn", "User")));

            conn.add(new Entry("cn=gooduser," + GROUPS_DN,
                    new Attribute("objectClass", "groupOfNames"),
                    new Attribute("cn", "gooduser"),
                    new Attribute("member", "uid=testuser," + PEOPLE_DN)));

            conn.add(new Entry("cn=badrole," + GROUPS_DN,
                    new Attribute("objectClass", "groupOfNames"),
                    new Attribute("cn", "badrole"),
                    new Attribute("member", "uid=baduser," + PEOPLE_DN)));

            log.info("LDAP directory populated: 2 users, 2 groups under {}", BASE_DN);
        }
    }
}
