package org.jboss.connectors.test.auth;

import org.jboss.connectors.test.utils.HttpClient;
import org.jboss.connectors.test.utils.HttpClient.HttpResponse;
import org.jboss.connectors.test.utils.TestTimeouts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Shared helpers for authentication propagation tests.
 */
final class AuthTestUtils {

    private static final Logger log = LoggerFactory.getLogger(AuthTestUtils.class);

    private AuthTestUtils() {
    }

    /**
     * Poll the AJP proxy URL until it responds with a non-5xx status,
     * indicating the proxy and backend are connected and ready.
     */
    static void awaitAjpAvailable(HttpClient httpClient, String url, Map<String, String> headers) {
        await().atMost(TestTimeouts.AJP_AVAILABLE)
                .pollInterval(ofSeconds(2))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    HttpResponse response = headers != null
                            ? httpClient.get(url, headers) : httpClient.get(url);
                    assertThat(response.getStatusCode()).isLessThan(500);
                });
        log.info("AJP proxy responding at {}", url);
    }

    /** Build HTTP Basic auth headers for the given credentials. */
    static Map<String, String> basicAuthHeaders(String username, String password) {
        String credentials = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes());
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Basic " + credentials);
        return headers;
    }
}
