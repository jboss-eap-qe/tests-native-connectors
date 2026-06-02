package org.jboss.connectors.test.utils;

import org.wildfly.extras.creaper.core.ManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineOptions;

import java.io.IOException;

public class ManagementClientFactory {

    public static OnlineManagementClient create(String host, int port) throws IOException {
        return ManagementClient.online(
            OnlineOptions.standalone()
                .hostAndPort(host, port)
                .auth("admin", "admin")
                .connectionTimeout(TestTimeouts.CONNECTION_TIMEOUT_MS)
                .bootTimeout(TestTimeouts.BOOT_TIMEOUT_MS)
                .build()
        );
    }
}
