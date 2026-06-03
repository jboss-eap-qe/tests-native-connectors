package org.jboss.connectors.test.proxy;

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
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            String dllPath = System.getProperty("isapi.redirect.dll.path");
            if (dllPath == null) {
                throw new IllegalStateException(
                        "Set -Disapi.redirect.dll.path to the isapi_redirect.dll location");
            }
            return new IISIsapiProxy(java.nio.file.Path.of(dllPath));
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
     */
    void configureAuth(String username, String password, String workerHost, int workerAjpPort) throws Exception;

    /**
     * Configure the proxy without authentication — no REMOTE_USER in AJP.
     *
     * @param workerHost the WildFly AJP listener host
     * @param workerAjpPort the WildFly AJP listener port
     */
    void configureNoAuth(String workerHost, int workerAjpPort) throws Exception;

    String getHttpUrl();
}
