package com.pulseops.monitor;

import com.pulseops.exception.SsrfException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Executes a single HTTP GET check for a monitor.
 *
 * Key design decisions:
 *
 * 1. SSRF on every hop — each redirect target is validated before following.
 *    This prevents an attacker from registering a public URL that then 301s
 *    to 169.254.169.254 (AWS metadata).
 *
 * 2. DNS-rebinding protection — SsrfValidator.resolveAndValidate() returns the
 *    validated InetAddress. We embed that IP in the request URI so the HTTP
 *    client connects to it directly (never re-resolves the hostname).
 *
 * 3. Max 5 redirect hops — prevents infinite redirect loops.
 *
 * 4. Timeout is per-monitor and configurable (1–30 seconds).
 *
 * 5. Result success = final status is 2xx or 3xx (after all redirects).
 *    4xx and 5xx count as DOWN.
 */
@Component
public class HttpCheckExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpCheckExecutor.class);
    private static final int MAX_REDIRECTS = 5;

    private final SsrfValidator ssrfValidator;

    // One shared client with no automatic redirect following — we handle redirects
    // manually so we can SSRF-validate each hop before following it.
    private final HttpClient httpClient;

    public HttpCheckExecutor(SsrfValidator ssrfValidator) {
        this.ssrfValidator = ssrfValidator;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Execute an HTTP GET check for the given monitor.
     * Returns a CheckResult — never throws (all errors are captured as failure results).
     */
    public CheckResult execute(Monitor monitor) {
        Instant start = Instant.now();
        try {
            return doCheck(monitor, start);
        } catch (SsrfException e) {
            long latency = Duration.between(start, Instant.now()).toMillis();
            log.warn("SSRF block on monitor {}: {}", monitor.getId(), e.getMessage());
            return CheckResult.failure(monitor.getId(), null, (int) latency,
                    "SSRF: " + e.getMessage());
        } catch (Exception e) {
            long latency = Duration.between(start, Instant.now()).toMillis();
            log.warn("Check failed for monitor {}: {}", monitor.getId(), e.getMessage());
            return CheckResult.failure(monitor.getId(), null, (int) latency,
                    truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 500));
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private CheckResult doCheck(Monitor monitor, Instant start) throws Exception {
        Duration timeout = Duration.ofSeconds(monitor.getTimeoutSeconds());
        String currentUrl = monitor.getUrl();
        Set<String> visited = new HashSet<>();
        int hops = 0;

        while (hops <= MAX_REDIRECTS) {
            if (!visited.add(currentUrl)) {
                // Redirect loop detected
                return CheckResult.failure(monitor.getId(), null, elapsed(start),
                        "Redirect loop detected at: " + currentUrl);
            }

            // 1. SSRF-validate the URL and resolve the IP — do this BEFORE connecting
            List<InetAddress> validatedAddresses = ssrfValidator.resolveAndValidate(currentUrl);
            InetAddress targetIp = validatedAddresses.get(0);

            // 2. Build a URI that connects to the validated IP directly (DNS-rebinding protection)
            URI originalUri = URI.create(currentUrl);
            URI safeUri = buildSafeUri(originalUri, targetIp);

            // 3. Build and send the request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(safeUri)
                    .timeout(timeout)
                    .header("Host", originalUri.getHost())   // keep original Host header
                    .header("User-Agent", "PulseOps-Monitor/1.0")
                    .GET()
                    .build();

            HttpResponse<Void> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.discarding());

            int statusCode = response.statusCode();
            int latency = elapsed(start);

            // 4. Handle redirects manually — validate each hop before following
            if (isRedirect(statusCode)) {
                if (hops == MAX_REDIRECTS) {
                    return CheckResult.failure(monitor.getId(), statusCode, latency,
                            "Too many redirects (>" + MAX_REDIRECTS + ")");
                }
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    return CheckResult.failure(monitor.getId(), statusCode, latency,
                            "Redirect with no Location header");
                }
                // Resolve relative redirects against the current URL
                currentUrl = originalUri.resolve(location).toString();
                hops++;
                continue;
            }

            // 5. Final response — 2xx/3xx = UP, anything else = DOWN
            boolean success = statusCode >= 200 && statusCode < 400;
            if (success) {
                return CheckResult.success(monitor.getId(), statusCode, latency);
            } else {
                return CheckResult.failure(monitor.getId(), statusCode, latency,
                        "HTTP " + statusCode);
            }
        }

        // Should not be reachable, but fail safe
        return CheckResult.failure(monitor.getId(), null, elapsed(start), "Exceeded redirect limit");
    }

    /**
     * Replace the hostname in the URI with the validated IP address.
     * This prevents the HTTP client from re-resolving the hostname at connect time
     * (DNS-rebinding attack prevention per spec §2.9).
     *
     * The original Host header is added separately to satisfy virtual-host routing.
     */
    private URI buildSafeUri(URI original, InetAddress ip) {
        String host = ip instanceof java.net.Inet6Address
                ? "[" + ip.getHostAddress() + "]"   // IPv6 literals need brackets in URIs
                : ip.getHostAddress();

        int port = original.getPort();
        String portPart = port == -1 ? "" : ":" + port;
        String path = original.getRawPath();
        String query = original.getRawQuery();
        String queryPart = query == null ? "" : "?" + query;

        return URI.create(original.getScheme() + "://" + host + portPart + path + queryPart);
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302
                || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private int elapsed(Instant start) {
        return (int) Duration.between(start, Instant.now()).toMillis();
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
