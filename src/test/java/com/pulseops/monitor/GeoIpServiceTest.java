package com.pulseops.monitor;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GeoIpServiceTest {

    private final GeoIpService service = new GeoIpService();

    @Test
    void parseJson_validResponse_extractsLocation() {
        String json = """
                {"status":"success","country":"United States","city":"San Francisco","lat":37.7749,"lon":-122.4194}
                """;

        Optional<GeoIpService.GeoLocation> result = service.parseJson(json);

        assertThat(result).isPresent();
        assertThat(result.get().country()).isEqualTo("United States");
        assertThat(result.get().city()).isEqualTo("San Francisco");
        assertThat(result.get().lat()).isEqualTo(37.7749);
        assertThat(result.get().lon()).isEqualTo(-122.4194);
    }

    @Test
    void parseJson_failStatus_returnsEmpty() {
        String json = """
                {"status":"fail","message":"private range"}
                """;

        assertThat(service.parseJson(json)).isEmpty();
    }
}
