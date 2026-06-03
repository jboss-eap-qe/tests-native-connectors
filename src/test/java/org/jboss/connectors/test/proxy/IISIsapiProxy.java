package org.jboss.connectors.test.proxy;

import org.jboss.connectors.test.utils.CommandResult;
import org.jboss.connectors.test.utils.NativeProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * IIS with isapi_redirect as an AJP reverse proxy.
 * Uses the system-installed IIS with isapi_redirect.dll from JBCS.
 *
 * <p>Setup sequence validated on Windows Server 2022:
 * <ol>
 *   <li>Unlock IIS config sections (handlers, windowsAuthentication, anonymousAuthentication)</li>
 *   <li>Switch DefaultAppPool to Classic pipeline mode (required for ISAPI filters)</li>
 *   <li>Create virtual directory /jakarta pointing to the DLL directory</li>
 *   <li>Register ISAPI filter globally</li>
 *   <li>Allow the DLL in ISAPI CGI restrictions</li>
 *   <li>Set handler access policy to include Execute</li>
 *   <li>Set registry keys for isapi_redirect configuration</li>
 *   <li>Configure Windows Authentication on the site</li>
 *   <li>Create local Windows user for testing</li>
 *   <li>Restart IIS</li>
 * </ol>
 */
public class IISIsapiProxy implements AjpProxy {

    private static final Logger log = LoggerFactory.getLogger(IISIsapiProxy.class);

    private static final String APPCMD = "C:\\Windows\\System32\\inetsrv\\appcmd.exe";

    private final Path isapiDir;
    private final Path dllPath;

    private String testUsername;
    private boolean started;

    /**
     * @param isapiredirectDllPath path to isapi_redirect.dll (from JBCS ZIP)
     */
    public IISIsapiProxy(Path isapiredirectDllPath) {
        this.dllPath = isapiredirectDllPath;
        this.isapiDir = Path.of("C:\\isapi");
    }

    @Override
    public void configureAuth(String username, String password, String workerHost, int workerAjpPort) throws Exception {
        this.testUsername = username;
        prepareIsapiDir(workerHost, workerAjpPort);

        createWindowsUser(username, password);

        setupIIS(true);
        log.info("Configured IIS ISAPI proxy with Windows auth for user '{}' -> {}:{}", username, workerHost, workerAjpPort);
    }

    @Override
    public void configureNoAuth(String workerHost, int workerAjpPort) throws Exception {
        prepareIsapiDir(workerHost, workerAjpPort);

        setupIIS(false);
        log.info("Configured IIS ISAPI proxy without auth -> {}:{}", workerHost, workerAjpPort);
    }

    @Override
    public void start() throws Exception {
        if (started) {
            throw new IllegalStateException("IIS proxy is already running");
        }

        execOrFail("iisreset", "/restart");
        started = true;
        log.info("IIS ISAPI proxy started on port 80");
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }

        try {
            archiveConfigs();
        } catch (Exception e) {
            log.warn("Failed to archive IIS configs: {}", e.getMessage());
        }

        try {
            cleanup();
        } catch (Exception e) {
            log.warn("Error during IIS cleanup: {}", e.getMessage());
        }

