package org.jboss.connectors.test.proxy;

import org.jboss.connectors.test.utils.NativePortAllocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Apache httpd with mod_jk as an AJP reverse proxy.
 * Requires mod_jk.so from a JBCS connectors ZIP, set via {@code -Dmod.jk.path}.
 */
public class HttpdModJkProxy extends AbstractHttpdProxy {

    private final Path modJkPath;

    public HttpdModJkProxy() {
        this(NativePortAllocator.HTTPD_PORT);
    }

    public HttpdModJkProxy(int listenPort) {
        super(listenPort, "httpd-modjk-proxy");
        String path = System.getProperty("mod.jk.path");
        if (path == null) {
            throw new IllegalStateException("Set -Dmod.jk.path to the mod_jk.so location");
        }
        this.modJkPath = Path.of(path);
        if (!modJkPath.toFile().exists()) {
            throw new IllegalStateException("mod_jk.so not found at: " + modJkPath);
        }
    }

    @Override
    protected StringBuilder buildBaseConfig(String workerHost, int workerAjpPort) {
        StringBuilder conf = buildCommonConfig();

        loadModules(conf,
                "mpm_event_module modules/mod_mpm_event.so",
                "unixd_module modules/mod_unixd.so",
                "authz_core_module modules/mod_authz_core.so",
                "authn_file_module modules/mod_authn_file.so",
                "authn_core_module modules/mod_authn_core.so",
                "authz_user_module modules/mod_authz_user.so",
                "auth_basic_module modules/mod_auth_basic.so",
                "log_config_module modules/mod_log_config.so");

        conf.append("LoadModule jk_module \"").append(modJkPath.toAbsolutePath()).append("\"\n\n");

        try {
            writeWorkersProperties(workerHost, workerAjpPort);
            writeUriWorkerMap();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write mod_jk config files", e);
        }

        conf.append("JkWorkersFile \"").append(confDir.resolve("workers.properties").toAbsolutePath()).append("\"\n");
        conf.append("JkLogFile \"").append(workDir.resolve("mod_jk.log").toAbsolutePath()).append("\"\n");
        conf.append("JkLogLevel info\n");
        conf.append("JkMount /secured/* worker1\n\n");

        return conf;
    }

    private void writeWorkersProperties(String workerHost, int workerAjpPort) throws IOException {
        // mod_jk resolves "localhost" to IPv6 (::1) on dual-stack systems,
        // but WildFly binds to 0.0.0.0 (IPv4 only). Force IPv4.
        String host = "localhost".equals(workerHost) ? "127.0.0.1" : workerHost;
        String content = "worker.list=worker1\n" +
                "worker.worker1.type=ajp13\n" +
                "worker.worker1.host=" + host + "\n" +
                "worker.worker1.port=" + workerAjpPort + "\n";
        Files.writeString(confDir.resolve("workers.properties"), content);
    }

    private void writeUriWorkerMap() throws IOException {
        Files.writeString(confDir.resolve("uriworkermap.properties"), "/secured/*=worker1\n");
    }
}
