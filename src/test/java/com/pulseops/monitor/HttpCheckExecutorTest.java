package com.pulseops.monitor;

import com.pulseops.exception.SsrfException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for HttpCheckExecutor — no network, no Testcontainers.
 *
 * The executor must never throw. All errors (SSRF, timeout, network) must be
 * captured and returned as a failed CheckResult. This test proves that
 * contract for the SSRF case.
 *
 * Live HTTP round-trip behaviour is covered by manual smoke tests after boot.
 */
@ExtendWith(MockitoExtension.class)
class HttpCheckExecutorTest {

    @Mock
    private SsrfValidator ssrfValidator;

    private HttpCheckExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new HttpCheckExecutor(ssrfValidator);
    }

    /**
     * When SsrfValidator blocks a URL, execute() must:
     * - return a failure CheckResult (never throw)
     * - leave statusCode null (no HTTP response was made)
     * - include "SSRF:" in the errorDetail
     */
    @Test
    void execute_whenSsrfBlocked_returnsFailureResult_neverThrows() {
        when(ssrfValidator.resolveAndValidate(anyString()))
                .thenThrow(new SsrfException("Blocked: loopback address"));

        Monitor monitor = new Monitor();
        monitor.setUrl("http://127.0.0.1:8080/health");
        monitor.setTimeoutSeconds(5);

        CheckResult result = executor.execute(monitor);

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatusCode()).isNull();
        assertThat(result.getErrorDetail()).startsWith("SSRF: Blocked:");
    }

    /**
     * When SsrfValidator throws a generic RuntimeException (unexpected error),
     * execute() must still return a failure result, never propagate the exception.
     */
    @Test
    void execute_whenUnexpectedErrorOccurs_returnsFailureResult_neverThrows() {
        when(ssrfValidator.resolveAndValidate(anyString()))
                .thenThrow(new RuntimeException("DNS resolution failed"));

        Monitor monitor = new Monitor();
        monitor.setUrl("http://does-not-exist.example.com/");
        monitor.setTimeoutSeconds(5);

        CheckResult result = executor.execute(monitor);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatusCode()).isNull();
        assertThat(result.getErrorDetail()).contains("RuntimeException");
    }

    /**
     * Proves execute() completes a real HTTP GET without hitting restricted-header errors.
     */
    @Test
    void execute_liveHttp200_returnsSuccessResult() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "OK".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            when(ssrfValidator.resolveAndValidate(anyString()))
                    .thenReturn(java.util.List.of(java.net.InetAddress.getByName("127.0.0.1")));

            Monitor monitor = new Monitor();
            monitor.setUrl("http://127.0.0.1:" + port + "/health");
            monitor.setTimeoutSeconds(5);

            CheckResult result = executor.execute(monitor);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(result.getLatencyMs()).isNotNull();
        } finally {
            server.stop(0);
        }
    }
}