        started = false;
        log.info("IIS ISAPI proxy stopped and cleaned up");
    }

    @Override
    public String getHttpUrl() {
        return "http://localhost";
    }

    private void prepareIsapiDir(String workerHost, int workerAjpPort) throws IOException {
        Files.createDirectories(isapiDir);
        Files.copy(dllPath, isapiDir.resolve("isapi_redirect.dll"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Files.writeString(isapiDir.resolve("workers.properties"),
                "worker.list=worker1\n" +
                "worker.worker1.type=ajp13\n" +
                "worker.worker1.host=" + workerHost + "\n" +
                "worker.worker1.port=" + workerAjpPort + "\n");

        Files.writeString(isapiDir.resolve("uriworkermap.properties"),
                "/secured/*=worker1\n");

        log.info("ISAPI config written to {}", isapiDir);
    }

    private void setupIIS(boolean enableAuth) throws Exception {
        // Unlock config sections
        appcmd("unlock", "config", "/section:handlers");
        appcmd("unlock", "config", "/section:windowsAuthentication");
        appcmd("unlock", "config", "/section:anonymousAuthentication");
        appcmd("unlock", "config", "/section:basicAuthentication");

        // Classic pipeline mode required for ISAPI filters
        appcmd("set", "apppool", "DefaultAppPool", "/managedPipelineMode:Classic");

        // Virtual directory for the DLL extension URI
        appcmd("add", "vdir", "/app.name:Default Web Site/",
                "/path:/jakarta", "/physicalPath:" + isapiDir);

        // Register ISAPI filter
        appcmd("set", "config", "/section:isapiFilters",
                "/+[name='isapi_redirect',path='" + isapiDir.resolve("isapi_redirect.dll") + "',enabled='true']");

        // Allow DLL in ISAPI CGI restrictions
        appcmd("set", "config", "/section:isapiCgiRestriction",
                "/+[path='" + isapiDir.resolve("isapi_redirect.dll") + "',description='isapi_redirect',allowed='true']");

        // Handler mapping for executing DLLs
        try {
            appcmd("set", "config", "Default Web Site", "/section:handlers",
                    "/+[name='ISAPI-dll',path='*.dll',verb='*',modules='IsapiModule',resourceType='File',requireAccess='Execute']");
        } catch (RuntimeException e) {
            if (!e.getMessage().contains("duplicate")) {
                throw e;
            }
        }

        // Grant Execute in handler access policy
        appcmd("set", "config", "Default Web Site",
                "/section:handlers", "/accessPolicy:Read,Script,Execute");

        // Grant filesystem permissions
        exec("icacls", isapiDir.toString(), "/grant", "IIS_IUSRS:(OI)(CI)RX", "/grant", "IUSR:(OI)(CI)RX");

        // Registry keys for isapi_redirect configuration
        String regKey = "HKLM\\SOFTWARE\\Apache Software Foundation\\Jakarta Isapi Redirector\\1.0";
        regAdd(regKey, "extension_uri", "/jakarta/isapi_redirect.dll");
        regAdd(regKey, "log_file", isapiDir.resolve("isapi_redirect.log").toString());
        regAdd(regKey, "log_level", "info");
        regAdd(regKey, "worker_file", isapiDir.resolve("workers.properties").toString());
        regAdd(regKey, "worker_mount_file", isapiDir.resolve("uriworkermap.properties").toString());

        // Authentication — use Basic auth (programmatically testable, same HTTP flow as httpd)
        if (enableAuth) {
            appcmd("set", "config", "Default Web Site",
                    "/section:basicAuthentication", "/enabled:true");
            appcmd("set", "config", "Default Web Site",
                    "/section:anonymousAuthentication", "/enabled:false");
        }
    }

    private void archiveConfigs() throws IOException {
        Path archiveDir = Path.of("target", "iis-config");
        Files.createDirectories(archiveDir);
        for (String name : new String[]{"workers.properties", "uriworkermap.properties",
                "isapi_redirect.properties", "isapi_redirect.log"}) {
            Path src = isapiDir.resolve(name);
            if (Files.exists(src)) {
                Files.copy(src, archiveDir.resolve(name),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // Export IIS site config
        try {
            CommandResult result = exec(APPCMD, "list", "config", "Default Web Site");
            Files.writeString(archiveDir.resolve("iis-site-config.xml"), result.getStdout());
        } catch (Exception e) {
            log.debug("Could not export IIS config: {}", e.getMessage());
        }
        log.info("IIS configs archived to {}", archiveDir);
    }

    private void cleanup() throws Exception {
        appcmdIgnoreErrors("set", "apppool", "DefaultAppPool", "/managedPipelineMode:Integrated");
        appcmdIgnoreErrors("set", "config", "/section:isapiFilters",
                "/-[name='isapi_redirect']");
        appcmdIgnoreErrors("set", "config", "/section:isapiCgiRestriction",
                "/-[path='" + isapiDir.resolve("isapi_redirect.dll") + "']");
        appcmdIgnoreErrors("delete", "vdir", "Default Web Site/jakarta");
        appcmdIgnoreErrors("set", "config", "Default Web Site",
                "/section:handlers", "/-[name='ISAPI-dll']");
        appcmdIgnoreErrors("set", "config", "Default Web Site",
                "/section:basicAuthentication", "/enabled:false");
        appcmdIgnoreErrors("set", "config", "Default Web Site",
                "/section:anonymousAuthentication", "/enabled:true");

        if (testUsername != null) {
            exec("net", "user", testUsername, "/delete");
        }

        exec("reg", "delete", "HKLM\\SOFTWARE\\Apache Software Foundation", "/f");

        execOrFail("iisreset", "/restart");
    }

    private void createWindowsUser(String username, String password) throws Exception {
        exec("net", "user", username, password, "/add");
    }

    private void appcmd(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = APPCMD;
        System.arraycopy(args, 0, cmd, 1, args.length);
        CommandResult result = NativeProcessManager.execCommand(Path.of("."), cmd);
        if (!result.isSuccess()) {
            throw new RuntimeException("appcmd failed: " + result.getStdout() + " " + result.getStderr());
        }
        log.debug("appcmd: {}", result.getStdout().trim());
    }

    private void appcmdIgnoreErrors(String... args) {
        try {
            appcmd(args);
        } catch (Exception e) {
            log.debug("appcmd (ignored): {}", e.getMessage());
        }
    }

    private void regAdd(String key, String name, String value) throws Exception {
        execOrFail("reg", "add", key, "/v", name, "/t", "REG_SZ", "/d", value, "/f");
    }

    private CommandResult exec(String... command) throws Exception {
        return NativeProcessManager.execCommand(Path.of("."), command);
    }

    private void execOrFail(String... command) throws Exception {
        CommandResult result = exec(command);
        if (!result.isSuccess()) {
            throw new RuntimeException("Command failed: " + String.join(" ", command)
                    + " — " + result.getStdout() + " " + result.getStderr());
        }
    }
}
