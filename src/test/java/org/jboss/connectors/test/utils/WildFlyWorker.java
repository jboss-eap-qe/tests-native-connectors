package org.jboss.connectors.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.commands.deployments.Deploy;
import org.wildfly.extras.creaper.commands.deployments.Undeploy;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native WildFly worker for connector testing.
 * Runs WildFly as a local OS process, no Docker.
 */
public class WildFlyWorker {

    private static final Logger log = LoggerFactory.getLogger(WildFlyWorker.class);

    private static final String STARTUP_LOG_PATTERN = "WFLYSRV0025";

    private final String name;
    private String javaOpts;
    private Path serverHome;
    private NativeProcessManager processManager;
    private OnlineManagementClient managementClient;

    public WildFlyWorker(String name) {
        this.name = name;
    }

    public WildFlyWorker withJavaOpts(String javaOpts) {
        this.javaOpts = javaOpts;
        return this;
    }

    public void start() {
        try {
            serverHome = NativeServerExtractor.extract(name);
            resetServerState();

            List<String> command = buildStartCommand();
            Map<String, String> env = buildEnvironment();

            processManager = new NativeProcessManager(name, command, serverHome, env);
            processManager.start();
            processManager.waitForStartup(STARTUP_LOG_PATTERN, TestTimeouts.WORKER_STARTUP);

            log.info("WildFly worker '{}' started at {}", name, serverHome);
        } catch (Exception e) {
            if (processManager != null && processManager.isRunning()) {
                log.warn("Killing WildFly process for '{}' after startup failure", name);
                processManager.kill();
                processManager = null;
            }
            throw new RuntimeException("Failed to start native WildFly worker '" + name + "'", e);
        }
    }

    public void stop() {
        closeManagementClient();
        if (processManager != null) {
            processManager.stop();
            processManager = null;
        }
        log.info("WildFly worker '{}' stopped", name);
    }

    public boolean isRunning() {
        return processManager != null && processManager.isRunning();
    }

    public String getName() {
        return name;
    }

    public String getHttpUrl() {
        return "http://localhost:" + NativePortAllocator.httpPort(name);
    }

    public String getManagementUrl() {
        return "http://localhost:" + NativePortAllocator.managementPort(name);
    }

    public int getManagementPort() {
        return NativePortAllocator.managementPort(name);
    }

    public Path getServerHome() {
        return serverHome;
    }

    // -- Management client --

    public OnlineManagementClient getManagementClient() throws IOException {
        if (managementClient == null) {
            managementClient = ManagementClientFactory.create(
                    "localhost", NativePortAllocator.managementPort(name));
        }
        return managementClient;
    }

    public Operations getOperations() throws IOException {
        return new Operations(getManagementClient());
    }

    public Administration getAdministration() throws IOException {
        return new Administration(getManagementClient());
    }

    public void reload() throws Exception {
        log.info("Reloading worker '{}'", name);
        if (managementClient != null) {
            try {
                managementClient.close();
            } catch (IOException ignored) {
            }
            managementClient = null;
        }

        try {
            getAdministration().reload();
        } catch (Exception e) {
            if (e instanceof java.util.concurrent.TimeoutException
                    || e.getCause() instanceof java.util.concurrent.TimeoutException
                    || (e.getMessage() != null && e.getMessage().contains("Waiting for server timed out"))) {
                log.warn("Reload timed out for '{}', waiting with fresh connection", name);
                managementClient = null;
                getAdministration().waitUntilRunning();
            } else {
                throw e;
            }
        }
        log.info("Worker '{}' reloaded", name);
    }

    // -- Deployment --

    public void deploy(File deploymentFile) throws Exception {
        log.info("Deploying {} to worker '{}'", deploymentFile.getName(), name);
        getManagementClient().apply(new Deploy.Builder(deploymentFile).build());
        log.info("Deployment {} succeeded on worker '{}'", deploymentFile.getName(), name);
    }

    public void undeploy(String deploymentName) throws Exception {
        log.info("Undeploying {} from worker '{}'", deploymentName, name);
        getManagementClient().apply(new Undeploy.Builder(deploymentName).build());
    }

    public boolean isDeployed(String deploymentName) throws Exception {
        Operations ops = getOperations();
        Address deploymentAddress = Address.deployment(deploymentName);
        if (!ops.exists(deploymentAddress)) {
            return false;
        }
        ModelNodeResult result = ops.readAttribute(deploymentAddress, "enabled");
        result.assertSuccess();
        return result.value().asBoolean();
    }

    // -- Command execution and file ops --

    public CommandResult execCommand(String... command) throws Exception {
        return NativeProcessManager.execCommand(serverHome, command);
    }

    public void copyClasspathResource(String classpathResource, String destPath) {
        try {
            Path dest = Path.of(destPath);
            if (!dest.isAbsolute()) {
                dest = serverHome.resolve(destPath);
            }
            Files.createDirectories(dest.getParent());

            URL resource = Thread.currentThread().getContextClassLoader().getResource(classpathResource);
            if (resource == null) {
                throw new RuntimeException("Classpath resource not found: " + classpathResource);
            }

            try (InputStream is = resource.openStream()) {
                Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy classpath resource '" + classpathResource
                    + "' to '" + destPath + "'", e);
        }
    }

    public String readFile(String path) throws Exception {
        Path filePath = Path.of(path);
        if (!filePath.isAbsolute()) {
            filePath = serverHome.resolve(path);
        }
        return Files.readString(filePath);
    }

    public String getServerLog() throws Exception {
        Path logPath = serverHome.resolve("standalone/log/server.log");
        if (Files.exists(logPath)) {
            return Files.readString(logPath);
        }
        return processManager != null ? processManager.readOutputLog() : "";
    }

    // -- Private --

    private void resetServerState() throws IOException {
        Path configBackup = serverHome.resolve("standalone/configuration/standalone.xml.original");
        Path config = serverHome.resolve("standalone/configuration/standalone.xml");
        if (Files.exists(configBackup)) {
            Files.copy(configBackup, config, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Restored clean standalone.xml for '{}'", name);
        }

        deleteDirectoryRecursively(serverHome.resolve("standalone/data"));
        deleteDirectoryRecursively(serverHome.resolve("standalone/tmp"));
        deleteDirectoryRecursively(serverHome.resolve("standalone/configuration/standalone_xml_history"));
    }

    private List<String> buildStartCommand() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String script = isWindows ? "standalone.bat" : "standalone.sh";
        Path scriptPath = serverHome.resolve("bin").resolve(script);

        List<String> cmd = new ArrayList<>();
        cmd.add(scriptPath.toAbsolutePath().toString());
        cmd.add("-b");
        cmd.add("0.0.0.0");
        cmd.add("-bmanagement");
        cmd.add("0.0.0.0");
        cmd.add("-Djboss.node.name=" + name);
        cmd.add("-Djboss.socket.binding.port-offset=" + NativePortAllocator.offset(name));

        return cmd;
    }

    private Map<String, String> buildEnvironment() {
        Map<String, String> env = new HashMap<>();
        if (javaOpts != null) {
            env.put("JAVA_OPTS", javaOpts);
        }
        return env;
    }

    private void closeManagementClient() {
        if (managementClient != null) {
            try {
                managementClient.close();
            } catch (IOException e) {
                log.warn("Error closing management client for worker '{}'", name, e);
            }
            managementClient = null;
        }
    }

    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        log.warn("Failed to delete {}: {}", p, e.getMessage());
                    }
                });
        }
    }
}
