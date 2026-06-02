package org.jboss.connectors.test.auth;

import org.jboss.connectors.test.utils.WildFlyWorker;
import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.commands.socketbindings.AddSocketBinding;
import org.wildfly.extras.creaper.commands.undertow.AddUndertowListener;
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

    private static final int AJP_PORT = 8019;
    private static final String AJP_SOCKET_BINDING = "ajp-test";
    private static final String AJP_LISTENER = "ajp-test-listener";

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

    /**
     * Add an AJP listener to the worker.
     *
     * @return the AJP port (including port offset)
     */
    public int addAjpListener(WildFlyWorker worker) throws Exception {
        Operations ops = worker.getOperations();

        Address sbAddr = Address.of("socket-binding-group", "standard-sockets")
                .and("socket-binding", AJP_SOCKET_BINDING);
        if (!ops.exists(sbAddr)) {
            worker.getManagementClient().apply(
                    new AddSocketBinding.Builder(AJP_SOCKET_BINDING).port(AJP_PORT).build());
        }

        Address listenerAddr = Address.subsystem("undertow")
                .and("server", "default-server")
                .and("ajp-listener", AJP_LISTENER);
        if (!ops.exists(listenerAddr)) {
            worker.getManagementClient().apply(
                    new AddUndertowListener.AjpBuilder(AJP_LISTENER, "default-server", AJP_SOCKET_BINDING).build());
            worker.reload();
        }

        int ajpPort = AJP_PORT + org.jboss.connectors.test.utils.NativePortAllocator.offset(worker.getName());
        log.info("AJP listener on port {} ready", ajpPort);
        return ajpPort;
    }

    public static class UserEntry {
        final String username;
        final String role;

        public UserEntry(String username, String role) {
            this.username = username;
            this.role = role;
        }
    }
}
