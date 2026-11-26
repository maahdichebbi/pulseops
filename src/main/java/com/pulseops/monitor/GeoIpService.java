package com.pulseops.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, fail-safe GeoIP lookup for a validated public IP address.
 *
 * Uses ip-api.com (no API key required) with a strict 2-second timeout.
 * Never throws — if the lookup fails, times out, or runs offline in tests,
 * it returns Optional.empty() so monitor creation/updates are never blocked.
 */
@Service
public class GeoIpService {

    private static final Logger log = LoggerFactory.getLogger(GeoIpService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final Pattern COUNTRY_PATTERN = Pattern.compile("\"country\":\"([^\"]*)\"");
    private static final Pattern CITY_PATTERN    = Pattern.compile("\"city\":\"([^\"]*)\"");
    private static final Pattern LAT_PATTERN     = Pattern.compile("\"lat\":(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern LON_PATTERN     = Pattern.compile("\"lon\":(-?\\d+(?:\\.\\d+)?)");

    public record GeoLocation(String country, String city, Double lat, Double lon) {}

    private final HttpClient httpClient;

    public GeoIpService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    public Optional<GeoLocation> lookup(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://ip-api.com/json/" + ipAddress
                            + "?fields=status,country,city,lat,lon"))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return parseJson(response.body());
        } catch (Exception e) {
            log.debug("GeoIP lookup skipped for {}: {}", ipAddress, e.getMessage());
            return Optional.empty();
        }
    }

    /** Package-private for deterministic unit testing without network calls. */
    Optional<GeoLocation> parseJson(String json) {
        if (json == null || !json.contains("\"status\":\"success\"")) {
            return Optional.empty();
        }
        String country = extractString(COUNTRY_PATTERN, json);
        String city    = extractString(CITY_PATTERN, json);
        Double lat     = extractDouble(LAT_PATTERN, json);
        Double lon     = extractDouble(LON_PATTERN, json);
        if (lat == null || lon == null) {
            return Optional.empty();
        }
        return Optional.of(new GeoLocation(country, city, lat, lon));
    }

    private String extractString(Pattern pattern, String json) {
        Matcher m = pattern.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private Double extractDouble(Pattern pattern, String json) {
        Matcher m = pattern.matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }
}
