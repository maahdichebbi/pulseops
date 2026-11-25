package com.pulseops.monitor;

import com.pulseops.exception.SsrfException;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Validates URLs against Server-Side Request Forgery (SSRF) threats.
 *
 * Enforces:
 * 1. Scheme must be http or https.
 * 2. Hostname resolves to at least one address; every resolved address is validated.
 * 3. Prohibits:
 *    - Loopback             (127.0.0.0/8, ::1)
 *    - RFC 1918 private     (10/8, 172.16/12, 192.168/16)       via isSiteLocalAddress()
 *    - Link-local           (169.254.0.0/16, fe80::/10)          via isLinkLocalAddress()
 *    - AWS instance metadata (169.254.169.254)                   covered by link-local above
 *    - Carrier-grade NAT    (100.64.0.0/10, RFC 6598)            explicit byte check
 *    - Unspecified          (0.0.0.0/8, ::)                      via isAnyLocalAddress()
 *    - Multicast            (224.0.0.0/4, ff00::/8)              via isMulticastAddress()
 *    - IPv6 ULA             (fc00::/7)                           explicit byte check
 *    - IPv4-mapped IPv6     (::ffff:0:0/96)                      explicit byte check + embedded IPv4 validation
 *    - IPv4-compatible IPv6 (::x.x.x.x, deprecated)             via isIPv4CompatibleAddress()
 *    - Reserved / future    (240.0.0.0/4, 198.18.0.0/15)        explicit byte check
 *
 * DNS-rebinding protection: use {@link #resolveAndValidate(String)} to get the validated
 * InetAddress list and pass it directly to the HTTP client — never let the client
 * re-resolve the hostname at connect time.
 */
@Component
public class SsrfValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    @FunctionalInterface
    public interface DnsResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final DnsResolver dnsResolver;

    public SsrfValidator() {
        this(InetAddress::getAllByName);
    }

    /** Package-private: inject a mock resolver in unit tests (no network required). */
    SsrfValidator(DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Validates {@code urlString} and returns the validated resolved addresses.
     * Callers MUST connect to one of the returned addresses (not re-resolve the
     * hostname) to prevent DNS-rebinding attacks.
     *
     * @throws SsrfException on scheme violation, DNS failure, or restricted IP
     */
    public List<InetAddress> resolveAndValidate(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new SsrfException("URL cannot be empty");
        }

        URI uri;
        try {
            uri = URI.create(urlString.trim());
        } catch (IllegalArgumentException e) {
            throw new SsrfException("Invalid URL format: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new SsrfException(
                    "Invalid scheme: '" + scheme + "'. Only HTTP and HTTPS are permitted");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfException("URL must contain a valid host");
        }

        // Strip IPv6 literal brackets (URI#getHost keeps them; InetAddress doesn't want them)
        String cleanHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;

        InetAddress[] addresses;
        try {
            addresses = dnsResolver.resolve(cleanHost);
        } catch (UnknownHostException e) {
            throw new SsrfException("Unable to resolve host: " + host);
        }

        if (addresses == null || addresses.length == 0) {
            throw new SsrfException("No IP addresses found for host: " + host);
        }

        for (InetAddress address : addresses) {
            if (isRestrictedAddress(address)) {
                throw new SsrfException(
                        "URL resolves to prohibited IP address: " + address.getHostAddress());
            }
        }

        return Arrays.asList(addresses);
    }

    /**
     * Convenience wrapper — validates without returning the addresses.
     * Use for creation-time checks when the caller only needs pass/fail.
     * For the HTTP check worker, use {@link #resolveAndValidate} instead.
     */
    public void validate(String urlString) {
        resolveAndValidate(urlString); // validated addresses discarded here
    }

    // ── IP classification ─────────────────────────────────────────────────────

    /**
     * Returns true if {@code address} falls into any restricted range.
     * Public so it can be reused for each redirect hop in the HTTP worker.
     */
    public boolean isRestrictedAddress(InetAddress address) {
        if (address == null) return true;

        // JDK covers: loopback, any-local (0.0.0.0/::), link-local, site-local, multicast
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        if (address instanceof Inet4Address ipv4) {
            return isRestrictedIpv4(ipv4);
        } else if (address instanceof Inet6Address ipv6) {
            return isRestrictedIpv6(ipv6);
        }

        return false; // unknown subclass — conservative fallback not needed; both are sealed
    }

    private boolean isRestrictedIpv4(Inet4Address ipv4) {
        byte[] b = ipv4.getAddress();
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;

        // 0.0.0.0/8  — current network / default route
        if (b0 == 0) return true;

        // 100.64.0.0/10 — Carrier-Grade NAT (RFC 6598)
        // Range: 100.64.0.0 – 100.127.255.255
        if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;

        // 169.254.0.0/16 — link-local (also covers 169.254.169.254 AWS metadata)
        // Note: also caught by isLinkLocalAddress() above; kept here as belt-and-suspenders
        if (b0 == 169 && b1 == 254) return true;

        // 198.18.0.0/15 — IANA benchmarking range (RFC 2544)
        if (b0 == 198 && (b1 == 18 || b1 == 19)) return true;

        // 240.0.0.0/4 — reserved for future use; 255.255.255.255 broadcast
        if (b0 >= 240) return true;

        return false;
    }

    private boolean isRestrictedIpv6(Inet6Address ipv6) {
        byte[] b = ipv6.getAddress();

        // ── IPv4-mapped IPv6: ::ffff:x.x.x.x (bytes 10-11 = 0xFF 0xFF) ──────
        //
        // WHY THIS MATTERS (security-critical):
        // Java's InetAddress.isLoopbackAddress() returns FALSE for ::ffff:127.0.0.1
        // because it only checks for literal ::1.  An attacker can bypass validation
        // by passing http://[::ffff:127.0.0.1]/ — the address object passes every JDK
        // check but the HTTP client actually connects to 127.0.0.1.
        //
        // Fix: detect the ::ffff:0:0/96 prefix, extract the embedded IPv4 bytes,
        // and run them through our full IPv4 classifier.
        //
        // Note: isIPv4CompatibleAddress() is the WRONG method here — it detects
        // the *deprecated* ::x.x.x.x form (bytes 10-11 = 0x00 0x00), not ::ffff:.
        if (isIpv4Mapped(b)) {
            try {
                // Extract the embedded 4-byte IPv4 address (bytes 12–15)
                byte[] ipv4Bytes = new byte[]{b[12], b[13], b[14], b[15]};
                Inet4Address embedded = (Inet4Address) InetAddress.getByAddress(ipv4Bytes);
                // A mapped address is restricted if the embedded IPv4 is restricted
                return isRestrictedAddress(embedded);
            } catch (UnknownHostException e) {
                return true; // 4 bytes always valid; can't happen, but fail safe
            }
        }

        // ── IPv4-compatible (deprecated) ::x.x.x.x (bytes 10-11 = 0x00 0x00) ─
        if (ipv6.isIPv4CompatibleAddress()) return true;

        // ── Unique Local Address: fc00::/7 (0xFC or 0xFD as first byte) ───────
        if ((b[0] & 0xFF & 0xFE) == 0xFC) return true;

        return false;
    }

    /**
     * Detects IPv4-mapped IPv6 addresses: ::ffff:x.x.x.x
     * Layout: 10 zero bytes | 0xFF 0xFF | 4 IPv4 bytes
     */
    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
    }
}
