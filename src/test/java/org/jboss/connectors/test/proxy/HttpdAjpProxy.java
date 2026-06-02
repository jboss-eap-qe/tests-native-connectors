package org.jboss.connectors.test.proxy;

import org.jboss.connectors.test.utils.CommandResult;
import org.jboss.connectors.test.utils.NativePortAllocator;
import org.jboss.connectors.test.utils.NativeProcessManager;
import org.jboss.connectors.test.utils.TestTimeouts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Apache httpd with mod_proxy_ajp as an AJP reverse proxy.
 * Uses the system-installed httpd with a generated configuration.
 */
public class HttpdAjpProxy implements AjpProxy {

    private static final Logger log = LoggerFactory.getLogger(HttpdAjpProxy.class);

    private final int listenPort;
    private final Path workDir;

    private NativeProcessManager processManager;
    private Path confDir;

    public HttpdAjpProxy() {
        this(NativePortAllocator.HTTPD_PORT);
    }

    public HttpdAjpProxy(int listenPort) {
        this.listenPort = listenPort;
        this.workDir = Path.of("target", "httpd-proxy").toAbsolutePath();
    }

    @Override
    public void configureAuth(String username, String password, String workerHost, int workerAjpPort) throws Exception {
        prepareWorkDir();

        Path htpasswdFile = confDir.resolve("test-users.htpasswd");
        createHtpasswd(htpasswdFile, username, password);

        StringBuilder conf = buildBaseConfig(workerHost, workerAjpPort);
        conf.append("<Location /secured>\n");
        conf.append("    AuthType Basic\n");
        conf.append("    AuthName \"Test\"\n");
        conf.append("    AuthBasicProvider file\n");
        conf.append("    AuthUserFile \"").append(htpasswdFile.toAbsolutePath()).append("\"\n");
        conf.append("    Require valid-user\n");
        conf.append("</Location>\n");

        writeConfig(conf.toString());
        log.info("Configured httpd AJP proxy with Basic auth for user '{}' -> {}:{}", username, workerHost, workerAjpPort);
    }

    @Override
    public void configureNoAuth(String workerHost, int workerAjpPort) throws Exception {
        prepareWorkDir();

        StringBuilder conf = buildBaseConfig(workerHost, workerAjpPort);
        writeConfig(conf.toString());
        log.info("Configured httpd AJP proxy without auth -> {}:{}", workerHost, workerAjpPort);
    }

    @Override
    public void start() throws Exception {
        if (processManager != null && processManager.isRunning()) {
            throw new IllegalStateException("httpd proxy is already running");
        }

        String httpdBin = findHttpdBinary();
        Path configFile = confDir.resolve("httpd.conf");

        processManager = new NativeProcessManager("httpd-proxy",
                List.of(httpdBin, "-f", configFile.toAbsolutePath().toString(), "-DFOREGROUND"),
                workDir, Map.of());
        processManager.start();

        // httpd doesn't emit a clear startup message — wait briefly and check it's alive
        Thread.sleep(1000);
        if (!processManager.isRunning()) {
            String output = processManager.readOutputLog();
            throw new RuntimeException("httpd failed to start. Output:\n" + output);
        }

        log.info("httpd AJP proxy started on port {}", listenPort);
    }

    @Override
    public void stop() {
        if (processManager != null) {
            processManager.stop();
            processManager = null;
            log.info("httpd AJP proxy stopped");
        }
    }

    @Override
    public String getHttpUrl() {
        return "http://localhost:" + listenPort;
    }

    private StringBuilder buildBaseConfig(String workerHost, int workerAjpPort) {
        StringBuilder conf = new StringBuilder();

        conf.append("ServerRoot \"").append(workDir.toAbsolutePath()).append("\"\n");
        conf.append("ServerName localhost\n");
        conf.append("Listen ").append(listenPort).append("\n");
        conf.append("PidFile \"").append(workDir.resolve("httpd.pid").toAbsolutePath()).append("\"\n");
        conf.append("ErrorLog \"").append(workDir.resolve("error.log").toAbsolutePath()).append("\"\n");
        conf.append("LogLevel info\n\n");

        // Load required modules
        String modulesDir = findModulesDir();
        for (String mod : new String[]{
                "mpm_event_module modules/mod_mpm_event.so",
                "unixd_module modules/mod_unixd.so",
                "authz_core_module modules/mod_authz_core.so",
                "proxy_module modules/mod_proxy.so",
                "proxy_ajp_module modules/mod_proxy_ajp.so",
                "authn_file_module modules/mod_authn_file.so",
                "authn_core_module modules/mod_authn_core.so",
                "authz_user_module modules/mod_authz_user.so",
                "auth_basic_module modules/mod_auth_basic.so",
                "log_config_module modules/mod_log_config.so"
        }) {
            conf.append("LoadModule ").append(mod.replace("modules/", modulesDir + "/")).append("\n");
        }

        conf.append("\n");
        conf.append("ProxyPass /secured/ ajp://").append(workerHost).append(":").append(workerAjpPort).append("/secured/\n");
        conf.append("ProxyPassReverse /secured/ ajp://").append(workerHost).append(":").append(workerAjpPort).append("/secured/\n\n");

        return conf;
    }

    private void prepareWorkDir() throws IOException {
        confDir = workDir.resolve("conf");
        Files.createDirectories(confDir);
        Files.createDirectories(workDir.resolve("logs"));
    }

    private void writeConfig(String config) throws IOException {
        Files.writeString(confDir.resolve("httpd.conf"), config);
    }

    private void createHtpasswd(Path htpasswdFile, String username, String password) throws Exception {
        String htpasswdBin = findHtpasswdBinary();
        CommandResult result = NativeProcessManager.execCommand(workDir,
                htpasswdBin, "-cb", htpasswdFile.toAbsolutePath().toString(), username, password);
        if (!result.isSuccess()) {
            throw new RuntimeException("htpasswd failed: " + result.getStderr());
        }
    }

    private static String findHttpdBinary() {
        for (String candidate : new String[]{"/usr/sbin/httpd", "/usr/sbin/apache2",
                "/usr/local/apache2/bin/httpd", "httpd", "apache2"}) {
            if (Path.of(candidate).toFile().exists() || isOnPath(candidate)) {
                return candidate;
            }
        }
        throw new RuntimeException("httpd/apache2 binary not found. Install httpd or apache2.");
    }

    private static String findHtpasswdBinary() {
        for (String candidate : new String[]{"/usr/bin/htpasswd", "/usr/local/apache2/bin/htpasswd",
                "htpasswd"}) {
            if (Path.of(candidate).toFile().exists() || isOnPath(candidate)) {
                return candidate;
            }
        }
        throw new RuntimeException("htpasswd binary not found. Install apache2-utils or httpd-tools.");
    }

    private static String findModulesDir() {
        for (String candidate : new String[]{"/usr/lib64/httpd/modules", "/usr/lib/apache2/modules",
                "/usr/local/apache2/modules"}) {
            if (Path.of(candidate).toFile().isDirectory()) {
                return candidate;
            }
        }
        return "/usr/lib64/httpd/modules";
    }

    private static boolean isOnPath(String binary) {
        try {
            Process p = new ProcessBuilder("which", binary)
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
