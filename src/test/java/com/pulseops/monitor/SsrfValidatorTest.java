package com.pulseops.monitor;

import com.pulseops.exception.SsrfException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfValidatorTest {

    // ── Scheme checks ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com",
            "file:///etc/passwd",
            "gopher://example.com",
            "javascript:alert(1)"
    })
    void validate_invalidScheme_throwsSsrfException(String url) {
        SsrfValidator validator = new SsrfValidator();
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(SsrfException.class)
                .hasMessageContaining("Only HTTP and HTTPS are permitted");
    }

    // ── Loopback ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1",
            "http://127.0.0.2:8080",
            "http://localhost:8080/metrics",
            "http://[::1]/secret"
    })
    void validate_loopbackAddresses_throwsSsrfException(String url) {
        SsrfValidator validator = new SsrfValidator();
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(SsrfException.class)
                .hasMessageContaining("prohibited IP address");
    }

    // ── RFC 1918 private ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.1/admin",
            "http://172.16.0.1",
            "http://172.31.255.255",
            "http://192.168.1.100",
            "http://192.168.0.1:5432"
    })
    void validate_rfc1918PrivateIps_throwsSsrfException(String url) {
        SsrfValidator validator = new SsrfValidator();
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(SsrfException.class)
                .hasMessageContaining("prohibited IP address");
    }

    // ── AWS metadata ──────────────────────────────────────────────────────────

    @Test
    void validate_awsMetadataService_throwsSsrfException() {
        SsrfValidator validator = new SsrfValidator();
        assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(SsrfException.class)
                .hasMessageContaining("prohibited IP address");
    }

    // ── Carrier-Grade NAT ─────────────────────────────────────────────────────

    @Test
    void validate_sharedCarrierGradeNat_throwsSsrfException() throws Exception {
        byte[] cgnatIp = new byte[]{(byte) 100, (byte) 64, 0, 1}; // 100.64.0.1
        InetAddress address = InetAddress.getByAddress("cgnat.test", cgnatIp);
        SsrfValidator validator = new SsrfValidator(host -> new InetAddress[]{address});

        assertThatThrownBy(() -> validator.validate("http://cgnat.test"))
                .isInstanceOf(SsrfException.class)
                .hasMessageContaining("prohibited IP address");
    }

    // ── IPv4-mapped IPv6 (::ffff:x.x.x.x) ───────────────────────────────────
    //
    // These are the security-critical new cases.
    // Java's InetAddress.isLoopbackAddress() returns FALSE for ::ffff:127.0.0.1
    // because it only checks literal ::1 — the mapped form is an Inet6Address
    // that bypasses every JDK convenience method but connects to 127.0.0.1.

    @Test
    void validate_ipv4MappedLoopback_throwsSsrfException() {
        // ::ffff:127.0.0.1 — IPv4-mapped loopback.
        // InetAddress.isLoopbackAddress() returns FALSE for this address.
        // Our isIpv4Mapped() + isRestrictedAddress(embedded Inet4) catches it.
        SsrfValidator validator = new SsrfValidator();
        assertThatThrownBy(() -> validator.validate("http://[::ffff:127.0.0.1]/"))
                .isInstanceOf(SsrfException.class)
                .hasMessageContaining("prohibited IP address");
    }

    @Test
    void validate_ipv4MappedPrivateRfc1918_throwsSsrfException() {
        // ::ffff:192.168.1.1 — IPv4-mapped private address
        SsrfValidator validator = new SsrfValidator();
        assertThatThrownBy(() -> validator.validate("http://[::ffff:192.168.1.1]/admin"))
                .isInstanceOf(SsrfException.class)
                .hasMessageContaining("prohibited IP address");
    }

    @Test
    void validate_ipv4MappedAwsMetadata_throwsSsrfException() {
        // ::ffff:169.254.169.254 — IPv4-mapped AWS metadata endpoint
        SsrfValidator validator = new SsrfValidator();
        assertThatThrownBy(() -> validator.validate("http://[::ffff:169.254.169.254]/"))
                .isInstanceOf(SsrfException.class)
                .hasMessageContaining("prohibited IP address");
    }

    @Test
    void validate_ipv4MappedPublicIp_succeeds() throws Exception {
        // ::ffff:93.184.216.34 — IPv4-mapped form of a real public IP (example.com)
        // This should PASS: the embedded IPv4 is public and unrestricted
        byte[] mappedBytes = new byte[]{
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,          // 10 zeros
                (byte) 0xFF, (byte) 0xFF,                 // ::ffff: marker
                (byte) 93, (byte) 184, (byte) 216, (byte) 34  // 93.184.216.34
        };
        InetAddress mapped = InetAddress.getByAddress(mappedBytes);
        SsrfValidator validator = new SsrfValidator(host -> new InetAddress[]{mapped});

        // Must not throw
        validator.validate("https://example.com/health");
    }

    // ── IPv6 Unique Local Address (fc00::/7) ──────────────────────────────────

    @Test
    void validate_ipv6UniqueLocalAddress_throwsSsrfException() throws Exception {
        // fd00::1 is a Unique Local Address (fc00::/7)
        byte[] ulaBytes = new byte[]{
                (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        };
        InetAddress ula = InetAddress.getByAddress("internal.corp", ulaBytes);
        SsrfValidator validator = new SsrfValidator(host -> new InetAddress[]{ula});

        assertThatThrownBy(() -> validator.validate("https://internal.corp"))
                .isInstanceOf(SsrfException.class)
                .hasMessageContaining("prohibited IP address");
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void validate_validPublicDomain_succeedsWithMockedDns() throws Exception {
        InetAddress publicIp = InetAddress.getByAddress(
                "public-service.com", new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34});
        SsrfValidator validator = new SsrfValidator(host -> new InetAddress[]{publicIp});

        validator.validate("https://public-service.com/health"); // must not throw
    }

    @Test
    void validate_unresolvableHost_throwsSsrfException() {
        SsrfValidator validator = new SsrfValidator(host -> {
            throw new UnknownHostException("Host does not exist");
        });

        assertThatThrownBy(() -> validator.validate("https://non-existent-domain-xyz.fake"))
                .isInstanceOf(SsrfException.class)
                .hasMessageContaining("Unable to resolve host");
    }

    @Test
    void validate_emptyOrBlankUrl_throwsSsrfException() {
        SsrfValidator validator = new SsrfValidator();
        assertThatThrownBy(() -> validator.validate(""))
                .isInstanceOf(SsrfException.class)
                .hasMessageContaining("URL cannot be empty");
    }

    // ── DNS rebinding: resolveAndValidate returns addresses ───────────────────

    @Test
    void resolveAndValidate_returnsValidatedAddresses_forPublicIp() throws Exception {
        InetAddress publicIp = InetAddress.getByAddress(
                "api.example.com", new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34});
        SsrfValidator validator = new SsrfValidator(host -> new InetAddress[]{publicIp});

        List<InetAddress> result = validator.resolveAndValidate("https://api.example.com/health");

        assertThat(result).isNotEmpty();
        // Caller must use this IP directly — no re-resolution at connect time
        assertThat(result.get(0)).isInstanceOf(Inet4Address.class);
        assertThat(result.get(0).getHostAddress()).isEqualTo("93.184.216.34");
    }

    @Test
    void resolveAndValidate_forRestrictedUrl_throwsBeforeReturning() {
        SsrfValidator validator = new SsrfValidator();
        assertThatThrownBy(() -> validator.resolveAndValidate("http://127.0.0.1/secret"))
                .isInstanceOf(SsrfException.class);
    }
}
