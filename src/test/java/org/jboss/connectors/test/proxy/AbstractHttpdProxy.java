package org.jboss.connectors.test.proxy;

import org.jboss.connectors.test.utils.CommandResult;
import org.jboss.connectors.test.utils.NativePortAllocator;
import org.jboss.connectors.test.utils.NativeProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for httpd-based AJP proxy implementations.
 * Provides shared httpd process management, binary discovery, and auth configuration.
 */
abstract class AbstractHttpdProxy implements AjpProxy {

    private static final Logger log = LoggerFactory.getLogger(AbstractHttpdProxy.class);

    protected final int listenPort;
    protected final Path workDir;
    protected Path confDir;

    private NativeProcessManager processManager;

    protected AbstractHttpdProxy(int listenPort, String workDirName) {
        this.listenPort = listenPort;
        this.workDir = Path.of("target", workDirName).toAbsolutePath();
    }

    protected abstract StringBuilder buildBaseConfig(String workerHost, int workerAjpPort);

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
        log.info("Configured {} with Basic auth for user '{}' -> {}:{}",
                getClass().getSimpleName(), username, workerHost, workerAjpPort);
    }

    @Override
    public void configureNoAuth(String workerHost, int workerAjpPort) throws Exception {
        prepareWorkDir();

        StringBuilder conf = buildBaseConfig(workerHost, workerAjpPort);
        writeConfig(conf.toString());
        log.info("Configured {} without auth -> {}:{}",
                getClass().getSimpleName(), workerHost, workerAjpPort);
    }

    @Override
    public void start() throws Exception {
        if (processManager != null && processManager.isRunning()) {
            throw new IllegalStateException("httpd proxy is already running");
        }

        String httpdBin = findHttpdBinary();
        Path configFile = confDir.resolve("httpd.conf");

        Map<String, String> env = new HashMap<>();
        String httpdHome = System.getProperty("httpd.home");
        if (httpdHome != null) {
            Path libDir = Path.of(httpdHome, "lib");
            if (libDir.toFile().isDirectory()) {
                String existing = System.getenv("LD_LIBRARY_PATH");
                String ldPath = libDir.toAbsolutePath().toString();
                if (existing != null && !existing.isEmpty()) {
                    ldPath = ldPath + ":" + existing;
                }
                env.put("LD_LIBRARY_PATH", ldPath);
            }
        }

        processManager = new NativeProcessManager("httpd-proxy",
                List.of(httpdBin, "-f", configFile.toAbsolutePath().toString(), "-DFOREGROUND"),
                workDir, env);
        processManager.start();

        Thread.sleep(1000);
        if (!processManager.isRunning()) {
            String output = processManager.readOutputLog();
            throw new RuntimeException("httpd failed to start. Output:\n" + output);
        }

        log.info("{} started on port {}", getClass().getSimpleName(), listenPort);
    }

    @Override
    public void stop() {
        if (processManager != null) {
            processManager.stop();
            processManager = null;
            log.info("{} stopped", getClass().getSimpleName());
        }
    }

    @Override
    public String getHttpUrl() {
        return "http://localhost:" + listenPort;
    }

    protected StringBuilder buildCommonConfig() {
        StringBuilder conf = new StringBuilder();
        conf.append("ServerRoot \"").append(workDir.toAbsolutePath()).append("\"\n");
        conf.append("ServerName localhost\n");
        conf.append("Listen ").append(listenPort).append("\n");
        conf.append("PidFile \"").append(workDir.resolve("httpd.pid").toAbsolutePath()).append("\"\n");
        conf.append("ErrorLog \"").append(workDir.resolve("error.log").toAbsolutePath()).append("\"\n");
        conf.append("LogLevel info\n\n");
        return conf;
    }

    protected void loadModules(StringBuilder conf, String... modules) {
        String modulesDir = findModulesDir();
        for (String mod : modules) {
            conf.append("LoadModule ").append(mod.replace("modules/", modulesDir + "/")).append("\n");
        }
        conf.append("\n");
    }

    protected void prepareWorkDir() throws IOException {
        confDir = workDir.resolve("conf");
        Files.createDirectories(confDir);
        Files.createDirectories(workDir.resolve("logs"));
    }

    protected void writeConfig(String config) throws IOException {
        Files.writeString(confDir.resolve("httpd.conf"), config);
    }

    protected void createHtpasswd(Path htpasswdFile, String username, String password) throws Exception {
        String htpasswdBin = findHtpasswdBinary();
        CommandResult result = NativeProcessManager.execCommand(workDir,
                htpasswdBin, "-cb", htpasswdFile.toAbsolutePath().toString(), username, password);
        if (!result.isSuccess()) {
            throw new RuntimeException("htpasswd failed: " + result.getStderr());
        }
    }

    static String findHttpdBinary() {
        String httpdHome = System.getProperty("httpd.home");
        if (httpdHome != null) {
            Path bin = Path.of(httpdHome, "sbin", "httpd");
            if (bin.toFile().exists()) {
                return bin.toAbsolutePath().toString();
            }
        }
        for (String candidate : new String[]{"/usr/sbin/httpd", "/usr/sbin/apache2",
                "/usr/local/apache2/bin/httpd", "httpd", "apache2"}) {
            if (Path.of(candidate).toFile().exists() || isOnPath(candidate)) {
                return candidate;
            }
        }
        throw new RuntimeException("httpd not found. Set -Dhttpd.home or install httpd.");
    }

    static String findHtpasswdBinary() {
        String httpdHome = System.getProperty("httpd.home");
        if (httpdHome != null) {
            Path bin = Path.of(httpdHome, "sbin", "htpasswd");
            if (bin.toFile().exists()) {
                return bin.toAbsolutePath().toString();
            }
        }
        for (String candidate : new String[]{"/usr/bin/htpasswd", "/usr/local/apache2/bin/htpasswd",
                "htpasswd"}) {
            if (Path.of(candidate).toFile().exists() || isOnPath(candidate)) {
                return candidate;
            }
        }
        throw new RuntimeException("htpasswd not found. Set -Dhttpd.home or install httpd-tools.");
    }

    static String findModulesDir() {
        String httpdHome = System.getProperty("httpd.home");
        if (httpdHome != null) {
            Path modules = Path.of(httpdHome, "modules");
            if (modules.toFile().isDirectory()) {
                return modules.toAbsolutePath().toString();
            }
        }
        for (String candidate : new String[]{"/usr/lib64/httpd/modules", "/usr/lib/apache2/modules",
                "/usr/local/apache2/modules"}) {
            if (Path.of(candidate).toFile().isDirectory()) {
                return candidate;
            }
        }
        return "/usr/lib64/httpd/modules";
    }

    static boolean isOnPath(String binary) {
        try {
            Process p = new ProcessBuilder("which", binary)
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
