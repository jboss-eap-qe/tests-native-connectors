package org.jboss.connectors.test.proxy;

import org.jboss.connectors.test.utils.NativePortAllocator;

/**
 * Apache httpd with mod_proxy_ajp as an AJP reverse proxy.
 */
public class HttpdAjpProxy extends AbstractHttpdProxy {

    public HttpdAjpProxy() {
        this(NativePortAllocator.HTTPD_PORT);
    }

    public HttpdAjpProxy(int listenPort) {
        super(listenPort, "httpd-proxy");
    }

    @Override
    protected StringBuilder buildBaseConfig(String workerHost, int workerAjpPort) {
        StringBuilder conf = buildCommonConfig();

        loadModules(conf,
                "mpm_event_module modules/mod_mpm_event.so",
                "unixd_module modules/mod_unixd.so",
                "authz_core_module modules/mod_authz_core.so",
                "proxy_module modules/mod_proxy.so",
                "proxy_ajp_module modules/mod_proxy_ajp.so",
                "authn_file_module modules/mod_authn_file.so",
                "authn_core_module modules/mod_authn_core.so",
                "authz_user_module modules/mod_authz_user.so",
                "auth_basic_module modules/mod_auth_basic.so",
                "log_config_module modules/mod_log_config.so");

        conf.append("ProxyPass /secured/ ajp://").append(workerHost).append(":").append(workerAjpPort).append("/secured/\n");
        conf.append("ProxyPassReverse /secured/ ajp://").append(workerHost).append(":").append(workerAjpPort).append("/secured/\n\n");

        return conf;
    }
}
