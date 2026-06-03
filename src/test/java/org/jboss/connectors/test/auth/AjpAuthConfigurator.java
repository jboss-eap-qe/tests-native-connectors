package org.jboss.connectors.test.auth;

import org.jboss.connectors.test.utils.WildFlyWorker;
import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

/**
 * Configures Elytron EXTERNAL mechanism authentication on WildFly workers
 * and adds an AJP listener for proxy connections.
 */
public class AjpAuthConfigurator {

    private static final Logger log = LoggerFactory.getLogger(AjpAuthConfigurator.class);

    private static final String REALM_NAME = "ajp-auth-realm";
    private static final String ROLE_DECODER_NAME = "ajp-role-decoder";
    private static final String SECURITY_DOMAIN_NAME = "ajp-auth-sd";
    private static final String AUTH_FACTORY_NAME = "ajp-auth-factory";
    private static final String APP_SECURITY_DOMAIN = "ajp-auth-domain";

    /**
     * Configure Elytron EXTERNAL mechanism on a worker.
     */
    public void configureElytron(WildFlyWorker worker, UserEntry... users) throws Exception {
        Operations ops = worker.getOperations();

        Address realmAddr = Address.subsystem("elytron").and("filesystem-realm", REALM_NAME);
        if (!ops.exists(realmAddr)) {
            ops.add(realmAddr, Values.of("path", "ajp-auth-users")
                    .and("relative-to", "jboss.server.config.dir")).assertSuccess();
        }

        for (UserEntry user : users) {
            ModelNodeResult result = ops.invoke("add-identity", realmAddr,
                    Values.of("identity", user.username));
            if (result.isSuccess()) {
                ops.invoke("add-identity-attribute", realmAddr,
                        Values.of("identity", user.username)
                                .and("name", "Roles")
                                .andList("value", user.role)).assertSuccess();
                log.info("Added user '{}' with role '{}' to realm", user.username, user.role);
            } else {
                log.info("User '{}' already exists in realm, skipping", user.username);
            }
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
        log.info("Elytron EXTERNAL mechanism configured on worker '{}'", worker.getName());
    }

    /** @see AjpListenerSetup#addAjpListener(WildFlyWorker) */
    public int addAjpListener(WildFlyWorker worker) throws Exception {
        return AjpListenerSetup.addAjpListener(worker);
    }

    /** A username-to-role mapping for the Elytron filesystem realm. */
    public static class UserEntry {
        final String username;
        final String role;

        public UserEntry(String username, String role) {
            this.username = username;
            this.role = role;
        }
    }
}
