package org.jboss.connectors.test.proxy;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Abstraction for an AJP reverse proxy fronting a WildFly worker.
 * Implementations manage the proxy process lifecycle and configuration.
 */
public interface AjpProxy {

    /**
     * Create a proxy for the current platform and connector type.
     *
     * <p>Selection logic:
     * <ul>
     *   <li>Windows: IISIsapiProxy (requires {@code -Disapi.redirect.dll.path})</li>
     *   <li>Linux with {@code -Dmod.jk.path}: HttpdModJkProxy</li>
     *   <li>Linux default: HttpdAjpProxy (mod_proxy_ajp)</li>
     * </ul>
     */
    static AjpProxy create() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            String dllPath = System.getProperty("isapi.redirect.dll.path");
            if (dllPath == null) {
                throw new IllegalStateException(
                        "Set -Disapi.redirect.dll.path to the isapi_redirect.dll location");
            }
            return new IISIsapiProxy(Path.of(dllPath));
        }
        if (System.getProperty("mod.jk.path") != null) {
            return new HttpdModJkProxy();
        }
        return new HttpdAjpProxy();
    }

    void start() throws Exception;

    void stop();

    /**
     * Configure the proxy to authenticate users via Basic/Windows auth
     * and forward REMOTE_USER over AJP.
     *
     * @param username the username for the htpasswd/Windows auth entry
     * @param password the password for that user
     * @param workerHost the WildFly AJP listener host
     * @param workerAjpPort the WildFly AJP listener port
     * @param ajpSecret the AJP secret shared with the WildFly listener, or {@code null} to omit
     */
    void configureAuth(String username, String password, String workerHost, int workerAjpPort,
                        String ajpSecret) throws Exception;

    /**
     * Configure the proxy without authentication — no REMOTE_USER in AJP.
     *
     * @param workerHost the WildFly AJP listener host
     * @param workerAjpPort the WildFly AJP listener port
     * @param ajpSecret the AJP secret shared with the WildFly listener, or {@code null} to omit
     */
    void configureNoAuth(String workerHost, int workerAjpPort, String ajpSecret) throws Exception;

    /**
     * Enable CPING health checks on the AJP connection.
     * The exact mechanism depends on the proxy implementation
     * (e.g. {@code ping=} on mod_proxy_ajp, {@code ping_mode} on mod_jk/ISAPI).
     */
    AjpProxy withCping();

    String getHttpUrl();

    /** Archive proxy configuration files to the given directory for post-test debugging. */
    void archiveConfigs(Path targetDir) throws Exception;
}
