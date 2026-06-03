package org.jboss.connectors.test.auth;

import org.jboss.connectors.test.utils.EmbeddedLdapServer;
import org.jboss.connectors.test.utils.WildFlyWorker;
import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

/**
 * Configures Elytron EXTERNAL mechanism with an LDAP realm for role resolution.
 *
 * <p>Unlike {@link AjpAuthConfigurator} which uses a filesystem-realm with hardcoded
 * user entries, this configurator uses an ldap-realm backed by a directory server.
 * The LDAP realm looks up the REMOTE_USER identity and resolves roles from group
 * membership (groupOfNames with member attribute), which scales to any number of users
 * without per-user Elytron configuration.</p>
 */
public class LdapAuthConfigurator {

    private static final Logger log = LoggerFactory.getLogger(LdapAuthConfigurator.class);

    private static final String DIR_CONTEXT_NAME = "ldap-dir-context";
    private static final String REALM_NAME = "ldap-auth-realm";
    private static final String ROLE_DECODER_NAME = "ldap-role-decoder";
    private static final String SECURITY_DOMAIN_NAME = "ldap-auth-sd";
    private static final String AUTH_FACTORY_NAME = "ldap-auth-factory";
    private static final String APP_SECURITY_DOMAIN = "ajp-auth-domain";

    /**
     * Configure Elytron with LDAP realm and EXTERNAL mechanism.
     *
     * @param worker   the WildFly worker to configure
     * @param ldapPort the embedded LDAP server port
     */
    public void configureElytron(WildFlyWorker worker, int ldapPort) throws Exception {
        Operations ops = worker.getOperations();

        // dir-context pointing to embedded LDAP
        Address dirContextAddr = Address.subsystem("elytron").and("dir-context", DIR_CONTEXT_NAME);
        if (!ops.exists(dirContextAddr)) {
            ModelNode credentialRef = new ModelNode();
            credentialRef.get("clear-text").set(EmbeddedLdapServer.BIND_PASSWORD);

            ops.add(dirContextAddr, Values.of("url", "ldap://localhost:" + ldapPort)
                    .and("principal", EmbeddedLdapServer.BIND_DN)
                    .and("credential-reference", credentialRef)).assertSuccess();
        }

        // ldap-realm with identity-mapping and attribute-mapping for group → role resolution
        Address realmAddr = Address.subsystem("elytron").and("ldap-realm", REALM_NAME);
        if (!ops.exists(realmAddr)) {
            ModelNode attrMapping = new ModelNode();
            attrMapping.get("filter-base-dn").set(EmbeddedLdapServer.GROUPS_DN);
            attrMapping.get("filter").set("(member={1})");
            attrMapping.get("from").set("cn");
            attrMapping.get("to").set("Roles");

            ModelNode identityMapping = new ModelNode();
            identityMapping.get("rdn-identifier").set("uid");
            identityMapping.get("search-base-dn").set(EmbeddedLdapServer.PEOPLE_DN);
            identityMapping.get("attribute-mapping").add(attrMapping);

            ops.add(realmAddr, Values.of("dir-context", DIR_CONTEXT_NAME)
                    .and("identity-mapping", identityMapping)).assertSuccess();
        }

        Address decoderAddr = Address.subsystem("elytron").and("simple-role-decoder", ROLE_DECODER_NAME);
        if (!ops.exists(decoderAddr)) {
            ops.add(decoderAddr, Values.of("attribute", "Roles")).assertSuccess();
        }

        Address domainAddr = Address.subsystem("elytron").and("security-domain", SECURITY_DOMAIN_NAME);
        if (!ops.exists(domainAddr)) {
            ModelNode realmEntry = new ModelNode();
            realmEntry.get("realm").set(REALM_NAME);
            realmEntry.get("role-decoder").set(ROLE_DECODER_NAME);

            ops.add(domainAddr, Values.of("default-realm", REALM_NAME)
                    .and("permission-mapper", "default-permission-mapper")
                    .andList("realms", realmEntry)).assertSuccess();
        }

        Address factoryAddr = Address.subsystem("elytron")
                .and("http-authentication-factory", AUTH_FACTORY_NAME);
        if (!ops.exists(factoryAddr)) {
            ModelNode mechanismEntry = new ModelNode();
            mechanismEntry.get("mechanism-name").set("EXTERNAL");

            ops.add(factoryAddr, Values.of("security-domain", SECURITY_DOMAIN_NAME)
                    .and("http-server-mechanism-factory", "global")
                    .andList("mechanism-configurations", mechanismEntry)).assertSuccess();
        }

        Address appSecDomain = Address.subsystem("undertow")
                .and("application-security-domain", APP_SECURITY_DOMAIN);
        if (!ops.exists(appSecDomain)) {
            ops.add(appSecDomain, Values.of("http-authentication-factory", AUTH_FACTORY_NAME)).assertSuccess();
        }

        worker.reload();
        log.info("Elytron EXTERNAL + LDAP realm configured on worker '{}' (ldap port {})",
                worker.getName(), ldapPort);
    }
}
